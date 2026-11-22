package update

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

// createNoWindow 是 CreateProcess 的 CREATE_NO_WINDOW：本程序是 GUI 程序，起控制台程序 powershell.exe 时不给它开控制台窗口。
const createNoWindow = 0x08000000

// Install 起安装程序：写好 install.ps1，起 PowerShell 执行它。起好返回 nil，之后本程序要马上退出。
func (in Installer) Install(msi string) error {
	me, err := self()
	if err != nil {
		return fmt.Errorf("启动安装程序失败：%w", err)
	}
	script, content, argv := in.plan(msi, os.Getpid(), me, os.Getenv("SystemRoot"))
	if err := os.MkdirAll(filepath.Dir(in.Log), 0o755); err != nil {
		return fmt.Errorf("启动安装程序失败：%w", err)
	}
	if err := os.WriteFile(script, content, 0o644); err != nil {
		return fmt.Errorf("启动安装程序失败：%w", err)
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: createNoWindow}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动安装程序失败：%w", err)
	}
	return cmd.Process.Release()
}
