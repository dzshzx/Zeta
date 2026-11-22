//go:build !windows

package update

import "testing"

func TestInstallOffWindows(t *testing.T) {
	if err := (Installer{Log: t.TempDir() + "/msi.log"}).Install("Zeta.msi"); err == nil || err.Error() != "只有 Windows 版能自动安装" {
		t.Errorf("error %v", err)
	}
}
