package main

import (
	"testing"

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
