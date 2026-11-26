package main

import (
	"os"
	"testing"
	"time"
)

// 侧栏排序用的时刻和位置（sessiontimes.go）。

func ts(sec int) string {
	return time.Date(2026, 11, 26, 8, 0, sec, 0, time.UTC).Format(time.RFC3339Nano)
}

func ms(sec int) int64 { return time.Date(2026, 11, 26, 8, 0, sec, 0, time.UTC).UnixMilli() }

func assistantLine(sec int, stop string, sidechain bool) string {
	side := "false"
	if sidechain {
		side = "true"
	}
	return `{"type":"assistant","isSidechain":` + side + `,"timestamp":"` + ts(sec) +
		`","message":{"stop_reason":"` + stop + `","content":[{"type":"text","text":"好"}]}}`
}

func userAt(sec int, text string) string {
	return `{"type":"user","cwd":"/w","timestamp":"` + ts(sec) + `","message":{"content":"` + text + `"}}`
}

func TestSummaryCreatedAndRepliedSkipToolsAndSidechains(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/33333333-3333-3333-3333-333333333333.jsonl"
	writeLines(t, path,
		`{"type":"queue-operation","timestamp":"`+ts(1)+`"}`,
		userAt(2, "第一句"),
		assistantLine(3, "tool_use", false),
		assistantLine(4, "end_turn", false), // 最后一次回答完
		filler(3*liteBytes),                 // 结尾那段里没有：往回找
		userAt(10, "接着做"),
		assistantLine(11, "tool_use", false),
		assistantLine(12, "end_turn", true), // 子代理的不算
		`{"type":"user","timestamp":"`+ts(13)+`","message":{"content":[{"type":"tool_result","content":"x"}]}}`,
	)
	got := summaryOf(t, path)
	if got["created"] != ms(1) || got["replied"] != ms(4) {
		t.Errorf("created %v replied %v, want %d %d", got["created"], got["replied"], ms(1), ms(4))
	}
}

func TestSummaryRepliedInTail(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/44444444-4444-4444-4444-444444444444.jsonl"
	writeLines(t, path, userAt(1, "hi"), assistantLine(2, "end_turn", false), filler(2*liteBytes),
		userAt(5, "再来"), assistantLine(6, "end_turn", false), assistantLine(7, "max_tokens", false))
	if got := summaryOf(t, path); got["replied"] != ms(7) {
		t.Errorf("replied %v, want %d", got["replied"], ms(7))
	}
}

func TestSummaryNoReplyFallsBackToCreated(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/55555555-5555-5555-5555-555555555555.jsonl"
	writeLines(t, path, userAt(3, "刚发出去"), assistantLine(4, "tool_use", false))
	got := summaryOf(t, path)
	if got["created"] != ms(3) || got["replied"] != ms(3) || got["rank"] != float64(ms(3)) {
		t.Errorf("summary %v", got)
	}
}

// 记录在一行中间跨过结尾那段的起点时，那一行整行留给往回找。
func TestSummaryReplyStraddlingTailStart(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/66666666-6666-6666-6666-666666666666.jsonl"
	reply := assistantLine(4, "end_turn", false)
	// 回答那一行的后一半落在最后 liteBytes 里
	writeLines(t, path, userAt(1, "hi"), filler(2*liteBytes), reply, filler(liteBytes-len(reply)/2-len(filler(0))-2))
	data, _ := os.ReadFile(path)
	if start := len(data) - liteBytes; string(data[start-1:start]) == "\n" {
		t.Fatal("reply line does not straddle the tail start")
	}
	if got := summaryOf(t, path); got["replied"] != ms(4) {
		t.Errorf("replied %v, want %d", got["replied"], ms(4))
	}
}

// 订阅里文件只是变长了：往回只找到上次的大小，再往前的用上次读到的。
func TestSummaryAfterStopsAtKnownSize(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/77777777-7777-7777-7777-777777777777.jsonl"
	writeLines(t, path, userAt(1, "hi"), assistantLine(2, "end_turn", false), filler(2*liteBytes))
	fi, _ := os.Stat(path)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(filler(3*liteBytes) + "\n"); err != nil {
		t.Fatal(err)
	}
	f.Close()
	// 上次读到的时刻故意和记录里的不同：用了它说明没有往前读
	s, ok, err := summarizeAfter(path, &replyMark{size: fi.Size(), at: 42})
	if err != nil || !ok || s.replied != 42 {
		t.Errorf("replied %d (%v %v), want the known 42", s.replied, ok, err)
	}
	if s, _, _ := summarizeAfter(path, &replyMark{}); s.replied != ms(2) {
		t.Errorf("full scan replied %d, want %d", s.replied, ms(2))
	}
}

func TestOrderSessionRanksAndPrunes(t *testing.T) {
	e := testEnv(t)
	a := e.root + "/-p/88888888-8888-8888-8888-888888888888.jsonl"
	b := e.root + "/-p/99999999-9999-9999-9999-999999999999.jsonl"
	writeLines(t, a, userAt(1, "a"))
	writeLines(t, b, userAt(2, "b"))
	if err := orderSession(e, "88888888-8888-8888-8888-888888888888", 2.5); err != nil {
		t.Fatal(err)
	}
	if err := orderSession(e, "99999999-9999-9999-9999-999999999999", -7); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(b); err != nil {
		t.Fatal(err)
	}
	// 再记一次时顺手清掉记录已经不在的
	if err := orderSession(e, "88888888-8888-8888-8888-888888888888", 1e15+0.5); err != nil {
		t.Fatal(err)
	}
	order := e.sessionOrder()
	if len(order) != 1 || order["88888888-8888-8888-8888-888888888888"] != 1e15+0.5 {
		t.Errorf("order %v", order)
	}
	items := lookupSessions(e, []string{"88888888-8888-8888-8888-888888888888"})
	if len(items) != 1 || items[0]["rank"] != 1e15+0.5 {
		t.Errorf("lookup %v", items)
	}
}
