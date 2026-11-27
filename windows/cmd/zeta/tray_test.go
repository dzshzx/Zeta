package main

import (
	"bytes"
	"testing"

	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/egoist/mygo"
)

// 关窗口：托盘起好了就藏起来；托盘起不来（窗口藏了就叫不回来）或正在退出（托盘菜单的退出、更新、关机）时照常关。
func TestHideOnClose(t *testing.T) {
	for _, c := range []struct {
		name     string
		tray     *mygo.Tray
		quitting bool
		want     bool
	}{
		{"托盘起好了", &mygo.Tray{}, false, true},
		{"正在退出", &mygo.Tray{}, true, false},
		{"没有托盘", nil, false, false},
	} {
		b := &background{tray: c.tray, quitting: c.quitting}
		if got := b.hideOnClose(); got != c.want {
			t.Errorf("%s：hideOnClose() = %v，要 %v", c.name, got, c.want)
		}
	}
}

// 托盘：正式版的样子不变；测试版换橙色图标、提示文字 Zeta Dev。
func TestTrayOptions(t *testing.T) {
	release := trayOptions(appversion.Version{Code: 812, Name: "0.7.6"})
	if release.ID != "zeta" || release.ToolTip != "Zeta" || !bytes.Equal(release.Icon, trayIcon) {
		t.Errorf("release tray: ID %q, tool tip %q", release.ID, release.ToolTip)
	}
	dev := trayOptions(appversion.Version{Code: 815, Name: "0.7.7-dev.3+gabc1234"})
	if dev.ToolTip != "Zeta Dev" || !bytes.Equal(dev.Icon, trayDevIcon) || bytes.Equal(trayIcon, trayDevIcon) {
		t.Errorf("dev tray: tool tip %q", dev.ToolTip)
	}
}
