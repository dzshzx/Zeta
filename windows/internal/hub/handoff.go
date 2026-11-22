package hub

import (
	"context"
	"encoding/json"
	"time"
)

// Handoff 是聊天窗格的会话正被 Zeta 之外的进程（终端里的 Claude Code）占着时，「别处运行」卡片背后的状态（同 Kotlin 版的
// SessionHandoff）：那边闲不闲（卡片显示着时隔几秒查一次，sessions.occupant），和把会话接过来（sessions.handoff；
// 交出来了核心解开开着的对话的 blocked，推来 conv.state，卡片跟着收起）。
type Handoff struct {
	// Occupant 是最近一次查到的那边的样子；还没查到时是 nil。
	Occupant *Occupant
	// Handing：正在接过来。
	Handing bool
	// Handed：交出来了，在等核心推来解开 blocked 的 conv.state；这期间不再查、不再接（handoffSettle 后还没解开才重来）。
	Handed bool
	// Problem 是上次没接过来的原因，再接时清掉。
	Problem string

	checking bool
	checked  time.Time
}

// Occupant 是占着会话的那个 Claude Code 进程的样子（helper/conv/handoff.go 的 Occupancy）。
type Occupant struct {
	// Status 是它登记的忙闲：idle、waiting（在等确认）、busy、shell（在跑 ! 命令）；看不出时是 unknown。没人占着时为空。
	Status string `json:"status"`
	// WaitingFor 是在等确认时等的是什么。
	WaitingFor string `json:"waiting_for"`
	// HandsOver：现在能交给 Zeta（两轮之间）。
	HandsOver bool `json:"hands_over"`
}

// 占着的进程的几种忙闲（Occupant.Status）。
const (
	OccupantIdle    = "idle"
	OccupantWaiting = "waiting"
	OccupantShell   = "shell"
	OccupantUnknown = "unknown"
)

// handoffStuck：发了中断，那边过了一会儿还没退。
const handoffStuck = "stuck"

// OccupantPoll 是卡片显示着时多久查一次那边闲不闲。
const OccupantPoll = 3 * time.Second

// handoffSettle 是交出来以后等核心解开 blocked 最多多久：推送丢了（断线这类）时到点再查一次，不一直等。
const handoffSettle = 15 * time.Second

// PollOccupant 在聊天窗格的对话被别处占着、卡片显示着时每一帧调：离上次查满 OccupantPoll 就在后台再查一次。
// 查到那边已经不占着了（自己退出了），就接过来，好让核心解开 blocked。返回多久以后该再画一帧来查。
func (h *Hub) PollOccupant(now time.Time) time.Duration {
	c := h.chat
	session := h.chatSession()
	if session == "" {
		return 0
	}
	ho := &c.Handoff
	if ho.Handed {
		if wait := handoffSettle - now.Sub(ho.checked); wait > 0 {
			return wait
		}
		ho.Handed = false
	}
	if wait := OccupantPoll - now.Sub(ho.checked); wait > 0 {
		return wait
	}
	if ho.checking || ho.Handing {
		return OccupantPoll
	}
	ho.checking, ho.checked = true, now
	call := h.sessionsCall(c.Backend)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		var o Occupant
		raw, err := call(ctx, "sessions.occupant", map[string]any{"session": session})
		if err == nil {
			err = json.Unmarshal(raw, &o)
		}
		return func() {
			ho.checking = false
			switch {
			case err != nil || h.chat != c:
			case o.Status == "":
				h.HandOver()
			default:
				ho.Occupant = &o
			}
		}
	})
	return OccupantPoll
}

// chatSession 是聊天窗格里对话的会话号；没有对话或还没有会话号时是空。
func (h *Hub) chatSession() string {
	c := h.chat
	switch {
	case c == nil:
		return ""
	case c.Conv != nil && c.Conv.Session() != "":
		return c.Conv.Session()
	}
	return c.Session.ID
}

// HandOver 把聊天窗格的会话从别处接过来：那边闲着或在等确认时让它像 /exit 那样退出，正忙时不动、照它现在的样子显示。
func (h *Hub) HandOver() {
	c := h.chat
	session := h.chatSession()
	if session == "" || c.Handoff.Handing || c.Handoff.Handed {
		return
	}
	ho := &c.Handoff
	ho.Handing, ho.Problem = true, ""
	call := h.sessionsCall(c.Backend)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		var r struct {
			Handed bool   `json:"handed"`
			Status string `json:"status"`
		}
		raw, err := call(ctx, "sessions.handoff", map[string]any{"session": session})
		if err == nil {
			err = json.Unmarshal(raw, &r)
		}
		return func() {
			ho.Handing = false
			switch {
			case err != nil:
				ho.Problem = "接管失败：" + err.Error()
			case r.Handed:
				ho.Occupant, ho.Handed = nil, true
			case r.Status == handoffStuck:
				ho.Problem = "对方的 Claude Code 没有退出，可以在终端里结束它后再试。"
			default:
				ho.Occupant = &Occupant{Status: r.Status}
			}
		}
	})
}
