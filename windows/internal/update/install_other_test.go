//go:build !windows

package update

import "testing"

func TestInstallOffWindows(t *testing.T) {
	if err := (Installer{Log: t.TempDir() + "/msi.log"}).Install("Zeta.msi"); err == nil || err.Error() != "仅 Windows 版支持自动安装" {
		t.Errorf("error %v", err)
	}
}
