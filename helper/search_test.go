package main

import (
	"fmt"
	"strings"
	"testing"
)

func TestSnippetCountsCharactersAndFoldsWhitespace(t *testing.T) {
	text := strings.Repeat("前", 30) + "命中\n\n词" + strings.Repeat("后", 100)
	got := snippet(text, 30, 2)
	if !strings.HasPrefix(got, "…"+strings.Repeat("前", 20)+"命中 词") || !strings.HasSuffix(got, "…") {
		t.Errorf("snippet = %q", got)
	}
	// 取第 10 到第 102 个字：前 20 字、命中 2 字、两个换行折成一个空格、词、后 67 字，两头各一个省略号
	if runeLen(got) != 1+20+2+1+1+67+1 {
		t.Errorf("snippet length %d: %q", runeLen(got), got)
	}
	if got := snippet("short hit", 6, 3); got != "short hit" {
		t.Errorf("no ellipsis: %q", got)
	}
}

func TestSearchFindsSpokenTextOnly(t *testing.T) {
	e := testEnv(t)
	session := func(n int, lines ...string) string {
		id := fmt.Sprintf("%08d-0000-0000-0000-000000000000", n)
		writeLines(t, e.root+"/-p/"+id+".jsonl", lines...)
		return id
	}
	inTool := session(1, `{"type":"user","message":{"content":[{"type":"tool_result","content":"needle \"q\""}]}}`)
	inPath := session(2, `{"type":"user","message":{"content":"x"},"cwd":"/needle \"q\""}`)
	quoted := session(3, `{"type":"user","message":{"content":"[Attached file: /very/long/path/needle \"Q\".txt]\nSay NEEDLE \"q\" please"}}`)
	queued := session(4, `{"type":"attachment","attachment":{"type":"queued_command","prompt":"插话 needle \"q\""}}`)
	answer := session(5, `{"type":"assistant","message":{"content":[{"type":"text","text":"Needle \"Q\" here"}]}}`)
	out := mustCall(t, e, "search", `  NEEDLE "q"  `)
	found := map[string]obj{}
	for _, m := range out[:len(out)-1] {
		found[asString(m["id"])] = m
	}
	if _, ok := found[inTool]; ok {
		t.Error("matched a tool result")
	}
	if _, ok := found[inPath]; ok {
		t.Error("matched a path field")
	}
	if got := asString(found[quoted]["snippet"]); got != `附件 needle "Q".txt Say NEEDLE "q" please` {
		t.Errorf("attachment snippet %q", got)
	}
	if found[queued]["role"] != "user" || found[answer]["role"] != "assistant" {
		t.Errorf("roles: %v %v", found[queued], found[answer])
	}
	done := out[len(out)-1]
	if done["type"] != "zeta_search_done" || num(t, done["matched"]) != 3 || num(t, done["scanned"]) != 5 || done["complete"] != true {
		t.Errorf("done %v", done)
	}
	if _, err := call(t, e, "search", " \t "); err == nil {
		t.Error("empty query accepted")
	}
}
