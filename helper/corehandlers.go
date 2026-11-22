package main

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/core"
)

// 核心上挂的处理器：按方法前缀分发（core.Mux）。serve 只转发，所有方法的逻辑都在这里，几个客户端共用一份。

// watchOptions 是会话列表订阅的节奏。
type watchOptions struct {
	debounce time.Duration // 会话目录变了以后等这么久再重读，其间的变化合成一次
	poll     time.Duration // 盯不了目录（inotify 用不了）、或要盯的目录还没有时，隔这么久重读一次
}

var defaultWatchOptions = watchOptions{debounce: 2 * time.Second, poll: 10 * time.Second}

// coreHandler 是核心上挂的处理器。只在这个进程拿到核心的锁以后才建（建的时候会接回托管进程）。
func coreHandler(e *env) core.Handler { return newCoreHandler(e, defaultWatchOptions) }

func newCoreHandler(e *env, watch watchOptions) core.Handler {
	var m core.Mux
	m.Handle("conv.", newConversations(e))
	m.Handle("sessions.", &sessionsHandler{e: e, opts: watch, watches: map[*core.Client]*sessionWatch{}})
	// 要跑 claude-switch、等进程退出，慢：各在自己的 goroutine 里做，不挡同一个客户端后面的请求
	accounts := accountsHandler{e}
	m.HandleAsync("accounts.", accounts)
	m.HandleAsync("quota.", accounts)
	return &m
}

// sessionsHandler 管会话列表的订阅：sessions.watch 先推一行整份的 zeta_sessions（full 为真），之后会话目录有变化时只推变了的几项
// （见 sessionWatch）；再发一次就再给一份整份的。
type sessionsHandler struct {
	e    *env
	opts watchOptions

	mu      sync.Mutex
	watches map[*core.Client]*sessionWatch
}

func (h *sessionsHandler) Request(c *core.Client, r core.Request) (any, error) {
	if r.Method != "sessions.watch" {
		return nil, errors.New("unknown method: " + r.Method)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if w := h.watches[c]; w != nil {
		w.resend()
		return nil, nil
	}
	h.watches[c] = startSessionWatch(h.e, h.opts, c.Send)
	return nil, nil
}

func (h *sessionsHandler) Gone(c *core.Client) {
	h.mu.Lock()
	w := h.watches[c]
	delete(h.watches, c)
	h.mu.Unlock()
	if w != nil {
		w.close()
	}
}

// Idle：订阅不让核心留着，有客户端连着时核心本来就不退。
func (h *sessionsHandler) Idle() bool { return true }

// accountsHandler 做 accounts.* 和 quota.*（见 docs/core.md「账号与额度」、serve_accounts.go）。
type accountsHandler struct{ e *env }

func (h accountsHandler) Request(_ *core.Client, r core.Request) (any, error) {
	return handleAccounts(context.Background(), h.e, r.Method, r.Raw)
}

func (accountsHandler) Gone(*core.Client) {}
func (accountsHandler) Idle() bool        { return true }
