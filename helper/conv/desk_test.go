package conv

import (
	"testing"
	"time"
)

func TestDeskWindow(t *testing.T) {
	h := newHarness(t)
	// 手机本机（没声明 desk）：不开
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	h.feed(h.conv(1, "a"), h.link(0), initLine("s1"))
	// 远程主机：新会话拿到会话号时开
	h.call(2, "conv.open", map[string]any{"conv": "b", "cwd": "/r", "options": map[string]any{"desk": true}})
	h.call(2, "conv.send", map[string]any{"conv": "b", "text": "一"})
	h.feed(h.conv(2, "b"), h.link(1), initLine("s2"))
	if got := nextDesk(t, h); got != "s2 /r" {
		t.Fatalf("开的窗口：%q", got)
	}
	// 接着已有会话起进程时再开一次
	h.call(2, "conv.open", map[string]any{"conv": "c", "session": "s3", "cwd": "/r", "options": map[string]any{"desk": true}})
	h.call(2, "conv.send", map[string]any{"conv": "c", "text": "一"})
	if got := nextDesk(t, h); got != "s3 /r" {
		t.Fatalf("开的窗口：%q", got)
	}
	select {
	case got := <-h.desks:
		t.Fatalf("多开了：%q", got)
	case <-time.After(50 * time.Millisecond):
	}
}

func nextDesk(t *testing.T, h *harness) string {
	t.Helper()
	select {
	case got := <-h.desks:
		return got
	case <-time.After(5 * time.Second):
		t.Fatal("没开窗口")
		return ""
	}
}
