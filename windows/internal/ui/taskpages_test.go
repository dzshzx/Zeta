package ui

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/tasks"
)

// 两个后台任务：一个在跑的后台命令（开始于 now 前 125 秒），一个做完了的子代理。
var twoTasks = `"process":true,"tasks":[
{"id":"b1","type":"local_bash","kind":"command","description":"监听构建日志","command":"tail -f build.log","tool_use_id":"toolu_b1",
 "started_at":` + ms(now.Add(-125*time.Second)) + `,"state":"running","running":true,"readable":true},
{"id":"a1","type":"local_agent","kind":"agent","description":"查找读配置的地方","command":"找出所有直接读 config 文件的地方","agent_type":"Explore",
 "tool_use_id":"toolu_a1","started_at":` + ms(now.Add(-300*time.Second)) + `,"ended_at":` + ms(now.Add(-294*time.Second)) + `,
 "state":"completed","tool_uses":7,"summary":"一共三处：config.go、main.go、server.go","running":false,"readable":false}]`

func ms(t time.Time) string { return strconv.FormatInt(t.UnixMilli(), 10) }

// pushOutput 推一份后台任务 task 的输出（conv.task_output，字段同 tasks.Output）。
func (f *chatFixture) pushOutput(task, fields string) {
	f.link().Push(`{"type":"conv.task_output","conv":"w1","task":"` + task + `",` + fields + `}`)
	f.tt.Frame()
}

func TestBackgroundTasksPage(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(twoTasks)
	f.click(t, "后台任务 1 个")
	f.see(t, "后台任务", "监听构建日志", "后台命令 · 已运行 2 分 05 秒", "查找读配置的地方", "子代理 · Explore · 已完成 · 用时 6 秒")
	if got := f.sent("conv.task_output"); got != nil {
		t.Fatalf("列表页就读了输出：%v", got)
	}

	// 后台命令：命令、输出（进页面开始看着，核心推来）、停止要先确认
	f.click(t, "监听构建日志")
	f.see(t, "后台命令", "运行中", "已运行", "2 分 05 秒", "tail -f build.log", "正在读取…")
	if got := f.sent("conv.task_output"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1","watch":true}` {
		t.Fatalf("看着输出：%v", got)
	}
	f.pushOutput("b1", `"text":"\u001b[32mok\u001b[0m 第 1 步\n","total_bytes":20470,"truncated":true`)
	f.see(t, "输出 · 共 20.5 KB，只显示最后 8 KB")
	if !strings.Contains(strings.Join(f.tt.Texts(), "\n"), "第 1 步") {
		t.Fatalf("没显示输出：%q", f.tt.Texts())
	}
	f.pushOutput("b1", `"text":"\u001b[32mok\u001b[0m 第 1 步\nok 第 2 步\n","total_bytes":20480,"truncated":true`)
	f.see(t, "ok 第 2 步")
	// 不定时拉：过了一会儿也不另发请求
	f.tt.Advance(5 * time.Second)
	f.tt.Frame()
	if got := f.sent("conv.task_output"); got != nil {
		t.Fatalf("看着时又拉了：%v", got)
	}
	f.click(t, "停止这个任务")
	f.see(t, "停止这个任务？")
	f.click(t, "取消")
	if got := f.sent("conv.stop_task"); got != nil {
		t.Fatalf("取消了还是停了：%v", got)
	}
	f.click(t, "停止这个任务")
	f.clickButton(t, "停止", 0)
	if got := f.sent("conv.stop_task"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1"}` {
		t.Fatalf("停止：%v", got)
	}
	f.push(strings.Replace(twoTasks, `"state":"running"`, `"state":"running","stopping":true`, 1))
	f.see(t, "正在停止…", "正在停止")
	// 停下了：状态是已停止，最后一次输出由核心推来
	f.push(strings.Replace(twoTasks, `"state":"running","running":true`, `"state":"stopped","ended_at":`+ms(now.Add(-5*time.Second))+`,"running":false`, 1))
	f.see(t, "已停止", "用时", "2 分 00 秒")
	f.notSee(t, "停止这个任务")
	if got := f.sent("conv.task_output"); got != nil {
		t.Fatalf("结束后另发了请求：%v", got)
	}

	// 离开页面：不再看着
	f.click(t, "返回")
	if got := f.sent("conv.task_output"); len(got) != 1 || got[0] != `{"conv":"w1","task":"b1","watch":false}` {
		t.Fatalf("离开后：%v", got)
	}

	// 子代理：类型、工具调用、交给它的指令和报告，没有输出，也不看输出
	f.click(t, "查找读配置的地方")
	f.see(t, "子代理", "已完成", "Explore", "7 次", "交给它的指令", "找出所有直接读 config 文件的地方", "报告", "一共三处：config.go、main.go、server.go")
	f.notSee(t, "输出")
	if got := f.sent("conv.task_output"); got != nil {
		t.Fatalf("子代理也看输出：%v", got)
	}
	// 「所属对话」回到聊天
	f.click(t, "所属对话")
	f.notSee(t, "子代理类型")
	f.see(t, "消息") // 回到了聊天；没有在跑的任务，状态行不显示
}

func TestTaskPageWhenGone(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(twoTasks)
	f.click(t, "后台任务 1 个")
	f.click(t, "监听构建日志")
	f.pushOutput("b1", `"text":"","total_bytes":0,"truncated":false,"error":"get_task_output: no shell or Monitor task with that task_id in this session"`)
	f.see(t, "没读到：get_task_output: no shell or Monitor task with that task_id in this session")
	f.push(`"tasks":[]`)
	f.see(t, "这个任务已经不在了")
	f.click(t, "返回")
	f.see(t, "没有后台任务")
	f.click(t, "返回")
	f.see(t, "消息")
}

func TestTaskLabels(t *testing.T) {
	end := int64(66_000)
	cases := []struct {
		task tasks.Task
		want string
	}{
		{tasks.Task{Kind: tasks.KindCommand, Command: "\n  npm run dev  \nx", StartedAt: 0, State: tasks.StateRunning}, "npm run dev|后台命令 · 已运行 1 小时 40 分"},
		{tasks.Task{Kind: tasks.KindMonitor, Description: "盯日志", StartedAt: 0, State: tasks.StateRunning, Stopping: true}, "盯日志|监视器 · 正在停止 · 已运行 1 小时 40 分"},
		{tasks.Task{Kind: tasks.KindAgent, StartedAt: 1000, EndedAt: &end, State: tasks.StateFailed}, "子代理|子代理 · 失败 · 用时 1 分 05 秒"},
		{tasks.Task{Kind: tasks.KindOther, Type: "remote_agent", State: tasks.StateEnded, EndedAt: &end}, "remote_agent|remote_agent · 已结束 · 用时 1 分 06 秒"},
		{tasks.Task{Kind: tasks.KindOther, State: tasks.StateCompleted, EndedAt: &end, StartedAt: 60_500}, "后台任务|后台任务 · 已完成 · 用时 5 秒"},
	}
	for _, c := range cases {
		if got := taskTitle(c.task) + "|" + taskSummary(c.task, 6_000_000); got != c.want {
			t.Errorf("%+v：%q，应为 %q", c.task, got, c.want)
		}
	}
	for o, want := range map[tasks.Output]string{
		{}:                                   "输出",
		{TotalBytes: 999}:                    "输出 · 999 B",
		{TotalBytes: 20480, Truncated: true}: "输出 · 共 20.5 KB，只显示最后 8 KB",
	} {
		if got := outputHeader(o); got != want {
			t.Errorf("outputHeader(%+v) = %q，应为 %q", o, got, want)
		}
	}
	if got := stopSummary(tasks.Task{Kind: tasks.KindAgent, Description: "查代码"}); got != "查代码\n子代理停下后，Claude 会收到通知并接着处理。" {
		t.Errorf("stopSummary = %q", got)
	}
}

func init() {
	// 预览：后台任务列表，和后台命令的详情（输出读到了）
	chatScenes["tasks-list"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,` + twoTasks)
		f.click(t, "后台任务 1 个")
	}
	chatScenes["task-output"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,` + twoTasks)
		f.click(t, "后台任务 1 个")
		f.click(t, "监听构建日志")
		f.pushOutput("b1", `"text":"[build] 编译 23 个包\n[build] ok  github.com/dzshzx/Zeta/windows/internal/chat  0.41s\n[build] ok  github.com/dzshzx/Zeta/windows/internal/ui  3.02s\n[watch] 等待改动…\n","total_bytes":20480,"truncated":true`)
	}
}
