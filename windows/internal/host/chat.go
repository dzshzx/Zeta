package host

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/remote"
)

// OpenChat 起一条到设备 h 上 serve 的连接给对话用（和会话列表的订阅各用各的：对话开在哪台和侧栏列哪台无关）。
// 推来的对话推送（type 以 conv. 开头的行）交给 push，连上（空）、断开或连不上（原因）交给 problem，都在读的 goroutine 上调用；
// 请求用返回的连接（一个 *Serve）的 Call。第一次就没连上时 Serve 停掉，Call 返回那次的原因，要再用得重新 OpenChat。
func (d *Devices) OpenChat(h remote.Host, push func(line []byte), problem func(why string)) ChatLink {
	shell := NewShell(d.Links, h)
	return StartServe(ServeOptions{
		Open: func() (*remote.Process, error) { return OpenServe(shell, d.Helper) },
		Message: func(kind string, line []byte) {
			if strings.HasPrefix(kind, "conv.") {
				push(line)
			}
		},
		Problem: problem,
	})
}

// dirCheckTimeout 是查一个目录最多等多久。
const dirCheckTimeout = 20 * time.Second

// CheckDir 看设备 h 上进不进得去目录 path（Claude Code 要在那里启动）：进不去时返回给人看的原因，进得去返回空。
// 查不成（连不上、超时）时返回错误，调用方当作不知道，不拦。阻塞。
func (d *Devices) CheckDir(h remote.Host, path string) (string, error) {
	shell := NewShell(d.Links, h)
	r, err := shell.Run(DirCheckCommand(path), nil, dirCheckTimeout, true)
	if err != nil {
		return "", err
	}
	if problem := shell.LinkProblem(r); problem != "" {
		return "", errors.New(problem)
	}
	if r.TimedOut {
		return "", errors.New("「" + h.Name() + "」没有响应（超时）")
	}
	return DirProblem(r.Stdout, h.Name(), path), nil
}

// DirCheckCommand 是查目录 path 的那一行：输出 zeta_dir 加 ok、denied（没有权限）、file（是文件）或 missing（没有）。
func DirCheckCommand(path string) string {
	return "p=" + Quote(path) + `; if [ -d "$p" ]; then if [ -r "$p" ] && [ -x "$p" ]; then k=ok; else k=denied; fi; ` +
		`elif [ -e "$p" ]; then k=file; else k=missing; fi; echo "zeta_dir $k"`
}

// DirProblem 把 DirCheckCommand 的输出换成给人看的原因（照 Kotlin 版的 CwdProblem）；进得去或认不出时是空。
func DirProblem(stdout, hostName, path string) string {
	for _, line := range strings.Split(stdout, "\n") {
		kind, ok := strings.CutPrefix(strings.TrimSpace(line), "zeta_dir ")
		if !ok {
			continue
		}
		switch kind {
		case "missing":
			return "「" + hostName + "」上没有 " + path + " 这个目录"
		case "file":
			return path + " 是文件，不是目录"
		case "denied":
			return "「" + hostName + "」上的 " + path + " 没有权限进去"
		}
		return ""
	}
	return ""
}

// imageTimeout 是取一张图最多等多久（图可能有几十 MB）。
const imageTimeout = 2 * time.Minute

// Image 取设备 h 上会话记录 file 里位置 ref 的那张图（zeta-helper image），返回图片文件的内容。阻塞。
func (d *Devices) Image(h remote.Host, file, ref string) ([]byte, error) {
	out, err := NewShell(d.Links, h).Helper(d.Helper, imageTimeout, "image", file, ref)
	if err != nil {
		return nil, err
	}
	return decodeImage(out)
}

// decodeImage 读 zeta-helper image 的输出：一行 {"type":"zeta_image","media_type":…,"data":<base64>}。
func decodeImage(out string) ([]byte, error) {
	for _, line := range strings.Split(out, "\n") {
		var msg struct {
			Type string `json:"type"`
			Data string `json:"data"`
		}
		if json.Unmarshal([]byte(line), &msg) != nil || msg.Type != "zeta_image" {
			continue
		}
		return base64.StdEncoding.DecodeString(msg.Data)
	}
	return nil, errors.New("没取到图片")
}

// ChatLink 是 OpenChat 给的对话连接：发请求、关掉。界面测试里换成假的（hubtest）。
type ChatLink interface {
	Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)
	Close() error
}
