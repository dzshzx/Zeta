package hub

import (
	"context"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat"
)

// Handoff 是聊天窗格的会话正被 Zeta 之外的进程（终端里的 Claude Code）占着时，「别处运行」卡片背后的状态（同 Kotlin 版的
// SessionHandoff）：那边闲不闲（卡片显示着时隔几秒查一次），和把会话接过来。开着的对话用 conv.occupant、conv.handoff，
// 接过来后核心解开对话的 Blocked。
type Handoff struct {
	// Occupant 是最近一次查到的那边的样子；还没查到时是 nil。
	Occupant *chat.Occupant
	// Handing：正在接过来。
	Handing bool
	// Problem 是上次没接过来的原因，接成或再接时清掉。
	Problem string

	checking bool
	checked  time.Time
}

// OccupantPoll 是卡片显示着时多久查一次那边闲不闲。
const OccupantPoll = 3 * time.Second

// PollOccupant 在聊天窗格的对话被别处占着、卡片显示着时每一帧调：离上次查满 OccupantPoll 就在后台再查一次。
// 查到那边已经不占着了（自己退出了），就接过来，好让核心解开 Blocked。返回多久以后该再画一帧来查。
func (h *Hub) PollOccupant(now time.Time) time.Duration {
	c := h.chat
	if c == nil || c.Conv == nil {
		return 0
	}
	ho := &c.Handoff
	if wait := OccupantPoll - now.Sub(ho.checked); wait > 0 {
		return wait
	}
	if ho.checking || ho.Handing {
		return OccupantPoll
	}
	ho.checking, ho.checked = true, now
	conv := c.Conv
	h.background(func(ctx context.Context) func() {
		o, err := conv.Occupant(ctx)
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

// HandOver 把聊天窗格的会话从别处接过来：那边闲着或在等确认时让它像 /exit 那样退出，正忙时不动、照它现在的样子显示。
func (h *Hub) HandOver() {
	c := h.chat
	if c == nil || c.Conv == nil || c.Handoff.Handing {
		return
	}
	ho := &c.Handoff
	ho.Handing, ho.Problem = true, ""
	conv := c.Conv
	h.background(func(ctx context.Context) func() {
		r, err := conv.Handoff(ctx)
		return func() {
			ho.Handing = false
			switch {
			case err != nil:
				ho.Problem = "接管失败：" + err.Error()
			case r.Handed:
				ho.Occupant = nil
			case r.Status == chat.HandoffStuck:
				ho.Problem = "对方的 Claude Code 没有退出，可以在终端里结束它后再试。"
			default:
				ho.Occupant = &chat.Occupant{Status: r.Status}
			}
		}
	})
}
