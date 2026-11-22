package chat

import (
	"context"
	"encoding/json"
	"log"
	"maps"
	"slices"
	"sync"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// Conversation 是 Client 上打开的一个对话。界面用 Take 取走推送带来的变化；别的方法把用户的操作发给核心，
// 结果照样经推送回来（发出的消息、改了的模式都从 conv.state、conv.items 里看到）。
type Conversation struct {
	client *Client
	name   string
	spec   Spec

	mu sync.Mutex
	// fields 是核心推来的 conv.state 合并到现在的各字段，state 是它解码成的样子。
	fields map[string]json.RawMessage
	state  State
	items  []items.Item
	// connected：连接在、核心那边这个对话开着；opened：打开成过（重连后要重新打开的只有这些）。
	connected, opened bool
	// forked：这是分支（conv.fork 来的），还没有会话号时按 key 重新打开。
	forked bool
	// pending 是上次 Take 以后的变化。
	pending Update
}

// Update 是上次 Take 以后的变化，界面在主线程上照着改自己的样子。
type Update struct {
	// State 是此刻的状态（整份，每次推送都是新解的，界面拿着不会被后来的推送改掉；只读）。
	State State
	// Connected：连接在、核心那边这个对话开着。断线重连期间为假，界面照旧显示，但操作会失败。
	Connected bool
	// Items 是这期间条目的变化，按到达的先后，界面依次套上；有整份的那次时它是第一项，之前的都已丢掉。
	Items []ItemsChange
	// Turns 是这期间到的 conv.turn，按到达的先后。
	Turns []Turn
}

// ItemsChange 是条目的一次变化（conv.items）：Full 时 Items 是整份，否则从 At 起去掉 Remove 项、换成 Items。
type ItemsChange struct {
	Full   bool         `json:"full"`
	At     int          `json:"at"`
	Remove int          `json:"remove"`
	Items  []items.Item `json:"items"`
}

// Turn 是 conv.turn：一轮的起止，界面据此提醒（比如回答完了而窗口不在前台时发通知）。
type Turn struct {
	// Phase 是 TurnStarted 或 TurnFinished。
	Phase string `json:"phase"`
	// Outcome 是结束时的结局：OutcomeDone、OutcomeStopped、OutcomeFailed、OutcomeInterrupted。
	Outcome string `json:"outcome"`
	// Text 是结局的一句话（已完成、已停止……）。
	Text string `json:"text"`
}

// conv.turn 的 phase 和 outcome。
const (
	TurnStarted  = "started"
	TurnFinished = "finished"

	OutcomeDone        = "done"
	OutcomeStopped     = "stopped"
	OutcomeFailed      = "failed"
	OutcomeInterrupted = "interrupted" // 进程意外没了
)

func newConversation(client *Client, name string, spec Spec) *Conversation {
	return &Conversation{client: client, name: name, spec: spec, fields: map[string]json.RawMessage{}}
}

// Name 是这个对话在这条连接上的名字（conv）。
func (c *Conversation) Name() string { return c.name }

// Take 取走上次取走以后的变化。Items、Turns 只有新到的；State、Connected 总是此刻的。
func (c *Conversation) Take() Update {
	c.mu.Lock()
	defer c.mu.Unlock()
	u := c.pending
	c.pending = Update{}
	u.State, u.Connected = c.state, c.connected
	return u
}

// Items 是此刻的整份条目（副本）。界面和推送对不上时用它整份重来。
func (c *Conversation) Items() []items.Item {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.items)
}

// open 发 conv.open。重连后再发一次时，接着的是此前收到的会话号（新对话第一轮以后才有）。
func (c *Conversation) open(ctx context.Context) error {
	c.mu.Lock()
	spec := c.spec
	if c.state.Session != "" {
		spec.Session = c.state.Session
	}
	key := ""
	if c.forked && spec.Session == "" {
		key = c.state.Key
	}
	c.mu.Unlock()
	params := map[string]any{"conv": c.name, "options": spec.Options}
	putNonEmpty(params, "key", key)
	putNonEmpty(params, "session", spec.Session)
	putNonEmpty(params, "file", spec.File)
	putNonEmpty(params, "cwd", spec.Cwd)
	putNonEmpty(params, "title", spec.Title)
	if _, err := c.client.link.Call(ctx, "conv.open", params); err != nil {
		return err
	}
	c.mu.Lock()
	c.connected, c.opened = true, true
	c.mu.Unlock()
	c.client.notify()
	return nil
}

func putNonEmpty(m map[string]any, key, value string) {
	if value != "" {
		m[key] = value
	}
}

// disconnected 标成没连上，返回它打开成过没有（重连后要不要重新打开）。
func (c *Conversation) disconnected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.connected = false
	return c.opened
}

// apply 合并一条推送，有变化时返回 true。
func (c *Conversation) apply(kind string, line []byte) bool {
	switch kind {
	case "conv.state":
		return c.applyState(line)
	case "conv.items":
		return c.applyItems(line)
	case "conv.turn":
		var t Turn
		if json.Unmarshal(line, &t) != nil {
			return false
		}
		c.mu.Lock()
		c.pending.Turns = append(c.pending.Turns, t)
		c.mu.Unlock()
		return true
	}
	// conv.mcp、conv.reply、conv.phone_cancel 只发给声明了手机能力的客户端；桌面版不声明，收不到。
	return false
}

func (c *Conversation) applyState(line []byte) bool {
	var msg struct {
		Full bool `json:"full"`
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(line, &msg) != nil || json.Unmarshal(line, &fields) != nil {
		return false
	}
	for _, k := range []string{"type", "conv", "full"} {
		delete(fields, k)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if msg.Full {
		c.fields = fields
	} else {
		maps.Copy(c.fields, fields)
	}
	state, err := decodeState(c.fields)
	if err != nil {
		log.Printf("chat: %s 的 conv.state 解不开：%v", c.name, err)
		return false
	}
	c.state = state
	return true
}

func (c *Conversation) applyItems(line []byte) bool {
	var msg ItemsChange
	if err := json.Unmarshal(line, &msg); err != nil {
		log.Printf("chat: %s 的 conv.items 解不开：%v", c.name, err)
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if msg.Full {
		c.items = msg.Items
		c.pending.Items = []ItemsChange{msg}
		return true
	}
	next, err := items.Splice(c.items, msg.At, msg.Remove, msg.Items)
	if err != nil {
		// 和核心那份对不上了（不该发生）：重新打开，核心再推一份整份的
		log.Printf("chat: %s：%v，重新打开", c.name, err)
		go c.resync()
		return false
	}
	c.items = next
	c.pending.Items = append(c.pending.Items, msg)
	return true
}

// resync 关掉再打开这个对话，拿到整份的状态和条目。
func (c *Conversation) resync() {
	ctx, cancel := context.WithTimeout(context.Background(), OpenTimeout)
	defer cancel()
	_, _ = c.client.link.Call(ctx, "conv.close", map[string]any{"conv": c.name})
	if err := c.open(ctx); err != nil {
		log.Printf("chat: %s 重新打开失败：%v", c.name, err)
		c.disconnected()
		c.client.notify()
	}
}

// Session 是这个对话此刻的会话号：接着旧会话时是它；新对话第一轮以后才有，之前为空。
func (c *Conversation) Session() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state.Session != "" {
		return c.state.Session
	}
	return c.spec.Session
}

// State 是此刻的状态（同 Take 给的那份，不取走变化）。可以在任何 goroutine 里调。
func (c *Conversation) State() State {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.state
}
