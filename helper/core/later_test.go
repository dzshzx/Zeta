package core

import (
	"errors"
	"os"
	"testing"
	"time"
)

// slowHandler：slow.wait 等 release 关上才答，slow.coded 答带代号的错；Release 记下被叫过。
type slowHandler struct {
	release  chan struct{}
	released chan struct{}
}

type codeErr struct{}

func (codeErr) Error() string { return "有 Claude Code 开着" }
func (codeErr) Code() string  { return "busy" }

func (h *slowHandler) Request(_ *Client, r Request) (any, error) {
	switch r.Method {
	case "slow.wait":
		<-h.release
		return "等到了", nil
	case "slow.coded":
		return nil, codeErr{}
	}
	return nil, errors.New("unknown")
}
func (h *slowHandler) Gone(*Client) {}
func (h *slowHandler) Idle() bool   { return true }
func (h *slowHandler) Release()     { close(h.released) }

// 慢的请求不挡同一个客户端后面的请求：后发的先答，慢的做完再答；错误的代号带进应答。
func TestAsyncRepliesLater(t *testing.T) {
	p := testPaths(t)
	slow := &slowHandler{release: make(chan struct{}), released: make(chan struct{})}
	var m Mux
	m.HandleAsync("slow.", slow)
	m.Handle("conv.", newFakeHandler())
	serve(t, p, &m, time.Minute)
	c := dial(t, p)
	c.send(`{"id":1,"method":"slow.wait"}`)
	if r := c.call(`{"id":2,"method":"conv.echo"}`); r["id"] != float64(2) {
		t.Fatalf("先答的：%v", r)
	}
	close(slow.release)
	if r := c.next(); r["id"] != float64(1) || r["result"] != "等到了" {
		t.Fatalf("慢请求的应答：%v", r)
	}
	if r := c.call(`{"id":3,"method":"slow.coded"}`); r["ok"] != false || r["code"] != "busy" || r["error"] != "有 Claude Code 开着" {
		t.Fatalf("带代号的错：%v", r)
	}
}

// 交班：答完就不再接新连接，让处理器放开托管进程，删掉套接字退出。
func TestHandover(t *testing.T) {
	p := testPaths(t)
	slow := &slowHandler{release: make(chan struct{}), released: make(chan struct{})}
	var m Mux
	m.HandleAsync("slow.", slow)
	r := serve(t, p, &m, time.Minute)
	c := dial(t, p)
	if rep := c.call(`{"id":1,"method":"core.handover"}`); rep["ok"] != true {
		t.Fatalf("交班的应答：%v", rep)
	}
	select {
	case <-slow.released:
	case <-time.After(5 * time.Second):
		t.Fatal("没让处理器放开托管进程")
	}
	r.exited()
	if _, err := os.Stat(p.Socket); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("套接字还在：%v", err)
	}
}
