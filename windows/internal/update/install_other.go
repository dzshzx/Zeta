//go:build !windows

package update

import (
	"errors"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// Install 只在 Windows 上能装；别的系统（开发时在 Linux 上跑）说明原因。
func (Installer) Install(string) error { return errors.New(locales.UpdateInstallWindowsOnly()) }
