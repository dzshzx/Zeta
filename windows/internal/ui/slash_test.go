package ui

import (
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// slashCommands 是测试里 Claude Code 报的几个命令。
const slashCommands = `"commands":[{"name":"compact","description":"压缩上下文","argumentHint":"<说明>"},` +
	`{"name":"clear","description":"清空对话","argumentHint":""},{"name":"review","description":"审查改动","argumentHint":""},` +
	`{"name":"__internal","description":"","argumentHint":""}]`

func TestSlashCommandsFilterAndInsert(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands)
	f.click(t, "消息")
	f.tt.Type("/")
	f.see(t, "/compact", "/clear", "/review", "压缩上下文", "<说明>")
	f.notSee(t, "/__internal")
	if got := f.link().Methods(); len(got) != 1 || got[0] != "conv.prepare" {
		t.Fatalf("打 / 时先起好进程：%v", got)
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

func TestSlashCommandsRememberedPerDevice(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands)
	f.click(t, "消息")
	f.tt.Type("/co")
	f.see(t, "/compact")
	// 换个新对话：进程还没起，提示这台上次报的
	f.click(t, "新对话")
	f.link().Took()
	f.click(t, "消息")
	f.tt.Type("/re")
	f.see(t, "/review", "审查改动")
}
