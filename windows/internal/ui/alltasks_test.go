package ui

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/egoist/mygo/ui"
)

// entry 是侧栏「后台任务」入口的样子：行尾的个数（没有时是空）和悬停提示里的说法。
func (f *chatFixture) entry(t *testing.T) (count, tip string) {
	t.Helper()
	a, ok := f.tt.AccessByRole(ui.RoleButton, "后台任务")
	if !ok {
		t.Fatalf("侧栏没有「后台任务」：%q", f.tt.Texts())
	}
	for _, n := range []string{"1", "2", "3", "4", "5"} {
		if r, ok := f.tt.Find(n); ok && r.Y >= a.Bounds.Y && r.Y < a.Bounds.Y+a.Bounds.H && r.X > a.Bounds.X {
			count = n
		}
	}
	return count, a.Description
}

func TestAllTasksPanel(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	if count, tip := f.entry(t); count != "" || tip != "" {
		t.Fatalf("什么都没在跑：%q %q", count, tip)
	}
	// 别的会话 s1 在回答、有一个后台任务（面板里不列它的任务，个数也不算）；当前对话有一个在跑、一个做完了
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering", Tasks: 1}, "s7": {State: "idle"}})
	f.push(twoTasks)
	if count, tip := f.entry(t); count != "2" || tip != "1 个对话 · 1 个任务在运行" {
		t.Fatalf("入口：%q %q", count, tip)
	}

	f.click(t, "后台任务")
	f.see(t, "后台对话 · 1", "修登录页", "正在回答", "新对话（当前对话）", "监听构建日志", "后台命令 · 已运行 2 分 05 秒",
		"查找读配置的地方", "子代理 · Explore · 已完成 · 用时 6 秒")
	f.notSee(t, "消息", "返回应用") // 盖住了聊天；侧栏照旧是会话，不是设置的分类
	// 看一个任务：是聊天窗格的任务页，返回回到面板
	f.click(t, "监听构建日志")
	f.see(t, "tail -f build.log", "正在读取…")
	if got := f.sent("conv.task_output"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1","watch":true}` {
		t.Fatalf("看着输出：%v", got)
	}
	f.click(t, "返回")
	f.see(t, "后台对话 · 1", "新对话（当前对话）")
	if got := f.sent("conv.task_output"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1","watch":false}` {
		t.Fatalf("返回后：%v", got)
	}
	// 子代理的「所属对话」回到聊天
	f.click(t, "查找读配置的地方")
	f.click(t, "所属对话")
	f.see(t, "消息")
	f.notSee(t, "后台对话 · 1", "子代理类型 Explore")

	// 点一个后台对话：切过去，回到聊天
	f.click(t, "后台任务")
	f.click(t, "修登录页")
	if got := f.sent("conv.open"); len(got) != 1 || !strings.Contains(got[0], `"session":"s1"`) {
		t.Fatalf("切到后台对话：%v", got)
	}
	f.notSee(t, "后台对话 · 1")
	// 切过来以后它是当前对话，不在后台对话里，入口也不算它
	f.click(t, "后台任务")
	f.see(t, "后台对话 · 0", "暂无后台运行的对话")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	f.notSee(t, "后台对话 · 0")
}

// 面板里开着任务页时离开（进设置再回来）：回到的是聊天，不是那一页，也不再看着输出。
func TestLeavingAllTasksClosesItsPages(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(twoTasks)
	f.click(t, "后台任务")
	f.click(t, "监听构建日志")
	f.sent("conv.task_output")
	f.tt.Key(ui.Cmd, ui.KeyComma)
	f.tt.Frame()
	f.click(t, "返回应用")
	f.see(t, "消息")
	f.notSee(t, "tail -f build.log")
	if got := f.sent("conv.task_output"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1","watch":false}` {
		t.Fatalf("离开后：%v", got)
	}
}

// 还没开对话时也能看后台对话；列着全部设备时一行注明是哪台。
func TestAllTasksWithoutAChat(t *testing.T) {
	f := newChatFixture(t)
	f.hub.ListAll()
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}})
	f.tt.Frame()
	if count, tip := f.entry(t); count != "1" || tip != "1 个对话" {
		t.Fatalf("入口：%q %q", count, tip)
	}
	f.click(t, "后台任务")
	f.see(t, "后台对话 · 1", "修登录页", "box · 等你确认", "暂无后台任务")
	f.click(t, "返回")
	f.notSee(t, "后台对话 · 1")
}

func TestBackgroundLabels(t *testing.T) {
	for _, c := range []struct {
		waiting, busy bool
		tasks         int
		want          string
	}{
		{true, true, 2, "等你确认"}, {false, true, 2, "正在回答"}, {false, false, 2, "2 个后台任务运行中"}, {false, false, 0, "已结束，稍后收起"},
	} {
		if got := conversationState(c.waiting, c.busy, c.tasks); got != c.want {
			t.Errorf("conversationState(%v, %v, %d) = %q，应为 %q", c.waiting, c.busy, c.tasks, got, c.want)
		}
	}
	for _, c := range []struct {
		chats, running, finished int
		want                     string
	}{
		{0, 0, 0, ""}, {2, 3, 1, "2 个对话 · 3 个任务在运行"}, {0, 0, 4, "4 个任务已结束"}, {1, 0, 2, "1 个对话 · 2 个任务已结束"},
	} {
		if got := backgroundSummary(c.chats, c.running, c.finished); got != c.want {
			t.Errorf("backgroundSummary(%d, %d, %d) = %q，应为 %q", c.chats, c.running, c.finished, got, c.want)
		}
	}
}
