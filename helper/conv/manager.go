package conv

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// releaseAfter：没人拿着、又闲着的对话过这么久断开背后的进程（看门狗约十分钟后收掉它），也从核心里去掉。
// 还没发过消息的分支没有会话号、会话记录里也没有它，收掉就没了：留 forkReleaseAfter，客户端断线重连时按 key 重新打开。
const (
	releaseAfter     = 30 * time.Second
	forkReleaseAfter = 10 * time.Minute
)

var errGone = codedError{"conversation_gone", locales.Err("errConvGone")}

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

	activity         map[*Conversation]sessionActivity // 有会话号、有东西可报的对话的忙闲
	activityWatchers map[int]bool                      // 订阅了 conv.activity 的客户端
}

// NewManager 建一个空的对话表，并开始定时收掉没人拿着的闲对话。
func NewManager(deps Deps, send Send) *Manager {
	m := &Manager{deps: deps, send: send, all: map[*Conversation]bool{}, bySession: map[string]*Conversation{},
		byName: map[int]map[string]*Conversation{}, activity: map[*Conversation]sessionActivity{},
		activityWatchers: map[int]bool{}}
	m.deps.SessionChanged = m.sessionChanged
	m.deps.ActivityChanged = m.activityChanged
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
	Choices   *Choices            `json:"choices"` // conv.open：客户端替还没有会话号的对话记着的选择（见 Choices）
	UUID      string              `json:"uuid"`
	Recall    bool                `json:"recall"`
	Rest      bool                `json:"rest"`
	Watch     bool                `json:"watch"`
	Key       string              `json:"key"`   // conv.open：重新打开核心里标识为 key 的对话（conv.state 的 key）；conv.image：图在哪个对话里
	Image     string              `json:"image"` // conv.image：图的 key
	RequestID string              `json:"request_id"`
	Reply     prompt.Reply        `json:"reply"`
	Action    prompt.ElicitAction `json:"action"`
	Values    map[string][]string `json:"values"`
	Value     *string             `json:"value"`
	Task      string              `json:"task"`
	Response  json.RawMessage     `json:"response"`
	Model     string              `json:"model"`
	Effort    string              `json:"effort"`
	Hint      string              `json:"hint"`
	Target    string              `json:"target"` // 条目 id：conv.rewrite、conv.drop、conv.code_changes、conv.restore_code 指的那条用户消息
	Drop      bool                `json:"drop"`
	As        string              `json:"as"`          // conv.fork：新对话在这个客户端里的名字
	Last      string              `json:"last"`        // conv.fork：从这一项所在那一轮之后分支
	ToolUseID string              `json:"tool_use_id"` // conv.subagent：发起这个子代理的 Agent 调用
	Resend    bool                `json:"resend"`      // conv.rewrite、conv.restore_code：照 target 原样重发
	Message
}

var errUnknownConv = locales.Err("errConvNotOpen")

// Request 处理客户端 client 的一个 conv.* 请求，等到应答的 result 为止。
func (m *Manager) Request(client int, method string, line []byte) (any, error) {
	v, err := m.Start(client, method, line)
	if p, ok := v.(Pending); ok && err == nil {
		return p()
	}
	return v, err
}

// Start 同 Request，只是要等的请求（等 Claude Code 应答的、载入更早历史、从别处接过会话）在请求的顺序里发出去后返回 Pending，
// 不在这里等：服务端一条连接的请求是一个一个处理的，在这里等会挡住这个客户端后面的请求（比如等后台任务输出时发不了消息）。
func (m *Manager) Start(client int, method string, line []byte) (any, error) {
	var r request
	if err := json.Unmarshal(line, &r); err != nil {
		return nil, err
	}
	if method == "conv.activity" {
		// 不带 watch 时是订阅：客户端可以把它和 sessions.watch 一样当作只发方法名的订阅
		watch := watchParam(line)
		m.watchActivity(client, watch == nil || *watch)
		return nil, nil
	}
	if method == "conv.image" {
		return m.image(r)
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
	if method == "conv.fork" {
		return nil, m.fork(client, r, c)
	}
	if v, handled, err := m.startSpecial(c, client, method, r, line); handled {
		return v, err
	}
	if ask, ok := asks[method]; ok {
		return startAsk(c, func() <-chan answer { return ask(c, client, r) })
	}
	handler, ok := handlers[method]
	if !ok {
		return nil, errors.New("不认识的方法 " + method)
	}
	return c.Call(func() (any, error) { return handler(c, client, r) })
}

// watchParam 是请求里的 watch：没写时为 nil。
func watchParam(line []byte) *bool {
	var p struct {
		Watch *bool `json:"watch"`
	}
	_ = json.Unmarshal(line, &p)
	return p.Watch
}

// startSpecial 处理不按 handlers、asks 的一般写法做的请求：要等别的事做完才有结果的，和按参数另走一条路的
// （带 watch 的 conv.task_output、带 resend 的 conv.rewrite 和 conv.restore_code）。不是这些时 handled 为假。
func (m *Manager) startSpecial(c *Conversation, client int, method string, r request, line []byte) (v any, handled bool, err error) {
	switch method {
	case "conv.earlier":
		v, err = startEarlier(c, r.Rest)
	case "conv.occupant":
		v, err = c.Call(func() (any, error) { return c.session, nil })
		if err == nil {
			session := v.(string)
			v = Pending(func() (any, error) { return m.occupancy(session), nil })
		}
	case "conv.handoff":
		v, err = m.handOff(c)
	case "conv.task_output":
		watch := watchParam(line)
		if watch == nil {
			return nil, false, nil
		}
		v, err = c.Call(func() (any, error) { return nil, c.watchTaskOutput(client, r.Task, *watch) })
	case "conv.rewrite", "conv.restore_code":
		if !r.Resend {
			return nil, false, nil
		}
		v, err = m.resend(c, client, method, r)
	default:
		return nil, false, nil
	}
	return v, true, err
}

// startEarlier 开始载入更早的一页（conv.earlier），接上以后 Pending 才给出结果。
func startEarlier(c *Conversation, rest bool) (any, error) {
	v, err := c.Call(func() (any, error) { return c.loadEarlier(rest) })
	if err != nil {
		return nil, err
	}
	return Pending(func() (any, error) {
		select {
		case err := <-v.(<-chan error):
			return nil, err
		case <-c.quit:
			return nil, errClosed
		}
	}), nil
}

// startAsk 在对话的 goroutine 上发出要等 Claude Code 应答的请求，Pending 等应答，最多 30 秒。
func startAsk(c *Conversation, ask func() <-chan answer) (any, error) {
	v, err := c.Call(func() (any, error) { return ask(), nil })
	if err != nil {
		return nil, err
	}
	return Pending(func() (any, error) {
		select {
		case a := <-v.(<-chan answer):
			return a.v, a.err
		case <-c.quit:
			return nil, errClosed
		case <-time.After(30 * time.Second):
			return nil, locales.Err("errClaudeNoAnswer")
		}
	}), nil
}

// answer 是要等 Claude Code 应答的请求的结果。
type answer struct {
	v   any
	err error
}

// asks 是要等 Claude Code 应答才回的请求：在对话的 goroutine 上发出控制请求，返回等应答的 channel，在外面等，最多 30 秒。
var asks = map[string]func(c *Conversation, client int, r request) <-chan answer{
	"conv.task_output":  func(c *Conversation, _ int, r request) <-chan answer { return c.taskOutput(r.Task) },
	"conv.quota":        func(c *Conversation, _ int, _ request) <-chan answer { return c.queryQuota() },
	"conv.code_changes": func(c *Conversation, client int, r request) <-chan answer { return c.codeChanges(client, r.Target) },
}

// handlers 是各个只改一个对话的请求。
var handlers = map[string]func(c *Conversation, client int, r request) (any, error){
	"conv.options": func(c *Conversation, client int, r request) (any, error) {
		if r.Options != nil {
			c.options[client] = *r.Options
		}
		return nil, nil
	},
	"conv.send": func(c *Conversation, client int, r request) (any, error) {
		return nil, c.sendMessage(client, r.Message)
	},
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
	"conv.elicit": func(c *Conversation, client int, r request) (any, error) {
		problems, err := c.elicit(client, r.RequestID, r.Action, r.Values)
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
	"conv.prepare": func(c *Conversation, client int, _ request) (any, error) { c.prepare(client); return nil, nil },
	"conv.stop_task": func(c *Conversation, _ int, r request) (any, error) {
		c.stopTask(r.Task)
		return nil, nil
	},
	"conv.rewrite": func(c *Conversation, client int, r request) (any, error) {
		return nil, c.rewrite(client, r.Target, r.Message)
	},
	"conv.drop": func(c *Conversation, client int, r request) (any, error) { return nil, c.drop(client, r.Target) },
	"conv.restore_code": func(c *Conversation, client int, r request) (any, error) {
		return nil, c.restoreCode(client, r.Target, r.Drop, r.Message)
	},
	"conv.title": func(c *Conversation, _ int, r request) (any, error) {
		_, err := c.rename(r.Title)
		return nil, err
	},
	"conv.end": func(c *Conversation, _ int, _ request) (any, error) { c.end(); return nil, nil },
	"conv.mcp_reply": func(c *Conversation, _ int, r request) (any, error) {
		c.mcpReply(r.RequestID, r.Response)
		return nil, nil
	},
	"conv.subagent": func(c *Conversation, client int, r request) (any, error) {
		return nil, c.watchSubagent(client, r.ToolUseID, r.Watch)
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
	// 只给会话号时核心自己找它的会话记录，客户端不用拼路径
	if r.File == "" && r.Session != "" && m.deps.SessionFile != nil {
		r.File = m.deps.SessionFile(r.Session)
	}
	// 核心里还没有这个会话、或已有的对话背后没有自己的进程时要看别处占用。接着自己进程的不用看：终端里 --resume 接回时
	// SessionStart hook 让这边的进程退出，核心随之锁上（handedAway）；没进程的（结束了、还没发过消息）不知道别处后来又开了它
	m.mu.Lock()
	existing := m.bySession[r.Session]
	m.mu.Unlock()
	var occupant sessionproto.Occupancy
	if existing == nil || existing.withoutProcess() {
		occupant = m.occupancy(r.Session)
	}
	blocked := occupant.Status != nil
	m.mu.Lock()
	if m.byName[client][r.Conv] != nil {
		m.mu.Unlock()
		return errors.New("这条连接已经有叫 " + r.Conv + " 的对话")
	}
	c := m.bySession[r.Session]
	if r.Key != "" {
		c = m.byKey(r.Key)
		if c == nil && r.Session == "" {
			m.mu.Unlock()
			return errGone
		}
		if c == nil {
			c = m.bySession[r.Session]
		}
	}
	if c == nil {
		m.seq++
		c = New("c"+strconv.Itoa(m.seq), Spec{Session: r.Session, File: r.File, Cwd: r.Cwd, Title: r.Title, Blocked: blocked, Occupant: &occupant}, m.deps, m.send)
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
	if blocked && c == existing {
		if err := c.blockReopened(occupant); err != nil {
			return err
		}
	}
	if r.Choices != nil {
		if _, err := c.Call(func() (any, error) { c.restoreChoices(*r.Choices); return nil, nil }); err != nil {
			return err
		}
	}
	var opts Options
	if r.Options != nil {
		opts = *r.Options
	}
	return c.Hold(client, r.Conv, opts)
}

// byKey 是标识为 key 的对话（conv.state 的 key）；持着 m.mu 调用。
func (m *Manager) byKey(key string) *Conversation {
	for c := range m.all {
		if c.ID == key {
			return c
		}
	}
	return nil
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
		c.unwatchSubagents(client)
		c.unwatchTaskOutputs(client)
		return nil, nil
	})
}

// Gone：客户端断开了，放开它拿着的全部对话。
func (m *Manager) Gone(client int) {
	m.mu.Lock()
	held := m.byName[client]
	delete(m.byName, client)
	delete(m.activityWatchers, client)
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
		if _, ok := m.activity[c]; ok {
			delete(m.activity, c)
			m.pushActivity()
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
	after := releaseAfter
	if c.forkSource != "" && c.session == "" {
		after = forkReleaseAfter
	}
	if now.Sub(c.idleFrom) < after {
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
