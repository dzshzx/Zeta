package host

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/hub/locales"
)

// ProbeScript 是加设备、检查连接时在那边查的几样：家目录、claude 与 agy 的版本、系统和 CPU、主机名。每样一行“zeta_<名字> <值>”，
// 查不到的值是空的；登录 shell 打出来的别的行（欢迎语之类）跳过（同 RemoteHosts.ProbeScript）。
const ProbeScript = `printf 'zeta_home %s\n' "$HOME"
printf 'zeta_claude %s\n' "$(claude --version 2>/dev/null | head -n 1)"
printf 'zeta_agy %s\n' "$(agy --version 2>/dev/null | head -n 1)"
printf 'zeta_system %s\n' "$(uname -sm 2>/dev/null)"
printf 'zeta_hostname %s\n' "$(uname -n 2>/dev/null)"`

// probeTimeout 是探测最多等多久。
const probeTimeout = 45 * time.Second

// Found 是连一次查到的情况。HostKey 是对方的主机公钥（base64），加设备时记下。
type Found struct {
	Home     string
	Claude   string // 版本号
	Agy      string // 版本号
	System   string // uname -sm
	Hostname string // 查不到是空
	HostKey  string
}

// DefaultHarness 返回根据探测结果推荐的默认 CLI 工具：仅安装了 agy 时为 "agy"，其余默认为 "claude"。
func (f Found) DefaultHarness() string {
	if f.Claude == "" && f.Agy != "" {
		return hostlink.HarnessAgy
	}
	return hostlink.HarnessClaude
}

// InstalledHarnesses 返回已检测到的 CLI 工具列表（"claude"、"agy"）。
func (f Found) InstalledHarnesses() []string {
	var out []string
	if f.Claude != "" {
		out = append(out, hostlink.HarnessClaude)
	}
	if f.Agy != "" {
		out = append(out, hostlink.HarnessAgy)
	}
	return out
}

// Summary 是给人看的一行：已检测到的 CLI 版本和系统。
func (f Found) Summary() string {
	var parts []string
	if f.Claude != "" {
		parts = append(parts, "Claude Code "+f.Claude)
	}
	if f.Agy != "" {
		parts = append(parts, "Antigravity "+f.Agy)
	}
	if f.System != "" {
		parts = append(parts, f.System)
	}
	return strings.Join(parts, " · ")
}

// Probe 直连地址 target（私钥 key，空照 ~/.ssh/config）查一次。expected 是记下的主机公钥（重新检查时），加设备时是空：谁都认，
// 看到的放进结果。指纹不对时返回 *sshlink.HostKeyChangedError，别的问题是一句给人看的话。阻塞。
func Probe(links *sshlink.Links, target, expected, key string) (Found, error) {
	command := hostlink.RemoteCommand(ProbeScript, LinuxName, hostlink.LocalTimeZone(), true)
	seen, r, err := links.Probe(target, key, expected, command, probeTimeout)
	if err != nil {
		return Found{}, err
	}
	f, err := parseProbe(r)
	f.HostKey = seen
	return f, err
}

// parseProbe 看 ProbeScript 跑出来的结果。
func parseProbe(r sshlink.Result) (Found, error) {
	values := map[string]string{}
	for _, line := range strings.Split(r.Stdout, "\n") {
		if rest, ok := strings.CutPrefix(line, "zeta_"); ok {
			name, value, _ := strings.Cut(rest, " ")
			values[name] = strings.TrimSpace(value)
		}
	}
	f := Found{Home: values["home"], System: values["system"], Hostname: values["hostname"]}
	f.Claude = cleanCLIVersion(values["claude"], "claude")
	f.Agy = cleanCLIVersion(values["agy"], "agy", "antigravity")
	switch {
	case r.TimedOut:
		return f, sshlink.Problem(locales.HostProbeTimeout())
	case r.Code == sshlink.ConnectionFailed || f.Home == "":
		why := lastLine(r.Stderr)
		if why == "" {
			why = locales.HostProbeNotConnected()
		}
		return f, sshlink.Problem(why)
	case f.Claude == "" && f.Agy == "":
		return f, sshlink.Problem(locales.HostProbeNoClaude())
	case !hostlink.HelperRuns(f.System):
		system := f.System
		if system == "" {
			system = locales.HostProbeUnknownSystem()
		}
		return f, sshlink.Problem(locales.HostProbeUnsupported(system))
	}
	return f, nil
}

// cleanCLIVersion 清理 --version 输出的首行，去掉括号后缀与前导命令名。
func cleanCLIVersion(raw string, names ...string) string {
	v, _, _ := strings.Cut(strings.TrimSpace(raw), " (")
	lower := strings.ToLower(v)
	for _, n := range names {
		if strings.HasPrefix(lower, n+" ") {
			v = strings.TrimSpace(v[len(n)+1:])
			lower = strings.ToLower(v)
		}
	}
	if strings.HasPrefix(lower, "version ") {
		v = strings.TrimSpace(v[len("version "):])
	}
	return v
}

// lastLine 是 text 去掉首尾空白后的最后一行。
func lastLine(text string) string {
	lines := strings.Split(strings.TrimSpace(text), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}
