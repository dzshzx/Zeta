package conv

import (
	"errors"
	"slices"
	"testing"
	"time"
)

// 会话正被别处占着：打开时输入栏就锁着，发不了消息；接不过来时还锁着，接过来以后解开。
func TestHandoffUnblocks(t *testing.T) {
	h := newHarness(t)
	busy, idle := "busy", "idle"
	occupant := &busy
	handed := make(chan Handoff, 2)
	h.m.deps.Occupant = func(session string) Occupancy {
		if session != "s1" || occupant == nil {
			return Occupancy{HandsOver: true}
		}
		return Occupancy{Status: occupant, HandsOver: *occupant == idle}
	}
	h.m.deps.Handoff = func(string) Handoff { return <-handed }

	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s2", "cwd": "/w"})
	waitFor(t, "打开时锁着", func() bool { return h.state(1, "blocked") == true })
	waitFor(t, "没人占着的不锁", func() bool { return h.state(2, "blocked") == false })
	if _, err := h.m.Request(1, "conv.send", []byte(`{"conv":"a","text":"一"}`)); !errors.Is(err, errBlocked) {
		t.Fatalf("锁着时发消息：%v", err)
	}
	if got := h.call(1, "conv.occupant", map[string]any{"conv": "a"}).(Occupancy); got.Status == nil || *got.Status != busy || got.HandsOver {
		t.Fatalf("占用：%+v", got)
	}

	handed <- Handoff{Status: &busy}
	if got := h.call(1, "conv.handoff", map[string]any{"conv": "a"}).(Handoff); got.Handed {
		t.Fatalf("那边正忙却交出来了：%+v", got)
	}
	if _, err := h.m.Request(1, "conv.send", []byte(`{"conv":"a","text":"一"}`)); !errors.Is(err, errBlocked) {
		t.Fatalf("没接过来却解开了：%v", err)
	}

	occupant = &idle
	handed <- Handoff{Handed: true}
	if got := h.call(1, "conv.handoff", map[string]any{"conv": "a"}).(Handoff); !got.Handed {
		t.Fatalf("没交出来：%+v", got)
	}
	waitFor(t, "接过来后解开", func() bool { return h.state(1, "blocked") == false })
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	if len(h.starts) != 1 || !slices.Contains(h.starts[0].Args, "s1") {
		t.Fatalf("接过来以后发消息该起进程接着 s1：%+v", h.starts)
	}
}

// 要等的请求不挡着同一个客户端后面的请求：Start 把交接发出去就返回，交接还没完时同一条连接照样能发消息。
func TestPendingDoesNotHoldTheClient(t *testing.T) {
	h := newHarness(t)
	release := make(chan Handoff)
	h.m.deps.Handoff = func(string) Handoff { return <-release }
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})

	started := make(chan any, 1)
	go func() {
		v, err := h.m.Start(1, "conv.handoff", []byte(`{"conv":"a"}`))
		if err != nil {
			t.Error(err)
		}
		started <- v
	}()
	var p Pending
	select {
	case v := <-started:
		p = v.(Pending)
	case <-time.After(5 * time.Second):
		t.Fatal("Start 在等交接，没有返回")
	}
	type result struct {
		v   any
		err error
	}
	done := make(chan result, 1)
	go func() { v, err := p(); done <- result{v, err} }()
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	release <- Handoff{Handed: true}
	if r := <-done; r.err != nil || !r.v.(Handoff).Handed {
		t.Fatalf("交接结果：%v %v", r.v, r.err)
	}
}

// host_key：接着进程时是托管标识，没有进程时为空。
func TestStateHostKey(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	waitFor(t, "没进程时为空", func() bool { return h.state(1, "host_key") == "" })
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	key := h.starts[0].Key
	if key == "" {
		t.Fatal("起进程没给托管标识")
	}
	waitFor(t, "接着进程时是托管标识", func() bool { return h.state(1, "host_key") == key })
}
