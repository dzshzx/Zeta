package hub

import "github.com/dzshzx/Zeta/client/hostlink"

// Alert 是聊天窗格没开着的一个会话要提醒用户的事：答完了一轮，或停下来等用户回应（Waiting）。
// 聊天窗格开着的那个由界面照它自己的推送（conv.turn、conv.state 的 prompts）提醒，不经这里。
type Alert struct {
	// Session 是侧栏列表里的那个会话；列表里还没有时只有 Backend 和 ID。
	Session hostlink.Session
	// Waiting：等用户批准或回答；否则是答完了一轮。
	Waiting bool
}

// OnAlert 设收提醒的函数（界面据此发系统通知），在主线程上调用；nil 是不收。
func (h *Hub) OnAlert(fn func(Alert)) { h.alerted = fn }

// alertOf：同一个会话前后两份忙闲 was、now 之间有没有要提醒的。刚开始等回应的先报；最后一轮的结束时刻往后挪了是答完了一轮。
func alertOf(was, now hostlink.Activity) (alert, waiting bool) {
	switch {
	case now.State == "waiting" && was.State != "waiting":
		return true, true
	case now.TurnEnd > was.TurnEnd:
		return true, false
	}
	return false, false
}

// alert 把会话 id（设备 backend 上的）的提醒交给界面。
func (h *Hub) alert(backend, id string, waiting bool) {
	if h.alerted == nil {
		return
	}
	s := hostlink.Session{Backend: backend, ID: id}
	for _, x := range h.sessions {
		if x.Backend == backend && x.ID == id {
			s = x
			break
		}
	}
	h.alerted(Alert{Session: s, Waiting: waiting})
}
