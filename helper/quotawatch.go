package main

import (
	"bytes"
	"encoding/json"
	"log"
	"sync"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/core"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// quotaWatch 管额度的订阅（quota.watch）：按 harness（claude / agy）分桶维护订阅者与最近一次推送，
// 订阅时先推一份在用账号的额度（在用中转时不带额度、带中转名），之后存了新数或换了号且内容变化时推给对应引擎的订阅客户端。
type quotaWatch struct {
	claude quotaBucket
	agy    quotaBucket
}

type quotaBucket struct {
	harness string
	read    func() (*accounts.Snapshot, string, error)

	mu      sync.Mutex
	clients map[*core.Client]bool
	last    []byte
}

// quotaPush 是推给订阅的客户端的一行。
type quotaPush struct {
	Type     string             `json:"type"`    // quota.snapshot
	Harness  string             `json:"harness"` // claude | agy
	Snapshot *accounts.Snapshot `json:"snapshot"`
	// Relay 是在用的中转名；在用中转时没有订阅额度，Snapshot 为 nil，客户端不显示额度。在用账号时为空。
	Relay string `json:"relay"`
}

func newQuotaWatch(e *env) *quotaWatch {
	return &quotaWatch{
		claude: newQuotaBucket(quotaBackendFor(e, sessionproto.HarnessClaude)),
		agy:    newQuotaBucket(quotaBackendFor(e, sessionproto.HarnessAgy)),
	}
}

func newQuotaBucket(b quotaBackend) quotaBucket {
	return quotaBucket{
		harness: b.harness,
		clients: map[*core.Client]bool{},
		read: func() (*accounts.Snapshot, string, error) {
			if r := b.currentRelay(); r != "" {
				return nil, r, nil
			}
			s, err := b.store.Get()
			return s, "", err
		},
	}
}

func (w *quotaWatch) bucket(harness string) *quotaBucket {
	if sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy {
		return &w.agy
	}
	return &w.claude
}

func (b *quotaBucket) current() (quotaPush, []byte) {
	snap, relay, err := b.read()
	if err != nil {
		log.Print("读额度（"+b.harness+"）：", err)
	}
	push := quotaPush{Type: "quota.snapshot", Harness: b.harness, Snapshot: snap, Relay: relay}
	encoded, _ := json.Marshal(push)
	return push, encoded
}

// watchFor：客户端 c 订阅或退订指定 harness 的额度推送。
func (w *quotaWatch) watchFor(c *core.Client, harness string, on bool) {
	b := w.bucket(harness)
	if !on {
		b.gone(c)
		return
	}
	push, encoded := b.current()
	b.mu.Lock()
	b.clients[c] = true
	b.last = encoded
	b.mu.Unlock()
	_ = c.Send(push)
}

func (b *quotaBucket) gone(c *core.Client) {
	b.mu.Lock()
	delete(b.clients, c)
	b.mu.Unlock()
}

func (w *quotaWatch) gone(c *core.Client) {
	w.claude.gone(c)
	w.agy.gone(c)
}

// changedFor：指定 harness 存额度或换号之后调，和上次推的一样时不推。
func (w *quotaWatch) changedFor(harness string) {
	b := w.bucket(harness)
	b.mu.Lock()
	if len(b.clients) == 0 {
		b.mu.Unlock()
		return
	}
	b.mu.Unlock()
	push, encoded := b.current()
	b.mu.Lock()
	if bytes.Equal(encoded, b.last) {
		b.mu.Unlock()
		return
	}
	b.last = encoded
	clients := make([]*core.Client, 0, len(b.clients))
	for c := range b.clients {
		clients = append(clients, c)
	}
	b.mu.Unlock()
	for _, c := range clients {
		_ = c.Send(push)
	}
}
