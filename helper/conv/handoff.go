package conv

// Occupancy 是 Zeta 之外占着一个会话的 Claude Code 进程的样子（终端里开着的，或另一个包托管的），「别处运行」卡片照它说话。
type Occupancy struct {
	// Status：没有别处的进程占着时为 null；有时是 idle、waiting、busy、shell，旧版 Claude Code 不报状态时是 unknown
	Status *string `json:"status"`
	// WaitingFor：在等确认时等的是什么（Claude Code 报了才有）
	WaitingFor *string `json:"waiting_for"`
	// HandsOver：现在能交给 Zeta（闲着、在等确认，或看不出忙闲）
	HandsOver bool `json:"hands_over"`
}

// Handoff 是把会话从别处接过来的结果。
type Handoff struct {
	// Handed：交出来了（没人占着也算）
	Handed bool `json:"handed"`
	// Status：没交出来时那边的状态（同 Occupancy.Status），发了中断还不退的是 stuck
	Status *string `json:"status"`
}

// occupancy 问一次别处占用；没接上这个能力（Deps.Occupant 为 nil）或还没有会话号时当没人占着。
func (m *Manager) occupancy(session string) Occupancy {
	if session == "" || m.deps.Occupant == nil {
		return Occupancy{HandsOver: true}
	}
	return m.deps.Occupant(session)
}

// handOff 把对话 c 的会话从别处接过来；交出来了就解开输入栏（blocked），推给拿着它的客户端。要等那边退出，最多十几秒，所以返回 Pending。
func (m *Manager) handOff(c *Conversation) (any, error) {
	v, err := c.Call(func() (any, error) { return c.session, nil })
	if err != nil {
		return nil, err
	}
	session := v.(string)
	return Pending(func() (any, error) {
		result := Handoff{Handed: true}
		if session != "" && m.deps.Handoff != nil {
			result = m.deps.Handoff(session)
		}
		if result.Handed {
			_, err := c.Call(func() (any, error) {
				c.blocked = false
				c.changed()
				return nil, nil
			})
			if err != nil {
				return nil, err
			}
		}
		return result, nil
	}), nil
}

// Pending 是要等一会儿才有结果的应答（等 Claude Code 应答、等别处的进程退出）。Manager.Start 在请求的顺序里把事情发出去，
// 返回它；调用方在别的 goroutine 里调它拿结果，这个客户端后面的请求不用排在它后面等。
type Pending func() (any, error)
