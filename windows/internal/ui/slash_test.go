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
