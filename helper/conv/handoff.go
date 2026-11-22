package conv

import "github.com/dzshzx/Zeta/helper/sessionproto"

// 别处占用与接管的结果（sessionproto.Occupancy、sessionproto.Handoff）是协议的一部分，客户端也用，所以放在 helper/sessionproto。

// occupancy 问一次别处占用；没接上这个能力（Deps.Occupant 为 nil）或还没有会话号时当没人占着。
func (m *Manager) occupancy(session string) sessionproto.Occupancy {
	if session == "" || m.deps.Occupant == nil {
		return sessionproto.Occupancy{HandsOver: true}
	}
	return m.deps.Occupant(session)
}

// handOff 把对话 c 的会话从别处接过来（conv.handoff）。要等那边退出，最多十几秒，所以返回 Pending。
func (m *Manager) handOff(c *Conversation) (any, error) {
	v, err := c.Call(func() (any, error) { return c.session, nil })
	if err != nil {
		return nil, err
	}
	session := v.(string)
	return Pending(func() (any, error) { return m.handOffWith(session, c) }), nil
}

// handOffWith 把会话 session 从别处接过来，没人占着也算交出来了。交出来了、核心里又开着它的对话 c（可以是 nil）时
// 解开 c 的输入栏（blocked），推给拿着它的客户端。conv.handoff 和 sessions.handoff（HandOffSession）都走这里。
func (m *Manager) handOffWith(session string, c *Conversation) (sessionproto.Handoff, error) {
	result := sessionproto.Handoff{Handed: true}
	if session != "" && m.deps.Handoff != nil {
		result = m.deps.Handoff(session)
	}
	if result.Handed && c != nil {
		_, err := c.Call(func() (any, error) {
			c.blocked = false
			c.changed()
			return nil, nil
		})
		if err != nil {
			return result, err
		}
	}
	return result, nil
}

// Pending 是要等一会儿才有结果的应答（等 Claude Code 应答、等别处的进程退出）。Manager.Start 在请求的顺序里把事情发出去，
// 返回它；调用方在别的 goroutine 里调它拿结果，这个客户端后面的请求不用排在它后面等。
type Pending func() (any, error)
