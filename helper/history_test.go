package main

import (
	"fmt"
	"strings"
	"testing"
)

func userLine(uuid, text string) string {
	return fmt.Sprintf(`{"type":"user","message":{"content":%q},"uuid":%q}`, text, uuid)
}

func answerLine(uuid, text string) string {
	return fmt.Sprintf(`{"type":"assistant","message":{"id":"m","model":"claude-x","content":[{"type":"text","text":%q}]},"uuid":%q}`, text, uuid)
}

func cutLine(cut, leaf string) string {
	l := "null"
	if leaf != "" {
		l = fmt.Sprintf("%q", leaf)
	}
	return fmt.Sprintf(`{"type":"last-prompt","cutUuid":%q,"leafUuid":%s,"explicit":true,"rewound":true}`, cut, l)
}

func historyTexts(t *testing.T, e *env, file string, args ...string) ([]string, obj) {
	t.Helper()
	out := mustCall(t, e, append([]string{"history", file}, args...)...)
	var texts []string
	for _, ev := range out[1:] {
		texts = append(texts, textOf(ev))
	}
	return texts, out[0]
}

func TestHistoryAppliesRewindCuts(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/11111111-1111-1111-1111-111111111111.jsonl"
	cases := []struct {
		name  string
		lines []string
		want  string
	}{
		{"cut found", []string{userLine("u1", "a"), answerLine("a1", "A"), userLine("u2", "b"), answerLine("a2", "B"), cutLine("u2", "a1"), userLine("u3", "c")}, "a|A|c"},
		{"cut missing, keep to leaf", []string{userLine("u1", "a"), answerLine("a1", "A"), userLine("u2", "b"), cutLine("nope", "a1")}, "a|A"},
		{"cut everything", []string{userLine("u1", "a"), answerLine("a1", "A"), cutLine("nope", ""), userLine("u2", "b")}, "b"},
		{"neither found", []string{userLine("u1", "a"), cutLine("nope", "nope")}, "a"},
		{"not explicit", []string{userLine("u1", "a"), `{"type":"last-prompt","cutUuid":"u1","leafUuid":null,"explicit":false,"rewound":true}`}, "a"},
	}
	for _, c := range cases {
		writeLines(t, file, c.lines...)
		texts, _ := historyTexts(t, e, file)
		if got := strings.Join(texts, "|"); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

// longSession 造一个很长的会话：每轮一句用户的话、几次工具调用、一句回答；开头几轮有清单工具。
func longSession(t *testing.T, file string, turns int) int {
	t.Helper()
	var lines []string
	events := 0
	for turn := 0; turn < turns; turn++ {
		lines = append(lines, userLine(fmt.Sprintf("u%d", turn), fmt.Sprintf("turn %d", turn)))
		for i := 0; i < turn%9; i++ {
			id := fmt.Sprintf("c%d-%d", turn, i)
			name := "Bash"
			if turn < 3 && i == 0 {
				name = "TodoWrite"
			}
			lines = append(lines,
				fmt.Sprintf(`{"type":"assistant","message":{"id":"m","model":"claude-x","content":[{"type":"tool_use","id":%q,"name":%q,"input":{}}]},"uuid":"a%s"}`, id, name, id),
				fmt.Sprintf(`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":%q,"content":"ok"}]},"uuid":"r%s"}`, id, id))
			events += 2
		}
		lines = append(lines, answerLine(fmt.Sprintf("a%d", turn), fmt.Sprintf("done %d", turn)))
		events += 2
	}
	writeLines(t, file, lines...)
	return events
}

func TestHistoryPagesBackToTheStart(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/22222222-2222-2222-2222-222222222222.jsonl"
	total := longSession(t, file, 900)
	out := mustCall(t, e, "history", file)
	var pages [][]obj
	pages = append(pages, out[1:])
	header := out[0]
	if len(out[1:]) > maxEvents+maxEvents/2 || num(t, header["skipped"]) == 0 {
		t.Fatalf("first page has %d events, skipped %v", len(out)-1, header["skipped"])
	}
	for num(t, header["skipped"]) > 0 {
		if len(asList(header["todo_seed"])) == 0 {
			t.Error("todo_seed is empty while the early TodoWrite calls are not loaded")
		}
		out = mustCall(t, e, "history", file, fmt.Sprint(num(t, header["skipped"])), asString(header["anchor"]))
		header = out[0]
		pages = append([][]obj{out[1:]}, pages...)
	}
	var all []obj
	for _, p := range pages {
		all = append(all, p...)
	}
	if len(all) != total {
		t.Fatalf("pages hold %d events, want %d", len(all), total)
	}
	// 每页都从一轮的开头起（用户的话），不把工具调用和它的结果分到两页
	for _, p := range pages[1:] {
		if p[0]["type"] != "user" || hasToolResult(asObj(p[0]["message"])["content"]) {
			t.Errorf("a page starts with %v", p[0])
		}
	}
	whole := mustCall(t, e, "history", file, fmt.Sprint(len(all)), "-", "all")
	if len(whole)-1 != total || num(t, whole[0]["skipped"]) != 0 {
		t.Errorf("all: %d events, skipped %v", len(whole)-1, whole[0]["skipped"])
	}
}

func TestHistoryRejectsStalePositions(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/33333333-3333-3333-3333-333333333333.jsonl"
	writeLines(t, file, userLine("u1", "a"), answerLine("a1", "A"), userLine("u2", "b"))
	for _, args := range [][]string{{"2", "u1"}, {"0", "-"}, {"4", "-"}, {"99999999999999999999", "-"}} {
		out := mustCall(t, e, append([]string{"history", file}, args...)...)
		if len(out) != 1 || out[0]["stale"] != true {
			t.Errorf("%v: %v", args, out)
		}
	}
	texts, header := historyTexts(t, e, file, "2", "a1")
	if strings.Join(texts, "|") != "a|A" || num(t, header["skipped"]) != 0 {
		t.Errorf("valid position: %v %v", texts, header)
	}
}

func TestPageStartPrefersNearestTurnThenSafeCut(t *testing.T) {
	user := obj{"type": "user", "message": obj{"content": []any{obj{"type": "text", "text": "x"}}}}
	call := func(id string) obj {
		return obj{"type": "assistant", "message": obj{"content": []any{obj{"type": "tool_use", "id": id}}}}
	}
	result := func(id string) obj {
		return obj{"type": "user", "message": obj{"content": []any{obj{"type": "tool_result", "tool_use_id": id}}}}
	}
	var events []obj
	for i := 0; i < 40; i++ {
		events = append(events, call(fmt.Sprint(i)), result(fmt.Sprint(i)))
	}
	events[30] = user // 第 30 条是一轮的开头
	b := pageStarts(events)
	if got := pageStart(b, 80, 50); got != 30 {
		t.Errorf("nearest turn start: %d", got)
	}
	events[30] = call("30") // 没有一轮的开头：取最近的不拆开调用和结果的地方
	if got := pageStart(pageStarts(events), 80, 50); got != 30 {
		t.Errorf("nearest safe cut: %d", got)
	}
	if got := pageStart(b, 60, 50); got != 0 {
		t.Errorf("less than half a page left: %d", got)
	}
	// 一个调用的结果隔得很远：中间都不能切，只好从目标位置切
	far := []obj{call("far")}
	for i := 0; i < 90; i++ {
		far = append(far, obj{"type": "assistant", "message": obj{"content": []any{}}})
	}
	far = append(far, result("far"))
	if got := pageStart(pageStarts(far), 91, 40); got != 51 {
		t.Errorf("no good cut: %d", got)
	}
}

func TestHistoryHeaderModelAndCompactDedup(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/44444444-4444-4444-4444-444444444444.jsonl"
	writeLines(t, file,
		userLine("u1", "a"),
		`{"type":"assistant","message":{"id":"m","model":"claude-real","content":[{"type":"text","text":"x","signature":"s"}],"usage":{"input_tokens":1e6,"other":1}},"uuid":"a1","effort":"high"}`,
		`{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"manual","preTokens":12345678901234567890},"uuid":"c1"}`,
		`{"type":"user","message":{"content":"<command-name>/compact</command-name>"},"uuid":"cmd"}`,
		`{"type":"assistant","message":{"id":"s","model":"<synthetic>","content":[{"type":"text","text":"err"}]},"uuid":"syn"}`)
	out := mustCall(t, e, "history", file)
	if out[0]["model"] != "claude-real" || out[0]["effort"] != "high" {
		t.Errorf("header %v", out[0])
	}
	if len(out) != 5 || out[3]["type"] != "system" || out[4]["type"] != "assistant" {
		t.Fatalf("events %v", out[1:])
	}
	if pre := asObj(out[3]["compact_metadata"])["pre_tokens"]; fmt.Sprint(pre) != "12345678901234567890" {
		t.Errorf("pre_tokens %v", pre)
	}
	usage := asObj(asObj(out[2]["message"])["usage"])
	if fmt.Sprint(usage["input_tokens"]) != "1e6" || usage["other"] != nil {
		t.Errorf("usage %v", usage)
	}
	if _, ok := asObj(asList(asObj(out[2]["message"])["content"])[0])["signature"]; ok {
		t.Error("signature kept")
	}
}

// ! 命令的两行原样交给 Zeta（它画成命令步骤），不当成用户说的话
func TestHistoryKeepsShellLinesRaw(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/55555555-5555-4555-8555-555555555555.jsonl"
	writeLines(t, file,
		`{"type":"user","message":{"role":"user","content":"<bash-input>pwd</bash-input>"},"uuid":"b1"}`,
		`{"type":"user","message":{"role":"user","content":"<bash-stdout>/w</bash-stdout><bash-stderr></bash-stderr>"},"uuid":"b2"}`,
		userLine("u1", "a"))
	out := mustCall(t, e, "history", file)
	var texts []string
	for _, o := range out[1:] {
		texts = append(texts, asString(asObj(asList(asObj(o["message"])["content"])[0])["text"]))
	}
	want := []string{"<bash-input>pwd</bash-input>", "<bash-stdout>/w</bash-stdout><bash-stderr></bash-stderr>", "a"}
	if fmt.Sprint(texts) != fmt.Sprint(want) {
		t.Errorf("texts %q, want %q", texts, want)
	}
}

func TestHistoryHeaderModelSwitchedInTerminal(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/55555555-5555-5555-5555-555555555555.jsonl"
	slashModel := func(uuid, name, rest string) string {
		return fmt.Sprintf(`{"type":"user","message":{"content":"<local-command-stdout>Set model to `+"`%s`"+`%s</local-command-stdout>"},"uuid":%q}`, name, rest, uuid)
	}
	// 一个回答都没有的会话：只有终端里换的模型
	writeLines(t, file, slashModel("s1", "Fable 5.2", " for this session only"))
	out := mustCall(t, e, "history", file)
	if out[0]["switched_model"] != "Fable 5.2" || out[0]["model"] != nil || len(out) != 1 {
		t.Errorf("bare %v", out)
	}
	// 回答之后换的算数，取最后一次；换了之后又有回答就以回答的为准
	writeLines(t, file,
		userLine("u1", "a"), answerLine("a1", "x"),
		slashModel("s1", "Opus 4.8", " and saved as your default for new sessions"),
		slashModel("s2", "Fable 5.2", " for this session only with `high` effort"))
	out = mustCall(t, e, "history", file)
	if out[0]["switched_model"] != "Fable 5.2" || out[0]["model"] != "claude-x" {
		t.Errorf("after answer %v", out[0])
	}
	writeLines(t, file,
		slashModel("s1", "Fable 5.2", " for this session only"),
		userLine("u1", "a"), answerLine("a1", "x"))
	out = mustCall(t, e, "history", file)
	if _, ok := out[0]["switched_model"]; ok {
		t.Errorf("answered since %v", out[0])
	}
	// 换模型的那段被剪掉了（改写到它之前）：不算
	writeLines(t, file,
		userLine("u1", "a"), answerLine("a1", "x"),
		userLine("u2", "b"),
		slashModel("s1", "Fable 5.2", " for this session only"),
		`{"type":"last-prompt","explicit":true,"rewound":true,"cutUuid":"u2","leafUuid":"a1"}`)
	out = mustCall(t, e, "history", file)
	if _, ok := out[0]["switched_model"]; ok || len(out) != 3 {
		t.Errorf("cut %v", out)
	}
}
