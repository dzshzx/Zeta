package tasks

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

// 后台任务清单的归约。事件照 Claude Code 2.1.328 的真实输出删减，用例和 Kotlin 的 TaskBoardTest、HostTasksTest 一一对应。
const (
	bashCall = `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_0","name":"Bash",
        "input":{"command":"echo hello-bg; sleep 300","description":"ticker in background","run_in_background":true}}]},"parent_tool_use_id":null}`
	bashListed  = `{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bvsjk0cv8","task_type":"local_bash","description":"ticker in background"}]}`
	bashStarted = `{"type":"system","subtype":"task_started","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","description":"ticker in background","is_backgrounded":true,"task_type":"local_bash"}`
	bashKilled  = `{"type":"system","subtype":"task_updated","task_id":"bvsjk0cv8","patch":{"status":"killed","end_time":5000}}`
	bashStopped = `{"type":"system","subtype":"task_notification","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","status":"stopped","output_file":"/tmp/x","summary":"ticker in background"}`
	empty       = `{"type":"system","subtype":"background_tasks_changed","tasks":[]}`
)

func (b *Board) see(line string, now int64) bool { return b.Observe([]byte(line), now) }

func eq[T comparable](t *testing.T, what string, got, want T) {
	t.Helper()
	if got != want {
		t.Errorf("%s = %v，应为 %v", what, got, want)
	}
}

func eqPtr[T comparable](t *testing.T, what string, got *T, want T) {
	t.Helper()
	if got == nil {
		t.Errorf("%s 没有，应为 %v", what, want)
	} else if *got != want {
		t.Errorf("%s = %v，应为 %v", what, *got, want)
	}
}

func single(t *testing.T, b *Board) Task {
	t.Helper()
	list := b.Tasks()
	if len(list) != 1 {
		t.Fatalf("清单有 %d 项，应为 1：%v", len(list), list)
	}
	return list[0]
}

func TestBackgroundCommandStartsAndIsStopped(t *testing.T) {
	b := New()
	eq(t, "调用", b.see(bashCall, 1000), false)
	// 清单先到，task_started 随后补上发起它的调用
	eq(t, "清单", b.see(bashListed, 1000), true)
	eq(t, "开始", b.see(bashStarted, 1001), true)
	task := single(t, b)
	eq(t, "id", task.ID, "bvsjk0cv8")
	eq(t, "kind", task.Kind, KindCommand)
	eq(t, "command", task.Command, "echo hello-bg; sleep 300")
	eq(t, "description", task.Description, "ticker in background")
	eq(t, "tool use", task.ToolUseID, "toolu_0")
	eq(t, "running && readable", task.Running() && task.Readable(), true)
	eq(t, "elapsed", task.ElapsedMs(5000), 4000)

	eq(t, "叫停", b.StopRequested("bvsjk0cv8"), true)
	eq(t, "stopping", single(t, b).Stopping, true)
	b.see(bashKilled, 5001)
	b.see(bashStopped, 5002)
	b.see(empty, 5003)
	stopped := single(t, b)
	eq(t, "state", stopped.State, StateStopped)
	eq(t, "stopping", stopped.Stopping, false)
	eqPtr(t, "ended", stopped.EndedAt, 5000)
	// 停止的通知里 summary 就是说明，不重复留
	eq(t, "summary", stopped.Summary, "")
	eq(t, "elapsed", stopped.ElapsedMs(99_999), 4000)
}

func TestStartedBeforeListedIsKeptUntilItGoesToTheBackground(t *testing.T) {
	b := New()
	b.see(bashCall, 1000)
	// 前台的任务只有 task_started，不进清单
	eq(t, "开始", b.see(bashStarted, 1000), false)
	eq(t, "清单", len(b.Tasks()), 0)
	// 后来转到后台（Ctrl+B、超时自动转）：清单里出现时用当初记下的开始时间和命令
	eq(t, "列出", b.see(bashListed, 3000), true)
	task := single(t, b)
	eq(t, "started", task.StartedAt, 1000)
	eq(t, "command", task.Command, "echo hello-bg; sleep 300")
}

func TestSubagentProgressAndReport(t *testing.T) {
	b := New()
	b.see(`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_0","name":"Agent","input":{"description":"look around","prompt":"look at the repo","subagent_type":"general-purpose","run_in_background":true}}]}}`, 0)
	b.see(`{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"aa26421d62638ceff","task_type":"local_agent","subagent_type":"general-purpose","description":"look around"}]}`, 10)
	b.see(`{"type":"system","subtype":"task_started","task_id":"aa26421d62638ceff","tool_use_id":"toolu_0","description":"look around","subagent_type":"general-purpose","is_backgrounded":true,"task_type":"local_agent","prompt":"look at the repo"}`, 10)
	// 子代理自己的消息带 parent_tool_use_id，它的工具调用不算主对话的
	b.see(`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_s0","name":"Bash","input":{"command":"echo from-sub"}}]},"parent_tool_use_id":"toolu_0"}`, 20)
	if _, ok := b.calls.get("toolu_s0"); ok {
		t.Error("子代理里的调用不该记下")
	}
	eq(t, "进展", b.see(`{"type":"system","subtype":"task_progress","task_id":"aa26421d62638ceff","description":"Running sub cmd","usage":{"total_tokens":23,"tool_uses":1,"duration_ms":3157},"last_tool_name":"Bash"}`, 3000), true)
	task := single(t, b)
	eq(t, "kind", task.Kind, KindAgent)
	eq(t, "agent type", task.AgentType, "general-purpose")
	eq(t, "command", task.Command, "look at the repo")
	eq(t, "progress", task.Progress, "Running sub cmd")
	eqPtr(t, "tool uses", task.ToolUses, 1)
	eq(t, "readable", task.Readable(), false)

	b.see(`{"type":"system","subtype":"task_updated","task_id":"aa26421d62638ceff","patch":{"status":"completed","end_time":6400}}`, 6400)
	b.see(`{"type":"system","subtype":"task_notification","task_id":"aa26421d62638ceff","status":"completed","summary":"sub done REPORT","summary_is_report":true}`, 6401)
	b.see(empty, 6402)
	task = single(t, b)
	eq(t, "state", task.State, StateCompleted)
	eq(t, "summary", task.Summary, "sub done REPORT")
}

func TestMonitorIsALocalBashTaskStartedByMonitor(t *testing.T) {
	b := New()
	b.see(`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Monitor","input":{"description":"watch events","timeout_ms":60000,"command":"tail -f log"}}]}}`, 0)
	b.see(`{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bmql5uhd1","task_type":"local_bash","description":"watch events"}]}`, 0)
	b.see(`{"type":"system","subtype":"task_started","task_id":"bmql5uhd1","tool_use_id":"toolu_1","description":"watch events","is_backgrounded":true,"task_type":"local_bash"}`, 0)
	task := single(t, b)
	eq(t, "kind", task.Kind, KindMonitor)
	eq(t, "command", task.Command, "tail -f log")
	eq(t, "readable", task.Readable(), true)
}

func TestDroppedWithoutAnOutcomeOrProcessGoneEndsIt(t *testing.T) {
	b := New()
	b.see(bashListed, 0)
	eq(t, "消失", b.see(empty, 700), true)
	eq(t, "state", single(t, b).State, StateEnded)
	eqPtr(t, "ended", single(t, b).EndedAt, 700)

	other := New()
	other.see(bashListed, 0)
	eq(t, "进程没了", other.ProcessEnded(900), true)
	eq(t, "state", single(t, other).State, StateEnded)
	eq(t, "再一次", other.ProcessEnded(1000), false)
}

func TestFailedCommandKeepsItsExitSummary(t *testing.T) {
	b := New()
	b.see(bashListed, 0)
	b.see(`{"type":"system","subtype":"task_notification","task_id":"bvsjk0cv8","status":"failed","summary":"Background command \"ticker in background\" failed with exit code 1"}`, 50)
	task := single(t, b)
	eq(t, "state", task.State, StateFailed)
	eq(t, "summary", task.Summary, `Background command "ticker in background" failed with exit code 1`)
	eqPtr(t, "ended", task.EndedAt, 50)
}

func TestReplayFromTheHelperCarriesTheCallAndStartTime(t *testing.T) {
	// 接回后台进程：zeta-helper hosts 给的 task_started 带着发起它的调用（zeta_tool）和当时的时间（zeta_at）
	b := New()
	b.see(`{"type":"system","subtype":"task_started","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","description":"ticker in background","task_type":"local_bash","zeta_at":1234,"zeta_tool":{"name":"Bash","input":{"command":"make test"}}}`, 9999)
	b.see(bashListed, 9999)
	task := single(t, b)
	eq(t, "started", task.StartedAt, 1234)
	eq(t, "command", task.Command, "make test")
}

func TestResumedTaskRunsAgainAndOldFinishedOnesAreTrimmed(t *testing.T) {
	b := NewKeeping(2)
	b.see(bashListed, 0)
	b.see(bashStopped, 10)
	// 续跑：同一个任务又回到清单
	b.see(bashListed, 20)
	eq(t, "state", single(t, b).State, StateRunning)
	if single(t, b).EndedAt != nil {
		t.Error("续跑后不该有结束时间")
	}
	for i := 1; i <= 4; i++ {
		b.see(fmt.Sprintf(`{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b%d","task_type":"local_bash","description":"t%d"}]}`, i, i), int64(100*i))
	}
	b.see(empty, 1000)
	// 在跑的没有了，结束的只留最近 2 个
	var ids []string
	for _, task := range b.Tasks() {
		ids = append(ids, task.ID)
	}
	if !reflect.DeepEqual(ids, []string{"b4", "b3"}) {
		t.Errorf("清单是 %v，应为 [b4 b3]", ids)
	}
	eq(t, "清掉", b.ClearFinished(), true)
	eq(t, "清单", len(b.Tasks()), 0)
}

// 叫停：stop_task 答错时撤掉「正在停止」，答成功时等后面的 task_updated。
func TestStopAnswered(t *testing.T) {
	b := New()
	b.see(bashListed, 0)
	b.StopRequested("bvsjk0cv8")
	eq(t, "成功", b.StopAnswered("bvsjk0cv8", []byte(`{"subtype":"success","request_id":"c1","response":{}}`)), false)
	eq(t, "stopping", single(t, b).Stopping, true)
	eq(t, "出错", b.StopAnswered("bvsjk0cv8", []byte(`{"subtype":"error","request_id":"c1","error":"no such task"}`)), true)
	eq(t, "stopping", single(t, b).Stopping, false)
	eq(t, "不认识的任务", b.StopRequested("nope"), false)
}

func TestOutputResponse(t *testing.T) {
	ok := ParseOutput([]byte(`{"subtype":"success","request_id":"c2","response":{"output":"hello-bg\nsecond-line\n","total_bytes":21,"truncated":false}}`))
	eq(t, "ok", ok, Output{Text: "hello-bg\nsecond-line\n", TotalBytes: 21})
	failed := ParseOutput([]byte(`{"subtype":"error","request_id":"c2","error":"get_task_output: no shell or Monitor task with that task_id in this session"}`))
	eq(t, "error", failed.Error, "get_task_output: no shell or Monitor task with that task_id in this session")
	eq(t, "没写原因", ParseOutput([]byte(`{"subtype":"error"}`)).Error, "没有读到输出")
}

func TestTerminalTextAndElapsed(t *testing.T) {
	eq(t, "颜色", PlainTerminalText("\x1b[31mred\x1b[0m plain"), "red plain")
	eq(t, "进度行", PlainTerminalText("10%\r50%\r100%\r\ndone"), "100%\ndone")
	eq(t, "OSC", PlainTerminalText("\x1b]0;title\x07a\x1b]8;;x\x1b\\b"), "ab")
	eq(t, "3 秒", FormatElapsed(3_400), "3 秒")
	eq(t, "2 分", FormatElapsed(125_000), "2 分 05 秒")
	eq(t, "1 小时", FormatElapsed(3_780_000), "1 小时 03 分")
	eq(t, "1 h", FormatElapsedIn("en", 3_780_000), "1 h 03 min")
	eq(t, "2 min", FormatElapsedIn("en", 125_000), "2 min 05 s")
}

// 接回后台进程时，zeta-helper hosts 从输出日志里摘出还在跑的后台任务的事件（task_events），重放后和实时收到时一样：
// 命令、开始时间、子代理的进展都在。下面是 Kotlin 的 HostTasksTest 那份输出日志经 hosts 摘出的事件
// （各任务的 task_started 带上 zeta_tool、zeta_at，再是最后的清单，最后是各任务最近的进展）。
func TestRunningTasksAreReplayedWithTheirCallAndStartTime(t *testing.T) {
	events := []string{
		`{"type":"system","subtype":"task_started","task_id":"b1","tool_use_id":"toolu_0","description":"run tests","task_type":"local_bash","zeta_tool":{"name":"Bash","input":{"command":"make test","run_in_background":true}},"zeta_at":1795054409893}`,
		`{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_1","description":"review","task_type":"local_agent","prompt":"review it","zeta_tool":{"name":"Agent","input":{"prompt":"review it","subagent_type":"general-purpose"}},"zeta_at":1795054440000}`,
		`{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"run tests"},{"task_id":"a1","task_type":"local_agent","subagent_type":"general-purpose","description":"review"}]}`,
		`{"type":"system","subtype":"task_progress","task_id":"a1","description":"Running checks","usage":{"tool_uses":3}}`,
	}
	b := New()
	for _, e := range events {
		b.see(e, 9_999_999_999_999)
	}
	byID := map[string]Task{}
	for _, task := range b.Tasks() {
		byID[task.ID] = task
	}
	if len(byID) != 2 {
		t.Fatalf("清单是 %v", byID)
	}
	eq(t, "b1 command", byID["b1"].Command, "make test")
	eq(t, "b1 started", byID["b1"].StartedAt, 1795054409893)
	eq(t, "a1 command", byID["a1"].Command, "review it")
	eq(t, "a1 started", byID["a1"].StartedAt, 1795054440000)
	eq(t, "a1 progress", byID["a1"].Progress, "Running checks")
	eqPtr(t, "a1 tool uses", byID["a1"].ToolUses, 3)
	eq(t, "都在跑", byID["a1"].Running() && byID["b1"].Running(), true)
	eq(t, "running", b.Running(), 2)
}

// 推给客户端的 JSON：snake_case，带上 running、readable，没有的项不写。
func TestTaskJSON(t *testing.T) {
	b := New()
	b.see(bashCall, 1000)
	b.see(bashListed, 1000)
	b.see(bashStarted, 1001)
	data, err := json.Marshal(b.Tasks())
	if err != nil {
		t.Fatal(err)
	}
	want := `[{"id":"bvsjk0cv8","type":"local_bash","kind":"command","description":"ticker in background","command":"echo hello-bg; sleep 300","tool_use_id":"toolu_0","started_at":1000,"state":"running","running":true,"readable":true}]`
	eq(t, "JSON", string(data), want)
	data, _ = json.Marshal(ParseOutput([]byte(`{"subtype":"error","error":"x"}`)))
	eq(t, "output JSON", string(data), `{"text":"","total_bytes":0,"truncated":false,"error":"x"}`)
}

// 记着的工具调用最多 64 个，满了丢最早的；同一个再记一次挪到最后。
func TestRememberedCallsAreBounded(t *testing.T) {
	b := New()
	var blocks []string
	for i := range maxRemembered + 1 {
		blocks = append(blocks, fmt.Sprintf(`{"type":"tool_use","id":"u%d","name":"Bash","input":{"command":"c%d"}}`, i, i))
	}
	b.see(`{"type":"assistant","message":{"content":[`+strings.Join(blocks, ",")+`]}}`, 0)
	if _, ok := b.calls.get("u0"); ok {
		t.Error("最早的应当丢掉")
	}
	if _, ok := b.calls.get("u64"); !ok {
		t.Error("最新的应当在")
	}
	b.calls.put("u1", call{name: "Bash"})
	b.calls.put("new", call{name: "Bash"})
	if _, ok := b.calls.get("u1"); !ok {
		t.Error("再记一次的不该丢")
	}
	eq(t, "个数", len(b.calls.keys), maxRemembered)
}

// 停下的子代理被 SendMessage 续派：Claude Code 对同一个任务再发一次 task_started，带的是 SendMessage 调用（2.1.330 实测）。
// 发起它的仍是原来的 Agent 调用，子代理查看页按它找记录。
func TestResumedSubagentKeepsTheLaunchingCall(t *testing.T) {
	agent := `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_agent","name":"Agent","input":{"description":"viewer","prompt":"build the viewer","subagent_type":"general-purpose","run_in_background":true}}]}}`
	send := `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_send","name":"SendMessage","input":{"to":"a405","message":"go on"}}]}}`
	listed := `{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a405","task_type":"local_agent","subagent_type":"general-purpose","description":"viewer"}]}`
	started := func(use, prompt string) string {
		return `{"type":"system","subtype":"task_started","task_id":"a405","tool_use_id":"` + use + `","description":"viewer","subagent_type":"general-purpose","is_backgrounded":true,"task_type":"local_agent","prompt":"` + prompt + `"}`
	}
	for _, c := range []struct {
		name   string
		events []string
	}{
		{"已在清单里", []string{agent, listed, started("toolu_agent", "build the viewer"),
			`{"type":"system","subtype":"task_updated","task_id":"a405","patch":{"status":"killed","end_time":50}}`,
			send, listed, started("toolu_send", "go on")}},
		{"清单还没到", []string{agent, started("toolu_agent", "build the viewer"), send, started("toolu_send", "go on"), listed}},
	} {
		t.Run(c.name, func(t *testing.T) {
			b := New()
			for i, e := range c.events {
				b.see(e, int64(10*i))
			}
			task := single(t, b)
			eq(t, "tool use", task.ToolUseID, "toolu_agent")
			eq(t, "command", task.Command, "build the viewer")
			eq(t, "kind", task.Kind, KindAgent)
			eq(t, "state", task.State, StateRunning)
		})
	}
}
