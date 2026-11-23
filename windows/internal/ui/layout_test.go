package ui

import (
	"testing"

	"github.com/egoist/mygo/ui"
)

// opened 是聊天窗格里开着的会话号（新对话没有会话号时是空）。
func (f *chatFixture) opened() string {
	if c := f.hub.Chat(); c != nil {
		return c.Session.ID
	}
	return ""
}

// 换会话的快捷键：Ctrl+1 是列表里第一个，Ctrl+Tab、Ctrl+Shift+Tab 往下、往上走，到头绕回去；Ctrl+N 开新对话。
func TestSessionShortcuts(t *testing.T) {
	f := newChatFixture(t)
	f.listSample()
	// 列表的先后：今天的 s1、s2，更早的 s3
	steps := []struct {
		mods ui.Modifiers
		key  ui.Key
		want string
	}{
		{ui.Cmd, ui.Key1, "s1"},
		{ui.Ctrl, ui.KeyTab, "s2"},
		{ui.Ctrl, ui.KeyTab, "s3"},
		{ui.Ctrl, ui.KeyTab, "s1"},
		{ui.Ctrl | ui.Shift, ui.KeyTab, "s3"},
		{ui.Cmd, ui.Key2, "s2"},
		{ui.Cmd, ui.Key9, "s2"}, // 没有第九个：不动
		{ui.Ctrl | ui.Alt, ui.KeyBracketRight, "s3"},
		{ui.Ctrl | ui.Alt, ui.KeyBracketLeft, "s2"},
		{ui.Ctrl | ui.Alt, ui.KeyRight, "s3"},
		{ui.Ctrl | ui.Alt, ui.KeyLeft, "s2"},
	}
	for i, s := range steps {
		f.tt.Key(s.mods, s.key)
		f.tt.Frame()
		if got := f.opened(); got != s.want {
			t.Fatalf("第 %d 步开着 %q，要 %q", i, got, s.want)
		}
		f.reportSession(s.want)
	}
	f.tt.Key(ui.Cmd, ui.KeyN)
	f.tt.Frame()
	if got := f.opened(); got != "" {
		t.Fatalf("Ctrl+N 以后开着 %q，要新对话", got)
	}
	f.tt.Key(ui.Cmd, ui.Key1)
	f.tt.Frame()
	f.reportSession("s1")
	f.tt.Key(ui.Cmd|ui.Shift, ui.KeyO)
	f.tt.Frame()
	if got := f.opened(); got != "" {
		t.Fatalf("Ctrl+Shift+O 以后开着 %q，要新对话", got)
	}
}

// Ctrl+, 打开设置；Ctrl+K 把焦点放进侧栏的搜索框，打字就是搜索。
func TestSettingsAndSearchShortcuts(t *testing.T) {
	f := newChatFixture(t)
	f.tt.Key(ui.Cmd, ui.KeyComma)
	f.tt.Frame()
	if f.app.page != pageSettings {
		t.Fatalf("Ctrl+, 以后在 %q 页", f.app.page)
	}
	f.tt.Key(ui.Cmd, ui.KeyK)
	f.tt.Frame()
	f.tt.Type("登录")
	f.tt.Frame()
	if f.app.query != "登录" {
		t.Fatalf("Ctrl+K 以后打的字进了 %q", f.app.query)
	}
}

// Ctrl+B 收起侧栏（马上存进设置），左上角留展开侧栏和新对话；再按一次展开。侧栏收着时 Ctrl+K 先展开它。
func TestSidebarCollapses(t *testing.T) {
	f := newChatFixture(t)
	f.see(t, "修登录页")
	f.tt.Key(ui.Cmd, ui.KeyB)
	f.tt.Frame()
	f.notSee(t, "修登录页")
	if !f.hub.Settings().SidebarHidden() {
		t.Fatal("收起侧栏没存进设置")
	}
	if _, ok := f.tt.AccessByRole(ui.RoleButton, "展开侧栏 (Ctrl+B)"); !ok {
		t.Fatal("收起后没有展开侧栏的按钮")
	}
	f.tt.Key(ui.Cmd, ui.KeyK)
	f.tt.Frame()
	f.see(t, "修登录页")
	if f.hub.Settings().SidebarHidden() {
		t.Fatal("Ctrl+K 展开侧栏没存进设置")
	}
	f.tt.Key(ui.Cmd|ui.Shift, ui.KeyS)
	f.tt.Frame()
	f.notSee(t, "修登录页")
	f.tt.Key(ui.Cmd|ui.Shift, ui.KeyS)
	f.tt.Frame()
	f.see(t, "修登录页")
}

// 拖过的侧栏宽度关窗口时存进设置，下次打开照它；拖不出 sidebarMin 到 sidebarMax。
func TestSidebarWidthIsSaved(t *testing.T) {
	f := newChatFixture(t)
	f.app.SaveLayout() // 没拖过：不写
	if _, ok := f.hub.Settings().Store().Int("desktopSidebarWidth"); ok {
		t.Fatal("没拖过也存了宽度")
	}
	f.app.layout.width, f.app.layout.dirty = 333, true
	f.app.SaveLayout()
	f.app.loadLayout()
	if f.app.layout.width != 333 {
		t.Fatalf("存过以后读回 %v", f.app.layout.width)
	}
}

// reportSession 照核心那样报刚打开的对话的会话号 id（录的回放里是别的号）。
func (f *chatFixture) reportSession(id string) {
	for _, c := range f.link().Took() {
		if c.Method == "conv.open" {
			f.link().Push(`{"type":"conv.state","conv":"` + c.Conv() + `","session":"` + id + `"}`)
		}
	}
	f.tt.Frame()
}
