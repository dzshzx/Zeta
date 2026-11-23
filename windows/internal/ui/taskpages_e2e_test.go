package ui

import (
	"context"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
)

// bgScenario 是假 Claude 的剧本：一轮里用 Bash 起一个后台命令（同真 Claude Code 的 background_tasks_changed、task_started），
// 读它的输出得到 output，stop_task 停它时照真的那样报 killed、stopped。第二轮把它的输出换成多一行的。
const bgScenario = `{"steps":[{"tool":"Bash","input":{"command":"while :; do echo tick; sleep 1; done","run_in_background":true,"description":"每秒报时"},"result":"Command running in background with ID: bfake01"},` +
	`{"emit":{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bfake01","task_type":"local_bash","description":"每秒报时"}]}},` +
	`{"emit":{"type":"system","subtype":"task_started","task_id":"bfake01","tool_use_id":"@TOOL@","description":"每秒报时","task_type":"local_bash"},"output":"tick\ntick\n"},` +
	`{"text":"放到后台了"}]}
` +
	`{"steps":[{"emit":{"type":"system","subtype":"task_started","task_id":"bfake01","tool_use_id":"@TOOL@","description":"每秒报时","task_type":"local_bash"},"output":"tick\ntick\ntick\n"},` +
	`{"text":"又报了一次"}]}
`

// 整条链路：经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，核心起假 Claude（tools/fake-claude）。
// 发一条消息让它起一个后台命令：输入栏上方出现后台任务条，点进去看到它和它的输出（核心推来的），输出变了跟着变，
// 停止以后变成已停止。
func TestBackgroundTaskThroughServe(t *testing.T) {
	f := newCoreFixture(t, 900)
	f.fakeClaude(t, bgScenario)
	h, app := f.hub, f.app
	h.Start()

	f.click(t, "新对话")
	f.until(t, "打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening && c.Conv != nil })
	f.click(t, "消息")
	f.tt.Type("在后台报时")
	f.click(t, "发送")
	f.until(t, "后台任务出现", func() bool { return f.shows("后台任务 · 1 个在运行") && f.shows("放到后台了") })

	f.click(t, "查看后台任务")
	f.see(t, "每秒报时")
	if !strings.Contains(strings.Join(f.tt.Texts(), "\n"), "后台命令 · 已运行") {
		t.Fatalf("列表：%q", f.tt.Texts())
	}
	f.click(t, "每秒报时")
	f.until(t, "读到输出", func() bool { return f.shows("tick\ntick") })
	f.see(t, "运行中", "while :; do echo tick; sleep 1; done", "输出 · 10 B")
	// 输出变了：看着时核心每秒问一次，变了推来，页面跟着变
	h.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, coreapi.Outgoing{Text: "再报一次"})
	}, nil)
	f.until(t, "推来新的输出", func() bool { return f.shows("tick\ntick\ntick") })
	f.see(t, "输出 · 15 B")

	f.click(t, "停止这个任务")
	f.clickButton(t, "停止", 0)
	f.until(t, "停下", func() bool { return f.shows("已停止") })
	f.notSee(t, "停止这个任务")
	f.click(t, "返回")
	if !strings.Contains(strings.Join(f.tt.Texts(), "\n"), "后台命令 · 已停止 · 用时") {
		t.Fatalf("停下以后的列表：%q", f.tt.Texts())
	}

	// 结束假 Claude，不留进程
	done := make(chan error, 1)
	h.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		done <- conv.End(ctx)
		return nil
	}, nil)
	f.until(t, "结束进程", func() bool {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
			done = nil
		default:
		}
		return done == nil && !app.pane.state.Process
	})
}
