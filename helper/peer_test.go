package main

import (
	"strings"
	"testing"
)

// peerLine 是会话记录里另一个会话发来的消息（会话互通）：Claude Code 记成 queued_command 附件，origin 里有发信会话和正文
// （2.1.x 实测的字段；较早的版本 origin 里没有 body，正文只在 prompt 的外壳里）。
func peerLine(name, body string, withBody bool) string {
	origin := `{"kind":"peer","from":"uds:/run/user/1000/cc-socks/1.sock","name":"` + name + `","fromMode":"prompting"`
	if withBody {
		origin += `,"body":"` + body + `"`
	}
	origin += "}"
	prompt := `<cross-session-message from=\"uds:/run/user/1000/cc-socks/1.sock\" from-name=\"` + name + `\" from-mode=\"prompting\">\n` + body + `\n</cross-session-message>`
	return `{"type":"attachment","attachment":{"type":"queued_command","commandMode":"prompt","isMeta":true,"origin":` + origin +
		`,"prompt":"` + prompt + `"},"uuid":"p-` + name + `"}`
}

// 另一个会话发来的消息：历史里是一条带 peer（发信会话）的用户消息，正文不带外壳；origin 没有 body 时从外壳里剥
func TestHistoryPeerMessages(t *testing.T) {
	e := testEnv(t)
	file := e.root + "/-p/66666666-6666-4666-8666-666666666666.jsonl"
	writeLines(t, file, peerLine("home-repo-e8", "README 已改正", true), peerLine("old-1", "旧版本的正文", false), userLine("u1", "a"))
	out := mustCall(t, e, "history", file)
	type msg struct{ peer, text string }
	var got []msg
	for _, o := range out[1:] {
		got = append(got, msg{asString(o["peer"]), asString(asObj(asList(asObj(o["message"])["content"])[0])["text"])})
	}
	want := []msg{{"home-repo-e8", "README 已改正"}, {"old-1", "旧版本的正文"}, {"", "a"}}
	if len(got) != len(want) {
		t.Fatalf("got %q", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("第 %d 条 %q，要 %q", i, got[i], want[i])
		}
	}
}

// 搜索按正文搜，正文前加「来自会话 名字：」（片段围着命中处截，开头可能截掉），没有外壳；导出写成「来自会话」一节
func TestPeerMessageSearchAndExport(t *testing.T) {
	e := testEnv(t)
	id := "77777777-7777-4777-8777-777777777777"
	file := e.root + "/-p/" + id + ".jsonl"
	writeLines(t, file, userLine("u1", "开头"), peerLine("home-repo-e8", "场馆图 needle 已补进仓", true))
	out := mustCall(t, e, "search", "needle")
	var snippet string
	for _, m := range out[:len(out)-1] {
		if m["id"] == id {
			snippet = asString(m["snippet"])
		}
	}
	if !strings.Contains(snippet, "会话 home-repo-e8：") || !strings.Contains(snippet, "needle") || strings.Contains(snippet, "cross-session") {
		t.Errorf("片段 %q", snippet)
	}
	markdown, _, err := exportSession(file, "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(markdown, "## 来自会话 home-repo-e8\n\n场馆图 needle 已补进仓") || strings.Contains(markdown, "cross-session") {
		t.Errorf("导出：%s", markdown)
	}
}
