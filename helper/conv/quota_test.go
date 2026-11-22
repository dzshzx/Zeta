package conv

import (
	"encoding/json"
	"testing"
	"time"
)

func TestQuota(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	if _, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`)); err != errNoProcess {
		t.Fatalf("没有进程时：%v", err)
	}
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))

	type result struct {
		v   any
		err error
	}
	done := make(chan result, 1)
	go func() {
		v, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`))
		done <- result{v, err}
	}()
	deadline := time.Now().Add(5 * time.Second)
	for len(l.lines("control_request/get_usage")) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("没发 get_usage")
		}
		time.Sleep(5 * time.Millisecond)
	}
	req := l.lines("control_request/get_usage")[0]
	if req["request"].(map[string]any)["skip_behaviors"] != true {
		t.Fatalf("请求：%v", req)
	}
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+req["request_id"].(string)+
		`","response":{"five_hour":{"utilization":42}}}}`)
	r := <-done
	b, _ := json.Marshal(r.v)
	if r.err != nil || string(b) != `{"five_hour":{"utilization":42}}` {
		t.Fatalf("应答 %s，出错 %v", b, r.err)
	}
}
