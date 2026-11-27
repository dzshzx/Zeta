// Package conv 是核心进程里的对话：一个对话的状态（聊天条目、这一轮、排队的消息、等用户回应的请求、后台任务、用量），
// 背后的 Claude Code 进程（经 zeta_host.sh 托管），和推给客户端的消息。协议见 docs/core.md「对话」一节。
//
// 每个对话的状态只在它自己的 goroutine 里改：外面（客户端的请求、托管进程的输出、定时器）都经 do 排进来，按到达顺序处理。
package conv

import (
	"encoding/json"
	"maps"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/helper/transcript"
	"github.com/dzshzx/Zeta/helper/usage"
)

// publishDelay：托管进程的输出攒多久推一次。流式回答时每推一次客户端就要重排一次版，
// 回答的字由客户端的逐字显现平滑带出来，合并得粗一点看不出来；远程主机上也省流量。
const publishDelay = 100 * time.Millisecond

// Send 把一条消息发给一个客户端（编号由核心定）。要在调用时就编成 JSON：消息里的条目之后还会在对话的 goroutine 里改。
type Send func(client int, msg any)

// Conversation 是一个对话。
type Conversation struct {
	ID string

	deps Deps
	send Send
	in   chan func()
	quit chan struct{}
	once sync.Once

	cwd     string
	title   string
	session string
	file    string // 会话记录，载入历史用

	r          *transcript.Reducer
	published  publishedItems
	lastState  map[string]json.RawMessage
	dirty      bool
	flushTimer *time.Timer

	busy               bool
	status             locales.Msg             // 等待指示旁的一句（正在压缩、正在重试），没有时 Key 为空；推送见 stateFields
	blocked            bool                    // 会话正被 Zeta 之外的 Claude Code 占着
	occupant           *sessionproto.Occupancy // blocked 时占着会话的那个进程的样子，还没查到时为 nil（occupant.go）
	watchingOccupant   bool                    // 在盯着占着会话的进程
	loadingHistory     bool
	earlier            int                // 会话记录里还没载入的更早事件数
	earlierAnchor      string             // 已载入那段之前的一行（uuid），要更早一页时交回去
	earlierFile        string             // 还没载入的那段在哪份会话记录里（分支是原会话的）
	earlierLoading     bool               // 正在载入更早的一页（conv.earlier）
	deferredEarlier    func()             // 剪的期间读回来的更早一页，剪完再接上
	todoSeed           []*transcript.Step // 没载入的更早历史里的清单工具调用（zeta-helper history 的 todo_seed）还原成的工具步骤，任务清单先套用它们
	queued             []*outgoing
	steering           bool
	recalled           *outgoing
	recalling          map[string]bool // 撤回时要放回输入框的排队消息
	interruptRequested bool
	shellCommands      map[string]bool // 在跑的 ! 命令（bash_command 的 uuid）：它们没有 result
	shellInput         string          // 回放出来、还没等到输出的 ! 命令那一行（<bash-input>…）
	contextNotes       map[string]bool // 交回去只进上下文的 ! 命令那一行（shouldQuery 为假的用户消息的 uuid）：它不算一轮
	unsent             []*outgoing     // 交给了新起的进程、还没见它有任何输出的消息
	prompts            []*prompt.Prompt
	launchHint         string

	model              *string // 这个对话指定的模型；nil 是不指定
	reportedModel      string  // Claude Code 报的、实际在用的模型
	switchedInTerminal string  // 终端里 /model 换的、之后还没回答过的模型
	effort             string
	modes              modes
	commands           json.RawMessage
	models             json.RawMessage
	usage              *usage.Tracker
	tasks              *tasks.Board
	backgroundTasks    int

	proc      *process
	lastReply string // 最近一条回复的消息 id，手机工具据此换控件编号
	// openedSession 是打开时接着的会话号：接着已有会话时选默认模型要明说 default
	openedSession string
	sendNow       bool // 这个进程的 Claude Code 认 interrupt 的 send_now
	autoName      autoTitle
	subagents     subagentTracker             // 子代理查看页（subagent.go）
	taskOutputs   map[string]*taskOutputWatch // 有客户端看着输出的后台任务（taskoutput.go）

	// forkSource 是分支的原会话号（子代理的记录可能还在原会话下面）；不是分支时为空
	forkSource string
	fork       *forkState     // 还没发过消息的分支要做的剪切，见 fork.go
	rewind     *pendingRewind // 在等 Claude Code 剪会话或还原代码；这期间不收新消息，见 rewind.go

	options map[int]Options // 各客户端给的设置
	starter int             // 起进程用谁的设置：最近一个发消息（或要起进程）的客户端
	device  Device          // starter 是哪台设备（Deps.ClientDevice）；记到托管进程上见 device.go
	// keptDev 是上次记到托管进程上的：哪个进程（托管标识）、哪台设备
	keptDev struct {
		key    string
		device Device
	}
	holders  map[int]string // 拿着这个对话的客户端 → 它给这个对话起的名字（推送里的 conv）
	idleFrom time.Time      // 没人拿着、又闲着的起点；不是这样时为零

	turnEnd         int64    // 最后一轮结束的时刻（Unix 毫秒）
	reported        Activity // 上次报给对话表的忙闲（conv.activity）
	reportedSession string

	attachAfterHistory *Hosted // 接回的托管进程，历史载完再接上

	// lastActivity 是最近一次有动静的时刻（Unix 毫秒）：发出消息、一轮开始、输出改了列表、重试、压缩。客户端据此显示「已等待 N 秒」
	lastActivity int64
}

// Options 是客户端给的、起 Claude Code 进程用的设置（见 docs/core.md 的 options）。
type Options struct {
	Env            map[string]string `json:"env"`
	SystemPrompt   string            `json:"system_prompt"`
	MCPConfig      string            `json:"mcp_config"`
	Phone          bool              `json:"phone"`
	AutoTitle      bool              `json:"auto_title"`
	CompactPercent int               `json:"compact_percent"`
	// Desk：这台主机是远程主机（不是客户端所在的设备），核心给会话在远程的「Zeta」tmux 会话里开窗口，回到电脑前在终端里接着
	Desk bool `json:"desk"`
	// ImageRefs：推给这个客户端的条目里实时收到的图只带 key、不带原文，要看时用 conv.image 取（images.go）
	ImageRefs bool `json:"image_refs"`
}

// Spec 是打开一个对话时给的。
type Spec struct {
	Session  string // 接着已有的会话时是它的会话号
	File     string // 会话记录，载入历史用
	Cwd      string
	Title    string
	Blocked  bool                    // 打开时会话正被 Zeta 之外的进程占着：输入栏先锁着，等 conv.handoff 或那边自己退出
	Occupant *sessionproto.Occupancy // Blocked 时已经查到的那边的样子
	attach   *Hosted                 // 接回的托管进程（Manager.Adopt）
}

// New 建一个对话并开始它的 goroutine；有会话记录时先在后台载入历史。
func New(id string, spec Spec, deps Deps, send Send) *Conversation {
	c := &Conversation{
		ID: id, deps: deps, send: send, in: make(chan func(), 256), quit: make(chan struct{}),
		cwd: spec.Cwd, title: spec.Title, session: spec.Session, file: spec.File, openedSession: spec.Session,
		r: transcript.New(""), recalling: map[string]bool{}, shellCommands: map[string]bool{}, contextNotes: map[string]bool{},
		options: map[int]Options{}, holders: map[int]string{},
		usage: usage.New(), tasks: tasks.New(),
	}
	c.modes.claudeDefault = deps.DefaultMode
	// 还没起进程时先给这台主机上记着的斜杠命令，起了进程换成它报的
	c.commands = deps.Commands.Recall(spec.Cwd)
	if spec.Blocked {
		c.block(spec.Occupant)
	}
	if spec.attach != nil {
		c.adopt(spec.attach)
	}
	go c.loop()
	if spec.File != "" {
		c.loadingHistory = true
		go c.loadHistory(spec.File)
	}
	return c
}

func (c *Conversation) loop() {
	for {
		select {
		case f := <-c.in:
			f()
		case <-c.quit:
			return
		}
	}
}

// do 把 f 排进对话的 goroutine。对话已关闭时丢掉。
func (c *Conversation) do(f func()) {
	select {
	case c.in <- f:
	case <-c.quit:
	}
}

// Call 在对话的 goroutine 里跑 f，等它跑完返回结果；跑完马上把变化推出去。
func (c *Conversation) Call(f func() (any, error)) (any, error) {
	type result struct {
		v   any
		err error
	}
	done := make(chan result, 1)
	c.do(func() {
		v, err := f()
		c.flush()
		done <- result{v, err}
	})
	select {
	case r := <-done:
		return r.v, r.err
	case <-c.quit:
		return nil, errClosed
	}
}

// close 停掉对话的 goroutine（进程该断开的由调用方先做）。
func (c *Conversation) close() {
	c.once.Do(func() { close(c.quit) })
}

// changed 记下有变化，稍后合成一次推出去。
func (c *Conversation) changed() {
	c.dirty = true
	if c.flushTimer == nil {
		c.flushTimer = time.AfterFunc(publishDelay, func() { c.do(c.flush) })
	}
}

// flush 把攒下的变化推出去：先状态、再条目。
func (c *Conversation) flush() {
	if c.flushTimer != nil {
		c.flushTimer.Stop()
		c.flushTimer = nil
	}
	c.dirty = false
	c.keepUserMode()
	c.keepDevice()
	c.markRounds()
	if fields := c.stateDelta(); len(fields) > 0 {
		fields["type"] = raw("conv.state")
		c.broadcast(func(name string) any { fields["conv"] = raw(name); return fields }, false)
	}
	if d := c.published.delta(c.r.Items); d != nil {
		d.Type = "conv.items"
		c.broadcastItems(d)
	}
	c.reportActivity()
}

// Hold：客户端 client 拿起这个对话。先把攒着的变化推给已经拿着的，再给它整份的状态和条目，之后它和别人收到一样的增量。
func (c *Conversation) Hold(client int, name string, opts Options) error {
	_, err := c.Call(func() (any, error) {
		c.flush()
		state := maps.Clone(c.lastState)
		state["type"], state["conv"], state["full"] = raw("conv.state"), raw(name), raw(true)
		c.send(client, state)
		c.send(client, &itemsMsg{Type: "conv.items", Conv: name, Full: true, Items: nonNilSlice(itemsFor(opts, c.r.Items))})
		c.holders[client] = name
		c.options[client] = opts
		c.idleFrom = time.Time{}
		return nil, nil
	})
	return err
}

// broadcast 发给拿着这个对话的各个客户端；msg 按各客户端给这个对话起的名字做出消息。phoneOnly 时只发给声明了处理手机工具的。
func (c *Conversation) broadcast(msg func(name string) any, phoneOnly bool) {
	for client, name := range c.holders {
		if !phoneOnly || c.options[client].Phone {
			c.send(client, msg(name))
		}
	}
}

// broadcastItems 把条目的一次变化 d 发给拿着这个对话的各个客户端：声明了 image_refs 的收到去掉实时图片原文的那份（只做一次）。
func (c *Conversation) broadcastItems(d *itemsMsg) {
	var lean *itemsMsg
	for client, name := range c.holders {
		msg := d
		if c.options[client].ImageRefs {
			if lean == nil {
				copied := *d
				copied.Items = withoutImageData(d.Items)
				lean = &copied
			}
			msg = lean
		}
		msg.Conv = name
		c.send(client, msg)
	}
}

// stateDelta 是和上次推出去的相比变了的字段；第一次是整份。
func (c *Conversation) stateDelta() map[string]json.RawMessage {
	now := c.stateFields()
	delta := map[string]json.RawMessage{}
	for k, v := range now {
		if old, ok := c.lastState[k]; !ok || string(old) != string(v) {
			delta[k] = v
		}
	}
	c.lastState = maps.Clone(now)
	return delta
}

func raw(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}
