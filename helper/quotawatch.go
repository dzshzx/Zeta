package main

import (
	"bytes"
	"encoding/json"
	"log"
	"sync"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/core"
)

// quotaWatch 管额度的订阅（quota.watch）：订阅时先推一份在用账号的额度（在用中转时不带额度、带中转名），之后这个核心存了新的数（对话交来的、客户端经
// quota.usage、quota.event 交来的）或换了号时，存着的额度（连同收到的时刻 updatedAt，客户端拿它看数有多旧）和上次推的
// 不一样就推给全部订阅的客户端。
// 别的包的核心、终端里的 claude-switch 改的它看不到，客户端打开额度页时照样可以 quota.get。
type quotaWatch struct {
	store accounts.QuotaStore

	mu      sync.Mutex
	clients map[*core.Client]bool
	last    []byte // 上次推出去的额度（JSON），一样的不重推
}

// quotaPush 是推给订阅的客户端的一行。
type quotaPush struct {
	Type     string             `json:"type"` // quota.snapshot
	Snapshot *accounts.Snapshot `json:"snapshot"`
	// Relay 是在用的中转名；在用中转时没有订阅额度，Snapshot 为 nil，客户端不显示额度。在用账号时为空。
	Relay string `json:"relay"`
}

func newQuotaWatch(e *env) *quotaWatch {
	return &quotaWatch{store: accounts.QuotaStore{Home: e.home}, clients: map[*core.Client]bool{}}
}

// current 是要推的一行：在用账号的额度（读不了时记进日志，当没有），在用中转时不读额度、带上中转名。
func (w *quotaWatch) current() (quotaPush, []byte) {
	push := quotaPush{Type: "quota.snapshot", Relay: accounts.CurrentRelay(w.store.Home)}
	if push.Relay == "" {
		snap, err := w.store.Get()
		if err != nil {
			log.Print("读额度：", err)
		}
		push.Snapshot = snap
	}
	b, _ := json.Marshal(push)
	return push, b
}

// watch：客户端 c 订阅（on 为真，先推一份现在的）或退订。
func (w *quotaWatch) watch(c *core.Client, on bool) {
	if !on {
		w.gone(c)
		return
	}
	push, b := w.current()
	w.mu.Lock()
	w.clients[c] = true
	w.last = b
	w.mu.Unlock()
	_ = c.Send(push)
}

func (w *quotaWatch) gone(c *core.Client) {
	w.mu.Lock()
	delete(w.clients, c)
	w.mu.Unlock()
}

// changed：存额度或换号之后调。和上次推的一样时不推。
func (w *quotaWatch) changed() {
	w.mu.Lock()
	if len(w.clients) == 0 {
		w.mu.Unlock()
		return
	}
	w.mu.Unlock()
	push, b := w.current()
	w.mu.Lock()
	if bytes.Equal(b, w.last) {
		w.mu.Unlock()
		return
	}
	w.last = b
	clients := make([]*core.Client, 0, len(w.clients))
	for c := range w.clients {
		clients = append(clients, c)
	}
	w.mu.Unlock()
	for _, c := range clients {
		_ = c.Send(push)
	}
}
