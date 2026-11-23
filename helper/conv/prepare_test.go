package conv

import (
	"errors"
	"reflect"
	"testing"
	"time"
)

// conv.prepare 只起进程、不发消息：initialize 的应答到了 commands、models 就有；已有进程时什么也不做，之后发消息用同一个进程。
func TestPrepare(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "options": map[string]any{"system_prompt": "hint"}})
	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	if len(l.lines("user")) != 0 || h.state(1, "process") != true || h.state(1, "busy") != false || len(h.client(1).turns) != 0 {
		t.Fatalf("发了消息或开了一轮：%v，状态 %v", l.lines("user"), h.client(1).state)
	}
	if got := h.starts[0].Args; !reflect.DeepEqual(got[len(got)-2:], []string{"--append-system-prompt", "hint"}) {
		t.Fatalf("没按这个客户端的设置起：%v", got)
	}
	init := l.lines("control_request/initialize")
	if len(init) != 1 {
		t.Fatalf("initialize：%v", init)
	}
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+init[0]["request_id"].(string)+
		`","response":{"commands":[{"name":"review"}],"models":[{"value":"default"}]}}}`)
	commands, _ := h.state(1, "commands").([]any)
	models, _ := h.state(1, "models").([]any)
	if len(commands) != 1 || len(models) != 1 {
		t.Fatalf("commands %v，models %v", h.state(1, "commands"), h.state(1, "models"))
	}

	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	h.settle(c)
	if len(h.starts) != 1 || len(l.lines("user")) != 1 || len(l.lines("control_request/initialize")) != 1 {
		t.Fatalf("起了 %d 个进程，发了 %d 条消息", len(h.starts), len(l.lines("user")))
	}
}

// 预先起好的进程闲着：没人拿着时照常满 releaseAfter 断开。
func TestPreparedProcessReleasedWhenIdle(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	c, l := h.conv(1, "a"), h.link(0)
	h.m.Gone(1)
	start := time.Now()
	releasable := func(at time.Time) bool {
		v, _ := c.Call(func() (any, error) { return c.releasable(at), nil })
		return v == true
	}
	if releasable(start) || releasable(start.Add(releaseAfter-time.Second)) {
		t.Fatal("没等够就收掉了")
	}
	if !releasable(start.Add(releaseAfter)) || !l.left || l.stopped {
		t.Fatalf("闲满了没断开：left %v stopped %v", l.left, l.stopped)
	}
}

// 起不来时和发消息时一样在列表里说明原因；会话被别处占着时不起。
func TestPrepareFailsOrBlocked(t *testing.T) {
	h := newHarness(t)
	h.m.deps.Start = func(StartOptions, Events) (Link, error) { return nil, errors.New("没有 claude") }
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	h.settle(h.conv(1, "a"))
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"notice:启动失败：没有 claude"}) || h.state(1, "process") != false {
		t.Fatalf("条目 %v，process %v", got, h.state(1, "process"))
	}

	h2 := newHarness(t)
	h2.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	c := h2.conv(1, "a")
	_, _ = c.Call(func() (any, error) { c.blocked = true; return nil, nil })
	h2.call(1, "conv.prepare", map[string]any{"conv": "a"})
	if len(h2.starts) != 0 {
		t.Fatalf("会话被别处占着还起了进程：%v", h2.starts)
	}
}
