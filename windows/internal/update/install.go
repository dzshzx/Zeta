package update

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Installer 在 Windows 上装下载好、核对过的 MSI：起一个 PowerShell，它等本进程退出（MSI 要换掉正在跑的 Zeta.exe）→
// msiexec /i <msi> /passive /norestart 等它装完 → 用本进程启动时的同一条命令重新打开 Zeta（装没装上都重开：没装上时 MSI 已回滚到旧版）。
//
// 脚本写成 MSI 旁边的 install.ps1（UTF-8 带 BOM：Windows PowerShell 5.1 读不带 BOM 的文件按系统代码页解码，中文路径会乱），
// 用 powershell.exe -NoProfile -ExecutionPolicy Bypass -File 起。不用 -EncodedCommand、-WindowStyle Hidden：有的杀毒软件
// （实测卡巴斯基）把「隐藏窗口加编码命令」当恶意脚本拒绝起进程，还会把本进程之后起的进程全拦下；控制台窗口由起进程时的
// CREATE_NO_WINDOW 免掉。对话的 Claude Code 进程在远程主机上由托管脚本管着，Zeta 退出不影响。
type Installer struct {
	Log string // msiexec 的安装记录（/l），装不上时看它；放在设置目录的 diag/msi-install.log
}

// scriptName 是安装脚本的文件名，和 MSI 放在一起（下次启动清理下载目录时一并删掉）。
const scriptName = "install.ps1"

// launch 是一条要执行的命令。
type launch struct {
	exe  string
	args []string
	dir  string
}

// plan 是装 msi 要写的脚本文件（路径、内容）和起它的命令行。self 是本进程（重开用），systemRoot 是 %SystemRoot%。
func (in Installer) plan(msi string, pid int, self launch, systemRoot string) (script string, content []byte, argv []string) {
	script = filepath.Join(filepath.Dir(msi), scriptName)
	return script, withBOM(installScript(pid, msi, in.Log, self)), powershell(systemRoot, script)
}

// installScript 是安装脚本：等进程 pid 退出，装 msi（记录写到 log），再执行 reopen。本进程过两分钟还没退出就不装了。
func installScript(pid int, msi, log string, reopen launch) string {
	p := strconv.Itoa(pid)
	open := "Start-Process -FilePath " + psQuote(reopen.exe)
	if len(reopen.args) > 0 {
		open += " -ArgumentList " + psQuote(commandLine(reopen.args))
	}
	open += " -WorkingDirectory " + psQuote(reopen.dir)
	return strings.Join([]string{
		"Wait-Process -Id " + p + " -Timeout 120 -ErrorAction SilentlyContinue",
		"if (Get-Process -Id " + p + " -ErrorAction SilentlyContinue) { exit 1 }",
		"Start-Process -FilePath 'msiexec.exe' -ArgumentList " + psQuote(commandLine([]string{"/i", msi, "/passive", "/norestart", "/l", log})) + " -Wait",
		open,
	}, "\n")
}

// powershell 是起 PowerShell 执行脚本文件的命令行。-ExecutionPolicy Bypass 只管这一次，免得系统策略禁了本地脚本。
func powershell(systemRoot, script string) []string {
	exe := "powershell.exe"
	if systemRoot != "" {
		exe = systemRoot + `\System32\WindowsPowerShell\v1.0\powershell.exe`
	}
	return []string{exe, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script}
}

// withBOM 是脚本文件的内容：UTF-8 带 BOM。
func withBOM(script string) []byte { return append([]byte{0xEF, 0xBB, 0xBF}, script...) }

// psQuote 是 PowerShell 的单引号字符串：里面的单引号写两遍，别的字符都不转义。
func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// commandLine 把参数拼成 Windows 的命令行，按 CommandLineToArgvW 的规则：带空白、引号或为空的加双引号，引号前的反斜杠加倍。
func commandLine(args []string) string {
	quoted := make([]string, len(args))
	for i, a := range args {
		quoted[i] = quoteArg(a)
	}
	return strings.Join(quoted, " ")
}

func quoteArg(a string) string {
	if a != "" && !strings.ContainsAny(a, " \t\"") {
		return a
	}
	var b strings.Builder
	b.WriteByte('"')
	slashes := 0
	for _, r := range a {
		switch r {
		case '\\':
			slashes++
			continue
		case '"':
			b.WriteString(strings.Repeat(`\`, slashes*2+1))
		default:
			b.WriteString(strings.Repeat(`\`, slashes))
		}
		slashes = 0
		b.WriteRune(r)
	}
	b.WriteString(strings.Repeat(`\`, slashes*2))
	b.WriteByte('"')
	return b.String()
}

// self 是本进程的重开命令：同一个 exe、同样的参数、同一个工作目录。
func self() (launch, error) {
	exe, err := os.Executable()
	if err != nil {
		return launch{}, err
	}
	dir, err := os.Getwd()
	if err != nil {
		dir = filepath.Dir(exe)
	}
	return launch{exe: exe, args: os.Args[1:], dir: dir}, nil
}
