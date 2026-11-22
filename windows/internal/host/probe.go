package host

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/remote"
)

// ProbeScript 是加设备、检查连接时在那边查的几样：家目录、claude 的版本、系统和 CPU、主机名。每样一行“zeta_<名字> <值>”，
// 查不到的值是空的；登录 shell 打出来的别的行（欢迎语之类）跳过（同 RemoteHosts.ProbeScript）。
const ProbeScript = `printf 'zeta_home %s\n' "$HOME"
printf 'zeta_claude %s\n' "$(claude --version 2>/dev/null | head -n 1)"
printf 'zeta_system %s\n' "$(uname -sm 2>/dev/null)"
printf 'zeta_hostname %s\n' "$(uname -n 2>/dev/null)"`

// probeTimeout 是探测最多等多久。
const probeTimeout = 45 * time.Second

// Found 是连一次查到的情况。HostKey 是对方的主机公钥（base64），加设备时记下。
type Found struct {
	Home     string
	Claude   string // 版本号
	System   string // uname -sm
	Hostname string // 查不到是空
	HostKey  string
}

// Summary 是给人看的一行：Claude Code 的版本和系统。
func (f Found) Summary() string {
	parts := []string{"Claude Code " + f.Claude}
	if f.System != "" {
		parts = append(parts, f.System)
	}
	return strings.Join(parts, " · ")
}

// Probe 直连地址 target（私钥 key，空照 ~/.ssh/config）查一次。expected 是记下的主机公钥（重新检查时），加设备时是空：谁都认，
// 看到的放进结果。指纹不对时返回 *remote.HostKeyChangedError，别的问题是一句给人看的话。阻塞。
func Probe(links *remote.Links, target, expected, key string) (Found, error) {
	command := RemoteCommand(ProbeScript, LinuxName, LocalTimeZone(), true)
	seen, r, err := links.Probe(target, key, expected, command, probeTimeout)
	if err != nil {
		return Found{}, err
	}
	f, err := parseProbe(r)
	f.HostKey = seen
	return f, err
}

// parseProbe 看 ProbeScript 跑出来的结果。
func parseProbe(r remote.Result) (Found, error) {
	values := map[string]string{}
	for _, line := range strings.Split(r.Stdout, "\n") {
		if rest, ok := strings.CutPrefix(line, "zeta_"); ok {
			name, value, _ := strings.Cut(rest, " ")
			values[name] = strings.TrimSpace(value)
		}
	}
	f := Found{Home: values["home"], System: values["system"], Hostname: values["hostname"]}
	f.Claude, _, _ = strings.Cut(values["claude"], " (")
	switch {
	case r.TimedOut:
		return f, remote.Problem("连接超时")
	case r.Code == remote.ConnectionFailed || f.Home == "":
		why := lastLine(r.Stderr)
		if why == "" {
			why = "没有连上"
		}
		return f, remote.Problem(why)
	case f.Claude == "":
		return f, remote.Problem("那台设备上找不到 claude（登录 shell 的 PATH 里和 ~/.local/bin 都没有）")
	case !HelperRuns(f.System):
		system := f.System
		if system == "" {
			system = "未知的系统"
		}
		return f, remote.Problem("Zeta 读会话记录的程序只能在 arm64、x86-64 的 Linux 上跑，这台是 " + system)
	}
	return f, nil
}
