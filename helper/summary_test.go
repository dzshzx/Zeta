package main

import (
	"os"
	"slices"
	"strings"
	"testing"
)

// 概要只读记录的头尾（summarize）。

// filler 是一行约 n 字节、概要用不上的记录（助手的回答）。
func filler(n int) string {
	return `{"type":"assistant","message":{"content":[{"type":"text","text":"` + strings.Repeat("x", n) + `"}]}}`
}

func summaryOf(t *testing.T, path string) obj {
	t.Helper()
	s, ok, err := summarize(path)
	if err != nil || !ok {
		t.Fatalf("summarize %s: %v %v", path, ok, err)
	}
	return s.item(sessionMarks{})
}

func TestSummaryReadsOnlyHeadAndTail(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/11111111-1111-1111-1111-111111111111.jsonl"
	writeLines(t, path,
		`{"type":"user","cwd":"/work","message":{"content":"/model opus"}}`,
		`{"type":"user","message":{"content":"真正的第一句"}}`,
		filler(3*liteBytes),
		// 中间的不读：Claude Code 会把最新的标题补写到末尾
		`{"type":"custom-title","customTitle":"中间的名字"}`,
		filler(liteBytes),
		`{"type":"ai-title","aiTitle":"末尾的标题"}`,
		filler(100),
	)
	got := summaryOf(t, path)
	if got["preview"] != "真正的第一句" || got["title"] != "末尾的标题" || got["cwd"] != "/work" {
		t.Errorf("summary %v", got)
	}
}

func TestSummaryTailStartingAtALineKeepsThatLine(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/22222222-2222-2222-2222-222222222222.jsonl"
	title := `{"type":"ai-title","aiTitle":"正好在边界上"}` + "\n"
	// 标题行加上后面这一行（连两个换行）正好是最后 liteBytes 个字节
	rest := filler(liteBytes - len(title) - 1 - len(filler(0)))
	writeFile(t, path, `{"type":"user","message":{"content":"hi"}}`+"\n"+filler(2*liteBytes)+"\n"+title+rest+"\n")
	data, _ := os.ReadFile(path)
	if start := len(data) - liteBytes; string(data[start:start+len(title)]) != title {
		t.Fatalf("title line does not start the last %d bytes", liteBytes)
	}
	if got := summaryOf(t, path); got["title"] != "正好在边界上" {
		t.Errorf("title line at the tail start dropped: %v", got)
	}
}

func TestSummaryHeadReadsOnUntilAUserMessage(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/33333333-3333-3333-3333-333333333333.jsonl"
	writeLines(t, path,
		filler(2*liteBytes), // 用户的话之前就已超过 liteBytes
		`{"type":"user","message":{"content":"`+strings.Repeat("长", liteBytes)+`"}}`,
		filler(3*liteBytes),
	)
	if got := summaryOf(t, path); got["preview"] != strings.Repeat("长", 200) {
		t.Errorf("preview %v", got["preview"])
	}
}

func TestSummarySlashCommandFirstStopsLooking(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/44444444-4444-4444-4444-444444444444.jsonl"
	writeLines(t, path,
		`{"type":"user","message":{"content":"/goal 重写它"}}`,
		filler(2*liteBytes),
		`{"type":"user","message":{"content":"中间的话"}}`,
		filler(2*liteBytes),
		// 结尾那段里的话也不是第一句
		`{"type":"user","message":{"content":"继续"}}`,
		filler(100),
	)
	if got := summaryOf(t, path); got["preview"] != "/goal 重写它" {
		t.Errorf("preview %v", got["preview"])
	}
}

// 结尾那段里有剪到第一条消息之前的记号：之后的第一句才是预览。
func TestSummaryRewindInTheTail(t *testing.T) {
	e := testEnv(t)
	for _, first := range []string{"原来的第一句", "/goal 原来的"} {
		path := e.root + "/-p/66666666-6666-6666-6666-666666666666.jsonl"
		writeLines(t, path,
			`{"type":"user","message":{"content":"`+first+`"}}`,
			filler(3*liteBytes),
			`{"type":"last-prompt","cutUuid":"u1","leafUuid":null,"explicit":true,"rewound":true}`,
			`{"type":"user","message":{"content":"/model opus"}}`,
			`{"type":"user","message":{"content":"重来的第一句"}}`,
			filler(100),
		)
		if got := summaryOf(t, path); got["preview"] != "重来的第一句" {
			t.Errorf("first %q: preview %v", first, got["preview"])
		}
	}
}

func TestSummaryCountsAnUnfinishedLastLine(t *testing.T) {
	e := testEnv(t)
	path := e.root + "/-p/55555555-5555-5555-5555-555555555555.jsonl"
	writeFile(t, path, `{"type":"user","message":{"content":"hi"}}`+"\n"+filler(3*liteBytes)+"\n"+
		`{"type":"custom-title","customTitle":"没换行"}`)
	if got := summaryOf(t, path); got["title"] != "没换行" {
		t.Errorf("title %v", got["title"])
	}
}

func TestSessionTitlesOfOneCwd(t *testing.T) {
	e := testEnv(t)
	record := func(id, cwd, title string) {
		lines := []string{`{"type":"user","cwd":"` + cwd + `","message":{"content":"问"}}`}
		if title != "" {
			lines = append(lines, `{"type":"custom-title","customTitle":"`+title+`"}`)
		}
		writeLines(t, e.root+"/-p/"+id+".jsonl", lines...)
	}
	record("11111111-1111-1111-1111-111111111111", "/work", "分支 · 问题")
	record("22222222-2222-2222-2222-222222222222", "/work", "问题")
	record("33333333-3333-3333-3333-333333333333", "/work", "")
	record("44444444-4444-4444-4444-444444444444", "/other", "分支 2 · 问题")
	got := sessionTitles(e, "/work")
	slices.Sort(got)
	if !slices.Equal(got, []string{"分支 · 问题", "问题"}) {
		t.Fatalf("标题：%v", got)
	}
}
