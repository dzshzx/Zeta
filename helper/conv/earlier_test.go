package conv

import (
	"reflect"
	"testing"
	"time"
)

func TestEarlierPage(t *testing.T) {
	h := newHarness(t)
	h.history = []byte(`{"type":"zeta_history","skipped":12,"anchor":"u1"}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"近的问题"}]}}
{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"近的回答"}]}}
`)
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)

	h.mu.Lock()
	h.history = []byte(`{"type":"zeta_history","skipped":0}
{"type":"user","uuid":"u0","message":{"content":[{"type":"text","text":"早的问题"}]}}
{"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"早的回答"}]}}
`)
	before := len(h.client(1).msgs)
	h.mu.Unlock()
	h.call(1, "conv.earlier", map[string]any{"conv": "a"})
	h.settle(c)

	if got := h.historyArgs[1]; !reflect.DeepEqual(got, []string{"/f.jsonl", "12", "u1"}) {
		t.Fatalf("要页的参数：%v", got)
	}
	want := []string{"user:早的问题", "answer:早的回答", "user:近的问题", "answer:近的回答"}
	if got := h.texts(1); !reflect.DeepEqual(got, want) || h.state(1, "earlier") != float64(0) || h.state(1, "earlier_loading") != false {
		t.Fatalf("条目 %v，earlier %v，earlier_loading %v", got, h.state(1, "earlier"), h.state(1, "earlier_loading"))
	}
	// 新的一页是一次接在最前面的替换，不是整份：客户端据此保住正看着的位置
	h.mu.Lock()
	var pages []map[string]any
	for _, m := range h.client(1).msgs[before:] {
		if m["type"] == "conv.items" {
			pages = append(pages, m)
		}
	}
	h.mu.Unlock()
	if len(pages) != 1 || pages[0]["full"] != nil || pages[0]["at"] != float64(0) || pages[0]["remove"] != float64(0) || len(pages[0]["items"].([]any)) != 2 {
		t.Fatalf("推的条目：%v", pages)
	}
	if _, err := h.m.Request(1, "conv.earlier", []byte(`{"conv":"a"}`)); err != errNoEarlier {
		t.Fatalf("没有更早的了还要：%v", err)
	}
}

func TestEarlierStaleAndRest(t *testing.T) {
	h := newHarness(t)
	h.history = []byte(`{"type":"zeta_history","skipped":5,"anchor":"u1"}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"近的问题"}]}}
`)
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.mu.Lock()
	h.history = []byte(`{"type":"zeta_history","stale":true,"skipped":0}` + "\n")
	h.mu.Unlock()
	if _, err := h.m.Request(1, "conv.earlier", []byte(`{"conv":"a","rest":true}`)); err != errEarlierStale {
		t.Fatalf("记录在别处改过：%v", err)
	}
	h.settle(c)
	if got := h.historyArgs[1]; !reflect.DeepEqual(got, []string{"/f.jsonl", "5", "u1", "all"}) {
		t.Fatalf("要剩下全部的参数：%v", got)
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:近的问题"}) || h.state(1, "earlier") != float64(5) {
		t.Fatalf("没对上还是改了列表：%v，earlier %v", got, h.state(1, "earlier"))
	}
}

func waitLoaded(t *testing.T, h *harness, c *Conversation) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for h.state(1, "loading_history") != false {
		if time.Now().After(deadline) {
			t.Fatal("历史没载完")
		}
		h.settle(c)
		time.Sleep(10 * time.Millisecond)
	}
}

// 剪的期间读回来的更早一页等剪完再接上：接在剪完以后的列表前面。
func TestEarlierWaitsForRewind(t *testing.T) {
	h, c, l := twoTurns(t)
	_, _ = c.Call(func() (any, error) {
		c.earlier, c.earlierAnchor, c.earlierFile = 4, "u0", "/f.jsonl"
		return nil, nil
	})
	h.mu.Lock()
	h.history = []byte(`{"type":"zeta_history","skipped":0}
{"type":"user","uuid":"u0","message":{"content":[{"type":"text","text":"早的问题"}]}}
`)
	h.mu.Unlock()
	second := h.item(1, "user", "二")
	h.call(1, "conv.drop", map[string]any{"conv": "a", "target": second["id"]})
	_, id := waitRequest(t, l, "rewind_conversation", 1)

	done := make(chan error, 1)
	go func() {
		_, err := h.m.Request(1, "conv.earlier", []byte(`{"conv":"a"}`))
		done <- err
	}()
	select {
	case err := <-done:
		t.Fatalf("剪完之前就接上了：%v", err)
	case <-time.After(100 * time.Millisecond):
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	h.settle(c)
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:早的问题", "user:一", "answer:回答一"}) {
		t.Fatalf("条目：%v", got)
	}
}
