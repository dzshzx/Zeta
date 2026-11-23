package hub

import (
	"cmp"
	"slices"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// Activity 是会话列表里一个会话眼下在干什么（同 Kotlin 版的 SessionStatus.Activity）。
type Activity int

const (
	// Quiet：只是历史记录，进程不在，行尾不标。
	Quiet Activity = iota
	// Waiting：等用户批准一次工具调用（或回答提问）。
	Waiting
	// Answering：这一轮正在回答。
	Answering
	// Background：这一轮答完了，还有后台任务在跑。
	Background
	// Unread：不在眼前时答完了一轮，用户还没点开看。
	Unread
	// Idle：进程还开着，没在干活。
	Idle
)

// Status 是一个会话行尾标的状态；Tasks 是还在跑的后台任务数。
type Status struct {
	Activity Activity
	Tasks    int
}

// Local 是这边聊天窗格里开着的那个对话的样子（从它的 conv.state 来）；别的会话是零值。
type Local struct {
	Waiting bool // 有等用户回应的请求
	Busy    bool // 在回答
	Tasks   int  // 还在跑的后台任务
}

// StatusOf 把几样合成一个：几样同时成立时只报最要紧的一样——等确认、在回答、后台任务、有新回答、闲着。
func StatusOf(waiting, busy bool, tasks int, unread, alive bool) Status {
	switch {
	case waiting:
		return Status{Waiting, tasks}
	case busy:
		return Status{Answering, tasks}
	case tasks > 0:
		return Status{Background, tasks}
	case unread:
		return Status{Activity: Unread}
	case alive:
		return Status{Activity: Idle}
	}
	return Status{}
}

// Status 是会话 s 行尾标的状态：这边开着的对话的样子 local，加上它所在设备的核心报的（conv.activity），和有没有没看的新回答。
func (h *Hub) Status(s hostlink.Session, local Local) Status {
	core, known := h.activity[s.Backend][s.ID]
	return StatusOf(
		local.Waiting || core.State == "waiting",
		local.Busy || core.State == "answering",
		max(local.Tasks, core.Tasks),
		h.unread[Key(s)],
		known && core.State != "",
	)
}

// activityReceived：设备 id 的核心推来了各会话的忙闲（整份）。两份里都有、不是正开着的会话：最后一轮的结束时刻往后挪了的记成有新回答，
// 答完了一轮或刚开始等用户回应的交给界面提醒（notify.go）。第一次见的不算（不知道它是不是刚结束）。
func (h *Hub) activityReceived(id string, sessions map[string]hostlink.Activity) {
	before := h.activity[id]
	h.activity[id] = sessions
	for session, now := range sessions {
		was, ok := before[session]
		if !ok || h.showing(id, session) {
			continue
		}
		if now.TurnEnd > was.TurnEnd {
			h.unread[Key(hostlink.Session{Backend: id, ID: session})] = true
		}
		if alert, waiting := alertOf(was, now); alert {
			h.alert(id, session, waiting)
		}
	}
}

// showing：聊天窗格正开着设备 backend 上的会话 session。
func (h *Hub) showing(backend, session string) bool {
	c := h.chat
	return c != nil && c.Backend == backend && (c.Session.ID == session || c.Conv != nil && c.Conv.Session() == session)
}

// seen：点开了会话 s，有新回答的标记去掉。
func (h *Hub) seen(s hostlink.Session) { delete(h.unread, Key(s)) }

// BackgroundChat 是后台任务面板「后台对话」里的一个：会话（列表里还没有它时只有会话号和设备）和它所在设备的核心报的样子。
type BackgroundChat struct {
	Session  hostlink.Session
	Activity hostlink.Activity
}

// BackgroundChats 是列着的各台上核心报在回答、等确认或还有后台任务的会话（同手机版 parkedConversations 里还在跑的），
// 聊天窗格开着的那个不算：桌面版只拿着眼前这一个对话，别的对话在核心里接着跑，忙闲看 conv.activity，
// 手机或别的客户端开着的也在里面。按会话列表的先后（新的在前），列表里还没有的排在最后、按会话号。
func (h *Hub) BackgroundChats() []BackgroundChat {
	var out []BackgroundChat
	for _, id := range h.Listed() {
		for session, a := range h.activity[id] {
			if !inProgress(a) || h.showing(id, session) {
				continue
			}
			s := hostlink.Session{ID: session, Backend: id}
			if i := slices.IndexFunc(h.sessions, func(x hostlink.Session) bool { return x.Backend == id && x.ID == session }); i >= 0 {
				s = h.sessions[i]
			}
			out = append(out, BackgroundChat{Session: s, Activity: a})
		}
	}
	slices.SortStableFunc(out, func(x, y BackgroundChat) int {
		return cmp.Or(cmp.Compare(y.Session.Updated, x.Session.Updated), cmp.Compare(Key(x.Session), Key(y.Session)))
	})
	return out
}

// inProgress：核心报这个会话在进行（在回答、等确认，或答完了还有后台任务）；闲着的核心过一会儿自己放手，不算。
func inProgress(a hostlink.Activity) bool {
	return a.State == "waiting" || a.State == "answering" || a.State == "background" || a.Tasks > 0
}
