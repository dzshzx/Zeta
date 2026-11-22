// Package chat 是桌面版的对话：接主机上 zeta-helper serve 的对话协议（conv.*，见 docs/core.md「对话」），
// 把核心推来的状态、条目、一轮的起止整理成界面要的样子，并把用户的操作（发消息、停止、插话、回应确认等）
// 发回去。对话的逻辑都在核心里，这里不另存一份对话状态，只照推送合并。
//
// 一个 Client 对应一台主机上的一条 serve 连接。连接本身（SSH 通道、ping、重连）由调用方做：它实现 Link，
// 把收到的每一行推送交给 Client.Handle；断线时调 Disconnected，重连上以后调 Reconnected，让各对话重新打开。
package chat

import (
	"context"
	"encoding/json"
	"strconv"
	"sync"
	"time"
)

// Link 是到 serve 的连接。
type Link interface {
	// Call 发一个请求：params 的各项和 id、method 平级写出，等到对应的 zeta_reply。
	// ok 时返回 result（没有时为 nil），否则返回带 serve 给的原因的错误。
	Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)
}

// OpenTimeout 是打开一个对话最多等多久（核心可能要先拉起来、载入历史）。
const OpenTimeout = 30 * time.Second

// Client 管一条 serve 连接上打开的各个对话。方法可以在任何 goroutine 里调。
type Client struct {
	link   Link
	notify func()

	mu    sync.Mutex
	seq   int
	convs map[string]*Conversation
}

// NewClient 建一个 Client。notify 在任何对话有了变化以后调（在收推送或发请求的 goroutine 里），
// 界面据此在主线程上 Take、重画；它不能阻塞，也不能回头调 Client 的方法等结果。
func NewClient(link Link, notify func()) *Client {
	if notify == nil {
		notify = func() {}
	}
	return &Client{link: link, notify: notify, convs: map[string]*Conversation{}}
}

// Spec 是打开对话时给核心的。
type Spec struct {
	// Session 是接着的会话号，新对话为空。
	Session string
	// File 是它的会话记录在主机上的路径，载入历史用。
	File  string
	Cwd   string
	Title string
	// Options 是起 Claude Code 进程用的设置，打开以后可以用 SetOptions 改。
	Options Options
}

// Options 是 conv.open、conv.options 的 options（见 docs/core.md）。
type Options struct {
	// Env 是只给这个 Claude Code 进程的环境变量（比如自动压缩）。
	Env map[string]string `json:"env,omitempty"`
	// SystemPrompt 是 --append-system-prompt。
	SystemPrompt string `json:"system_prompt,omitempty"`
	// AutoTitle：一轮以后请 Claude Code 起标题。
	AutoTitle bool `json:"auto_title,omitempty"`
	// CompactPercent 是自动压缩的百分比，用量条用；0 是没设。
	CompactPercent int `json:"compact_percent,omitempty"`
	// Desk：这台是远程主机。新会话拿到会话号时、之后每次起进程时，核心在那边 tmux 会话「zeta」里补开它的窗口（docs/core.md）。
	Desk bool `json:"desk,omitempty"`
}

// Open 打开一个对话。核心里已有这个会话时拿到的就是它（别的客户端也开着时，大家看到的是同一份）。
// 返回时第一份整份的状态和条目通常已经到了。
func (c *Client) Open(ctx context.Context, spec Spec) (*Conversation, error) {
	c.mu.Lock()
	c.seq++
	conv := newConversation(c, "w"+strconv.Itoa(c.seq), spec)
	// 先登记再发：核心可能在应答之前就推来这个对话的整份状态。
	c.convs[conv.name] = conv
	c.mu.Unlock()
	if err := conv.open(ctx); err != nil {
		c.forget(conv)
		return nil, err
	}
	return conv, nil
}

// Conversations 是现在打开着的对话，按打开的先后。
func (c *Client) Conversations() []*Conversation {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]*Conversation, 0, len(c.convs))
	for i := 1; i <= c.seq; i++ {
		if conv := c.convs["w"+strconv.Itoa(i)]; conv != nil {
			out = append(out, conv)
		}
	}
	return out
}

// Disconnected：连接断了。各对话标成没连上，等 Reconnected。
func (c *Client) Disconnected() {
	for _, conv := range c.Conversations() {
		conv.disconnected()
	}
	c.notify()
}

// Reconnected：连接重新连上了（新的 serve 进程）。打开成过的对话按原来的名字重新打开，核心推来整份状态和条目；
// 连接断开期间核心也可能重启过，这样都能接上。还在第一次打开的不动：它自己的 conv.open 正等着这条连接。
// 打不开的对话留着、标成没连上，返回遇到的第一个错误。
func (c *Client) Reconnected(ctx context.Context) error {
	var first error
	for _, conv := range c.Conversations() {
		if !conv.disconnected() {
			continue
		}
		if err := conv.open(ctx); err != nil && first == nil {
			first = err
		}
	}
	return first
}

func (c *Client) find(name string) *Conversation {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.convs[name]
}

func (c *Client) forget(conv *Conversation) {
	c.mu.Lock()
	if c.convs[conv.name] == conv {
		delete(c.convs, conv.name)
	}
	c.mu.Unlock()
}

// push 是 serve 推来的一行里这里要看的部分。
type push struct {
	Type string `json:"type"`
	Conv string `json:"conv"`
}

// Handle 处理 serve 推来的一行（不是 zeta_reply 的那些）。是对话的推送时返回 true，别的（会话列表等）返回 false，
// 由调用方交给别处。推送里的对话已经关掉时照样返回 true，什么也不做。
func (c *Client) Handle(line []byte) bool {
	var p push
	if json.Unmarshal(line, &p) != nil || !isConvPush(p.Type) {
		return false
	}
	conv := c.find(p.Conv)
	if conv == nil {
		return true
	}
	if conv.apply(p.Type, line) {
		c.notify()
	}
	return true
}

func isConvPush(t string) bool {
	switch t {
	case "conv.state", "conv.items", "conv.turn", "conv.subagent", "conv.mcp", "conv.reply", "conv.phone_cancel":
		return true
	}
	return false
}
