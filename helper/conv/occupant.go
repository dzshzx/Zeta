package conv

import (
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 会话被 Zeta 之外的进程占着（blocked）时，核心自己盯着那个进程：每个对话只盯一份，查到的样子放进 conv.state 的 occupant，
// 「别处运行」卡片照它画；那边退出了（没人占着了）就解开 blocked。客户端不用轮询 conv.occupant、也不用代发 conv.handoff，
// 只在用户点「接管」时发 conv.handoff。

// occupantPoll 是 blocked 时多久查一次占着会话的进程；测试里调快。
var occupantPoll = 3 * time.Second

// occupantView 是 conv.state 的 occupant：blocked 时查到的样子，不 blocked 或还没查到时为 nil。
func (c *Conversation) occupantView() *sessionproto.Occupancy {
	if !c.blocked {
		return nil
	}
	return c.occupant
}

// block：会话到了别处（打开时、要起进程时它正被别处占着，或终端里接回去了）。known 是已经查到的那边的样子（没查过时为 nil）。
// 锁住输入栏，开始盯着那边。
func (c *Conversation) block(known *sessionproto.Occupancy) {
	c.blocked = true
	c.occupant = known
	c.changed()
	if c.watchingOccupant || c.deps.Occupant == nil {
		return
	}
	c.watchingOccupant = true
	go c.followOccupant(c.session, known != nil)
}

// unblock：会话接过来了（或那边自己退出了），解开输入栏。还在盯着的那份下次查到时自己停下。
func (c *Conversation) unblock() {
	c.blocked = false
	c.occupant = nil
	c.changed()
}

// followOccupant 在自己的 goroutine 里隔 occupantPoll 查一次占着会话 session 的进程（wait 为假时先马上查一次），
// 交回对话的 goroutine 处理，直到不再 blocked 或对话关了。
func (c *Conversation) followOccupant(session string, wait bool) {
	for {
		if wait {
			select {
			case <-time.After(occupantPoll):
			case <-c.quit:
				return
			}
		}
		wait = true
		seen := c.deps.Occupant(session)
		next := make(chan string, 1)
		c.do(func() { next <- c.occupantSeen(seen) })
		select {
		case s, ok := <-next:
			if !ok || s == "" {
				return
			}
			session = s
		case <-c.quit:
			return
		}
	}
}

// occupantSeen：查到了那边的样子 seen。没人占着了就解开；返回接着要查的会话号，不用再查时为空。
func (c *Conversation) occupantSeen(seen sessionproto.Occupancy) string {
	switch {
	case !c.blocked:
	case seen.Status == nil:
		c.unblock()
	default:
		c.occupant = &seen
		c.changed()
		if c.session != "" {
			return c.session
		}
	}
	c.watchingOccupant = false
	return ""
}
