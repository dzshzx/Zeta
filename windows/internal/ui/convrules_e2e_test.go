package ui

import (
	"testing"

	"github.com/egoist/mygo/ui"
)

// 整条链路（同 TestEditResendThroughCore：sshtest 上真起 zeta-helper serve 和核心，核心起 tools/fake-claude）走一遍收进核心的对话规则。

// 假 Claude 调 TodoWrite 列出清单：核心归并成 conv.state 的 todos，输入栏上方的任务清单条照它画。
func TestTodosThroughCore(t *testing.T) {
	f := newCoreFixture(t, 860)
	f.fakeClaude(t, `{"steps":[{"tool":"TodoWrite","input":{"todos":[`+
		`{"content":"读登录页的代码","status":"completed","activeForm":"正在读登录页的代码"},`+
		`{"content":"修表单校验","status":"in_progress","activeForm":"正在修表单校验"},`+
		`{"content":"跑测试","status":"pending","activeForm":"正在跑测试"}]},"result":"Todos have been modified successfully."},`+
		`{"text":"先列了清单"}]}
`)
	app := f.app
	f.hub.NewChat()
	f.until(t, "打开新对话", func() bool { return app.pane != nil && app.pane.src.Conv != nil && app.pane.connected })
	f.click(t, "消息")
	f.tt.Type("把登录页修好")
	f.tt.Key(0, ui.KeyEnter)
	f.until(t, "清单条出现", func() bool { return f.shows("2/3") && f.shows("正在修表单校验") })
	if l := app.pane.state.Todos; len(l) != 3 || l.Current() == nil || l.Current().Content != "修表单校验" {
		t.Fatalf("核心给的清单：%+v", l)
	}
	f.click(t, "展开任务清单")
	f.see(t, "读登录页的代码", "跑测试")
}

// 重新生成：核心照原来那条消息原样重发（resend），列表里那一轮换成新的回答，提问不变。
func TestRegenerateThroughCore(t *testing.T) {
	f := newCoreFixture(t, 860)
	f.fakeClaude(t, `{"steps":[{"text":"第一版回答"}]}
{"steps":[{"text":"重新生成的回答"}]}
`)
	app := f.app
	f.hub.NewChat()
	f.until(t, "打开新对话", func() bool { return app.pane != nil && app.pane.src.Conv != nil && app.pane.connected })
	f.click(t, "消息")
	f.tt.Type("第一个问题")
	f.tt.Key(0, ui.KeyEnter)
	f.until(t, "答完第一轮，能重新生成", func() bool {
		return rounds(app) == "user:第一个问题 | answer:第一版回答" && app.pane.state.CanRewind && len(f.buttons("重新生成")) == 1
	})
	f.clickButton(t, "重新生成", 0)
	f.until(t, "确认框问完改动", func() bool { cf := app.pane.acts.confirm; return cf != nil && cf.asked })
	f.clickButton(t, "重新生成", 0) // 对话框里的：开着对话框时后面的都不算
	f.until(t, "重新生成以后答完", func() bool {
		return rounds(app) == "user:第一个问题 | answer:重新生成的回答" && !app.pane.state.Busy && !app.pane.state.Rewinding
	})
	if app.pane.failed != "" || app.pane.draft != "" {
		t.Fatalf("出错 %q，输入栏 %q", app.pane.failed, app.pane.draft)
	}
}
