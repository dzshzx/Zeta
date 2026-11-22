package chat

import (
	"context"
	"encoding/json"
	"log"
	"maps"
	"slices"
	"sync"
)

// Conversation 是 Client 上打开的一个对话。View 给界面读；别的方法把用户的操作发给核心，
// 结果照样经推送回来（发出的消息、改了的模式都从 conv.state、conv.items 里看到）。
type Conversation struct {
	client *Client
	name   string
	spec   Spec

	mu sync.Mutex
	// fields 是核心推来的 conv.state 合并到现在的各字段，state 是它解码成的样子。
	fields    map[string]json.RawMessage
	state     State
	items     []json.RawMessage
	connected bool
	closed    bool
	turns     []Turn
}

// View 是对话此刻的样子，界面照着画。各切片是副本，界面拿着不会被后来的推送改掉。
type View struct {
	State State
	// Items 是聊天条目，每项是核心推来的 JSON（格式见 helper/transcript/items.go），由 chat/items 解码。
	Items []json.RawMessage
	// Connected：连接在、核心那边这个对话开着。断线重连期间为假，界面可以照旧显示，但操作会失败。
	Connected bool
	// Closed：核心说这个对话没了（conv.closed），界面丢掉它。
	Closed bool
}

// Turn 是 conv.turn：一轮的起止，界面据此提醒（比如回答完了响一声）。
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

// View 返回对话此刻的样子。
func (c *Conversation) View() View {
	c.mu.Lock()
	defer c.mu.Unlock()
	return View{State: c.state.clone(), Items: slices.Clone(c.items), Connected: c.connected, Closed: c.closed}
}

// TakeTurns 取走上次取走以后到的 conv.turn，按到达顺序。
func (c *Conversation) TakeTurns() []Turn {
	c.mu.Lock()
	defer c.mu.Unlock()
	t := c.turns
	c.turns = nil
	return t
}

// open 发 conv.open。重连后再发一次时，接着的是此前收到的会话号（新对话第一轮以后才有）。
func (c *Conversation) open(ctx context.Context) error {
	c.mu.Lock()
	spec := c.spec
	if c.state.Session != "" {
		spec.Session = c.state.Session
	}
	c.mu.Unlock()
	params := map[string]any{"conv": c.name, "options": spec.Options}
	putNonEmpty(params, "session", spec.Session)
	putNonEmpty(params, "file", spec.File)
	putNonEmpty(params, "cwd", spec.Cwd)
	putNonEmpty(params, "title", spec.Title)
	if _, err := c.client.link.Call(ctx, "conv.open", params); err != nil {
		return err
	}
	c.setConnected(true)
	c.client.notify()
	return nil
}

func putNonEmpty(m map[string]any, key, value string) {
	if value != "" {
		m[key] = value
	}
}

func (c *Conversation) setConnected(v bool) {
	c.mu.Lock()
	c.connected = v
	c.mu.Unlock()
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
		c.turns = append(c.turns, t)
		c.mu.Unlock()
		return true
	case "conv.closed":
		c.mu.Lock()
		c.closed, c.connected = true, false
		c.mu.Unlock()
		c.client.forget(c)
		return true
	}
	// conv.mcp 是手机工具的调用，只发给声明了手机能力的客户端；桌面版不声明，收不到。
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

// itemsMsg 是 conv.items：Full 时 Items 是整份，否则从 At 起去掉 Remove 项、换成 Items。
type itemsMsg struct {
	Full   bool              `json:"full"`
	At     int               `json:"at"`
	Remove int               `json:"remove"`
	Items  []json.RawMessage `json:"items"`
}

func (c *Conversation) applyItems(line []byte) bool {
	var msg itemsMsg
	if json.Unmarshal(line, &msg) != nil {
		return false
	}
	c.mu.Lock()
	if msg.Full {
		c.items = msg.Items
		c.mu.Unlock()
		return true
	}
	if msg.At < 0 || msg.Remove < 0 || msg.At+msg.Remove > len(c.items) {
		// 和核心那份对不上了（不该发生）：重新打开，核心再推一份整份的。
		log.Printf("chat: %s 的 conv.items 越界（at %d、remove %d、现有 %d 项），重新打开", c.name, msg.At, msg.Remove, len(c.items))
		c.mu.Unlock()
		go c.resync()
		return false
	}
	c.items = slices.Replace(c.items, msg.At, msg.At+msg.Remove, msg.Items...)
	c.mu.Unlock()
	return true
}

// resync 关掉再打开这个对话，拿到整份的状态和条目。
func (c *Conversation) resync() {
	ctx := context.Background()
	_, _ = c.client.link.Call(ctx, "conv.close", map[string]any{"conv": c.name})
	if err := c.open(ctx); err != nil {
		log.Printf("chat: %s 重新打开失败：%v", c.name, err)
		c.setConnected(false)
		c.client.notify()
	}
}
