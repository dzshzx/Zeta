package diag

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/redact"
	"github.com/dzshzx/Zeta/windows/internal/runlog"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// exportNote 是「导出诊断包」下面的说明。
const exportNote = "把运行日志、环境和上面这些日志（各取最后 64 KB）打成一个 zip。token、密码这类值会先打码。"

// exportGroup 是「导出诊断包」一行：打包时写正在打包，存好后写存到哪，没成时写原因。
func (p *Page) exportGroup(c *ui.Context) {
	k := widget.Of(c)
	widget.Group(c, "", exportNote, func(l *widget.List) {
		row := widget.Row{Title: "导出诊断包"}
		switch {
		case p.exporting:
			row.Summary = "正在打包…"
		case p.exportErr != nil:
			row.Summary, row.SummaryColor = "没有导出："+p.exportErr.Error(), k.Error
		case p.saved != "":
			row.Summary = "已保存到 " + p.saved
		}
		if !p.exporting {
			row.OnClick = p.export
		}
		l.Row(row)
	})
}

// export 在后台打包，再弹保存对话框存下。设备清单和环境信息在主线程上取。
func (p *Page) export() {
	if p.exporting {
		return
	}
	devices := p.o.Devices()
	var env []Line
	if p.o.Env != nil {
		env = p.o.Env()
	}
	now := p.o.Now()
	p.exporting, p.saved, p.exportErr = true, "", nil
	p.o.Run(func() {
		data, err := Bundle(context.Background(), devices, env, p.o.Dir, now)
		saved := ""
		if err == nil {
			saved, err = p.o.Save("zeta-diag-"+now.Format("20060102-150405")+".zip", data)
		}
		p.o.Post(func() { p.exporting, p.saved, p.exportErr = false, saved, err })
	})
}

// Bundle 是诊断包（zip）：
//
//   - 环境.txt：env 各行和打包的时刻；
//   - 每台连着的设备一个目录（设备名，重名的加序号）：running、finished、debug 下各文件的最后 64 KB（核心打过码），
//     在跑的按托管目录名起名；读不到的写进这个目录的「读取失败.txt」；
//   - 本机：dir 里的文件，运行日志整份、别的取最后 64 KB（这里打码）。
//
// 「连接」关着的设备只在环境里有一行。
func Bundle(ctx context.Context, devices []Device, env []Line, dir string, now time.Time) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	add := func(name, text string) error {
		w, err := zw.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: now})
		if err != nil {
			return err
		}
		_, err = io.WriteString(w, text)
		return err
	}
	var head strings.Builder
	head.WriteString("Zeta 桌面版诊断包\n打包时刻：" + now.Format("2006-01-02 15:04:05 -0700") + "\n\n")
	for _, l := range env {
		head.WriteString(l.Name + "：" + l.Value + "\n")
	}
	if err := add("环境.txt", redact.Text(head.String())); err != nil {
		return nil, err
	}
	used := map[string]bool{}
	for _, d := range devices {
		if d.Core == nil {
			continue
		}
		if err := addDevice(ctx, add, uniqueDir(used, d.Name), d.Core); err != nil {
			return nil, err
		}
	}
	if err := addLocal(add, dir); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// addDevice 放一台设备的各日志。读不到的记进「读取失败.txt」，不让整个包失败。
func addDevice(ctx context.Context, add func(name, text string) error, dir string, core *coreapi.Client) error {
	var failed []string
	cctx, cancel := context.WithTimeout(ctx, timeout)
	files, err := core.DiagFiles(cctx)
	cancel()
	if err != nil {
		failed = append(failed, "列日志："+err.Error())
	}
	for _, f := range files {
		cctx, cancel := context.WithTimeout(ctx, timeout)
		tail, err := core.ReadDiag(cctx, f.Path, true)
		cancel()
		if err != nil {
			failed = append(failed, f.Path+"："+err.Error())
			continue
		}
		name := path.Base(f.Path)
		if f.Kind == coreapi.DiagRunning {
			name = path.Base(path.Dir(f.Path)) + ".log"
		}
		text := tail.Text
		if tail.Truncated {
			text = "# 只取了最后 64 KB（整个文件 " + strconv.FormatInt(tail.Size, 10) + " 字节）\n" + text
		}
		if err := add(dir+"/"+f.Kind+"/"+safeName(name), text); err != nil {
			return err
		}
	}
	if len(failed) > 0 {
		return add(dir+"/读取失败.txt", redact.Text(strings.Join(failed, "\n")+"\n"))
	}
	return nil
}

// addLocal 放这台电脑上 Zeta 诊断目录里的文件（运行日志、崩溃记录、安装记录）：运行日志整份（它自己有上限），别的取最后 64 KB；
// 都打码。没有这个目录时什么也不放。
func addLocal(add func(name, text string) error, dir string) error {
	if dir == "" {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	for _, en := range entries {
		if !en.Type().IsRegular() {
			continue
		}
		limit := int64(coreapi.DiagTailBytes)
		if strings.HasPrefix(en.Name(), runlog.FileName) {
			limit = runlog.MaxBytes
		}
		text, err := tail(filepath.Join(dir, en.Name()), limit)
		if err != nil {
			continue
		}
		if err := add("本机/"+safeName(en.Name()), redact.Text(text)); err != nil {
			return err
		}
	}
	return nil
}

// tail 读本机文件的最后 n 字节，坏字节换成替代符。msiexec 的记录是 UTF-16，有 BOM 时换成 UTF-8。
func tail(name string, n int64) (string, error) {
	fh, err := os.Open(name)
	if err != nil {
		return "", err
	}
	defer fh.Close()
	st, err := fh.Stat()
	if err != nil {
		return "", err
	}
	head := make([]byte, 2)
	_, _ = fh.ReadAt(head, 0)
	start := max(st.Size()-n, 0)
	b, err := io.ReadAll(io.NewSectionReader(fh, start, st.Size()-start))
	if err != nil {
		return "", err
	}
	if head[0] == 0xFF && head[1] == 0xFE {
		return utf16le(b, start), nil
	}
	return strings.ToValidUTF8(string(b), "\uFFFD"), nil
}

// utf16le 把小端 UTF-16 解成字符串；start 是这段在文件里的位置（奇数时先丢一个字节对齐），开头的 BOM 去掉，
// 落单的代理项换成替代符。
func utf16le(b []byte, start int64) string {
	if start%2 == 1 && len(b) > 0 {
		b = b[1:]
	}
	u := make([]uint16, len(b)/2)
	for i := range u {
		u[i] = uint16(b[2*i]) | uint16(b[2*i+1])<<8
	}
	if start == 0 && len(u) > 0 && u[0] == 0xFEFF {
		u = u[1:]
	}
	return string(utf16.Decode(u))
}

// uniqueDir 是设备在包里的目录名：设备名去掉路径里不能用的字符，重名的加序号。
func uniqueDir(used map[string]bool, name string) string {
	base := safeName(name)
	if base == "" {
		base = "设备"
	}
	dir := base
	for i := 2; used[dir]; i++ {
		dir = base + " (" + strconv.Itoa(i) + ")"
	}
	used[dir] = true
	return dir
}

// safeName 把 Windows 文件名里不能用的字符换成下划线。
func safeName(s string) string {
	return strings.Trim(strings.Map(func(r rune) rune {
		if r < 0x20 || strings.ContainsRune(`/\:*?"<>|`, r) {
			return '_'
		}
		return r
	}, s), " .")
}
