package conv

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/usage"
)

// writeFile 写一份一行一个 JSON 的文件，返回路径。
func writeFile(t *testing.T, name string, lines ...string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(p, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

// costStateRecord 写一份会话记录，最后一行 cost-state 记着累计输入 30、费用 0.3。
func costStateRecord(t *testing.T) string {
	return writeFile(t, "s1.jsonl",
		`{"type":"cost-state","totalCostUSD":0.1,"modelUsage":{"m":{"inputTokens":10}}}`,
		`{"type":"user","message":{"content":"hi"}}`,
		`{"type":"cost-state","totalCostUSD":0.3,"modelUsage":{"m":{"inputTokens":30}}}`)
}

// result 是累计输入 input、费用 cost 的 result 行，这一轮自己的输入是 2。
func result(input, cost string) string {
	return `{"type":"result","subtype":"success","is_error":false,"result":"ok","total_cost_usd":` + cost +
		`,"usage":{"input_tokens":2},"modelUsage":{"m":{"inputTokens":` + input + `}}}`
}

// hostLog 写一份托管输出日志：跑完过一轮，累计输入 40、费用 0.45。接上它的一方从第 3 行读起。
func hostLog(t *testing.T) string {
	return writeFile(t, "out.jsonl", initLine("s1"), result("40", "0.45"))
}

// lastTurn 是对话 c 最近一轮的用量。
func lastTurn(t *testing.T, c *Conversation) usage.Turn {
	t.Helper()
	v, _ := c.Call(func() (any, error) { return c.usageView(), nil })
	turns := v.(usage.Usage).Turns
	if len(turns) == 0 {
		t.Fatal("没有这一轮的用量")
	}
	return turns[len(turns)-1]
}

// resumeOneTurn 打开会话 s1（记录见 costStateRecord），发一条消息起进程，托管脚本报 attached 后跑完一轮，返回这一轮的用量。
// 托管输出日志都是 log。
func resumeOneTurn(t *testing.T, log string, attached Attached, final string) usage.Turn {
	t.Helper()
	h := newHarness(t)
	record := costStateRecord(t)
	h.m.deps.SessionFile = func(string) string { return record }
	h.m.deps.HostOutput = func(string) string { return log }
	h.history = []byte(`{"type":"zeta_history","skipped":0}` + "\n")
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "接着"})
	l := h.link(0)
	l.events.Attached(attached)
	h.feed(c, l, initLine("s1"), final)
	return lastTurn(t, c)
}

// 续上会话起了新进程：第一轮从记录里最后一行 cost-state 接着算，费用是 Claude Code 报的。
// 接上了本包已在跑的进程：从它输出日志里接上之前最后一个 result 接着算；它还一轮没跑完时不知道，退回只算主对话这一轮。
func TestFirstTurnBaselineFollowsTheAttachedProcess(t *testing.T) {
	empty := writeFile(t, "empty.jsonl", initLine("s1"))
	if turn := resumeOneTurn(t, empty, Attached{Key: "k", From: 1}, result("40", "0.45")); turn.Tokens.Input != 10 ||
		turn.CostUSD == nil || *turn.CostUSD < 0.1499 || *turn.CostUSD > 0.1501 || turn.CostEstimated {
		t.Fatalf("起了新进程：%+v", turn)
	}
	if turn := resumeOneTurn(t, hostLog(t), Attached{Key: "running", From: 3, Joined: true}, result("55", "0.5")); turn.Tokens.Input != 15 ||
		turn.CostUSD == nil || *turn.CostUSD < 0.0499 || *turn.CostUSD > 0.0501 {
		t.Fatalf("接上跑完过一轮的进程：%+v", turn)
	}
	if turn := resumeOneTurn(t, empty, Attached{Key: "running", From: 2, Joined: true}, result("40", "0.45")); turn.Tokens.Input != 2 {
		t.Fatalf("接上还一轮没跑完的进程：%+v", turn)
	}
}

// 重新打开对话、接回还在跑的后台进程：第一轮也从它输出日志里接上之前最后一个 result 接着算。
func TestReopenedConversationContinuesTheRunningProcess(t *testing.T) {
	h := newHarness(t)
	log := hostLog(t)
	h.m.deps.HostOutput = func(key string) string {
		if key != "run" {
			t.Errorf("托管标识：%s", key)
		}
		return log
	}
	h.history = []byte(`{"type":"zeta_history","skipped":0}` + "\n")
	h.m.Adopt([]Hosted{{Key: "run", Session: "s1", Cwd: "/w", Lines: 2, Busy: true}})
	deadline := time.Now().Add(5 * time.Second)
	for {
		h.mu.Lock()
		n := len(h.attaches)
		h.mu.Unlock()
		if n == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("没有接回后台进程")
		}
		time.Sleep(5 * time.Millisecond)
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	l := linkOf(t, h, "run")
	l.events.Attached(Attached{Key: "run", From: 3})
	h.feed(c, l, result("55", "0.5"))
	if turn := lastTurn(t, c); turn.Tokens.Input != 15 || turn.CostUSD == nil || *turn.CostUSD < 0.0499 || *turn.CostUSD > 0.0501 {
		t.Fatalf("接回后台进程的那一轮：%+v", turn)
	}
}

// 起进程时的基线：新对话从零起；续上会话读它自己的记录；分支（还没有自己的会话号）读原会话的；找不到记录时不知道。
func TestCarriedUsagePicksTheResumedRecord(t *testing.T) {
	record := costStateRecord(t)
	var asked []string
	c := &Conversation{deps: Deps{SessionFile: func(s string) string {
		asked = append(asked, s)
		if s == "missing" {
			return ""
		}
		return record
	}}}
	if got := c.carriedUsage(); got == nil || *got != (usage.Carried{}) {
		t.Fatalf("新对话：%+v", got)
	}
	c.forkSource = "src"
	if got := c.carriedUsage(); got == nil || got.Tokens.Input != 30 || asked[len(asked)-1] != "src" {
		t.Fatalf("分支：%+v %v", got, asked)
	}
	c.session = "s1"
	if got := c.carriedUsage(); got == nil || asked[len(asked)-1] != "s1" {
		t.Fatalf("续上会话：%+v %v", got, asked)
	}
	c.session = "missing"
	if got := c.carriedUsage(); got != nil {
		t.Fatalf("找不到记录：%+v", got)
	}
}
