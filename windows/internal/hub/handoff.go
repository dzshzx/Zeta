package hub

import (
	"context"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// Handoff 是聊天窗格的会话正被 Zeta 之外的进程（终端里的 Claude Code）占着时，「别处运行」卡片上「接管」背后的状态
// （同 Kotlin 版的 SessionHandoff）。那边闲不闲由核心盯着，推在 conv.state 的 occupant 里；那边自己退出时核心自动解开
// blocked。这里只管用户点了「接管」：sessions.handoff 把会话接过来，交出来了核心解开 blocked、推来 conv.state，卡片跟着收起。
type Handoff struct {
	// Handing：正在接过来。
	Handing bool
	// Problem 是上次没接过来的原因，再接时清掉。
	Problem string
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

// HandOver 把聊天窗格的会话从别处接过来（用户点了「接管」）：那边闲着或在等确认时让它像 /exit 那样退出，正忙时不动。
func (h *Hub) HandOver() {
	c := h.chat
	session := h.chatSession()
	if session == "" || c.Handoff.Handing {
		return
	}
	ho := &c.Handoff
	ho.Handing, ho.Problem = true, ""
	core := h.sessionsCall(c.Backend)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		r, err := core.HandOffSession(ctx, session)
		return func() {
			ho.Handing = false
			switch {
			case err != nil:
				ho.Problem = locales.HandoffFailed(err.Error())
			case !r.Handed && r.Status != nil && *r.Status == sessionproto.StatusStuck:
				ho.Problem = locales.HandoffStuck()
			}
		}
	})
}
