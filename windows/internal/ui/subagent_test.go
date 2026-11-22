package ui

import (
	"strings"
	"testing"
	"time"

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
	// 记录还没推来：说明、类型、指令先取自聊天里发起它的那一步
	f.see(t, "子代理", "查找读配置的地方", "正在读取", "子代理类型 Explore", "交给它的指令", "正在读取…")
	f.link().Push(agentLog)
	f.tt.Frame()
	f.see(t, "运行中", "子代理类型 Explore · 模型 claude-haiku-4-5 · 工具调用 2 次", "找到 config.go 和 main.go 两处，server.go 交给另一个子代理细看。", "正在工作")
	f.notSee(t, "正在读取…")

	// 它又派的子代理：接着点进去，返回时不再看它；外面那个还看着
	f.click(t, "子代理：细看 server.go")
	if got := f.sent("conv.subagent"); len(got) != 1 || got[0] != `{"conv":"w1","tool_use_id":"toolu_n1","watch":true}` {
		t.Fatalf("看里面那个：%v", got)
	}
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

func TestSubagentPromptExpands(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	long := strings.Repeat("一行很长的指令。", 30)
	f.pushItems(strings.Replace(agentItems, "找出所有直接读 config 文件的地方，列出文件和行号", long, 1))
	f.app.openSubagent(f.app.pane, "toolu_a1")
	f.tt.Frame()
	f.click(t, "展开指令")
	f.click(t, "收起")
	f.see(t, "展开指令")
}

func TestSubagentStateText(t *testing.T) {
	for _, c := range []struct {
		s    chat.Subagent
		want string
	}{
		{chat.Subagent{}, "正在读取"},
		{chat.Subagent{Running: true}, "运行中"},
		{chat.Subagent{Loaded: true, Outcome: "completed"}, "已完成"},
		{chat.Subagent{Loaded: true, Outcome: "failed"}, "失败"},
		{chat.Subagent{Loaded: true, Outcome: "stopped"}, "已停止"},
		{chat.Subagent{Loaded: true}, "未做完"},
		{chat.Subagent{Running: true, Outcome: "completed"}, "运行中"},
	} {
		if got := subagentState(c.s); got != c.want {
			t.Errorf("subagentState(%+v) = %q，应为 %q", c.s, got, c.want)
		}
	}
}

func init() {
	// 预览：子代理页（在跑，聊天里点进来的）
	chatScenes["subagent"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.pushItems(agentItems)
		f.click(t, "已完成 1 个步骤")
		f.tt.Advance(time.Second) // 展开的动画走完
		f.tt.Frame()
		f.click(t, "子代理：查找读配置的地方")
		f.link().Push(agentLog)
		f.tt.Frame()
	}
}
