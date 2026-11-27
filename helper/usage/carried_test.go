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

func resultLine(input int64, cost string) string {
	return `{"type":"result","subtype":"success","total_cost_usd":` + cost + `,"usage":{"input_tokens":1,"output_tokens":1},` +
		`"modelUsage":{"claude-opus-5-5":{"inputTokens":` + strconv.FormatInt(input, 10) +
		`,"outputTokens":5,"cacheReadInputTokens":500,"cacheCreationInputTokens":40}}}`
}

func writeLines(t *testing.T, lines ...string) string {
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
	got, err := LastCostState(writeLines(t, costStateLine(10, "0.1"), `{"type":"user"}`, costStateLine(30, "0.3"),
		`{"type":"assistant"}`, quoted))
	if err != nil {
		t.Fatal(err)
	}
	if got.Tokens != (Tokens{30, 1, 2, 3}) || got.CostUSD != 0.3 {
		t.Fatalf("最后一行：%+v", got)
	}
	none, err := LastCostState(writeLines(t, `{"type":"user"}`, quoted))
	if err != nil || none != (Carried{}) {
		t.Fatalf("没有 cost-state：%+v %v", none, err)
	}
	if _, err := LastCostState(filepath.Join(t.TempDir(), "missing.jsonl")); err == nil {
		t.Fatal("读不了的文件应出错")
	}
}

// 托管输出日志里第 line 行之前最后一个 result：之后的不算；之前没有时 ok 为 false。
func TestLastResultBeforeTheAttachedLine(t *testing.T) {
	log := writeLines(t, `{"type":"system","subtype":"init"}`, resultLine(40, "0.45"), `{"type":"assistant"}`, resultLine(55, "0.5"))
	got, ok, err := LastResultBefore(log, 4)
	if err != nil || !ok || got.Tokens != (Tokens{40, 5, 500, 40}) || got.CostUSD != 0.45 {
		t.Fatalf("第 4 行之前：%+v %v %v", got, ok, err)
	}
	if got, ok, _ := LastResultBefore(log, 9); !ok || got.CostUSD != 0.5 {
		t.Fatalf("整个日志：%+v %v", got, ok)
	}
	if _, ok, err := LastResultBefore(log, 2); ok || err != nil {
		t.Fatalf("还一轮没跑完：%v %v", ok, err)
	}
}

// 续上会话的进程带着记录里的累计：第一轮就是 result 的累计减去它，费用是 Claude Code 报的、不是估的。
func TestCarriedBaselineGivesTheFirstResumedTurn(t *testing.T) {
	meter := New()
	meter.ProcessStarted(&Carried{Tokens: Tokens{30, 3, 300, 30}, CostUSD: 0.3}, 0)
	observe(meter, resultLine(40, "0.45"))
	turns := meter.Snapshot(nil).Turns
	if len(turns) != 1 {
		t.Fatalf("turns = %d", len(turns))
	}
	eq(t, "tokens", turns[0].Tokens, Tokens{10, 2, 200, 10})
	near(t, "cost", turns[0].CostUSD, 0.15, 1e-12)
	eq(t, "estimated", turns[0].CostEstimated, false)
}
