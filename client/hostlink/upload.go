package hostlink

import (
	"archive/tar"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// 本机的文件、文件夹传到主机上，随消息附带（conv.send 的 files 只收主机上的路径，传上去归客户端，见 docs/core.md）。
// 放在 ~/.cache/<ZETA_NAME>/files/<8 位随机>/<名字>，同 shared 的 Uploads.copyFrom：手机和电脑传的在同一处，谁的清理都认得。
// 经一条 SSH 通道（StartPlain）把内容灌进那边 cat 的标准输入，文件夹打成 tar 流给 tar -x。

// ErrTooLarge：内容超过了上限，传了一半的已经删掉。
var ErrTooLarge = errors.New("超过上限")

// CleanName 是传上去用的文件名：斜杠、反斜杠和控制字符换成 _，去掉首尾空白，最多 120 个字符；空的、. 和 .. 用 file。
// 同 Uploads.cleanName（多换反斜杠：Windows 的路径分隔符）。
func CleanName(name string) string {
	name = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r < 0x20 || r == 0x7f {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if utf8.RuneCountInString(name) > 120 {
		name = string([]rune(name)[:120])
	}
	if name == "" || name == "." || name == ".." {
		return "file"
	}
	return name
}

// uploadDir 是这次传的目录（shell 里的写法，带引号）：~/.cache/<ZETA_NAME>/files/<id>。
func uploadDir(id string) string { return `"$HOME/.cache/${ZETA_NAME:-zeta}/files/"` + Quote(id) }

// UploadCommand 是收这次内容的那一行：文件时 cat 进 <目录>/<name>，文件夹时 tar 解进 <目录>/<name>/；成了输出一行
// zeta_upload <主机上的路径>。
func UploadCommand(id, name string, folder bool) string {
	target := uploadDir(id) + "/" + Quote(name)
	if folder {
		return "mkdir -p " + target + " && tar -xf - -C " + target + " && printf 'zeta_upload %s\\n' " + target
	}
	return "mkdir -p " + uploadDir(id) + " && cat > " + target + " && printf 'zeta_upload %s\\n' " + target
}

// Upload 把 src 的内容传成主机上的文件 name（先过 CleanName），返回那边的路径。progress 不为空时每写一块报一次已传的字节数。
// 超过 limit 字节返回 ErrTooLarge；ctx 取消时不再传、返回 ctx 的错误；这两种和别的失败都删掉传了一半的。阻塞。
func (s Shell) Upload(ctx context.Context, name string, src io.Reader, limit int64, progress func(int64)) (string, error) {
	return s.upload(ctx, CleanName(name), false, src, limit, progress)
}

// UploadFolder 把本机的文件夹 local 整个传上去（tar 流，只带目录和普通文件，符号链接之类跳过），返回主机上那个文件夹的路径。
// limit 管 tar 流的总字节数。别的同 Upload。
func (s Shell) UploadFolder(ctx context.Context, local string, limit int64, progress func(int64)) (string, error) {
	pr, pw := io.Pipe()
	go func() { pw.CloseWithError(WriteTar(pw, local)) }()
	defer pr.Close() // 传不下去时让写 tar 的 goroutine 停下
	return s.upload(ctx, CleanName(filepath.Base(local)), true, pr, limit, progress)
}

// FolderSize 是文件夹 local 里普通文件的总大小（WriteTar 会带上的那些），算进度用。
func FolderSize(local string) (int64, error) {
	var total int64
	err := filepath.WalkDir(local, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.Type().IsRegular() {
			info, err := d.Info()
			if err != nil {
				return err
			}
			total += info.Size()
		}
		return nil
	})
	return total, err
}

// WriteTar 把文件夹 root 里的目录和普通文件写成 tar 流（路径相对 root，用 /）。
func WriteTar(w io.Writer, root string) error {
	tw := tar.NewWriter(w)
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil || rel == "." {
			return err
		}
		if !d.IsDir() && !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		h, err := tar.FileInfoHeader(info, "")
		if err != nil {
			return err
		}
		h.Name = filepath.ToSlash(rel)
		if d.IsDir() {
			h.Name += "/"
		}
		h.Uname, h.Gname, h.Uid, h.Gid = "", "", 0, 0
		if err := tw.WriteHeader(h); err != nil || d.IsDir() {
			return err
		}
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.CopyN(tw, f, h.Size)
		return err
	})
	if err != nil {
		return err
	}
	return tw.Close()
}

func (s Shell) upload(ctx context.Context, name string, folder bool, src io.Reader, limit int64, progress func(int64)) (string, error) {
	id, err := randomID()
	if err != nil {
		return "", err
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	p, err := s.StartPlain(UploadCommand(id, name, folder))
	if err != nil {
		return "", err
	}
	// 传不下去（取消、超限、读不出来）时只关标准输入：那边的 cat、tar 读到结尾自己退出，等它退出了再删传了一半的，
	// 删在它之后才不会又被它建出来。过了 abortGrace 那边还没退（连接僵住、磁盘卡住）才关通道。
	abort, settled := abortAfter(func() { _ = p.Stdin.Close() }, p.Close, abortGrace)
	stop := context.AfterFunc(ctx, abort)
	var stdout, stderr bytes.Buffer
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); _, _ = io.Copy(&stdout, p.Stdout) }()
	go func() { defer wg.Done(); _, _ = io.Copy(&stderr, p.Stderr) }()
	over, werr := copyLimited(p.Stdin, src, limit, progress)
	if over || werr != nil {
		abort()
	} else {
		_ = p.Stdin.Close() // 标准输入关了 cat、tar 才结束
	}
	code := p.Wait()
	settled()
	wg.Wait()
	stop()
	path := uploadedPath(stdout.String())
	switch {
	case ctx.Err() != nil:
		err = ctx.Err()
	case over:
		err = ErrTooLarge
	case werr != nil && !errors.Is(werr, io.ErrClosedPipe):
		err = werr
	case code != 0 || path == "":
		err = s.uploadProblem(code, stderr.String())
	default:
		return path, nil
	}
	s.removeUpload(id)
	return "", err
}

// abortGrace 是不传了以后等那边的命令自己退出多久，过了就关通道。
const abortGrace = 3 * time.Second

// abortAfter 给出两个函数：abort 先调 gentle，过了 grace 再调 hard；settled 表示已经结束了，之后不再调 hard。
// abort 可以调多次、可以并发调，只有第一次算数。
func abortAfter(gentle, hard func(), grace time.Duration) (abort, settled func()) {
	done := make(chan struct{})
	abort = sync.OnceFunc(func() {
		gentle()
		go func() {
			select {
			case <-done:
			case <-time.After(grace):
				hard()
			}
		}()
	})
	return abort, sync.OnceFunc(func() { close(done) })
}

// uploadProblem 是没传成的原因：连接断了时说连不上，否则是那边的报错。
func (s Shell) uploadProblem(code int, stderr string) error {
	if problem := s.LinkProblem(sshlink.Result{Code: code, Stderr: stderr}); problem != "" {
		return errors.New(problem)
	}
	if why := lastLine(stderr); why != "" {
		return errors.New("没有传上去：" + why)
	}
	return errors.New("没有传上去")
}

// removeUpload 删掉这次传了一半的目录（尽力而为，删不掉不报）。
func (s Shell) removeUpload(id string) {
	_, _ = s.Run("rm -rf "+uploadDir(id), nil, 20*time.Second, true)
}

// uploadedPath 读 UploadCommand 成功时那行 zeta_upload <路径>。
func uploadedPath(stdout string) string {
	for _, line := range strings.Split(stdout, "\n") {
		if path, ok := strings.CutPrefix(strings.TrimRight(line, "\r"), "zeta_upload "); ok && strings.HasPrefix(path, "/") {
			return path
		}
	}
	return ""
}

// copyLimited 把 src 抄进 dst，超过 limit 字节就停下（超了的那一块不写），返回是不是超了。
func copyLimited(dst io.Writer, src io.Reader, limit int64, progress func(int64)) (bool, error) {
	buf := make([]byte, 64<<10)
	var total int64
	for {
		n, err := src.Read(buf)
		if n > 0 {
			total += int64(n)
			if total > limit {
				return true, nil
			}
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return false, werr
			}
			if progress != nil {
				progress(total)
			}
		}
		if err == io.EOF {
			return false, nil
		}
		if err != nil {
			return false, err
		}
	}
}

// randomID 是这次传的目录名：8 位十六进制，同 Kotlin 版取 UUID 的前 8 位。
func randomID() (string, error) {
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
