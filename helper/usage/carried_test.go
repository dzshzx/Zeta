package usage

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func costStateLine(input int64, cost string) string {
	return `{"type":"cost-state","sessionId":"s","totalCostUSD":` + cost + `,"modelUsage":{"claude-haiku-5-5":{"inputTokens":` +
		strconv.FormatInt(input, 10) + `,"outputTokens":1,"cacheReadInputTokens":2,"cacheCreationInputTokens":3}}}`
}

func writeTranscript(t *testing.T, lines ...string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "s.jsonl")
	if err := os.WriteFile(p, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

// 取最后一行 cost-state；文字里提到它（引号转义过）的行不算；一行都没有时是零；文件读不了时出错。
func TestLastCostStateIsTheLastLine(t *testing.T) {
	quoted := `{"type":"user","message":{"content":"{\"type\":\"cost-state\",\"totalCostUSD\":9}"}}`
	got, err := LastCostState(writeTranscript(t, costStateLine(10, "0.1"), `{"type":"user"}`, costStateLine(30, "0.3"),
		`{"type":"assistant"}`, quoted))
	if err != nil {
		t.Fatal(err)
	}
	if got.Tokens != (Tokens{30, 1, 2, 3}) || got.CostUSD != 0.3 {
		t.Fatalf("最后一行：%+v", got)
	}
	none, err := LastCostState(writeTranscript(t, `{"type":"user"}`, quoted))
	if err != nil || none != (Carried{}) {
		t.Fatalf("没有 cost-state：%+v %v", none, err)
	}
	if _, err := LastCostState(filepath.Join(t.TempDir(), "missing.jsonl")); err == nil {
		t.Fatal("读不了的文件应出错")
	}
}

// 记录比一段长、最后一行 cost-state 跨在两段的边界上、后面还跟着一大行，也找得到。
func TestLastCostStateAcrossChunks(t *testing.T) {
	big := `{"type":"user","message":{"content":"` + strings.Repeat("x", costStateChunk+100) + `"}}`
	line := costStateLine(42, "0.42")
	// 让 cost-state 这一行跨过从末尾数一段的位置
	pad := `{"type":"user","message":{"content":"` + strings.Repeat("y", costStateChunk-len(line)/2-40) + `"}}`
	for name, lines := range map[string][]string{
		"跨段":   {big, line, pad},
		"后面大行": {line, big, big},
	} {
		got, err := LastCostState(writeTranscript(t, lines...))
		if err != nil || got.Tokens.Input != 42 || got.CostUSD != 0.42 {
			t.Errorf("%s：%+v %v", name, got, err)
		}
	}
}

// 续上会话的进程带着记录里的累计：第一轮就是 result 的累计减去它，费用是 Claude Code 报的、不是估的。
func TestCarriedBaselineGivesTheFirstResumedTurn(t *testing.T) {
	meter := New()
	meter.ProcessStarted(&Carried{Tokens: Tokens{30, 3, 300, 30}, CostUSD: 0.3}, 0)
	observe(meter, `{"type":"result","total_cost_usd":0.45,"usage":{"input_tokens":1,"output_tokens":1},`+
		`"modelUsage":{"claude-opus-5-5":{"inputTokens":40,"outputTokens":5,"cacheReadInputTokens":500,"cacheCreationInputTokens":40}}}`)
	turns := meter.Snapshot(nil).Turns
	if len(turns) != 1 {
		t.Fatalf("turns = %d", len(turns))
	}
	eq(t, "tokens", turns[0].Tokens, Tokens{10, 2, 200, 10})
	near(t, "cost", turns[0].CostUSD, 0.15, 1e-12)
	eq(t, "estimated", turns[0].CostEstimated, false)

	// 接上的是已在跑的进程：基线作废，第一轮按 result.usage 算
	joined := New()
	joined.ProcessStarted(&Carried{Tokens: Tokens{30, 3, 300, 30}, CostUSD: 0.3}, 0)
	joined.Joined(false)
	observe(joined, `{"type":"result","total_cost_usd":0.45,"usage":{"input_tokens":1,"output_tokens":1},`+
		`"modelUsage":{"claude-opus-5-5":{"inputTokens":40,"outputTokens":5,"cacheReadInputTokens":500,"cacheCreationInputTokens":40}}}`)
	eq(t, "joined tokens", joined.Snapshot(nil).Turns[0].Tokens, Tokens{1, 1, 0, 0})
}
