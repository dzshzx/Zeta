package core

import (
	"strings"
	"testing"
)

type named struct {
	name string
	idle bool
	gone int
}

func (n *named) Request(*Client, Request) (any, error) { return n.name, nil }
func (n *named) Gone(*Client)                          { n.gone++ }
func (n *named) Idle() bool                            { return n.idle }

func TestMux(t *testing.T) {
	conv, sub, cfg := &named{name: "conv", idle: true}, &named{name: "sub", idle: true}, &named{name: "config"}
	var m Mux
	m.Handle("conv.", conv)
	m.Handle("conv.sub.", sub)
	m.Handle("config.", cfg)
	for method, want := range map[string]string{"conv.send": "conv", "conv.sub.x": "sub", "config.get": "config"} {
		if got, err := m.Request(nil, Request{Method: method}); err != nil || got != want {
			t.Fatalf("%s 交给了 %v（%v）", method, got, err)
		}
	}
	if _, err := m.Request(nil, Request{Method: "accounts.list"}); err == nil || !strings.Contains(err.Error(), "accounts.list") {
		t.Fatalf("没人认的方法：%v", err)
	}
	m.Gone(nil)
	if conv.gone != 1 || sub.gone != 1 || cfg.gone != 1 {
		t.Fatal("断开没告诉每个处理器")
	}
	if m.Idle() {
		t.Fatal("有处理器忙着却说闲着")
	}
	cfg.idle = true
	if !m.Idle() {
		t.Fatal("都闲着却说忙着")
	}
}
