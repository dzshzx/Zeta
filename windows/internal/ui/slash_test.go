package ui

import (
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// slashCommands 是测试里核心报的几个命令（还没起进程时是它记着的那份）。
const slashCommands = `"commands":[{"name":"compact","description":"压缩上下文","argumentHint":"<说明>"},` +
	`{"name":"clear","description":"清空对话","argumentHint":""},{"name":"review","description":"审查改动","argumentHint":""}]`

func TestSlashCommandsFilterAndInsert(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands)
	f.click(t, "消息")
	f.tt.Type("/")
	f.see(t, "/compact", "/clear", "/review", "压缩上下文", "<说明>")
	if got := f.link().Methods(); len(got) != 0 {
		t.Fatalf("打 / 时不用另外请求：%v", got)
	}
	// 以 c 开头的排前面，名字里含 c 的跟在后面
	f.tt.Type("c")
	f.see(t, "/compact", "/clear")
	f.notSee(t, "/review")
	f.tt.Key(0, ui.KeyDown)
	f.tt.Key(0, ui.KeyEnter)
	f.tt.Frame()
	if f.app.pane.draft != "/clear " {
		t.Fatalf("回车填进去的：%q", f.app.pane.draft)
	}
	if got := f.sent("conv.send"); got != nil {
		t.Fatalf("选命令的回车发出去了：%v", got)
	}
	f.notSee(t, "/compact") // 打了空格就收起
	// 有了空格以后回车照常发
	f.tt.Type("全部")
	f.tt.Key(0, ui.KeyEnter)
	if got := f.sent("conv.send"); len(got) != 1 || !strings.Contains(got[0], `"text":"/clear 全部"`) {
		t.Fatalf("发出：%v", got)
	}
	// 点一条也填进去；上键从第一条绕到最后一条；Esc 收起
	f.tt.Type("/re")
	f.click(t, "/review")
	if f.app.pane.draft != "/review " {
		t.Fatalf("点了填进去的：%q", f.app.pane.draft)
	}
	f.tt.Key(0, ui.KeyBackspace) // 去掉空格又列出来
	f.see(t, "/review")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	f.notSee(t, "/review")
}

// 句中空一格再打 / 也提示（同 Claude Code 终端界面），选了只换掉那个词；紧挨着别的字时不提示；光标挪回那个词又列出来。
func TestSlashCommandsMidText(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands)
	f.click(t, "消息")
	f.tt.Type("帮我 /co")
	f.see(t, "/compact")
	f.notSee(t, "/review")
	f.tt.Key(0, ui.KeyEnter)
	f.tt.Frame()
	if f.app.pane.draft != "帮我 /compact " {
		t.Fatalf("回车填进去的：%q", f.app.pane.draft)
	}
	f.tt.Type("你好/c")
	f.notSee(t, "/clear")
	f.tt.Type(" 再 /re")
	f.see(t, "/review")
	// 光标挪到「/」前面收起，挪回「/r」后面按 r 列出来
	// （只按下、先不松开就看：松开键也会建一帧，盖住只挪了光标时没要下一帧的错）
	f.tt.Key(0, ui.KeyLeft)
	f.tt.Key(0, ui.KeyLeft)
	f.tt.KeyDown(ui.KeyLeft)
	f.notSee(t, "/review")
	f.tt.KeyUp(ui.KeyLeft)
	f.tt.Key(0, ui.KeyRight)
	f.tt.KeyDown(ui.KeyRight)
	f.see(t, "/review", "/clear")
	f.tt.KeyUp(ui.KeyRight)
	f.tt.Key(0, ui.KeyTab)
	f.tt.Frame()
	if want := "帮我 /compact 你好/c 再 /review "; f.app.pane.draft != want {
		t.Fatalf("Tab 填进去的：%q，要 %q", f.app.pane.draft, want)
	}
	f.tt.Type("x")
	f.tt.Frame()
	if want := "帮我 /compact 你好/c 再 /review x"; f.app.pane.draft != want {
		t.Fatalf("光标不在命令后面：%q", f.app.pane.draft)
	}
}

// 列表比输入框先建、按键时的字可能比草稿新：光标按上一帧的加上多打、删掉的字数算。
func TestSlashCaretFollowsTyping(t *testing.T) {
	m := slashMenu{caret: 3, caretText: "ab/"}
	if got := m.caretIn("ab/co"); got != 5 {
		t.Fatalf("多打两个字：%d", got)
	}
	if got := m.caretIn("ab"); got != 2 {
		t.Fatalf("删一个字：%d", got)
	}
	if got := (&slashMenu{caret: -1}).caretIn("x"); got != -1 {
		t.Fatalf("选中一段时不提示：%d", got)
	}
}
