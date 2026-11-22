package host

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/assets"
	"github.com/dzshzx/Zeta/windows/internal/remote"
)

// 程序名，也是 assets 里的目录名和装好的文件名的前缀（彼此不能是对方的前缀：装新版时按前缀删旧版）。
const (
	ZetaHelper   = "zeta-helper"
	ClaudeSwitch = "claude-switch"
)

// Missing 是没装时 Program.Command 的退出码；标准错误里有一行“<程序名>-missing <uname -m>”。
const Missing = 97

// arches 是带着的两份程序，先后也是算版本时的先后（同 HelperProgram.ARCHES）。
var arches = []string{"arm64", "amd64"}

// Program 是带到设备上跑的 Go 程序（zeta-helper、claude-switch），每个带 arm64、amd64 两份。
// 程序不随每条命令传：第一次用时装到那边的 ~/.cache/zeta/bin/<程序名>-<版本>，之后直接执行装好的。版本是两份程序内容的哈希，
// 和手机版的算法一样，所以手机版和桌面版装的是同一个文件（同 shared 的 HelperProgram.kt）。
type Program struct {
	Name     string
	Version  string
	binaries map[string][]byte
}

// LoadProgram 从编进程序的 assets 里取程序 name 的两份。
func LoadProgram(name string) (*Program, error) {
	binaries := map[string][]byte{}
	for _, arch := range arches {
		data, err := assets.Read(name + "/linux-" + arch)
		if err != nil {
			return nil, fmt.Errorf("桌面版里没有 %s：%w", name, err)
		}
		binaries[arch] = data
	}
	return NewProgram(name, binaries), nil
}

// NewProgram 是由 binaries（arm64、amd64 各一份）组成的程序 name。
func NewProgram(name string, binaries map[string][]byte) *Program {
	h := sha256.New()
	for _, arch := range arches {
		h.Write(binaries[arch])
	}
	return &Program{Name: name, Version: hex.EncodeToString(h.Sum(nil))[:16], binaries: binaries}
}

// RelativePath 是装好的程序相对那边家目录的路径。
func (p *Program) RelativePath() string {
	return ".cache/" + LinuxName + "/bin/" + p.Name + "-" + p.Version
}

func (p *Program) dir() string  { return `"$HOME/.cache/` + LinuxName + `/bin"` }
func (p *Program) file() string { return `"$HOME/` + p.RelativePath() + `"` }

// check 看装没装，没装就报出那台的 CPU 架构、以 Missing 退出。
func (p *Program) check() string {
	return "f=" + p.file() + `; [ -x "$f" ] || { echo "` + p.Name + `-missing $(uname -m)" >&2; exit ` + fmt.Sprint(Missing) + "; }"
}

// Command 是跑子命令 sub 的那一行，参数逐个加引号：装好了就执行，没装就报架构、以 Missing 退出。
func (p *Program) Command(sub string, args ...string) string {
	var b strings.Builder
	b.WriteString(p.check() + `; exec "$f" ` + sub)
	for _, a := range args {
		b.WriteString(" " + Quote(a))
	}
	return b.String()
}

// InstallCommand 把经标准输入传来的程序 binary 装上：写进同一目录下的临时文件，核对哈希（传到一半断了的不装），改名成正式的名字，
// 再删掉这个程序别的版本；zeta-helper 还让不带版本的链接指向它（电脑那边 tmux 窗口、Claude Code 的 hook 用这个固定的名字）。
func (p *Program) InstallCommand(binary []byte) string {
	sum := sha256.Sum256(binary)
	link := ""
	if p.Name == ZetaHelper {
		link = `ln -sfn "` + p.Name + "-" + p.Version + `" "$d/` + p.Name + `"; `
	}
	return "d=" + p.dir() + `; mkdir -p "$d" && t=$(mktemp "$d/.` + p.Name + `.XXXXXX") || exit 1; ` +
		`if cat > "$t" && [ "$(sha256sum < "$t")" = "` + hex.EncodeToString(sum[:]) + `  -" ] && chmod 755 "$t" && mv -f "$t" ` + p.file() + "; then " +
		`for o in "$d"/` + p.Name + "-*; do [ \"$o\" = " + p.file() + ` ] || rm -f "$o"; done; ` +
		link +
		`else rm -f "$t"; echo '` + p.Name + ` 没有完整传过去' >&2; exit 1; fi`
}

// Exec 在那边跑一行（plain，可带标准输入），返回结果；连不上时是错误。
type Exec func(command string, stdin []byte) (remote.Result, error)

// Run 经 exec 跑子命令 sub；没装就先装上再跑一次。
func (p *Program) Run(exec Exec, sub string, args ...string) (remote.Result, error) {
	return p.installing(exec, func() (remote.Result, error) { return exec(p.Command(sub, args...), nil) })
}

// Ensure 只确认装好了（没装就装上）：要自己起进程（serve）之前用。装好了是退出码 0 的结果。
func (p *Program) Ensure(exec Exec) (remote.Result, error) {
	return p.installing(exec, func() (remote.Result, error) { return exec(p.check(), nil) })
}

func (p *Program) installing(exec Exec, attempt func() (remote.Result, error)) (remote.Result, error) {
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
		return remote.Result{Code: Missing, Stderr: p.Name + " 没有这台机器（" + machine + "）能用的版本"}, nil
	}
	installed, err := exec(p.InstallCommand(binary), binary)
	if err != nil || installed.Code != 0 {
		installed.Stderr = "装 " + p.Name + " 没成功：" + strings.TrimSpace(installed.Stderr)
		return installed, err
	}
	return attempt()
}

// missingOn：r 是“没装”的话，那台的 uname -m。
func (p *Program) missingOn(r remote.Result) (machine string, missing bool) {
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
	r, err := p.Run(s.plainExec(timeout), sub, args...)
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
	return func(command string, stdin []byte) (remote.Result, error) {
		limit := timeout
		if stdin != nil && limit < time.Minute {
			limit = time.Minute
		}
		return s.Run(command, stdin, limit, true)
	}
}
