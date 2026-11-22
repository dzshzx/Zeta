package conv

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

// releaseAfter：没人拿着、又闲着的对话过这么久断开背后的进程（看门狗约十分钟后收掉它），也从核心里去掉。
const releaseAfter = 30 * time.Second

// Manager 是核心进程里的全部对话。客户端用自己起的名字（请求里的 conv）指一个对话，只在它那条连接里有效；
// 几个客户端打开同一个会话号拿到的是同一个对话。
type Manager struct {
	deps Deps
	send Send

	mu        sync.Mutex
	seq       int
	all       map[*Conversation]bool
	bySession map[string]*Conversation
	byName    map[int]map[string]*Conversation // 客户端 → 它起的名字 → 对话
}

// NewManager 建一个空的对话表，并开始定时收掉没人拿着的闲对话。
func NewManager(deps Deps, send Send) *Manager {
	m := &Manager{deps: deps, send: send, all: map[*Conversation]bool{}, bySession: map[string]*Conversation{},
		byName: map[int]map[string]*Conversation{}}
	m.deps.SessionChanged = m.sessionChanged
	go func() {
		for range time.Tick(5 * time.Second) {
			m.sweep()
		}
	}()
	return m
}

// request 是 conv.* 请求的参数，和 id、method 平级（见 docs/core.md）。
type request struct {
	Conv      string              `json:"conv"`
	Session   string              `json:"session"`
	File      string              `json:"file"`
	Cwd       string              `json:"cwd"`
	Title     string              `json:"title"`
	Options   *Options            `json:"options"`
	UUID      string              `json:"uuid"`
	Recall    bool                `json:"recall"`
	RequestID string              `json:"request_id"`
	Reply     json.RawMessage     `json:"reply"`
	Action    string              `json:"action"`
	Values    map[string][]string `json:"values"`
	Value     *string             `json:"value"`
	Task      string              `json:"task"`
	Response  json.RawMessage     `json:"response"`
	Model     string              `json:"model"`
	Effort    string              `json:"effort"`
	Hint      string              `json:"hint"`
	Message
}

var errUnknownConv = errors.New("没有打开这个对话")

// Request 处理客户端 client 的一个 conv.* 请求，返回应答的 result。
func (m *Manager) Request(client int, method string, line []byte) (any, error) {
	var r request
	if err := json.Unmarshal(line, &r); err != nil {
		return nil, err
	}
	if r.Conv == "" {
		return nil, errors.New("缺 conv")
	}
	if method == "conv.open" {
		return nil, m.open(client, r)
	}
	c := m.find(client, r.Conv)
	if c == nil {
		return nil, errUnknownConv
	}
	if method == "conv.close" {
		m.release(client, r.Conv, c)
		return nil, nil
	}
	if method == "conv.task_output" {
		v, err := c.Call(func() (any, error) { return c.taskOutput(r.Task), nil })
		if err != nil {
			return nil, err
		}
		select {
		case out := <-v.(<-chan any):
			return out, nil
		case <-time.After(30 * time.Second):
			return nil, errors.New("Claude Code 没有回应")
		}
	}
	handler, ok := handlers[method]
	if !ok {
		return nil, errors.New("不认识的方法 " + method)
	}
	return c.Call(func() (any, error) { return handler(c, client, r) })
}

// handlers 是各个只改一个对话的请求。
var handlers = map[string]func(c *Conversation, client int, r request) (any, error){
	"conv.options": func(c *Conversation, client int, r request) (any, error) {
		if r.Options != nil {
			c.options[client] = *r.Options
		}
		return nil, nil
	},
	"conv.send":      func(c *Conversation, client int, r request) (any, error) { return nil, c.sendMessage(client, r.Message) },
	"conv.interrupt": func(c *Conversation, _ int, _ request) (any, error) { c.interrupt(); return nil, nil },
	"conv.steer":     func(c *Conversation, _ int, _ request) (any, error) { c.steer(); return nil, nil },
	"conv.cancel": func(c *Conversation, _ int, r request) (any, error) {
		c.cancelQueued(r.UUID, r.Recall)
		return nil, nil
	},
	"conv.take_recalled": func(c *Conversation, _ int, _ request) (any, error) { return c.takeRecalled(), nil },
	"conv.answer": func(c *Conversation, _ int, r request) (any, error) {
		return nil, c.answer(r.RequestID, r.Reply)
	},
	"conv.elicit": func(c *Conversation, _ int, r request) (any, error) {
		problems, err := c.elicit(r.RequestID, r.Action, r.Values)
		if problems != nil {
			return map[string]any{"errors": problems}, err
		}
		return nil, err
	},
	"conv.model":  func(c *Conversation, _ int, r request) (any, error) { c.chooseModel(value(r)); return nil, nil },
	"conv.effort": func(c *Conversation, _ int, r request) (any, error) { c.chooseEffort(value(r)); return nil, nil },
	"conv.mode":   func(c *Conversation, _ int, r request) (any, error) { c.chooseMode(value(r)); return nil, nil },
	"conv.preset": func(c *Conversation, _ int, r request) (any, error) {
		c.preset(r.Model, r.Effort, r.Hint)
		return nil, nil
	},
	"conv.compact": func(c *Conversation, client int, _ request) (any, error) { return nil, c.compact(client) },
	"conv.stop_task": func(c *Conversation, _ int, r request) (any, error) {
		c.stopTask(r.Task)
		return nil, nil
	},
	"conv.end": func(c *Conversation, _ int, _ request) (any, error) { c.closeProcess(true); return nil, nil },
	"conv.mcp_reply": func(c *Conversation, _ int, r request) (any, error) {
		c.mcpReply(r.RequestID, r.Response)
		return nil, nil
	},
}

func value(r request) string {
	if r.Value == nil {
		return ""
	}
	return *r.Value
}

// open 打开一个对话：有会话号且核心里已有这个会话时拿到的就是它，否则新建（有会话记录时载入历史）。
func (m *Manager) open(client int, r request) error {
	m.mu.Lock()
	if m.byName[client][r.Conv] != nil {
		m.mu.Unlock()
		return errors.New("这条连接已经有叫 " + r.Conv + " 的对话")
	}
	c := m.bySession[r.Session]
	if r.Session == "" || c == nil {
		m.seq++
		c = New("c"+strconv.Itoa(m.seq), Spec{Session: r.Session, File: r.File, Cwd: r.Cwd, Title: r.Title}, m.deps, m.send)
		m.all[c] = true
		if r.Session != "" {
			m.bySession[r.Session] = c
		}
	}
	if m.byName[client] == nil {
		m.byName[client] = map[string]*Conversation{}
	}
	m.byName[client][r.Conv] = c
	m.mu.Unlock()
	var opts Options
	if r.Options != nil {
		opts = *r.Options
	}
	return c.Hold(client, r.Conv, opts)
}

func (m *Manager) find(client int, name string) *Conversation {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.byName[client][name]
}

// release：客户端放开它叫 name 的对话。
func (m *Manager) release(client int, name string, c *Conversation) {
	m.mu.Lock()
	delete(m.byName[client], name)
	m.mu.Unlock()
	_, _ = c.Call(func() (any, error) {
		delete(c.holders, client)
		delete(c.options, client)
		return nil, nil
	})
}

// Gone：客户端断开了，放开它拿着的全部对话。
func (m *Manager) Gone(client int) {
	m.mu.Lock()
	held := m.byName[client]
	delete(m.byName, client)
	m.mu.Unlock()
	for name, c := range held {
		m.release(client, name, c)
	}
}

// Idle：核心里没有对话了（没人拿着、闲着的都已收掉）。
func (m *Manager) Idle() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.all) == 0
}

// sweep 收掉没人拿着、闲着满 releaseAfter 的对话：断开背后的进程，从核心里去掉。
func (m *Manager) sweep() {
	m.mu.Lock()
	all := make([]*Conversation, 0, len(m.all))
	for c := range m.all {
		all = append(all, c)
	}
	m.mu.Unlock()
	now := time.Now()
	for _, c := range all {
		v, err := c.Call(func() (any, error) { return c.releasable(now), nil })
		if err != nil || v != true {
			continue
		}
		m.mu.Lock()
		delete(m.all, c)
		if m.bySession[c.session] == c {
			delete(m.bySession, c.session)
		}
		m.mu.Unlock()
		c.close()
	}
}

// releasable：没人拿着、又闲着满 releaseAfter，断开进程后返回 true。
func (c *Conversation) releasable(now time.Time) bool {
	if len(c.holders) > 0 || c.active() {
		c.idleFrom = time.Time{}
		return false
	}
	if c.idleFrom.IsZero() {
		c.idleFrom = now
	}
	if now.Sub(c.idleFrom) < releaseAfter {
		return false
	}
	c.closeProcess(false)
	return true
}

// active：在回答、在等用户回应，或者还有后台任务在跑。
func (c *Conversation) active() bool {
	return c.busy || len(c.prompts) > 0 || c.backgroundTasks > 0
}

// sessionChanged：对话拿到了（或换了）会话号，按会话号找对话的地方跟着换。
func (m *Manager) sessionChanged(c *Conversation, before, after string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if before != "" && m.bySession[before] == c {
		delete(m.bySession, before)
	}
	if strings.TrimSpace(after) != "" {
		m.bySession[after] = c
	}
}
