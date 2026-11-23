package conv

import (
	"encoding/json"
	"strconv"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 核心起来时接回还在干活的托管进程：上一个核心崩溃、被结束，或升级换了核心时，zeta_host.sh 托管的 Claude Code 进程还在跑。
// 在回答、等确认、有后台任务的接上（没人拿着，跑完闲够了照常放开）；闲着的不动，看门狗按闲置超时收掉它，
// 这之前有人打开那个会话发消息时 zeta_host.sh start 照常接回同一个进程。

// Hosted 是一个托管进程（zeta-helper hosts 的一行）。
type Hosted struct {
	Key     string `json:"key"`
	Cwd     string `json:"cwd"`
	Title   string `json:"title"`
	Session string `json:"session"`
	File    string `json:"file"`
	// Lines 是输出日志已有的行数，接上时从下一行读起
	Lines int  `json:"lines"`
	Busy  bool `json:"busy"`
	// Pending 是还在等用户回应的 control_request 行（原样），按到达顺序
	Pending []json.RawMessage `json:"pending"`
	// Mode 是进程现在的权限模式
	Mode string `json:"mode"`
	// UserMode 是用户在这个对话里的权限模式（核心记在托管目录的 user_mode，见 Deps.KeepMode）；没记过时为空
	UserMode string `json:"user_mode"`
	// Tasks 是还在跑的后台任务数；TaskEvents 是它们的事件（task_started、最后一条 background_tasks_changed、最近的 task_progress）
	Tasks      int               `json:"tasks"`
	TaskEvents []json.RawMessage `json:"task_events"`
}

// Active：在回答、等确认或有后台任务，和 Conversation.active 同一口径。这一轮结束了但后台任务还在跑的也算，否则结束它会把任务一起杀掉。
func (h Hosted) Active() bool { return h.Busy || len(h.Pending) > 0 || h.Tasks > 0 }

// Adopt 处理扫到的托管进程：已经接着的、闲着的不动，在干活的接上。
func (m *Manager) Adopt(hosted []Hosted) {
	for _, h := range hosted {
		if m.attached(h.Key) || !h.Active() {
			continue
		}
		m.mu.Lock()
		if h.Session != "" && m.bySession[h.Session] != nil {
			m.mu.Unlock()
			continue
		}
		m.seq++
		c := New("c"+strconv.Itoa(m.seq), Spec{Session: h.Session, File: h.File, Cwd: h.Cwd, Title: h.Title, attach: &h}, m.deps, m.send)
		m.all[c] = true
		if h.Session != "" {
			m.bySession[h.Session] = c
		}
		m.mu.Unlock()
	}
}

// attached：核心里已经有对话接着托管进程 key。
func (m *Manager) attached(key string) bool {
	m.mu.Lock()
	all := make([]*Conversation, 0, len(m.all))
	for c := range m.all {
		all = append(all, c)
	}
	m.mu.Unlock()
	for _, c := range all {
		v, _ := c.Call(func() (any, error) { return c.proc != nil && c.proc.link.Key() == key, nil })
		if v == true {
			return true
		}
	}
	return false
}

// adopt 记下接回的进程现在的样子；有会话记录时等历史载完再接（免得实时事件落在即将被替换掉的列表上），没有就马上接。
// 在 New 里、对话的 goroutine 开始之前调用。
func (c *Conversation) adopt(h *Hosted) {
	c.busy = h.Busy
	c.backgroundTasks = h.Tasks
	now := c.deps.now()
	c.lastActivity = now
	for _, line := range h.TaskEvents {
		c.tasks.Observe(line, now)
		// 后台子代理的 task_started：记下它是哪个 Agent 调用发起的（确认框上的叫法要用）
		var e event
		if json.Unmarshal(line, &e) == nil {
			c.observeSubagents(e, line)
		}
	}
	// 进程现在的模式可能是后台运行的，用户的另记在托管目录里
	c.modes.process = h.Mode
	c.modes.user = h.UserMode
	c.modes.kept.key, c.modes.kept.mode = h.Key, h.UserMode
	if h.File != "" {
		c.attachAfterHistory = h
		return
	}
	c.do(func() { c.attach(h) })
}

// attach 接上托管进程 h，从输出日志里上次读到的下一行读起，再把还没答复的确认请求重新排上。
func (c *Conversation) attach(h *Hosted) {
	p := newProcess()
	link, err := c.deps.Attach(h.Key, h.Lines+1, c.events(p))
	if err != nil {
		c.endTurnState()
		c.r.AddNoticeMsg(locales.M("noticeReattachFailed", "reason", locales.ErrArg(err)), true)
		c.changed()
		return
	}
	p.link = link
	c.proc = p
	// 接上的进程是以前起的，不知道当时给的自动压缩百分比，按拿着它的客户端现在的设置算
	c.processStarted(p, false, c.launchOptions().CompactPercent)
	for _, line := range h.Pending {
		c.receive(p, line)
	}
	c.changed()
}

// Release 交班时用：关掉每个对话到托管进程的连接（进程照跑，新核心起来时接回在干活的），关掉全部对话。
func (m *Manager) Release() {
	m.mu.Lock()
	all := make([]*Conversation, 0, len(m.all))
	for c := range m.all {
		all = append(all, c)
	}
	clear(m.all)
	clear(m.bySession)
	m.mu.Unlock()
	for _, c := range all {
		_, _ = c.Call(func() (any, error) {
			if c.proc != nil {
				c.proc.link.Leave()
				c.proc = nil
			}
			return nil, nil
		})
		c.close()
	}
}
