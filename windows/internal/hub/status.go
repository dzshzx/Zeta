package hub

import (
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

// activityReceived：设备 id 的核心推来了各会话的忙闲（整份）。两份里都有、最后一轮的结束时刻往后挪了的，不是正开着的话记成有新回答；
// 第一次见的不算（不知道它是不是刚结束）。
func (h *Hub) activityReceived(id string, sessions map[string]hostlink.Activity) {
	before := h.activity[id]
	for session, now := range sessions {
		if was, ok := before[session]; ok && now.TurnEnd > was.TurnEnd && !h.showing(id, session) {
			h.unread[key(id, session)] = true
		}
	}
	h.activity[id] = sessions
}

// showing：聊天窗格正开着设备 backend 上的会话 session。
func (h *Hub) showing(backend, session string) bool {
	c := h.chat
	return c != nil && c.Backend == backend && (c.Session.ID == session || c.Conv != nil && c.Conv.Session() == session)
}

// seen：点开了会话 s，有新回答的标记去掉。
func (h *Hub) seen(s hostlink.Session) { delete(h.unread, Key(s)) }
