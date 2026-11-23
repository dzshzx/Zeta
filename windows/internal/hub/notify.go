package hub

import "github.com/dzshzx/Zeta/client/hostlink"

// Alert 是聊天窗格没开着的一个会话要提醒用户的事（Kind）。
// 聊天窗格开着的那个由界面照它自己的推送（conv.turn、conv.state 的 prompts）提醒，不经这里。
type Alert struct {
	// Session 是侧栏列表里的那个会话；列表里还没有时只有 Backend 和 ID。
	Session hostlink.Session
	Kind    AlertKind
}

// AlertKind 是提醒的哪一种。
type AlertKind int

const (
	// AlertFinished：答完了一轮。
	AlertFinished AlertKind = iota
	// AlertWaiting：开始等用户批准或回答。
	AlertWaiting
	// AlertSettled：不再等用户回应了（在别处答了、被撤回、对话结束），界面据此撤掉等回应的通知。
	AlertSettled
)

// OnAlert 设收提醒的函数（界面据此发、撤系统通知），在主线程上调用；nil 是不收。
func (h *Hub) OnAlert(fn func(Alert)) { h.alerted = fn }

// alertOf：同一个会话前后两份忙闲 was、now 之间有没有要提醒的。刚开始等回应的先报；最后一轮的结束时刻往后挪了是答完了一轮
// （它的通知换掉等回应的那条，不必另撤）；都不是而刚才在等、现在不等了，是那个请求处理掉了。
func alertOf(was, now hostlink.Activity) (AlertKind, bool) {
	switch {
	case now.State == "waiting" && was.State != "waiting":
		return AlertWaiting, true
	case now.TurnEnd > was.TurnEnd:
		return AlertFinished, true
	case was.State == "waiting" && now.State != "waiting":
		return AlertSettled, true
	}
	return 0, false
}

// alert 把会话 id（设备 backend 上的）的提醒交给界面。
func (h *Hub) alert(backend, id string, kind AlertKind) {
	if h.alerted == nil {
		return
	}
	h.alerted(Alert{Session: h.listed(backend, id), Kind: kind})
}
