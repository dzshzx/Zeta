package host

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
)

// OpenChat 起一条到设备 h 上 serve 的连接给对话用（和会话列表的订阅各用各的：对话开在哪台和侧栏列哪台无关）。
// 推来的对话推送（type 以 conv. 开头的行）和搜索的命中（sessions.match）交给 push，每次连上时 serve 的招呼交给 hello（可以是 nil），
// 连上（空）、断开或连不上（原因）交给 problem，都在读的 goroutine 上调用；
// 请求用返回的连接（一个 *Serve）的 Call。第一次就没连上时 Serve 停掉，Call 返回那次的原因，要再用得重新 OpenChat。
func (d *Devices) OpenChat(h sshlink.Host, push func(line []byte), hello func(hostlink.Hello), problem func(why string)) ChatLink {
	shell := hostlink.NewShell(d.Links, h, LinuxName)
	return hostlink.StartServe(hostlink.ServeOptions{
		Open:   func() (*sshlink.Process, error) { return hostlink.OpenServe(shell, d.Helper) },
		Device: ThisDevice(),
		Hello:  hello,
		Message: func(kind string, line []byte) {
			if strings.HasPrefix(kind, "conv.") || kind == "sessions.match" {
				push(line)
			}
		},
		Problem: problem,
	})
}

// ThisDevice 是这台电脑的名字（Windows 的计算机名，os.Hostname），对话连接连上时报给核心（core.client）：
// 别的包看到这里在用的对话被占着时说「在哪台设备的 Zeta 里」。查不到时为空，不报。
var ThisDevice = sync.OnceValue(func() string {
	name, _ := os.Hostname()
	return name
})

// OpenBulk 另起一条到设备 h 上 serve 的连接，只取大块数据（sessions.image、sessions.export）：应答是一整行，几 MB 的
// 和对话的推送挤在 OpenChat 那条上时，流式输出要排在它后面。两条是同一个 SSH 连接上的两个通道，各走各的。不收推送。
func (d *Devices) OpenBulk(h sshlink.Host) ChatLink {
	return d.OpenChat(h, func([]byte) {}, nil, func(string) {})
}

// dirCheckTimeout 是查一个目录最多等多久。
const dirCheckTimeout = 20 * time.Second

// CheckDir 看设备 h 上进不进得去目录 path（Claude Code 要在那里启动）：进不去时返回给人看的原因，进得去返回空。
// 查不成（连不上、超时）时返回错误，调用方当作不知道，不拦。阻塞。
func (d *Devices) CheckDir(h sshlink.Host, path string) (string, error) {
	shell := hostlink.NewShell(d.Links, h, LinuxName)
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
	return "p=" + hostlink.Quote(path) + `; if [ -d "$p" ]; then if [ -r "$p" ] && [ -x "$p" ]; then k=ok; else k=denied; fi; ` +
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

// ChatLink 是 OpenChat 给的对话连接：发请求、关掉。界面测试里换成假的（hubtest）。
type ChatLink interface {
	Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)
	Close() error
}
