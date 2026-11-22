package conv

import (
	"encoding/json"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// twoTurns 开一个对话，走完两轮（「一」→「回答一」，「二」→「回答二」），会话号 s1、标题「问题」。
func twoTurns(t *testing.T) (*harness, *Conversation, *fakeLink) {
	t.Helper()
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "title": "问题"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m1", "回答一")...)
	h.feed(c, l, resultOK)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m2", "回答二")...)
	h.feed(c, l, resultOK)
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", "user:二", "answer:回答二"}) {
		t.Fatalf("两轮的条目：%v", got)
	}
	return h, c, l
}

// item 是客户端 client 手里种类为 kind、文字为 text 的那一项。
func (h *harness) item(client int, kind, text string) map[string]any {
	h.t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, it := range h.client(client).items {
		m := it.(map[string]any)
		if m["kind"] == kind && m["text"] == text {
			return m
		}
	}
	h.t.Fatalf("客户端 %d 手里没有 %s:%s", client, kind, text)
	return nil
}

// lastRequest 是写进 l 的最后一个 subtype 控制请求的 request 一项和请求号。
func lastRequest(t *testing.T, l *fakeLink, subtype string) (map[string]any, string) {
	t.Helper()
	reqs := l.lines("control_request/" + subtype)
	if len(reqs) == 0 {
		t.Fatalf("没有发 %s", subtype)
	}
	last := reqs[len(reqs)-1]
	return last["request"].(map[string]any), last["request_id"].(string)
}

// respond 扮 Claude Code 应答请求号 id：response 是应答里 response 一项的 JSON；errText 不为空时答 error。
func (h *harness) respond(c *Conversation, l *fakeLink, id, response, errText string) {
	h.t.Helper()
	body := map[string]any{"subtype": "success", "request_id": id}
	if response != "" {
		body["response"] = json.RawMessage(response)
	}
	if errText != "" {
		body["subtype"], body["error"] = "error", errText
	}
	b, _ := json.Marshal(map[string]any{"type": "control_response", "response": body})
	h.feed(c, l, string(b))
}

// sentTexts 是写进 l 的各条用户消息的文字。
func sentTexts(l *fakeLink) []string {
	var out []string
	for _, m := range l.lines("user") {
		b, _ := json.Marshal(m["message"])
		var msg struct {
			Content []struct {
				Text string `json:"text"`
			} `json:"content"`
		}
		_ = json.Unmarshal(b, &msg)
		for _, block := range msg.Content {
			out = append(out, block.Text)
		}
	}
	return out
}

func TestRewriteWaitsForCut(t *testing.T) {
	h, c, l := twoTurns(t)
	first, second := h.item(1, "user", "一"), h.item(1, "user", "二")
	h.call(1, "conv.rewrite", map[string]any{"conv": "a", "target": first["id"], "text": " 新的一 "})
	h.settle(c)
	req, id := lastRequest(t, l, "rewind_conversation")
	if req["target_message_uuid"] != first["uuid"] || req["last_seen_user_message_uuid"] != second["uuid"] {
		t.Fatalf("剪会话的请求：%v", req)
	}
	// 答应之前：列表已经是改过的样子，消息还没交出去，不收新消息
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:新的一"}) {
		t.Fatalf("剪的期间的条目：%v", got)
	}
	if got := sentTexts(l); len(got) != 2 {
		t.Fatalf("剪完之前就发了：%v", got)
	}
	if h.state(1, "can_rewind") != false {
		t.Fatal("剪的期间 can_rewind 还是真")
	}
	if _, err := h.m.Request(1, "conv.send", []byte(`{"conv":"a","text":"插一句"}`)); err != errRewinding {
		t.Fatalf("剪的期间发消息：%v", err)
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if got := sentTexts(l); !reflect.DeepEqual(got, []string{"一", "二", "新的一"}) {
		t.Fatalf("发出去的：%v", got)
	}
	if h.state(1, "busy") != true {
		t.Fatal("交出去以后不在回答")
	}
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m3", "新回答")...)
	h.feed(c, l, resultOK)
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:新的一", "answer:新回答"}) || h.state(1, "can_rewind") != true {
		t.Fatalf("条目 %v，can_rewind %v", got, h.state(1, "can_rewind"))
	}
}

func TestRewriteRefused(t *testing.T) {
	cases := []struct {
		name, response, errText, notice string
	}{
		{"另有新内容", `{"rewound":false,"reason":"stale_target"}`, "", "这个会话在别处有了新内容，重新打开它再试"},
		{"还在回答", `{"rewound":false,"reason":"commands_queued"}`, "", "Claude 还在处理消息，等这一轮结束再试"},
		{"压缩之前", `{"rewound":false,"reason":"target_not_found"}`, "", "会话记录里找不到这条消息（上下文压缩之前的消息改不了）"},
		{"工具中间", `{"rewound":false,"reason":"poll_tool_result_target"}`, "", "这条消息在工具调用中间，不能从这里改"},
		{"写不进", `{"rewound":false,"reason":"persist_failed"}`, "", "写不进会话记录"},
		{"不认识的原因", `{"rewound":false,"reason":"weird"}`, "", "Claude Code 没有剪：weird"},
		{"带说明", `{"rewound":false,"reason":"weird","error":"说明"}`, "", "Claude Code 没有剪：说明"},
		{"拒绝", "", "boom", "Claude Code 拒绝了这次操作：boom"},
		{"没说剪没剪", "", "", "Claude Code 没有说剪没剪"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h, c, l := twoTurns(t)
			second := h.item(1, "user", "二")
			h.call(1, "conv.rewrite", map[string]any{"conv": "a", "target": second["id"], "text": "改过的二"})
			_, id := lastRequest(t, l, "rewind_conversation")
			h.respond(c, l, id, tc.response, tc.errText)
			want := []string{"user:一", "answer:回答一", "user:二", "answer:回答二", "notice:没有发出：" + tc.notice}
			if got := h.texts(1); !reflect.DeepEqual(got, want) {
				t.Fatalf("条目：%v", got)
			}
			recalled, _ := h.state(1, "recalled").(map[string]any)
			if recalled["text"] != "改过的二" || h.state(1, "busy") != false || h.state(1, "can_rewind") != true {
				t.Fatalf("放回的 %v，busy %v，can_rewind %v", recalled, h.state(1, "busy"), h.state(1, "can_rewind"))
			}
			if got := sentTexts(l); len(got) != 2 {
				t.Fatalf("没剪成也发了：%v", got)
			}
			if last := h.client(1).turns[len(h.client(1).turns)-1]; last["outcome"] != "failed" {
				t.Fatalf("这一轮的结局：%v", last)
			}
		})
	}
}

func TestRewindAbortedWhenProcessGoes(t *testing.T) {
	for _, tc := range []struct{ name, notice string }{
		{"进程退出", "没有发出：Claude Code 进程退出了"},
		{"结束对话", "没有发出：对话已关闭"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h, c, l := twoTurns(t)
			h.call(1, "conv.rewrite", map[string]any{"conv": "a", "target": h.item(1, "user", "二")["id"], "text": "改过的二"})
			_, id := lastRequest(t, l, "rewind_conversation")
			if tc.name == "进程退出" {
				l.events.Exit(Exit{Code: 0})
			} else {
				h.call(1, "conv.end", map[string]any{"conv": "a"})
			}
			h.settle(c)
			if got := h.texts(1); got[len(got)-1] != "notice:"+tc.notice || len(got) != 5 {
				t.Fatalf("条目：%v", got)
			}
			recalled, _ := h.state(1, "recalled").(map[string]any)
			if recalled["text"] != "改过的二" || h.state(1, "can_rewind") != true {
				t.Fatalf("放回的 %v，can_rewind %v", recalled, h.state(1, "can_rewind"))
			}
			// 迟到的应答不再管用
			h.respond(c, l, id, `{"rewound":true}`, "")
			if got := sentTexts(l); len(got) != 2 {
				t.Fatalf("收掉以后又发了：%v", got)
			}
		})
	}
}

func TestDrop(t *testing.T) {
	h, c, l := twoTurns(t)
	second := h.item(1, "user", "二")
	h.call(1, "conv.drop", map[string]any{"conv": "a", "target": second["id"]})
	h.settle(c)
	req, id := lastRequest(t, l, "rewind_conversation")
	if req["target_message_uuid"] != second["uuid"] || req["last_seen_user_message_uuid"] != second["uuid"] {
		t.Fatalf("剪会话的请求：%v", req)
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一"}) {
		t.Fatalf("剪的期间的条目：%v", got)
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一"}) || h.state(1, "busy") != false {
		t.Fatalf("删完的条目 %v，busy %v", got, h.state(1, "busy"))
	}
	if got := sentTexts(l); len(got) != 2 {
		t.Fatalf("只剪不发，却发了：%v", got)
	}

	// 没剪成：恢复原样，说明原因
	first := h.item(1, "user", "一")
	h.call(1, "conv.drop", map[string]any{"conv": "a", "target": first["id"]})
	_, id = lastRequest(t, l, "rewind_conversation")
	h.respond(c, l, id, `{"rewound":false,"reason":"persist_failed"}`, "")
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", "notice:没有删除：写不进会话记录"}) {
		t.Fatalf("没删成的条目：%v", got)
	}
}

func TestRewindRefusedWhileBusy(t *testing.T) {
	h, c, l := twoTurns(t)
	first := h.item(1, "user", "一")
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	h.settle(c)
	if h.state(1, "can_rewind") != false {
		t.Fatal("回答进行中 can_rewind 还是真")
	}
	for _, method := range []string{"conv.rewrite", "conv.drop", "conv.restore_code"} {
		b, _ := json.Marshal(map[string]any{"conv": "a", "target": first["id"], "text": "x"})
		if _, err := h.m.Request(1, method, b); err != errNotNow {
			t.Fatalf("%s 在回答进行中：%v", method, err)
		}
	}
	if n := len(l.lines("control_request/rewind_conversation")); n != 0 {
		t.Fatalf("发了 %d 个剪会话的请求", n)
	}
	// 不认得的条目
	h.feed(c, l, resultOK)
	b, _ := json.Marshal(map[string]any{"conv": "a", "target": h.item(1, "answer", "回答一")["id"]})
	if _, err := h.m.Request(1, "conv.drop", b); err != errNoTarget {
		t.Fatalf("对着回答删：%v", err)
	}
}

// codeChangesAsync 在后台发 conv.code_changes（它要等应答才回），返回拿结果的通道。
func (h *harness) codeChangesAsync(target any) <-chan any {
	out := make(chan any, 1)
	b, _ := json.Marshal(map[string]any{"conv": "a", "target": target})
	go func() {
		v, err := h.m.Request(1, "conv.code_changes", b)
		if err != nil {
			v = err
		}
		out <- v
	}()
	return out
}

// waitRequest 等 l 里出现第 n 个 subtype 控制请求，返回它的 request 一项和请求号。
func waitRequest(t *testing.T, l *fakeLink, subtype string, n int) (map[string]any, string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for len(l.lines("control_request/"+subtype)) < n {
		if time.Now().After(deadline) {
			t.Fatalf("没等到第 %d 个 %s", n, subtype)
		}
		time.Sleep(5 * time.Millisecond)
	}
	return lastRequest(t, l, subtype)
}

func TestCodeChanges(t *testing.T) {
	h, c, l := twoTurns(t)
	first := h.item(1, "user", "一")
	got := h.codeChangesAsync(first["id"])
	req, id := waitRequest(t, l, "rewind_files", 1)
	if req["user_message_id"] != first["uuid"] || req["dry_run"] != true {
		t.Fatalf("dry_run 的请求：%v", req)
	}
	h.respond(c, l, id, `{"canRewind":true,"filesChanged":["/w/a.go","/w/b.go"],"insertions":3,"deletions":1}`, "")
	want := &CodeChanges{Files: []string{"/w/a.go", "/w/b.go"}, Insertions: 3, Deletions: 1}
	if v := <-got; !reflect.DeepEqual(v, want) {
		t.Fatalf("会动到的：%#v", v)
	}

	// 没有可还原的：null
	for i, response := range []string{`{"canRewind":false,"error":"no checkpoints"}`, `{"canRewind":true,"filesChanged":[]}`} {
		got = h.codeChangesAsync(first["id"])
		_, id = waitRequest(t, l, "rewind_files", i+2)
		h.respond(c, l, id, response, "")
		v := <-got
		if b, _ := json.Marshal(v); v != (*CodeChanges)(nil) || string(b) != "null" {
			t.Fatalf("应答 %s 的结果：%#v，编成 %s", response, v, b)
		}
	}
	// 现在不能还原：不问 Claude Code，直接 null
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	if v := <-h.codeChangesAsync(first["id"]); v != (*CodeChanges)(nil) || len(l.lines("control_request/rewind_files")) != 3 {
		t.Fatalf("回答进行中：%#v", v)
	}
}

func TestRestoreCode(t *testing.T) {
	h, c, l := twoTurns(t)
	second := h.item(1, "user", "二")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"]})
	h.settle(c)
	req, id := lastRequest(t, l, "rewind_files")
	if req["user_message_id"] != second["uuid"] || req["dry_run"] != nil || h.state(1, "can_rewind") != false {
		t.Fatalf("还原的请求 %v，can_rewind %v", req, h.state(1, "can_rewind"))
	}
	h.respond(c, l, id, `{"canRewind":true}`, "")
	if got := h.texts(1); got[len(got)-1] != "notice:代码已还原到这条消息之前" || len(got) != 5 {
		t.Fatalf("还原成的条目：%v", got)
	}

	// 没还原成
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"]})
	_, id = lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":false,"error":"No file checkpoint found for this message."}`, "")
	if got := h.texts(1); got[len(got)-1] != "notice:没有还原代码：No file checkpoint found for this message." {
		t.Fatalf("没还原成的条目：%v", got)
	}
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"]})
	_, id = lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, "", "denied")
	if got := h.texts(1); got[len(got)-1] != "notice:Claude Code 拒绝了：denied" {
		t.Fatalf("被拒的条目：%v", got)
	}
	if n := len(l.lines("control_request/rewind_conversation")); n != 0 {
		t.Fatalf("没要删却剪了 %d 次", n)
	}
}

func TestRestoreCodeThenDrop(t *testing.T) {
	h, c, l := twoTurns(t)
	second := h.item(1, "user", "二")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"], "drop": true})
	_, id := lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":true}`, "")
	// 还原成了接着剪这一轮，剪的期间照样不收新消息
	req, id := lastRequest(t, l, "rewind_conversation")
	if req["target_message_uuid"] != second["uuid"] || h.state(1, "can_rewind") != false {
		t.Fatalf("接着删的请求 %v，can_rewind %v", req, h.state(1, "can_rewind"))
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一"}) || h.state(1, "can_rewind") != true {
		t.Fatalf("条目 %v，can_rewind %v", got, h.state(1, "can_rewind"))
	}

	// 没还原成就不删
	first := h.item(1, "user", "一")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": first["id"], "drop": true})
	_, id = lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":false}`, "")
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", "notice:没有还原代码：没有这条消息之前的快照"}) {
		t.Fatalf("条目：%v", got)
	}
	if n := len(l.lines("control_request/rewind_conversation")); n != 1 {
		t.Fatalf("剪了 %d 次", n)
	}
}

// forkFrom 从客户端 1 的对话 a 里条目 last 所在那一轮之后分支，新对话叫 b。
func (h *harness) forkFrom(last any) *Conversation {
	h.t.Helper()
	h.call(1, "conv.fork", map[string]any{"conv": "a", "as": "b", "last": last})
	b := h.conv(1, "b")
	if b == nil {
		h.t.Fatal("没有叫 b 的对话")
	}
	h.settle(b)
	return b
}

// clientState 是客户端 client 手里名字为 name 的那个对话的状态（从推送里拼出来的）。
func (h *harness) clientState(client int, name string) map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	state := map[string]any{}
	for _, m := range h.client(client).msgs {
		if m["type"] != "conv.state" || m["conv"] != name {
			continue
		}
		if m["full"] == true {
			state = map[string]any{}
		}
		for k, v := range m {
			state[k] = v
		}
	}
	return state
}

// clientItems 是客户端 client 手里名字为 name 的那个对话的条目文字。
func (h *harness) clientItems(client int, name string) []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	var items []any
	for _, m := range h.client(client).msgs {
		if m["type"] != "conv.items" || m["conv"] != name {
			continue
		}
		got, _ := m["items"].([]any)
		if m["full"] == true {
			items = got
			continue
		}
		at, remove := int(m["at"].(float64)), int(m["remove"].(float64))
		items = append(append(append([]any{}, items[:at]...), got...), items[at+remove:]...)
	}
	var out []string
	for _, it := range items {
		m := it.(map[string]any)
		out = append(out, m["kind"].(string)+":"+m["text"].(string))
	}
	return out
}

const forkNotice = "notice:从这里分支出的新对话，原对话不变。发出消息后保存"

func TestForkFromMiddle(t *testing.T) {
	h, _, l := twoTurns(t)
	h.call(1, "conv.effort", map[string]any{"conv": "a", "value": "high"})
	h.call(1, "conv.mode", map[string]any{"conv": "a", "value": "acceptEdits"})
	var asked string
	h.m.deps.SessionTitles = func(cwd string) []string { asked = cwd; return []string{"分支 · 问题", "别的"} }
	second := h.item(1, "user", "二")
	b := h.forkFrom(h.item(1, "answer", "回答一")["id"])

	if asked != "/w" {
		t.Fatalf("查标题的工作目录：%q", asked)
	}
	if got := h.clientItems(1, "b"); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", forkNotice}) {
		t.Fatalf("分支的条目：%v", got)
	}
	state := h.clientState(1, "b")
	if state["title"] != "分支 2 · 问题" || state["session"] != "" || state["effort"] != "high" ||
		state["reported_model"] != "claude-fake" || state["can_rewind"] != true {
		t.Fatalf("分支的状态：%v", state)
	}
	// 原对话不动
	if got := h.clientItems(1, "a"); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", "user:二", "answer:回答二"}) {
		t.Fatalf("原对话的条目：%v", got)
	}

	// 第一条消息：起进程从原会话复制，先剪掉下一轮再发
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "换个问法"})
	bl := h.link(1)
	h.settle(b)
	args := strings.Join(h.starts[1].Args, " ")
	if !strings.Contains(args, "--resume s1 --fork-session --name 分支 2 · 问题") ||
		!strings.Contains(args, "--effort high") || !strings.Contains(args, "--permission-mode acceptEdits") ||
		strings.Contains(args, "--model") {
		t.Fatalf("分支的启动参数：%s", args)
	}
	req, id := lastRequest(t, bl, "rewind_conversation")
	if req["target_message_uuid"] != second["uuid"] || req["last_seen_user_message_uuid"] != second["uuid"] {
		t.Fatalf("分支剪的请求：%v", req)
	}
	if got := sentTexts(bl); len(got) != 0 {
		t.Fatalf("剪之前就发了：%v", got)
	}
	h.respond(b, bl, id, `{"rewound":true}`, "")
	if got := sentTexts(bl); !reflect.DeepEqual(got, []string{"换个问法"}) {
		t.Fatalf("分支发出去的：%v", got)
	}
	h.feed(b, bl, initLine("s2"))
	h.feed(b, bl, answerLines("m9", "新的回答")...)
	h.feed(b, bl, resultOK)
	if h.clientState(1, "b")["session"] != "s2" || b.forkSource != "s1" {
		t.Fatalf("分支的会话 %v，原会话 %q", h.clientState(1, "b")["session"], b.forkSource)
	}
	// 有了自己的会话以后接着它，不再剪
	bl.events.Exit(Exit{})
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "再来"})
	if args := strings.Join(h.starts[2].Args, " "); !strings.Contains(args, "--resume s2") || strings.Contains(args, "--fork-session") {
		t.Fatalf("接着分支的参数：%s", args)
	}
	if n := len(h.link(2).lines("control_request/rewind_conversation")); n != 0 {
		t.Fatalf("分支有了会话还剪了 %d 次", n)
	}
	if got := sentTexts(l); len(got) != 2 {
		t.Fatalf("原对话多发了：%v", got)
	}
}

func TestForkCutFails(t *testing.T) {
	h, _, _ := twoTurns(t)
	b := h.forkFrom(h.item(1, "user", "一")["id"])
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "换个问法"})
	bl := h.link(1)
	_, id := lastRequest(t, bl, "rewind_conversation")
	h.respond(b, bl, id, `{"rewound":false,"reason":"target_not_found"}`, "")
	want := []string{"user:一", "answer:回答一", forkNotice, "notice:没有发出：分支没有建成：会话记录里找不到这条消息（上下文压缩之前的消息改不了）"}
	if got := h.clientItems(1, "b"); !reflect.DeepEqual(got, want) {
		t.Fatalf("条目：%v", got)
	}
	recalled, _ := h.clientState(1, "b")["recalled"].(map[string]any)
	if recalled["text"] != "换个问法" || len(sentTexts(bl)) != 0 {
		t.Fatalf("放回的 %v，发出去的 %v", recalled, sentTexts(bl))
	}

	// 换了进程要重新剪
	bl.events.Exit(Exit{})
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "再试"})
	if n := len(h.link(2).lines("control_request/rewind_conversation")); n != 1 {
		t.Fatalf("新进程剪了 %d 次", n)
	}
}

func TestForkLastTurnCopiesWhole(t *testing.T) {
	h, _, _ := twoTurns(t)
	b := h.forkFrom(h.item(1, "answer", "回答二")["id"])
	if got := h.clientItems(1, "b"); len(got) != 5 || got[4] != forkNotice || h.clientState(1, "b")["title"] != "分支 · 问题" {
		t.Fatalf("条目 %v，标题 %v", got, h.clientState(1, "b")["title"])
	}
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "接着问"})
	bl := h.link(1)
	h.settle(b)
	if n := len(bl.lines("control_request/rewind_conversation")); n != 0 || !reflect.DeepEqual(sentTexts(bl), []string{"接着问"}) {
		t.Fatalf("整个复制却剪了 %d 次，发出去的 %v", n, sentTexts(bl))
	}
}

func TestRewriteInUnsentFork(t *testing.T) {
	h, _, _ := twoTurns(t)
	second := h.item(1, "user", "二")
	b := h.forkFrom(h.item(1, "answer", "回答二")["id"])
	// 分支还没发过消息时改写第一轮：报原会话的最后一条，剪成了不再另剪
	// 客户端手里现在是分支的条目（推送不分对话，后拿起的 b 盖过了 a）
	first := h.item(1, "user", "一")
	h.call(1, "conv.rewrite", map[string]any{"conv": "b", "target": first["id"], "text": "新的一"})
	bl := h.link(1)
	req, id := lastRequest(t, bl, "rewind_conversation")
	if req["target_message_uuid"] != first["uuid"] || req["last_seen_user_message_uuid"] != second["uuid"] {
		t.Fatalf("剪的请求：%v", req)
	}
	h.respond(b, bl, id, `{"rewound":true}`, "")
	if got := sentTexts(bl); !reflect.DeepEqual(got, []string{"新的一"}) {
		t.Fatalf("发出去的：%v", got)
	}
	h.feed(b, bl, resultOK)
	h.call(1, "conv.send", map[string]any{"conv": "b", "text": "再问"})
	if n := len(bl.lines("control_request/rewind_conversation")); n != 1 {
		t.Fatalf("剪了 %d 次", n)
	}
}

func TestForkRefused(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	b, _ := json.Marshal(map[string]any{"conv": "a", "as": "b", "last": "x"})
	if _, err := h.m.Request(1, "conv.fork", b); err != errForkNoSession {
		t.Fatalf("没有会话就分支：%v", err)
	}
	h2, c, l := twoTurns(t)
	h2.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	h2.settle(c)
	b, _ = json.Marshal(map[string]any{"conv": "a", "as": "b", "last": h2.item(1, "answer", "回答一")["id"]})
	if _, err := h2.m.Request(1, "conv.fork", b); err != errNotNow {
		t.Fatalf("回答进行中分支：%v", err)
	}
	h2.feed(c, l, resultOK)
	b, _ = json.Marshal(map[string]any{"conv": "a", "as": "a", "last": h2.item(1, "answer", "回答一")["id"]})
	if _, err := h2.m.Request(1, "conv.fork", b); err == nil {
		t.Fatal("名字重了也分支了")
	}
}

func TestBranchEnd(t *testing.T) {
	_, c, _ := twoTurns(t)
	v, _ := c.Call(func() (any, error) { return copyItems(c.r.Items), nil })
	items := v.([]*transcript.Item)
	id := func(i int) string { return items[i].ID }
	cases := []struct {
		last    string
		end     int
		cut     string
		ok      bool
		comment string
	}{
		{id(0), 2, items[2].UUID, true, "第一轮的用户消息"},
		{id(1), 2, items[2].UUID, true, "第一轮的回答"},
		{id(3), 4, "", true, "最后一轮"},
		{"nope", 0, "", false, "找不到"},
	}
	for _, tc := range cases {
		end, cut, ok := branchEnd(items, tc.last)
		if end != tc.end || cut != tc.cut || ok != tc.ok {
			t.Fatalf("%s：%d %q %v", tc.comment, end, cut, ok)
		}
	}
	// 下一轮的消息没有 uuid：剪不准
	items[2].UUID = ""
	if _, _, ok := branchEnd(items, id(1)); ok {
		t.Fatal("剪不准也给分支")
	}
	// 第一条用户消息之前的内容不属于任何一轮
	notice := &transcript.Item{Kind: "notice", ID: "n0", Text: "载入历史"}
	if _, _, ok := branchEnd(slices.Insert(items, 0, notice), "n0"); ok {
		t.Fatal("第一条用户消息之前也给分支")
	}
}

func TestBranchTitle(t *testing.T) {
	cases := []struct {
		title string
		taken []string
		want  string
	}{
		{"问题", nil, "分支 · 问题"},
		{"  问题 ", []string{"分支 · 问题"}, "分支 2 · 问题"},
		{"问题", []string{"分支 · 问题", "分支 2 · 问题"}, "分支 3 · 问题"},
		{"分支 2 · 问题", []string{"分支 · 问题"}, "分支 2 · 问题"},
		{"分支 · 分支 · x", nil, "分支 · 分支 · x"},
		{"", nil, "分支 · 新对话"},
	}
	for _, tc := range cases {
		if got := branchTitle(tc.title, tc.taken); got != tc.want {
			t.Errorf("branchTitle(%q, %v) = %q，要 %q", tc.title, tc.taken, got, tc.want)
		}
	}
}
