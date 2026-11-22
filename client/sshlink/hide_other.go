//go:build !windows

package sshlink

import "os/exec"

// hideWindow 只在 Windows 上有事做。
func hideWindow(*exec.Cmd) {}
