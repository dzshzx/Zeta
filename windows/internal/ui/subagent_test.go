package ui

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/chat"
)

// agentItems 是聊天里派了一个子代理的一轮。
const agentItems = `[{"kind":"user","id":"u1","text":"看看哪些地方直接读配置文件"},
{"kind":"work","id":"w1","steps":[{"kind":"tool","id":"toolu_a1","name":"Agent","status":"success",
 "input":{"description":"查找读配置的地方","subagent_type":"Explore","prompt":"找出所有直接读 config 文件的地方，列出文件和行号"},"result":"一共三处"}]},
{"kind":"answer","id":"a1","text":"一共三处。"}]`

// agentLog 是核心推来的子代理记录：整份，在跑，它又派了一个子代理。
const agentLog = `{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","full":true,"at":0,"remove":0,"items":[
 {"kind":"work","id":"s1","steps":[{"kind":"tool","id":"g1","name":"Grep","status":"success","input":{"pattern":"config\\.Load"},"result":"config.go:12\nmain.go:30"},
  {"kind":"tool","id":"toolu_n1","name":"Agent","status":"running","input":{"description":"细看 server.go","prompt":"读 server.go"}}]},
 {"kind":"answer","id":"s2","text":"找到 config.go 和 main.go 两处，server.go 交给另一个子代理细看。"}],
 "meta":{"agent_id":"ag1","agent_type":"Explore","description":"查找读配置的地方","model":"claude-haiku-4-5","finished":false,"stopped":false,"background":false},
 "prompt":"找出所有直接读 config 文件的地方，列出文件和行号","tool_uses":2,"running":true,"loaded":true,"missing":false,"file":"/home/box/.claude/a.jsonl","skipped":0}`

func TestSubagentPageFromTheChat(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.pushItems(agentItems)
	f.click(t, "已完成 1 个步骤")
	f.tt.Advance(time.Second) // 展开的动画走完
	f.tt.Frame()
	f.click(t, "子代理：查找读配置的地方")
	if got := f.sent("conv.subagent"); len(got) != 1 || got[0] != `{"conv":"w1","tool_use_id":"toolu_a1","watch":true}` {
		t.Fatalf("看：%v", got)
	}
	// 还什么都没推来：只有占位的说法
	f.see(t, "子代理", "正在读取", "正在读取…")
	f.notSee(t, "查找读配置的地方", "交给它的指令")
	// 记录还没读到：核心先用发起它的 Agent 调用的参数填上说明、类型、指令
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","full":true,"items":[],
		"meta":{"agent_id":"","agent_type":"Explore","description":"查找读配置的地方","model":"","finished":false,"stopped":false,"background":null},
		"prompt":"找出所有直接读 config 文件的地方，列出文件和行号","tool_uses":0,"running":false,"loaded":false,"missing":false,"file":"","skipped":0}`)
	f.tt.Frame()
	f.see(t, "查找读配置的地方", "正在读取", "子代理类型 Explore", "交给它的指令", "正在读取…")
	f.link().Push(agentLog)
	f.tt.Frame()
	f.see(t, "运行中", "子代理类型 Explore · 模型 claude-haiku-4-5 · 工具调用 2 次", "找到 config.go 和 main.go 两处，server.go 交给另一个子代理细看。", "正在工作")
	f.notSee(t, "正在读取…")

	// 它又派的子代理：接着点进去，返回时不再看它；外面那个还看着
	f.click(t, "子代理：细看 server.go")
	if got := f.sent("conv.subagent"); len(got) != 1 || got[0] != `{"conv":"w1","tool_use_id":"toolu_n1","watch":true}` {
		t.Fatalf("看里面那个：%v", got)
	}
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_n1","full":true,"items":[],
		"meta":{"agent_id":"","agent_type":"","description":"细看 server.go","model":"","finished":false,"stopped":false,"background":null},
		"prompt":"读 server.go","tool_uses":0,"running":false,"loaded":false,"missing":false,"file":"","skipped":0}`)
	f.tt.Frame()
	f.see(t, "细看 server.go", "读 server.go")
	f.click(t, "返回")
	if got := f.sent("conv.subagent"); len(got) != 1 || got[0] != `{"conv":"w1","tool_use_id":"toolu_n1","watch":false}` {
		t.Fatalf("离开里面那个：%v", got)
	}
	f.see(t, "找到 config.go 和 main.go 两处，server.go 交给另一个子代理细看。")

	// 做完了：增量只换状态
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","at":0,"remove":0,"items":[],"meta":null,
		"prompt":"找出所有直接读 config 文件的地方，列出文件和行号","tool_uses":3,"running":false,"loaded":true,"outcome":"completed","file":"x","skipped":4}`)
	f.tt.Frame()
	f.see(t, "已完成", "步骤 · 更早的 4 条记录未载入")
	f.notSee(t, "正在工作")
	f.click(t, "返回")
	if got := f.sent("conv.subagent"); len(got) != 1 || got[0] != `{"conv":"w1","tool_use_id":"toolu_a1","watch":false}` {
		t.Fatalf("离开：%v", got)
	}
	f.see(t, "一共三处。")
}

func TestSubagentPageFromATask(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"busy":true,` + twoTasks)
	f.click(t, "后台任务 1 个")
	f.click(t, "查找读配置的地方")
	f.click(t, "查看它的步骤")
	if got := f.sent("conv.subagent"); len(got) != 1 || !strings.Contains(got[0], `"tool_use_id":"toolu_a1","watch":true`) {
		t.Fatalf("看：%v", got)
	}
	// 发起它的那一步不在载入的聊天里、记录也还没有：说明和指令取自它的 meta
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","full":true,"items":[],"meta":null,"prompt":"",
		"tool_uses":0,"running":false,"loaded":false,"missing":true,"file":"","skipped":0}`)
	f.tt.Frame()
	f.see(t, "没有找到它的记录：Claude Code 还没写出来，或者会话已经删了")
	f.click(t, "返回")
	f.see(t, "查看它的步骤")
}

// 子代理页照推来的增量一段段改列表（不整份换，换掉的那几步以外的对比留着），两帧之间来了几段就套几段。
func TestSubagentPageSplicesPushes(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.pushItems(agentItems)
	f.app.openSubagent(f.app.pane, "toolu_a1")
	f.link().Push(agentLog)
	f.tt.Frame()
	v := f.app.pane.pages.stack[0].agent
	rev := v.list.Rev()
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","at":2,"remove":0,"items":[{"kind":"answer","id":"s3","text":"再看 server.go。"}],
		"meta":null,"prompt":"","tool_uses":2,"running":true,"loaded":true,"file":"x","skipped":0}`)
	f.link().Push(`{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","at":3,"remove":0,"items":[{"kind":"answer","id":"s4","text":"看完了。"}],
		"meta":null,"prompt":"","tool_uses":2,"running":true,"loaded":true,"file":"x","skipped":0}`)
	f.tt.Frame()
	f.see(t, "再看 server.go。", "看完了。")
	if got := v.list.Rev() - rev; got != 2 {
		t.Fatalf("两段增量套了 %d 次", got)
	}
	s, _ := f.app.pane.src.Conv.Subagent("toolu_a1")
	if len(v.list.Items()) != len(s.Items) || v.rev != s.Rev {
		t.Fatalf("列表 %d 条、第 %d 版，协议层 %d 条、第 %d 版", len(v.list.Items()), v.rev, len(s.Items), s.Rev)
	}
}

func TestSubagentPromptExpands(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	long := strings.Repeat("一行很长的指令。", 30)
	f.pushItems(agentItems)
	f.app.openSubagent(f.app.pane, "toolu_a1")
	f.link().Push(strings.Replace(agentLog, "找出所有直接读 config 文件的地方，列出文件和行号", long, 1))
	f.tt.Frame()
	f.click(t, "展开指令")
	f.click(t, "收起")
	f.see(t, "展开指令")
}

func TestSubagentStateText(t *testing.T) {
	for _, c := range []struct {
		s    chat.Subagent
		want string
		tone tone
	}{
		{chat.Subagent{}, "正在读取", toneQuiet},
		{chat.Subagent{Running: true}, "运行中", toneRunning},
		{chat.Subagent{Loaded: true, Outcome: "completed"}, "已完成", toneDone},
		{chat.Subagent{Loaded: true, Outcome: "failed"}, "失败", toneFailed},
		{chat.Subagent{Loaded: true, Outcome: "stopped"}, "已停止", toneQuiet},
		{chat.Subagent{Loaded: true}, "未做完", toneQuiet},
		{chat.Subagent{Running: true, Outcome: "completed"}, "运行中", toneRunning},
	} {
		if got, tone := subagentState(c.s); got != c.want || tone != c.tone {
			t.Errorf("subagentState(%+v) = %q %d，应为 %q %d", c.s, got, tone, c.want, c.tone)
		}
	}
}

// 任务的状态：正在停止的照运行中的颜色，不知道结局的说已结束。
func TestTaskStateText(t *testing.T) {
	for _, c := range []struct {
		state    tasks.State
		stopping bool
		want     string
		tone     tone
	}{
		{tasks.StateRunning, false, "运行中", toneRunning},
		{tasks.StateRunning, true, "正在停止", toneRunning},
		{tasks.StateCompleted, false, "已完成", toneDone},
		{tasks.StateFailed, false, "失败", toneFailed},
		{tasks.StateStopped, false, "已停止", toneQuiet},
		{tasks.StateEnded, false, "已结束", toneQuiet},
	} {
		if got, tone := taskState(c.state, c.stopping); got != c.want || tone != c.tone {
			t.Errorf("taskState(%s, %v) = %q %d，应为 %q %d", c.state, c.stopping, got, tone, c.want, c.tone)
		}
	}
}

func init() {
	// 预览：子代理页（在跑，聊天里点进来的）
	chatScenes["subagent"] = func(t *testing.T, f *chatFixture) { f.openSubagentWith(t, agentLog) }
	// 预览：子代理页，步骤多到超过窗口（跟到了底部，概要已滚上去）
	chatScenes["subagent-long"] = func(t *testing.T, f *chatFixture) { f.openSubagentWith(t, longAgentLog(20)) }
	// 预览：同上，翻回顶上
	chatScenes["subagent-long-top"] = func(t *testing.T, f *chatFixture) {
		f.openSubagentWith(t, longAgentLog(20))
		scrollToTop(f)
	}
}

// longAgentLog 是一份步骤很多的子代理记录（在跑）：n 轮，每轮一组两个工具调用和一段回答。
func longAgentLog(n int) string {
	var list []string
	for i := range n {
		id := strconv.Itoa(i)
		list = append(list, `{"kind":"work","id":"w`+id+`","steps":[`+
			`{"kind":"tool","id":"r`+id+`","name":"Read","status":"success","input":{"file_path":"/src/pkg`+id+`/config.go"},"result":"package pkg"},`+
			`{"kind":"tool","id":"g`+id+`","name":"Grep","status":"success","input":{"pattern":"config\\.Load"},"result":"pkg`+id+`/main.go:30"}]}`,
			`{"kind":"answer","id":"a`+id+`","text":"第 `+strconv.Itoa(i+1)+` 个包看完了：读配置都经 config.Load。"}`)
	}
	return `{"type":"conv.subagent","conv":"w1","tool_use_id":"toolu_a1","full":true,"at":0,"remove":0,"items":[` + strings.Join(list, ",") + `],
 "meta":{"agent_id":"ag1","agent_type":"Explore","description":"查找读配置的地方","model":"claude-haiku-4-5","finished":false,"stopped":false,"background":false},
 "prompt":"找出所有直接读 config 文件的地方，列出文件和行号","tool_uses":` + strconv.Itoa(2*n) + `,"running":true,"loaded":true,"missing":false,"file":"x","skipped":0}`
}

// openSubagentWith 在聊天里点进子代理页，推来记录 log，等列表跟到底部的动画走完。
func (f *chatFixture) openSubagentWith(t *testing.T, log string) {
	t.Helper()
	f.newChat(t)
	f.pushItems(agentItems)
	f.click(t, "已完成 1 个步骤")
	f.tt.Advance(time.Second) // 展开的动画走完
	f.tt.Frame()
	f.click(t, "子代理：查找读配置的地方")
	f.link().Push(log)
	f.tt.Frame()
	f.tt.Advance(2 * time.Second)
	f.tt.Frame()
}

// 步骤卡片跟着内容长：步骤少时「正在工作」紧跟在最后一条下面，不拉到窗口底；步骤多时整页一起滚，概要跟着滚上去。
func TestSubagentStepsCardFitsItsContent(t *testing.T) {
	f := newChatFixture(t)
	f.openSubagentWith(t, agentLog)
	answer, _ := f.tt.Find("找到 config.go 和 main.go 两处，server.go 交给另一个子代理细看。")
	busy, ok := f.tt.Find("正在工作")
	if !ok || busy.Y-answer.Y > 120 || busy.Y > 600 {
		t.Fatalf("步骤少：回答在 %v，正在工作在 %v（窗口高 860）", answer, busy)
	}

	f = newChatFixture(t)
	f.openSubagentWith(t, longAgentLog(20))
	busy, ok = f.tt.Find("正在工作")
	if !ok || busy.Y+busy.H > 860 {
		t.Fatalf("步骤多：跟到底部时正在工作在 %v", busy)
	}
	if r, ok := f.tt.Find("查找读配置的地方"); ok && r.Y+r.H > 60 {
		t.Fatalf("步骤多：跟到底部时概要还在 %v，应已滚上去", r)
	}
	scrollToTop(f)
	if r, ok := f.tt.Find("查找读配置的地方"); !ok || r.Y > 120 {
		t.Fatalf("步骤多：翻到顶上时概要在 %v（%v）", r, ok)
	}
	f.notSee(t, "正在工作")
}

// scrollToTop 在页面中间往上翻到顶。
func scrollToTop(f *chatFixture) {
	for range 40 {
		f.tt.Scroll(800, 400, 0, -400)
	}
	f.tt.Frame()
}
