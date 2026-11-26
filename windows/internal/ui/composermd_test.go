package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 打字时样式跟着当前的文字走：每帧给输入框的区间是按这一帧的草稿算的；标记符号排在最后，压过加粗。
func TestDraftMarksFollowTyping(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.click(t, "消息")
	f.tt.Type("看 **这里**")
	f.tt.Frame()
	p := f.app.pane
	if p.marks.text != p.draft || p.draft != "看 **这里**" {
		t.Fatalf("marks for %q, draft %q", p.marks.text, p.draft)
	}
	k := widget.For(false)
	got := p.marks.of(k, p.draft)
	want := []ui.TextRange{
		{Start: 2, End: 8, Weight: 700},
		{Start: 2, End: 4, Color: k.Tertiary, Weight: 400},
		{Start: 6, End: 8, Color: k.Tertiary, Weight: 400},
	}
	if len(got) != len(want) {
		t.Fatalf("ranges %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("range %d: %v, want %v", i, got[i], want[i])
		}
	}
}

// 预览（TestPreviews）：带 Markdown 的输入栏。
func init() {
	chatScenes["composer-markdown"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.app.pane.draft = "## 改一下登录页\n帮我把 **超时时间** 改成 *30 秒*，不要动 `retry_count`\n- 旧逻辑 ~~每次重连~~ 删掉\n> 参考上周的讨论\n```go\nfmt.Println(x)\n```"
	}
}
