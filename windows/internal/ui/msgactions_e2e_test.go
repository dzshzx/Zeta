package ui

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// 整条链路的编辑重发：窗口经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，核心起按剧本回话的
// 假 Claude（tools/fake-claude）。发一条、答完以后右键编辑、改了再发：核心请假 Claude 剪掉原来那一轮（rewind_conversation），
// 再发改过的，列表里换成新的一轮。
func TestEditResendThroughCore(t *testing.T) {
	f := newCoreFixture(t, 860)
	f.fakeClaude(t, `{"steps":[{"text":"第一版回答"}]}
{"steps":[{"text":"改过以后的回答"}]}
`)
	app := f.app

	f.hub.NewChat()
	f.until(t, "打开新对话", func() bool { return app.pane != nil && app.pane.src.Conv != nil && app.pane.connected })
	f.click(t, "消息")
	f.tt.Type("第一个问题")
	f.tt.Key(0, ui.KeyEnter)
	f.until(t, "答完第一轮", func() bool {
		return rounds(app) == "user:第一个问题 | answer:第一版回答" && app.pane.state.CanRewind
	})

	f.startEditing(t, "第一个问题")
	app.pane.draft = "改过的问题"
	f.tt.Frame()
	f.click(t, "发送")
	f.until(t, "改写以后答完", func() bool {
		return rounds(app) == "user:改过的问题 | answer:改过以后的回答" && !app.pane.state.Busy && !app.pane.state.Rewinding
	})
	if app.pane.failed != nil {
		t.Fatalf("出错：%v", app.pane.failed)
	}
}

// rounds 是聊天列表里的提问和回答，「类别:正文」用「 | 」连起来。
func rounds(app *App) string {
	var out []string
	for _, it := range app.pane.list.Items() {
		if it.Kind == items.User || it.Kind == items.Answer {
			out = append(out, string(it.Kind)+":"+it.Text)
		}
	}
	return strings.Join(out, " | ")
}

// startEditing 右键聊天里写着 text 的气泡选「编辑」，核对输入栏换成了它。侧栏和标题上也有这句话：点最靠右的那处。
func (f *coreFixture) startEditing(t *testing.T, text string) {
	t.Helper()
	var bubble ui.Rect
	for _, a := range f.tt.AccessAll() {
		if a.Name == text && a.Bounds.X > bubble.X {
			bubble = a.Bounds
		}
	}
	f.tt.RightClickAt(bubble.X+bubble.W/2, bubble.Y+bubble.H/2)
	if err := f.tt.ChooseMenuItem("编辑"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	if f.app.pane.draft != text || !f.tt.HasText("编辑消息") {
		t.Fatalf("开始编辑：输入栏 %q", f.app.pane.draft)
	}
}
