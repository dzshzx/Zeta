package hostlink

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// 程序名，也是装好的文件名的前缀（彼此不能是对方的前缀：装新版时按前缀删旧版）。
const (
	ZetaHelper   = "zeta-helper"
	ClaudeSwitch = "claude-switch"
)

// KeptVersions 是装新版时每个程序留几份（连刚装的在内，按装的先后留最近的，见 InstallCommand；同 HelperProgram.KEPT_VERSIONS）。
const KeptVersions = 2

// Missing 是没装时 Program.Command 的退出码；标准错误里有一行“<程序名>-missing <uname -m>”。
const Missing = 97

// Arches 是带着的两份程序的 CPU 架构（GOARCH 的写法），先后也是算版本时的先后（同 HelperProgram.ARCHES）。
var Arches = []string{"arm64", "amd64"}

// Program 是带到设备上跑的 Go 程序（zeta-helper、claude-switch），每个带 arm64、amd64 两份。
// 程序不随每条命令传：第一次用时装到那边的 ~/.cache/<ZETA_NAME>/bin/<程序名>-<版本>，之后直接执行装好的。版本是两份程序内容的哈希，
// 和手机版的算法一样，所以手机版和桌面版装的是同一个文件（同 shared 的 HelperProgram.kt）。
type Program struct {
	Name     string
	Version  string
	binaries map[string][]byte
}

// NewProgram 是由 binaries（键是 Arches 里的架构，arm64、amd64 各一份）组成的程序 name。
func NewProgram(name string, binaries map[string][]byte) *Program {
	h := sha256.New()
	for _, arch := range Arches {
		h.Write(binaries[arch])
	}
	return &Program{Name: name, Version: hex.EncodeToString(h.Sum(nil))[:16], binaries: binaries}
}

// RelativePath 是装好的程序相对那边家目录的路径，linuxName 是 Zeta 在那边的目录名（ZETA_NAME）。
func (p *Program) RelativePath(linuxName string) string {
	return ".cache/" + linuxName + "/bin/" + p.Name + "-" + p.Version
}

func (p *Program) dir(linuxName string) string { return `"$HOME/.cache/` + linuxName + `/bin"` }
func (p *Program) file(linuxName string) string {
	return `"$HOME/` + p.RelativePath(linuxName) + `"`
}

// check 看装没装，没装就报出那台的 CPU 架构、以 Missing 退出。
func (p *Program) check(linuxName string) string {
	return "f=" + p.file(linuxName) + `; [ -x "$f" ] || { echo "` + p.Name + `-missing $(uname -m)" >&2; exit ` + fmt.Sprint(Missing) + "; }"
}

// Command 是跑子命令 sub 的那一行，参数逐个加引号：装好了就执行，没装就报架构、以 Missing 退出。linuxName 同 RelativePath。
func (p *Program) Command(linuxName, sub string, args ...string) string {
	var b strings.Builder
	b.WriteString(p.check(linuxName) + `; exec "$f" ` + sub)
	for _, a := range args {
		b.WriteString(" " + Quote(a))
	}
	return b.String()
}

// InstallCommand 把经标准输入传来的程序 binary 装上：写进同一目录下的临时文件，核对哈希（传到一半断了的不装），改名成正式的名字，
// 再删掉这个程序更早的版本，只留最近装的 KeptVersions 份；zeta-helper 还让不带版本的链接指向它（电脑那边 tmux 窗口、
// Claude Code 的 hook 用这个固定的名字）。
// 留两份：同一台主机上两个版本的客户端（手机升级了、桌面版还没升）各用各的，不会互相删掉对方的版本、每次调用都重传一遍。
// 链接先建在临时名字上再改名换过去（rename 是原子的）：几处同时装时 ln -sfn 先删后建，会撞上别人刚建的链接而失败。
// 手机版 shared 的 HelperProgram.installCommand 是同一段命令，两份都要拼成 testdata/install-command.txt（两边的测试各自核对）。
func (p *Program) InstallCommand(linuxName string, binary []byte) string {
	sum := sha256.Sum256(binary)
	file := p.file(linuxName)
	link := ""
	if p.Name == ZetaHelper {
		link = `l="$d/.` + p.Name + `.link.$$"; ln -sfn "` + p.Name + "-" + p.Version + `" "$l" && mv -f "$l" "$d/` + p.Name + `"; `
	}
	return "d=" + p.dir(linuxName) + `; mkdir -p "$d" && t=$(mktemp "$d/.` + p.Name + `.XXXXXX") || exit 1; ` +
		`if cat > "$t" && [ "$(sha256sum < "$t")" = "` + hex.EncodeToString(sum[:]) + `  -" ] && chmod 755 "$t" && mv -f "$t" ` + file + "; then " +
		`ls -t "$d"/` + p.Name + "-* | tail -n +" + fmt.Sprint(KeptVersions+1) + ` | while read -r o; do [ "$o" = ` + file + ` ] || rm -f "$o"; done; ` +
		link +
		`else rm -f "$t"; echo '` + p.Name + ` 没有完整传过去' >&2; exit 1; fi`
}

// Exec 在那边跑一行（plain，可带标准输入），返回结果；连不上时是错误。
type Exec func(command string, stdin []byte) (sshlink.Result, error)

// Run 经 exec 跑子命令 sub；没装就先装上再跑一次。linuxName 同 RelativePath。
func (p *Program) Run(exec Exec, linuxName, sub string, args ...string) (sshlink.Result, error) {
	return p.installing(exec, linuxName, func() (sshlink.Result, error) { return exec(p.Command(linuxName, sub, args...), nil) })
}

// Ensure 只确认装好了（没装就装上）：要自己起进程（serve）之前用。装好了是退出码 0 的结果。linuxName 同 RelativePath。
func (p *Program) Ensure(exec Exec, linuxName string) (sshlink.Result, error) {
	return p.installing(exec, linuxName, func() (sshlink.Result, error) { return exec(p.check(linuxName), nil) })
}

func (p *Program) installing(exec Exec, linuxName string, attempt func() (sshlink.Result, error)) (sshlink.Result, error) {
	first, err := attempt()
	if err != nil {
		return first, err
	}
	machine, missing := p.missingOn(first)
	if !missing {
		return first, nil
	}
	binary := p.binaries[archOf(machine)]
	if binary == nil {
		return sshlink.Result{Code: Missing, Stderr: p.Name + " 没有这台机器（" + machine + "）能用的版本"}, nil
	}
	installed, err := exec(p.InstallCommand(linuxName, binary), binary)
	if err != nil || installed.Code != 0 {
		installed.Stderr = "装 " + p.Name + " 没成功：" + strings.TrimSpace(installed.Stderr)
		return installed, err
	}
	return attempt()
}

// missingOn：r 是“没装”的话，那台的 uname -m。
func (p *Program) missingOn(r sshlink.Result) (machine string, missing bool) {
	if r.Code != Missing {
		return "", false
	}
	for _, line := range strings.Split(r.Stderr, "\n") {
		if rest, ok := strings.CutPrefix(strings.TrimSpace(line), p.Name+"-missing "); ok {
			return strings.TrimSpace(rest), true
		}
	}
	return "", false
}

// archOf 是 uname -m 报的 machine 对应哪一份；不认识的是空。
func archOf(machine string) string {
	switch machine {
	case "aarch64", "arm64":
		return "arm64"
	case "x86_64", "amd64":
		return "amd64"
	}
	return ""
}

// HelperRuns：uname -sm 报的 system 上 zeta-helper 跑得起来（Linux，CPU 是 arm64 或 x86-64）。
func HelperRuns(system string) bool {
	i := strings.LastIndex(system, " ")
	return strings.HasPrefix(system, "Linux ") && archOf(system[i+1:]) != ""
}

// Helper 在 s 那台上跑 zeta-helper 的子命令（没装先装上），返回它的标准输出。退出码 0 才算跑成，
// 没跑成时的错误是给人看的原因：连不上、超时、它在标准错误里说的。
func (s Shell) Helper(p *Program, timeout time.Duration, sub string, args ...string) (string, error) {
	r, err := p.Run(s.plainExec(timeout), s.Name, sub, args...)
	if err != nil {
		return "", errors.New("连不上「" + s.Host.Name() + "」：" + err.Error())
	}
	if problem := s.LinkProblem(r); problem != "" {
		return "", errors.New(problem)
	}
	switch {
	case r.Code == 0:
		return r.Stdout, nil
	case r.TimedOut:
		return "", errors.New("「" + s.Host.Name() + "」没有响应（超时）")
	case strings.TrimSpace(r.Stderr) != "":
		return "", errors.New(tail(r.Stderr, 300))
	}
	return "", fmt.Errorf("%s 出错（退出码 %d）", p.Name, r.Code)
}

// plainExec 是 Program 用的 Exec：不起登录 shell，限时 timeout（装程序时至少给一分钟）。
func (s Shell) plainExec(timeout time.Duration) Exec {
	return func(command string, stdin []byte) (sshlink.Result, error) {
		limit := timeout
		if stdin != nil && limit < time.Minute {
			limit = time.Minute
		}
		return s.Run(command, stdin, limit, true)
	}
}
