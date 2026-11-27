package conv

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/helper/usage"
)

// costStateRecord 写一份会话记录，最后一行 cost-state 记着累计输入 30、费用 0.3。
func costStateRecord(t *testing.T) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "s1.jsonl")
	data := `{"type":"cost-state","totalCostUSD":0.1,"modelUsage":{"m":{"inputTokens":10}}}` + "\n" +
		`{"type":"user","message":{"content":"hi"}}` + "\n" +
		`{"type":"cost-state","totalCostUSD":0.3,"modelUsage":{"m":{"inputTokens":30}}}` + "\n"
	if err := os.WriteFile(p, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

const resumedResult = `{"type":"result","subtype":"success","is_error":false,"result":"ok","total_cost_usd":0.45,` +
	`"usage":{"input_tokens":2},"modelUsage":{"m":{"inputTokens":40}}}`

// resumeOneTurn 打开会话 s1（记录见 costStateRecord），发一条消息起进程；join 时扮托管脚本接上了本包已在跑的进程。
// 返回这一轮的用量。
func resumeOneTurn(t *testing.T, join bool) usage.Turn {
	t.Helper()
	h := newHarness(t)
	record := costStateRecord(t)
	h.m.deps.SessionFile = func(session string) string { return record }
	h.history = []byte(`{"type":"zeta_history","skipped":0}` + "\n")
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "接着"})
	l := h.link(0)
	if join {
		l.mu.Lock()
		l.key = "running-key"
		l.mu.Unlock()
	}
	h.feed(c, l, initLine("s1"), resumedResult)
	v, _ := c.Call(func() (any, error) { return c.usageView(), nil })
	turns := v.(usage.Usage).Turns
	if len(turns) == 0 {
		t.Fatal("没有这一轮的用量")
	}
	return turns[len(turns)-1]
}

// 续上会话：第一轮从记录里最后一行 cost-state 接着算，费用是 Claude Code 报的；
// 接上的是本包已在跑的进程时，那一行不是它的基线，退回只算主对话这一轮。
func TestResumedTurnStartsFromTheLastCostState(t *testing.T) {
	turn := resumeOneTurn(t, false)
	if turn.Tokens.Input != 10 || turn.CostUSD == nil || *turn.CostUSD < 0.1499 || *turn.CostUSD > 0.1501 || turn.CostEstimated {
		t.Fatalf("续上会话的第一轮：%+v", turn)
	}
	if joined := resumeOneTurn(t, true); joined.Tokens.Input != 2 {
		t.Fatalf("接上已在跑的进程：%+v", joined)
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

// 放开连接后又回来发消息，托管脚本接上的正是这个对话上次接着的那个进程：基线照上次最后一个 result 的累计。
func TestRejoiningTheSameProcessKeepsItsBaseline(t *testing.T) {
	h := newHarness(t)
	record := costStateRecord(t)
	h.m.deps.SessionFile = func(session string) string { return record }
	h.history = []byte(`{"type":"zeta_history","skipped":0}` + "\n")
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	first := h.link(0)
	h.feed(c, first, initLine("s1"), resumedResult) // 累计 40
	_, _ = c.Call(func() (any, error) { c.closeProcess(false); return nil, nil })

	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	again := h.link(1)
	again.mu.Lock()
	again.key = first.key // 托管脚本接上了还在跑的那个进程
	again.mu.Unlock()
	h.feed(c, again, `{"type":"result","subtype":"success","is_error":false,"result":"ok","total_cost_usd":0.5,`+
		`"usage":{"input_tokens":2},"modelUsage":{"m":{"inputTokens":55}}}`)
	v, _ := c.Call(func() (any, error) { return c.usageView(), nil })
	turns := v.(usage.Usage).Turns
	last := turns[len(turns)-1]
	if last.Tokens.Input != 15 || last.CostUSD == nil || *last.CostUSD < 0.0499 || *last.CostUSD > 0.0501 {
		t.Fatalf("接回同一个进程的那一轮：%+v", last)
	}
}
