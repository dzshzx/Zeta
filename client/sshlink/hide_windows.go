package sshlink

import (
	"os/exec"
	"syscall"
)

// hideWindow：图形界面程序起控制台程序（ssh.exe）时不弹黑框。
func hideWindow(cmd *exec.Cmd) {
	const createNoWindow = 0x08000000
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}
