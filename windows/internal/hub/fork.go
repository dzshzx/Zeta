package hub

import (
	"context"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/chat"
)

// Fork 从聊天窗格里对话的条目 last 所在那一轮之后分支（conv.fork）：新对话由这个客户端拿着，聊天窗格换成它，
// 原对话放开（conv.close），会话记录不动。新对话发出第一条消息后才有会话号、出现在侧栏里。
// 没分成时把原因交给主线程的 failed；那之前已经换走了对话时，新对话关掉。
func (h *Hub) Fork(last string, failed func(err error)) {
	c := h.chat
	if c == nil || c.Conv == nil {
		return
	}
	src := c.Conv
	h.background(chat.OpenTimeout, func(ctx context.Context) func() {
		fork, err := src.Fork(ctx, last)
		return func() {
			switch {
			case err != nil:
				if h.chat == c && failed != nil {
					failed(err)
				}
			case h.chat != c:
				h.closeConv(fork)
			default:
				cwd := fork.State().Cwd
				if cwd == "" {
					cwd = c.Session.Cwd
				}
				h.CloseChat()
				h.chat = &Chat{Backend: c.Backend, Session: hostlink.Session{Cwd: cwd, Backend: c.Backend}, Conv: fork}
			}
		}
	})
}
