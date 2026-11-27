package ui

import (
	"math"
	"testing"

	"github.com/dzshzx/Zeta/hub"
	"github.com/egoist/mygo/ui"
)

// Ctrl++（按不按 Shift）一档档放大、Ctrl+- 缩小、Ctrl+0 回到 100%，到头了不动；每次都马上存进设置，
// 界面真按这个倍数画（控件在窗口里的大小跟着变）。
func TestZoomShortcuts(t *testing.T) {
	f := newChatFixture(t)
	base, ok := f.tt.Find("修登录页")
	if !ok {
		t.Fatal("侧栏里没有会话")
	}
	steps := []struct {
		mods ui.Modifiers
		key  ui.Key
		want int
	}{
		{ui.Cmd, ui.KeyEqual, 110},
		{ui.Cmd | ui.Shift, ui.KeyEqual, 125},
		{ui.Cmd, ui.KeyMinus, 110},
		{ui.Cmd, ui.KeyMinus, 100},
		{ui.Cmd, ui.KeyMinus, 90},
		{ui.Cmd, ui.Key0, 100},
	}
	for i, s := range steps {
		f.tt.Key(s.mods, s.key)
		f.tt.Frame()
		if f.app.zoom != s.want || f.hub.Settings().Zoom() != s.want {
			t.Fatalf("第 %d 步缩放是 %d、存着 %d，要 %d", i, f.app.zoom, f.hub.Settings().Zoom(), s.want)
		}
	}
	f.tt.Key(ui.Cmd, ui.KeyEqual)
	f.tt.Frame()
	r, _ := f.tt.Find("修登录页")
	if math.Abs(float64(r.W/base.W-1.1)) > 0.02 {
		t.Fatalf("110%% 时会话行的字宽 %v，100%% 时 %v", r.W, base.W)
	}
	for range 20 {
		f.tt.Key(ui.Cmd, ui.KeyEqual)
	}
	f.tt.Frame()
	if f.app.zoom != zoomLevels[len(zoomLevels)-1] {
		t.Fatalf("一直放大停在 %d", f.app.zoom)
	}
	for range 20 {
		f.tt.Key(ui.Cmd, ui.KeyMinus)
	}
	f.tt.Frame()
	if f.app.zoom != zoomLevels[0] {
		t.Fatalf("一直缩小停在 %d", f.app.zoom)
	}
}

// 焦点在输入框里时缩放的键照样管用，也不往输入框里打字。
func TestZoomWhileTyping(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.click(t, "消息")
	f.tt.Type("你好")
	f.tt.Key(ui.Cmd, ui.KeyEqual)
	f.tt.Key(ui.Cmd, ui.Key0)
	f.tt.Key(ui.Cmd, ui.KeyMinus)
	f.tt.Frame()
	if f.app.zoom != 90 {
		t.Fatalf("输入框里按缩放键以后缩放是 %d", f.app.zoom)
	}
	if f.app.pane.draft != "你好" {
		t.Fatalf("输入框里成了 %q", f.app.pane.draft)
	}
}

// 设置「常规」里选一档，存进设置；下次打开窗口照存着的，存着的不是档位上的数时取最近的一档。
func TestZoomInSettings(t *testing.T) {
	f := newFixture(t)
	f.app.open(pageSettings)
	f.tt.Frame()
	f.see(t, "界面缩放", "100%", "Ctrl++", "放大界面", "Ctrl+-", "Ctrl+0")
	f.click(t, "100%")
	f.clickMenuItem(t, "150%")
	if f.app.zoom != 150 || f.hub.Settings().Zoom() != 150 {
		t.Fatalf("选了 150%%：缩放 %d、存着 %d", f.app.zoom, f.hub.Settings().Zoom())
	}
	sync := func(fn func()) { fn() }
	if got := New(f.hub, sync, sync).zoom; got != 150 {
		t.Fatalf("重开窗口缩放是 %d", got)
	}
	f.hub.Settings().SetZoom(130)
	if got := New(hub.New(f.hub.Settings(), f.devices, sync, sync), sync, sync).zoom; got != 125 {
		t.Fatalf("存着 130 时取了 %d", got)
	}
}
