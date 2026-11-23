package update

import (
	"bytes"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestInstallPlan(t *testing.T) {
	in := Installer{Log: `C:\Users\张三\AppData\Roaming\Zeta\diag\msi-install.log`}
	msi := `C:\Users\张三\AppData\Local\Zeta\update\Zeta-v0.7.6.msi`
	self := launch{exe: `C:\Users\张三\AppData\Local\Programs\Zeta\Zeta.exe`, args: []string{"--from", "it's here"}, dir: `C:\Users\张三`}
	script, content, argv := in.plan(msi, 4242, self, `C:\Windows`)

	// 安装脚本和 MSI 放在一起（下次启动清理下载目录时一并删掉）
	if want := filepath.Join(filepath.Dir(msi), "install.ps1"); script != want {
		t.Errorf("script at %q", script)
	}
	// 起 PowerShell 执行脚本文件，不用 -EncodedCommand、-WindowStyle Hidden（杀毒软件会拦）
	wantArgv := []string{`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script}
	if !slices.Equal(argv, wantArgv) {
		t.Errorf("argv %q\nwant %q", argv, wantArgv)
	}
	for _, a := range argv {
		if strings.EqualFold(a, "-EncodedCommand") || strings.EqualFold(a, "-WindowStyle") || strings.EqualFold(a, "-Command") {
			t.Errorf("argv has %s", a)
		}
	}
	// UTF-8 带 BOM，Windows PowerShell 5.1 才按 UTF-8 读中文路径
	if !bytes.HasPrefix(content, []byte{0xEF, 0xBB, 0xBF}) {
		t.Errorf("no BOM: % x", content[:3])
	}
	want := strings.Join([]string{
		"Wait-Process -Id 4242 -Timeout 120 -ErrorAction SilentlyContinue",
		"if (Get-Process -Id 4242 -ErrorAction SilentlyContinue) { exit 1 }",
		`Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i C:\Users\张三\AppData\Local\Zeta\update\Zeta-v0.7.6.msi /passive /norestart /l C:\Users\张三\AppData\Roaming\Zeta\diag\msi-install.log' -Wait`,
		`Start-Process -FilePath 'C:\Users\张三\AppData\Local\Programs\Zeta\Zeta.exe' -ArgumentList '--from "it''s here"' -WorkingDirectory 'C:\Users\张三'`,
	}, "\n")
	if got := string(content[3:]); got != want {
		t.Errorf("script\n%s\nwant\n%s", got, want)
	}
}

func TestInstallScriptQuoting(t *testing.T) {
	got := installScript(7, `C:\My Files\O'Neil\Zeta.msi`, `C:\Zeta\diag\msi-install.log`, launch{exe: `C:\Zeta\Zeta.exe`, dir: `C:\Zeta`})
	lines := strings.Split(got, "\n")
	if want := `Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i "C:\My Files\O''Neil\Zeta.msi" /passive /norestart /l C:\Zeta\diag\msi-install.log' -Wait`; lines[2] != want {
		t.Errorf("install line\n%s\nwant\n%s", lines[2], want)
	}
	// 没有参数时不写 -ArgumentList（空的会让 Start-Process 报错）
	if want := `Start-Process -FilePath 'C:\Zeta\Zeta.exe' -WorkingDirectory 'C:\Zeta'`; lines[3] != want {
		t.Errorf("reopen line\n%s\nwant\n%s", lines[3], want)
	}
}

func TestPowerShellWithoutSystemRoot(t *testing.T) {
	if got := powershell("", `C:\x\install.ps1`); got[0] != "powershell.exe" {
		t.Errorf("argv %q", got)
	}
}

// commandLine 按 CommandLineToArgvW 的规则拼，和 Go 在 Windows 上的 syscall.EscapeArg 一样。
func TestCommandLine(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"/i", `C:\a\b.msi`}, `/i C:\a\b.msi`},
		{[]string{""}, `""`},
		{[]string{"a b"}, `"a b"`},
		{[]string{`C:\with space\`}, `"C:\with space\\"`},
		{[]string{`say "hi"`}, `"say \"hi\""`},
		{[]string{`a\"b`}, `"a\\\"b"`},
		{[]string{`tab	here`}, "\"tab\there\""},
	} {
		if got := commandLine(tc.args); got != tc.want {
			t.Errorf("commandLine(%q) = %s, want %s", tc.args, got, tc.want)
		}
	}
}

func TestSelf(t *testing.T) {
	me, err := self()
	if err != nil {
		t.Fatal(err)
	}
	exe, _ := os.Executable()
	wd, _ := os.Getwd()
	if me.exe != exe || me.dir != wd || !slices.Equal(me.args, os.Args[1:]) {
		t.Errorf("self %+v", me)
	}
}
