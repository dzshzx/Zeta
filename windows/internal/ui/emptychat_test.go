package ui

import (
	"testing"

	"github.com/egoist/mygo/ui"
)

// 新对话空白时中间是标题和建议卡片；点一张只把它的提问发出去，输入栏里没发的字留着；条目来了空白页就收起。
func TestEmptyChatSuggestions(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.see(t, "有什么可以帮你？", "看看这个项目", "检查 Git 状态", "接着上次做", "设备状况")
	f.click(t, "消息")
	f.tt.Type("还没想好")
	f.click(t, "检查 Git 状态")
	if got := f.sent("conv.send"); len(got) != 1 ||
		got[0] != `{"conv":"w1","text":"查看当前目录的 git 状态和最近的提交，告诉我有哪些未提交的改动。"}` {
		t.Fatalf("点建议发出的：%v", got)
	}
	if f.app.pane.draft != "还没想好" {
		t.Errorf("输入栏 = %q", f.app.pane.draft)
	}
	// 发出去到条目推来之间不再显示，点不了第二次
	f.notSee(t, "有什么可以帮你？", "看看这个项目")
	f.replay("turn")
	f.notSee(t, "有什么可以帮你？", "看看这个项目")
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

// 没发出去：原因显示在输入栏上方，空白页回来，可以再点。
func TestEmptyChatSuggestionFails(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.failOn("conv.send", "核心不在")
	f.click(t, "设备状况")
	f.tt.Frame()
	f.see(t, "没发出去：核心不在", "有什么可以帮你？", "设备状况")
}

// 工作目录进不去时卡片灰着，点了不发。
func TestEmptyChatInAMissingDirectory(t *testing.T) {
	f := newChatFixture(t)
	f.devices.DirProblems = map[string]string{"/home/box": "「box」上没有 /home/box 这个目录"}
	f.click(t, "新对话")
	f.see(t, "有什么可以帮你？")
	if a, ok := f.tt.AccessByRole(ui.RoleButton, "看看这个项目"); !ok || !a.Disabled {
		t.Fatalf("进不去时建议卡片：%+v %v", a, ok)
	}
}

// 预览（TestPreviews）：新对话的空白页，和收藏一条消息后的 toast。
func init() {
	chatScenes["chat-new"] = func(t *testing.T, f *chatFixture) { f.newChat(t) }
	chatScenes["chat-saved-prompt"] = func(t *testing.T, f *chatFixture) {
		f.click(t, "修登录页")
		f.push(`"can_rewind":true`)
		_ = f.tt.RightClick("看看有什么文件")
		_ = f.tt.ChooseMenuItem("收藏为常用指令")
	}
}
