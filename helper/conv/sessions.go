package conv

import (
	"errors"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 按会话号操作的入口：侧栏的 sessions.rename、sessions.handoff、sessions.delete 不知道哪个客户端开着这个会话，
// 由核心看它这里开着没有。开着就走对话自己的改名、接管、结束（和 conv.title、conv.handoff、conv.end 同一条路），
// 没开着时调用方照旧改文件。

// openSession 是核心里开着的会话 session 的对话；没有时为 nil。
func (m *Manager) openSession(session string) *Conversation {
	if session == "" {
		return nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.bySession[session]
}

// RenameSession 给会话 session 改名。核心里开着这个会话时同 conv.title（写会话记录、换对话的标题、让开着的 Claude Code 自己也改名），
// open 为真，written 是实际写下的标题；没开着（或正好收掉了）时什么也不做，open 为假，由调用方自己写会话记录。
func (m *Manager) RenameSession(session, title string) (written string, open bool, err error) {
	c := m.openSession(session)
	if c == nil {
		return "", false, nil
	}
	v, err := c.Call(func() (any, error) { return c.rename(title) })
	if errors.Is(err, errClosed) {
		return "", false, nil
	}
	if err != nil {
		return "", true, err
	}
	return v.(string), true, nil
}

// HandOffSession 把会话 session 从别处接过来（同 conv.handoff）：核心里开着这个会话时，交出来了就解开它的输入栏。
// 要等那边退出，最多十几秒。
func (m *Manager) HandOffSession(session string) (sessionproto.Handoff, error) {
	result, err := m.handOffWith(session, m.openSession(session))
	if errors.Is(err, errClosed) {
		// 对话正好收掉了，没有输入栏要解开
		return result, nil
	}
	return result, err
}

// EndSession 结束核心里开着的会话 session 的进程（同 conv.end），对话留着。返回原先有没有进程；没开着时什么也不做。
func (m *Manager) EndSession(session string) bool {
	c := m.openSession(session)
	if c == nil {
		return false
	}
	v, _ := c.Call(func() (any, error) { return c.end(), nil })
	return v == true
}

// end 结束这个会话的进程（等于终端里 /exit），对话内容留着，下次发消息时 --resume 接上。返回原先有没有进程。
// 对话没接着进程时，本包托管的进程也可能还在跑这个会话（见 Deps.EndHosted；接回的还在等历史载完时也是这样），照样结束它：
// 用户结束的是会话，不是这条连接。
func (c *Conversation) end() bool {
	if c.proc != nil {
		c.closeProcess(true)
		return true
	}
	if c.attachAfterHistory != nil {
		c.attachAfterHistory = nil
		c.endTurnState()
	}
	return c.session != "" && c.deps.EndHosted != nil && c.deps.EndHosted(c.session)
}

// SessionActivity 是核心里开着的会话 session 眼下的忙闲（同 conv.activity 推的那一项）；没开着时是零值。
func (m *Manager) SessionActivity(session string) Activity {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.activity {
		if s.session == session {
			return s.Activity
		}
	}
	return Activity{}
}
