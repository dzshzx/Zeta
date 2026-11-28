package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui"
)

// 新对话空白时中间只有标题；条目来了空白页就收起。
func TestEmptyChat(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.see(t, "有什么可以帮你？")
	f.replay("turn")
	f.notSee(t, "有什么可以帮你？")
	f.see(t, "你好，介绍一下自己")

	// 接着旧会话打开的不显示，哪怕一条条目也没有
	f.click(t, "修登录页")
	f.notSee(t, "有什么可以帮你？")
	conv := ""
	for _, c := range f.link().Took() {
		if c.Method == "conv.open" {
			conv = c.Conv()
		}
	}
	f.link().Push(`{"type":"conv.items","conv":"` + conv + `","full":true,"at":0,"remove":0,"items":[]}`)
	f.tt.Frame()
	if len(f.app.pane.list.Items()) != 0 {
		t.Fatalf("条目没清空：%d", len(f.app.pane.list.Items()))
	}
	f.notSee(t, "有什么可以帮你？")
}

// hostPill 是空白页上设备胶囊读屏读的名字。
func hostPill(name string) string { return locales.EmptyChatHostLabel(name) }

// 侧栏列着全部设备时空白页上有设备胶囊：选另一台，新对话在那台的默认目录重开，侧栏照旧列全部；只列一台时没有。
// 那台已断开、打不开时说明下面也有，能换回能用的一台。
func TestEmptyChatPicksTheDevice(t *testing.T) {
	f := newChatFixture(t)
	lab := f.withHosts(t, "lab")[0]
	f.newChat(t)
	if f.shows(hostPill("box")) {
		t.Fatal("只列一台时也有设备胶囊")
	}
	f.click(t, "box")
	f.click(t, "全部设备")
	f.see(t, "有什么可以帮你？")
	f.click(t, hostPill("box"))
	f.clickMenuItem(t, "lab")
	if c := f.hub.Chat(); c == nil || c.Backend != lab.ID || c.Session.Cwd != "/home/lab" || !c.Fresh() {
		t.Fatalf("换到 lab 的新对话：%+v", c)
	}
	if open := f.devices.Chat(lab.ID).Took(); len(open) != 2 || open[0].Method != "conv.open" || open[0].Str("cwd") != "/home/lab" || open[1].Method != "config.models.read" {
		t.Fatalf("lab 上的打开：%+v", open)
	}
	if !f.hub.AllHosts() {
		t.Error("换设备以后侧栏不列全部了")
	}
	f.see(t, "有什么可以帮你？", "修登录页") // box 上的会话还列着
	if !f.showsPrefix("lab · ") {
		t.Errorf("顶栏第二行不是 lab：%q", f.tt.Texts())
	}

	// Ctrl+N 的新对话也开在 lab
	f.tt.Key(ui.Cmd, ui.KeyN)
	f.tt.Frame()
	if c := f.hub.Chat(); c.Backend != lab.ID {
		t.Errorf("Ctrl+N 开在 %q", c.Backend)
	}

	// lab 已断开：说明下面照样能换回 box
	if err := f.hub.SetConnected(lab.ID, false); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	f.see(t, "「lab」已断开")
	f.click(t, hostPill("lab"))
	f.clickMenuItem(t, "box")
	if c := f.hub.Chat(); c.Backend != f.id || c.Conv == nil {
		t.Fatalf("换回 box：%+v", c)
	}

	// box 上打不开：说明下面也有
	f.failOn("conv.open", "核心不在")
	f.tt.Key(ui.Cmd, ui.KeyN)
	f.tt.Frame()
	f.see(t, "无法打开该对话：核心不在")
	if !f.shows(hostPill("box")) {
		t.Error("打不开时没有设备胶囊")
	}

	// 接着旧会话打开的（同样打不开）没有
	f.click(t, "修登录页")
	f.see(t, "无法打开该对话：核心不在")
	if f.shows(hostPill("box")) {
		t.Error("接着旧会话也有设备胶囊")
	}
}

// 预览（TestPreviews）：新对话的空白页（只列一台、列着全部设备），和收藏一条消息后的 toast。
func init() {
	chatScenes["chat-new"] = func(t *testing.T, f *chatFixture) { f.newChat(t) }
	chatScenes["chat-new-all-hosts"] = func(t *testing.T, f *chatFixture) {
		f.withHosts(t, "lab")
		f.hub.ListAll()
		f.newChat(t)
		f.click(t, hostPill("box"))
	}
	chatScenes["chat-saved-prompt"] = func(t *testing.T, f *chatFixture) {
		f.click(t, "修登录页")
		f.push(`"can_rewind":true`)
		_ = f.tt.RightClick("看看有什么文件")
		_ = f.tt.ChooseMenuItem("收藏为常用指令")
	}
}
