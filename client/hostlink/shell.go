// Package hostlink 在一台远程主机上跑命令：交给那边 shell 的写法（同 shared 的 LinuxShell.kt 的 remoteCommand）、
// zeta-helper 这类程序按内容哈希装到 ~/.cache/<ZETA_NAME>/bin 再调用、到 zeta-helper serve 的常驻连接（会话列表的订阅、
// 对话的请求），和会话列表的格式。连接经 sshlink；客户端共用（Windows 桌面版链接，Android 以后经随 APK 带的 Go 程序用），
// 不碰界面，也不管是哪个客户端：Zeta 在那边的目录名、带着的程序由调用方给。
package hostlink

import (
	"fmt"
	"os"
	"runtime"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// Quote 给 shell 加单引号。
func Quote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

// RemoteCommand 是交给那边用户 shell（bash、zsh、fish 都行）的一行：换成 bash 跑 command，带上 Zeta 的目录名和这台电脑的时区。
// 命令一律在 C.UTF-8 下跑（输出要解析）；那边原来的 LANG 放进 ZETA_LANG，起 Claude Code 时用它（见 zeta_host.sh 的 host_lang）。
// login 时是 bash 的登录 shell，PATH 再补上 ~/.local/bin（Claude Code 的安装程序放在那里，有的设备只在交互 shell 里加进
// PATH）；不是时不读那边的登录配置（只用 bash、coreutils 和按绝对路径执行的 zeta-helper 的命令，省下登录配置的几十毫秒）。
func RemoteCommand(command, linuxName, timeZone string, login bool) string {
	prefix := "exec env ZETA_NAME=" + Quote(linuxName) + " TZ=" + Quote(timeZone) + ` ZETA_LANG="$LANG" LANG=C.UTF-8 TERM=dumb bash `
	if login {
		return prefix + "-lc " + Quote(`PATH="$PATH:$HOME/.local/bin"; `+command)
	}
	return prefix + "-c " + Quote(command)
}

// Shell 在设备 Host 上跑命令，经 Links 的连接。
type Shell struct {
	Links *sshlink.Links
	Host  sshlink.Host
	Name  string // Zeta 在那边的目录名（ZETA_NAME）：同一个包的几个客户端用同一个，见 RemoteCommand
	TZ    string // 交给那边的时区（TZ 环境变量）
}

// NewShell 是设备 h 上的 Shell，目录名是 name（ZETA_NAME），时区照这台电脑的。
func NewShell(links *sshlink.Links, h sshlink.Host, name string) Shell {
	return Shell{Links: links, Host: h, Name: name, TZ: LocalTimeZone()}
}

// Start 在那边的登录 shell 里起 command。
func (s Shell) Start(command string) (*sshlink.Process, error) {
	return s.Links.Start(s.Host, RemoteCommand(command, s.Name, s.TZ, true))
}

// StartPlain 同 Start，只是不起登录 shell（见 RemoteCommand）。
func (s Shell) StartPlain(command string) (*sshlink.Process, error) {
	return s.Links.Start(s.Host, RemoteCommand(command, s.Name, s.TZ, false))
}

// Run 跑完 command 收齐输出：stdin 写进标准输入后关掉，到 timeout 还没结束就关掉通道、结果标成超时。plain 时经 StartPlain 起。
// 连不上时返回错误（一句给人看的话）。
func (s Shell) Run(command string, stdin []byte, timeout time.Duration, plain bool) (sshlink.Result, error) {
	start := s.Start
	if plain {
		start = s.StartPlain
	}
	p, err := start(command)
	if err != nil {
		return sshlink.Result{}, err
	}
	return p.Capture(stdin, timeout), nil
}

// LinkProblem：命令以 r 结束时是连接断了的话给一句话（「连不上「名字」：原因」），别的情况是空。
func (s Shell) LinkProblem(r sshlink.Result) string {
	if r.Code != sshlink.ConnectionFailed || r.TimedOut {
		return ""
	}
	why := lastLine(r.Stderr)
	if why == "" {
		why = "连接断了"
	}
	return "连不上「" + s.Host.Name() + "」：" + why
}

// LocalTimeZone 是交给那边的时区：TZ 环境变量，或系统时区的 IANA 名字；Windows 上 Go 拿不到 IANA 名字，
// 换成 POSIX 写法的固定偏移（如 <+08>-8），时刻对、夏令时不跟。
func LocalTimeZone() string {
	if tz := os.Getenv("TZ"); tz != "" {
		return tz
	}
	if name := time.Local.String(); name != "Local" && name != "" {
		return name
	}
	if runtime.GOOS != "windows" {
		if link, err := os.Readlink("/etc/localtime"); err == nil {
			if _, name, ok := strings.Cut(link, "zoneinfo/"); ok {
				return name
			}
		}
	}
	return posixZone(time.Now())
}

// posixZone 是 now 所在时区的固定偏移，写成 POSIX 的 TZ（东八区是 <+08>-8，偏移的正负和 UTC+8 相反）。
func posixZone(now time.Time) string {
	_, offset := now.Zone()
	sign, inverse := "+", "-"
	if offset < 0 {
		sign, inverse, offset = "-", "", -offset
	}
	h, m := offset/3600, offset%3600/60
	if m != 0 {
		return fmt.Sprintf("<%s%02d%02d>%s%d:%02d", sign, h, m, inverse, h, m)
	}
	return fmt.Sprintf("<%s%02d>%s%d", sign, h, inverse, h)
}

func lastLine(text string) string {
	lines := strings.Split(strings.TrimSpace(text), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

// tail 是 text 去掉首尾空白后的最后 n 个字符。
func tail(text string, n int) string {
	r := []rune(strings.TrimSpace(text))
	if len(r) > n {
		r = r[len(r)-n:]
	}
	return string(r)
}
