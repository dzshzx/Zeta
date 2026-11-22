package conv

import (
	"reflect"
	"testing"
)

// activityOf 是客户端 client 收到的最后一份 conv.activity 里的各会话。
func (h *harness) activityOf(client int) map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	var last map[string]any
	for _, m := range h.client(client).other {
		if m["type"] == "conv.activity" {
			last, _ = m["sessions"].(map[string]any)
		}
	}
	return last
}

func TestActivity(t *testing.T) {
	h := newHarness(t)
	h.call(9, "conv.activity", map[string]any{}) // 不带 watch 也是订阅
	if got := h.activityOf(9); got == nil || len(got) != 0 {
		t.Fatalf("订阅时先给一份整份：%v", got)
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	if got := h.activityOf(9); len(got) != 0 {
		t.Fatalf("还没有会话号就报了：%v", got)
	}
	h.feed(c, l, initLine("s1"))
	if got := h.activityOf(9); !reflect.DeepEqual(got, map[string]any{"s1": map[string]any{"activity": "answering"}}) {
		t.Fatalf("回答中：%v", got)
	}
	h.feed(c, l, `{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}`)
	if got := h.activityOf(9)["s1"].(map[string]any)["activity"]; got != "waiting" {
		t.Fatalf("等确认：%v", got)
	}
	h.call(1, "conv.answer", map[string]any{"conv": "a", "request_id": "p1", "reply": map[string]any{"kind": "allow"}})
	h.feed(c, l, resultOK)
	s1 := h.activityOf(9)["s1"].(map[string]any)
	if s1["activity"] != "idle" || s1["turn_end"] == nil {
		t.Fatalf("答完：%v", s1)
	}
	// 退订以后不再收
	h.call(9, "conv.activity", map[string]any{"watch": false})
	h.mu.Lock()
	before := len(h.client(9).other)
	h.mu.Unlock()
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	h.settle(c)
	h.mu.Lock()
	after := len(h.client(9).other)
	h.mu.Unlock()
	if after != before {
		t.Fatal("退订以后还在推")
	}
}
