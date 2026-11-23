package host

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// installTimeout 是装一个程序最多等多久（要传几 MB 过去）。
const installTimeout = 2 * time.Minute

// ClaudeSwitch 是带到设备 h 上的 claude-switch（账号与额度用）：装好后的路径随 accounts.* 请求带给 serve，
// serve 说没装时装上。Devices.Switch 为 nil 时路径为空、装不上。
func (d *Devices) ClaudeSwitch(h sshlink.Host) accounts.Program {
	return installed{shell: hostlink.NewShell(d.Links, h, LinuxName), program: d.Switch}
}

// installed 是带到一台设备上的一个程序。
type installed struct {
	shell   hostlink.Shell
	program *hostlink.Program
}

func (i installed) Path() string {
	if i.program == nil {
		return ""
	}
	return i.program.RelativePath(i.shell.Name)
}

// Install 没装就装上。ctx 到期时不再等那边的结果（命令本身由 installTimeout 收尾）。
func (i installed) Install(ctx context.Context) error {
	if i.program == nil {
		return errors.New(locales.HostProgramMissing())
	}
	exec := func(command string, stdin []byte) (sshlink.Result, error) {
		return i.shell.Run(command, stdin, installTimeout, true)
	}
	done := make(chan error, 1)
	go func() {
		r, err := i.program.Ensure(exec, i.shell.Name)
		switch {
		case err != nil:
			done <- err
		case i.shell.LinkProblem(r) != "":
			done <- errors.New(i.shell.LinkProblem(r))
		case r.Code != 0:
			done <- errors.New(orDefault(strings.TrimSpace(r.Stderr), locales.HostProgramNotInstalled(i.program.Name)))
		default:
			done <- nil
		}
	}()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func orDefault(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
