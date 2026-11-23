package conv

import (
	"encoding/json"
	"reflect"
	"slices"
	"testing"
	"time"
)

func TestAdoptHosts(t *testing.T) {
	h := newHarness(t)
	h.history = []byte(`{"type":"zeta_history","skipped":0}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"跑个后台任务"}]}}
`)
	pending := json.RawMessage(`{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}`)
	h.m.Adopt([]Hosted{
		{Key: "idle", Session: "s0", Cwd: "/w"},
		{Key: "asking", Session: "s1", Cwd: "/w", Lines: 7, Busy: true, Pending: []json.RawMessage{pending}, Mode: "acceptEdits"},
		{Key: "tasks", Session: "s2", File: "/s2.jsonl", Cwd: "/w", Lines: 3, Tasks: 1},
	})

	// 闲着的不接也不动（留给看门狗），历史载完才接：两个都接上了，各从日志的下一行读起
	deadline := time.Now().Add(5 * time.Second)
	for {
		h.mu.Lock()
		n := len(h.attaches)
		h.mu.Unlock()
		if n == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("接上的：%v", h.attaches)
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got := slices.Sorted(slices.Values(h.attaches)); !reflect.DeepEqual(got, []string{"asking@8", "tasks@4"}) {
		t.Fatalf("接上的：%v", got)
	}

	// 客户端打开同一个会话拿到的就是接回的那个：还在回答，确认请求重新排上了
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	h.settle(c)
	prompts, _ := h.state(1, "prompts").([]any)
	if h.state(1, "busy") != true || h.state(1, "process") != true || len(prompts) != 1 {
		t.Fatalf("busy %v process %v prompts %v", h.state(1, "busy"), h.state(1, "process"), prompts)
	}
	if mode := h.state(1, "mode").(map[string]any); mode["process"] != "acceptEdits" {
		t.Fatalf("权限模式：%v", mode)
	}
	// 答了确认，回答照常推回来
	h.call(1, "conv.answer", map[string]any{"conv": "a", "request_id": "p1", "reply": map[string]any{"kind": "allow"}})
	l := linkOf(t, h, "asking")
	if resp := l.lines("control_response"); len(resp) != 1 {
		t.Fatalf("应答：%v", resp)
	}
	h.feed(c, l, `{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"好了"}]}}`, resultOK)
	if got := h.texts(1); !slices.Contains(got, "answer:好了") || h.state(1, "busy") != false {
		t.Fatalf("条目 %v，busy %v", got, h.state(1, "busy"))
	}

	// 再扫一次：已经接着的不再接
	h.m.Adopt([]Hosted{{Key: "asking", Session: "s1", Busy: true}})
	if len(h.attaches) != 2 {
		t.Fatalf("重复处理：接上 %v", h.attaches)
	}
}

func linkOf(t *testing.T, h *harness, key string) *fakeLink {
	t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, l := range h.links {
		if l.key == key {
			return l
		}
	}
	t.Fatalf("没有接上 %s", key)
	return nil
}
