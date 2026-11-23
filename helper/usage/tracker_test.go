package usage

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// 样本取自 Claude Code 2.1.327 在手机上的一次真实 stream-json 输出（三轮：两条短消息、一次 /compact），
// 只留与用量有关的字段，会话号等换成了假的。用例和 Kotlin 的 UsageTrackerTest 一一对应。
const (
	start1 = `{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5-5","id":"msg_1","usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4}}},"parent_tool_use_id":null}`
	delta1 = `{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4}},"parent_tool_use_id":null}`
	// result 行的键序不固定：type 在后面
	result1  = `{"duration_api_ms":1468,"duration_ms":2100,"total_cost_usd":0.09332420000000001,"usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4},"modelUsage":{"claude-opus-5-5":{"inputTokens":2,"outputTokens":4,"cacheReadInputTokens":13021,"cacheCreationInputTokens":11329,"costUSD":0.09332420000000001,"contextWindow":1000000}},"type":"result","subtype":"success"}`
	delta2   = `{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":2,"cache_creation_input_tokens":11482,"cache_read_input_tokens":15364,"output_tokens":4}},"parent_tool_use_id":null}`
	result2  = `{"total_cost_usd":0.188341,"usage":{"input_tokens":2,"cache_creation_input_tokens":11482,"cache_read_input_tokens":15364,"output_tokens":4},"modelUsage":{"claude-opus-5-5":{"inputTokens":4,"outputTokens":8,"cacheReadInputTokens":28385,"cacheCreationInputTokens":22811,"costUSD":0.188341,"contextWindow":1000000}},"type":"result","subtype":"success"}`
	boundary = `{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":26852,"post_tokens":7459,"cumulative_dropped_tokens":19393,"duration_ms":7070}}`
	// /compact 这一轮：result.usage 全是 0，压缩请求的花费只体现在累计里
	result3 = `{"is_error":false,"num_turns":0,"total_cost_usd":0.23370600000000002,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},"modelUsage":{"claude-opus-5-5":{"inputTokens":2164,"outputTokens":607,"cacheReadInputTokens":52750,"cacheCreationInputTokens":25295,"costUSD":0.23370600000000002,"contextWindow":1000000}},"subtype":"success","result":"","type":"result"}`
)

func observe(t *Tracker, lines ...string) {
	for _, l := range lines {
		t.Observe([]byte(l))
	}
}

func contextAnswer(totalTokens, maxTokens int64) []byte {
	return fmt.Appendf(nil, `{"subtype":"success","request_id":"zeta-9","response":{"totalTokens":%d,"maxTokens":%d,"model":"claude-opus-5-5"}}`, totalTokens, maxTokens)
}

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

func near(t *testing.T, what string, got *float64, want, tolerance float64) {
	t.Helper()
	if got == nil {
		t.Errorf("%s 没有，应为 %v", what, want)
	} else if math.Abs(*got-want) > tolerance {
		t.Errorf("%s = %v，应为 %v", what, *got, want)
	}
}

func TestContextIsTheLastRequestAndCompactionShrinksIt(t *testing.T) {
	meter := New()
	meter.ProcessStarted(true, 0)
	observe(meter, start1)
	eqPtr(t, "context", meter.Snapshot(nil).ContextTokens, 24356)
	observe(meter, delta1, result1, delta2)
	// 和压缩时 Claude Code 报的 pre_tokens 一致
	eqPtr(t, "context", meter.Snapshot(nil).ContextTokens, 26852)
	observe(meter, result2, boundary)
	after := meter.Snapshot(nil)
	eqPtr(t, "压缩后 context", after.ContextTokens, 7459)
	eq(t, "compactions", after.Compactions, 1)
	eq(t, "model", after.Model, "claude-opus-5-5")
	// 没问过 Claude Code 时用模型本身的窗口
	eqPtr(t, "window", after.ContextWindow, 1_000_000)
	// post_tokens 不含系统提示和工具：压缩后 Claude Code 的估算到了就换上
	eq(t, "estimated", after.ContextEstimated, true)
	meter.ContextUsageAnswered(contextAnswer(27040, 400_000))
	eqPtr(t, "估算的 context", meter.Snapshot(nil).ContextTokens, 27040)
	eq(t, "estimated", meter.Snapshot(nil).ContextEstimated, true)
	eqPtr(t, "Claude Code 的窗口", meter.Snapshot(nil).ContextWindow, 400_000)
	// 有实测数之后，估算不再覆盖它
	observe(meter, delta2)
	meter.ContextUsageAnswered(contextAnswer(30000, 400_000))
	eqPtr(t, "实测的 context", meter.Snapshot(nil).ContextTokens, 26852)
	eq(t, "estimated", meter.Snapshot(nil).ContextEstimated, false)
}

func TestTurnsAreDeltasOfTheProcessTotals(t *testing.T) {
	meter := New()
	meter.ProcessStarted(true, 0)
	observe(meter, start1, delta1, result1, delta2, result2, boundary, result3)
	u := meter.Snapshot(nil)
	if len(u.Turns) != 3 {
		t.Fatalf("turns = %d，应为 3", len(u.Turns))
	}
	eq(t, "turn 0", u.Turns[0].Tokens, Tokens{2, 4, 13021, 11329})
	near(t, "turn 0 cost", u.Turns[0].CostUSD, 0.0933242, 1e-9)
	eqPtr(t, "turn 0 duration", u.Turns[0].DurationMs, 2100)
	eq(t, "turn 1", u.Turns[1].Tokens, Tokens{2, 4, 15364, 11482})
	near(t, "turn 1 cost", u.Turns[1].CostUSD, 0.188341-0.0933242, 1e-9)
	// 压缩那一轮的 token 和费用来自累计之差
	eq(t, "turn 2", u.Turns[2].Tokens, Tokens{2160, 599, 24365, 2484})
	near(t, "turn 2 cost", u.Turns[2].CostUSD, 0.233706-0.188341, 1e-9)
	near(t, "total cost", u.TotalCost, 0.233706, 1e-9)
	eq(t, "total", u.Total, Tokens{2164, 607, 52750, 25295})
	eq(t, "cost partial", u.CostPartial, false)
}

func TestResumedProcessCarriesOldTotalsSoFirstCostIsUnknown(t *testing.T) {
	// --resume 之后 Claude Code 把上次进程的累计带了过来：0.2337 + 这一轮
	resumed := `{"type":"result","subtype":"success","total_cost_usd":0.33235440000000005,"usage":{"input_tokens":2,"output_tokens":3,"cache_read_input_tokens":15102,"cache_creation_input_tokens":11945},"modelUsage":{"claude-opus-5-5":{"inputTokens":2166,"outputTokens":610,"cacheReadInputTokens":67852,"cacheCreationInputTokens":37240,"costUSD":0.33235440000000005,"contextWindow":1000000}}}`
	meter := New()
	meter.ProcessStarted(false, 0)
	observe(meter, resumed)
	turns := meter.Snapshot(nil).Turns
	if len(turns) != 1 {
		t.Fatalf("turns = %d，应为 1", len(turns))
	}
	eq(t, "first", turns[0].Tokens, Tokens{2, 3, 15102, 11945})
	if turns[0].CostUSD != nil {
		t.Errorf("第一轮的费用应当不知道，得到 %v", *turns[0].CostUSD)
	}
	// 之后的一轮又能按差值算
	next := `{"type":"result","total_cost_usd":0.40,"usage":{"input_tokens":1,"output_tokens":1,"cache_read_input_tokens":1,"cache_creation_input_tokens":1},"modelUsage":{"claude-opus-5-5":{"inputTokens":2167,"outputTokens":620,"cacheReadInputTokens":67900,"cacheCreationInputTokens":37300}}}`
	observe(meter, next)
	u := meter.Snapshot(nil)
	eq(t, "second", u.Turns[1].Tokens, Tokens{1, 10, 48, 60})
	near(t, "second cost", u.Turns[1].CostUSD, 0.40-0.3323544, 1e-9)
	eq(t, "cost partial", u.CostPartial, true)
}

func TestResumedProcessWithoutCarriedTotalsKeepsCost(t *testing.T) {
	meter := New()
	meter.ProcessStarted(false, 0)
	observe(meter, result1)
	near(t, "cost", meter.Snapshot(nil).Turns[0].CostUSD, 0.0933242, 1e-9)
}

func TestCountersThatStartOverDoNotGiveNegativeTurns(t *testing.T) {
	meter := New()
	meter.ProcessStarted(true, 0)
	observe(meter, result1, result2, `{"type":"conversation_reset","new_conversation_id":"s2"}`)
	if c := meter.Snapshot(nil).ContextTokens; c != nil {
		t.Errorf("/clear 之后 context 应当没有，得到 %d", *c)
	}
	// /clear 之后累计从零开始：这一轮按 result.usage 算，费用与累计对得上
	observe(meter, `{"type":"result","total_cost_usd":0.01,"usage":{"input_tokens":3,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":900},"modelUsage":{"claude-opus-5-5":{"inputTokens":3,"outputTokens":5,"cacheReadInputTokens":0,"cacheCreationInputTokens":900}}}`)
	turns := meter.Snapshot(nil).Turns
	last := turns[len(turns)-1]
	eq(t, "tokens", last.Tokens, Tokens{3, 5, 0, 900})
	near(t, "cost", last.CostUSD, 0.01, 1e-9)
}

func TestSubagentMessagesDoNotMoveTheContext(t *testing.T) {
	meter := New()
	observe(meter, delta1,
		`{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":5,"cache_read_input_tokens":90000,"output_tokens":10}},"parent_tool_use_id":"toolu_1"}`)
	eqPtr(t, "context", meter.Snapshot(nil).ContextTokens, 24356)
}

func TestClaudeWindowWinsAndIsRemembered(t *testing.T) {
	meter := New()
	observe(meter, result1)
	meter.ContextUsageAnswered([]byte(`{"subtype":"success","request_id":"zeta-2","response":{"totalTokens":12410,"maxTokens":400000,"rawMaxTokens":400000,"model":"claude-opus-5-5"}}`))
	u := meter.Snapshot(nil)
	eqPtr(t, "window", u.ContextWindow, 400_000)
	model, window, ok := meter.LearnedWindow()
	if !ok || model != "claude-opus-5-5" || window != 400_000 {
		t.Errorf("learned = %q %d %v", model, window, ok)
	}
	// result1 之前没有消息事件，上下文先用 Claude Code 的估算
	eqPtr(t, "context", u.ContextTokens, 12410)
	meter.SettingsAnswered([]byte(`{"subtype":"success","request_id":"zeta-3","response":{"effective":{},"applied":{"model":"claude-opus-5-5","effort":"xhigh"}}}`))
	eq(t, "applied effort", meter.Snapshot(nil).AppliedEffort, "xhigh")
}

var historyLines = []string{
	`{"type":"user","message":{"content":[{"type":"text","text":"a"}]}}`,
	// 同一条消息按内容块拆成两行，usage 只算一次
	`{"type":"assistant","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":2,"output_tokens":40,"cache_read_input_tokens":100,"cache_creation_input_tokens":10},"content":[{"type":"thinking","thinking":"x"}]}}`,
	`{"type":"assistant","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":2,"output_tokens":40,"cache_read_input_tokens":100,"cache_creation_input_tokens":10},"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}`,
	`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`,
	`{"type":"assistant","message":{"id":"m2","model":"claude-opus-5-5","usage":{"input_tokens":1,"output_tokens":5,"cache_read_input_tokens":150,"cache_creation_input_tokens":20},"content":[{"type":"text","text":"done"}]}}`,
	`{"type":"user","message":{"content":[{"type":"text","text":"b"}]}}`,
	`{"type":"assistant","message":{"id":"m3","model":"claude-opus-5-5","usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":170,"cache_creation_input_tokens":8},"content":[{"type":"text","text":"ok"}]}}`,
}

func readHistory(lines []string) *Tracker {
	h := New()
	for _, l := range lines {
		h.ObserveHistory([]byte(l))
	}
	h.FinishHistory()
	return h
}

func TestHistoryRestoresContextAndTurns(t *testing.T) {
	known, err := LoadWindows(filepath.Join(t.TempDir(), "context-windows.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := known.Learn("claude-opus-5-5", 400_000); err != nil {
		t.Fatal(err)
	}
	history := readHistory(historyLines)
	restored := history.Snapshot(known)
	eqPtr(t, "context", restored.ContextTokens, 181)
	eqPtr(t, "window", restored.ContextWindow, 400_000)
	if len(restored.Turns) != 2 {
		t.Fatalf("turns = %d，应为 2", len(restored.Turns))
	}
	eq(t, "turn 0", restored.Turns[0].Tokens, Tokens{3, 45, 250, 30})
	// 记录里没有费用：按各条消息的模型和 token 数估，缓存写入按 1 小时档
	near(t, "turn 0 cost", restored.Turns[0].CostUSD, 1202e-6, 1e-12)
	near(t, "turn 1 cost", restored.Turns[1].CostUSD, 142e-6, 1e-12)
	for i, turn := range restored.Turns {
		eq(t, fmt.Sprintf("turn %d estimated", i), turn.CostEstimated, true)
	}
	eq(t, "cost estimated", restored.CostEstimated, true)
	eq(t, "cost partial", restored.CostPartial, false)

	// 压缩之后记录里只有压缩提示，占用取压缩后的大小
	compacted := readHistory(append(append([]string(nil), historyLines...),
		`{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":181,"post_tokens":60}}`))
	eqPtr(t, "压缩后 context", compacted.Snapshot(nil).ContextTokens, 60)
	eq(t, "compactions", compacted.Snapshot(nil).Compactions, 1)
	// 重新打开后起了进程：压缩后的数是估算，换成 Claude Code 报的
	reopened := New()
	reopened.PrependHistory(compacted)
	reopened.ContextUsageAnswered(contextAnswer(24400, 400_000))
	eqPtr(t, "重新打开后 context", reopened.Snapshot(nil).ContextTokens, 24400)

	// 历史接在实时数据前面
	live := New()
	live.ProcessStarted(false, 0)
	observe(live, result1)
	live.PrependHistory(history)
	merged := live.Snapshot(nil)
	eq(t, "merged turns", len(merged.Turns), 3)
	eq(t, "merged turn 0", merged.Turns[0].Tokens, Tokens{3, 45, 250, 30})
	eqPtr(t, "merged context", merged.ContextTokens, 181)
}

func level(contextTokens, window *int64) Level {
	u := Usage{ContextTokens: contextTokens, ContextWindow: window}
	u.fill()
	return u.Level
}

func TestLevelsAndFormatting(t *testing.T) {
	eq(t, "100/1000", level(ptr[int64](100), ptr[int64](1000)), LevelNormal)
	eq(t, "800/1000", level(ptr[int64](800), ptr[int64](1000)), LevelWarning)
	eq(t, "950/1000", level(ptr[int64](950), ptr[int64](1000)), LevelCritical)
	eq(t, "950/?", level(ptr[int64](950), nil), LevelNormal)
	for value, want := range map[int64]string{
		26852: "26.9K", 26618: "26.6K", 123_456: "123K", 99_960: "100K", 7459: "7.5K", 1000: "1K",
		400_000: "400K", 1_000_000: "1M", 1_250_000: "1.25M", 812: "812",
	} {
		eq(t, fmt.Sprint("FormatTokens ", value), FormatTokens(value), want)
	}
	eq(t, "FormatExactTokens", FormatExactTokens(26852), "26,852")
	eq(t, "FormatCost", FormatCost(0.0933242), "$0.0933")
	eq(t, "FormatCost", FormatCost(1.5), "$1.50")
	eq(t, "CompactNotice", CompactNotice([]byte(boundary)).String(), "上下文已压缩（压缩前 26.9K）")
	eq(t, "CompactNotice auto", CompactNotice([]byte(`{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto"}}`)).String(), "上下文已自动压缩")
}

// 进位照 Java 的 String.format：最短十进制写法上逢五进一。
func TestFixedRoundsHalfUpLikeJava(t *testing.T) {
	for _, c := range []struct {
		x      float64
		places int
		want   string
	}{
		{1.25, 1, "1.3"}, {0.125, 2, "0.13"}, {100.5, 0, "101"}, {9.995, 2, "10.00"}, {0.99995, 4, "1.0000"},
		{1.005, 2, "1.01"}, {26.852, 1, "26.9"}, {3, 2, "3.00"}, {0.0933242, 4, "0.0933"},
	} {
		eq(t, fmt.Sprint("fixed ", c.x, " ", c.places), fixed(c.x, c.places), c.want)
	}
	eq(t, "FormatTokens 1250", FormatTokens(1250), "1.3K")
	eq(t, "FormatExactTokens 0", FormatExactTokens(0), "0")
	eq(t, "FormatExactTokens 1234567", FormatExactTokens(1_234_567), "1,234,567")
}

// 压缩点：Claude Code 报的不含百分比，按百分比压到更早；关着时没有压缩点。
func TestCompactPointAndFractions(t *testing.T) {
	meter := New()
	meter.ProcessStarted(true, 50)
	meter.ContextUsageAnswered([]byte(`{"response":{"totalTokens":100000,"maxTokens":400000,"isAutoCompactEnabled":true,"autoCompactThreshold":367000}}`))
	u := meter.Snapshot(nil)
	eqPtr(t, "compact at", u.CompactAt, 190_000)
	eqPtr(t, "auto compact", u.AutoCompact, true)
	eqPtr(t, "fraction", u.Fraction, 0.25)
	eqPtr(t, "compact fraction", u.CompactFraction, 0.475)
	eq(t, "level", u.Level, LevelNormal)
	// 按离压缩点多近算：152000 / 190000 = 0.8
	u.ContextTokens = ptr[int64](152_000)
	u.fill()
	eq(t, "level near compact", u.Level, LevelWarning)

	// 10 万的 29%：双精度算出 28999
	eqPtr(t, "29%", compactThreshold(ptr[int64](100_000-summaryBuffer), 29), 28_999)
	eqPtr(t, "百分比不在 1..100", compactThreshold(ptr[int64](5000), 0), 5000)

	meter.ContextUsageAnswered([]byte(`{"response":{"maxTokens":1000000,"isAutoCompactEnabled":false}}`))
	u = meter.Snapshot(nil)
	if u.CompactAt != nil || u.CompactFraction != nil {
		t.Errorf("自动压缩关着时不该有压缩点：%v %v", u.CompactAt, u.CompactFraction)
	}
	eqPtr(t, "auto compact", u.AutoCompact, false)
	// 新进程的开关要等它自己报
	meter.ProcessStarted(false, 50)
	if meter.Snapshot(nil).AutoCompact != nil {
		t.Error("新进程还没报开关")
	}
}

func TestEstimatedCostFollowsThePriceTable(t *testing.T) {
	u := func(s string) *apiUsage {
		var a apiUsage
		if !decode([]byte(s), &a) {
			t.Fatal(s)
		}
		return &a
	}
	plain := u(`{"input_tokens":1000000}`)
	for model, want := range map[string]float64{
		"claude-opus-5-5": 4, "claude-opus-5-5-20261001": 4, "claude-opus-5-5[1m]": 4, "claude-haiku-5-5": 0.1,
	} {
		got, ok := estimateCost(model, plain)
		if !ok || got != want {
			t.Errorf("%s：%v %v，应为 %v", model, got, ok, want)
		}
	}
	if _, ok := estimateCost("claude-unknown-1", plain); ok {
		t.Error("表里没有的模型应当算不出来")
	}
	if _, ok := estimateCost("claude-opus-5-5", u(`{}`)); ok {
		t.Error("空的 usage 应当算不出来")
	}
	// 5 分钟档按 1.25 倍、其余按 2 倍；快速模式整体 2 倍
	got, _ := estimateCost("claude-opus-5-5", u(`{"cache_creation_input_tokens":1000000,"cache_creation":{"ephemeral_5m_input_tokens":400000},"speed":"fast"}`))
	if want := (400_000*4*1.25 + 600_000*4*2) * 2 / 1e6; math.Abs(got-want) > 1e-12 {
		t.Errorf("缓存写入 = %v，应为 %v", got, want)
	}
}

// 窗口按主机分开记：核心每台主机一个，各自的文件互不相干；记下的窗口重新载入还在，几个进程先后写不互相覆盖。
func TestKnownWindowsAreKeptPerHost(t *testing.T) {
	dir := t.TempDir()
	local, _ := LoadWindows(filepath.Join(dir, "local", "context-windows.json"))
	remote, _ := LoadWindows(filepath.Join(dir, "r-00000001", "context-windows.json"))
	if err := local.Learn("claude-fable-5-2", 400_000); err != nil {
		t.Fatal(err)
	}
	if err := remote.Learn("claude-fable-5-2", 500_000); err != nil {
		t.Fatal(err)
	}
	if w, _ := local.Window("claude-fable-5-2"); w != 400_000 {
		t.Errorf("local = %d", w)
	}
	if w, _ := remote.Window("claude-fable-5-2"); w != 500_000 {
		t.Errorf("remote = %d", w)
	}
	other, err := LoadWindows(filepath.Join(dir, "r-00000002", "context-windows.json"))
	if _, ok := other.Window("claude-fable-5-2"); ok || err != nil {
		t.Errorf("别的主机不该有：%v", err)
	}

	// 同一个文件：另一个进程先写了别的模型，这边再写时并上，不丢它那一项
	path := filepath.Join(dir, "local", "context-windows.json")
	second, _ := LoadWindows(path)
	if err := second.Learn("claude-opus-5-5", 1_000_000); err != nil {
		t.Fatal(err)
	}
	if err := local.Learn("claude-sonnet-5-5", 200_000); err != nil {
		t.Fatal(err)
	}
	reloaded, err := LoadWindows(path)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int64{"claude-fable-5-2": 400_000, "claude-opus-5-5": 1_000_000, "claude-sonnet-5-5": 200_000}
	if !reflect.DeepEqual(reloaded.known, want) {
		t.Errorf("文件里是 %v，应为 %v", reloaded.known, want)
	}
	entries, _ := os.ReadDir(filepath.Dir(path))
	if len(entries) != 1 {
		t.Errorf("目录里不该留临时文件：%v", entries)
	}

	// 坏了的文件当空的用，并说明原因
	if err := os.WriteFile(path, []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	broken, err := LoadWindows(path)
	if err == nil {
		t.Error("坏文件应当报出原因")
	}
	if _, ok := broken.Window("claude-fable-5-2"); ok {
		t.Error("坏文件应当当空的用")
	}
}

// 推给客户端的 JSON：snake_case，算好的几项都在，没有的项不写。
func TestUsageJSON(t *testing.T) {
	meter := New()
	meter.ProcessStarted(true, 0)
	observe(meter, start1, delta1, result1)
	data, err := json.Marshal(meter.Snapshot(nil))
	if err != nil {
		t.Fatal(err)
	}
	got := string(data)
	for _, part := range []string{
		`"context_tokens":24356`, `"context_window":1000000`, `"model":"claude-opus-5-5"`,
		`"tokens":{"input":2,"output":4,"cache_read":13021,"cache_write":11329,"total":24356}`, `"duration_ms":2100`,
		`"total_cost":0.0933242`, `"fraction":0.024356`, `"level":"normal"`,
	} {
		if !strings.Contains(got, part) {
			t.Errorf("JSON 里没有 %s：%s", part, got)
		}
	}
	for _, absent := range []string{"compact_at", "auto_compact", "cost_partial", "context_estimated", "applied_effort"} {
		if strings.Contains(got, absent) {
			t.Errorf("JSON 里不该有 %s：%s", absent, got)
		}
	}
}

// 字段的类型对不上时当它没有，别的字段照常用；不是 JSON 的行什么也不做。
func TestMalformedLines(t *testing.T) {
	meter := New()
	if meter.Observe([]byte(`not json`)) {
		t.Error("不是 JSON 的行不该算变化")
	}
	observe(meter, `{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":"x","output_tokens":7}},"parent_tool_use_id":null}`)
	eqPtr(t, "context", meter.Snapshot(nil).ContextTokens, 7)
}

// 客户端（桌面版）用同一个类型读 conv.state 的 usage：写出去再读回来要一样。
func TestUsageRoundTripsThroughJSON(t *testing.T) {
	cost := 0.25
	u := Usage{ContextTokens: ptr(int64(1200)), Model: "claude-opus-5-5",
		Turns: []Turn{{Tokens: Tokens{Input: 1, Output: 2, CacheRead: 3, CacheWrite: 4}, CostUSD: &cost}}}
	u.fill()
	b, err := json.Marshal(u)
	if err != nil {
		t.Fatal(err)
	}
	var back Usage
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(back, u) {
		t.Fatalf("读回来是 %+v，要 %+v", back, u)
	}
}
