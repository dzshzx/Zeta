package conv

// 各会话的忙闲（conv.activity）：会话列表的进行中、等确认、有新回答这些标记用它。只报这个核心里的对话：
// 别的 helper 版本的核心（比如手机和电脑装的版本不同）接着的进程，由那边的核心报。

// Activity 是会话列表里一个会话眼下的样子。
type Activity struct {
	// State：waiting（等用户回应）、answering（这一轮在回答）、background（这一轮答完了，后台任务还在跑）、idle（进程开着、没在干活）；
	// 进程没了时为空
	State string `json:"activity,omitempty"`
	// Tasks 是还在跑的后台任务数
	Tasks int `json:"tasks,omitempty"`
	// TurnEnd 是最后一轮结束的时刻（Unix 毫秒），客户端拿它算「不在眼前时答完了一轮、还没看」；这个核心里还没结束过一轮时为 0
	TurnEnd int64 `json:"turn_end,omitempty"`
}

func (c *Conversation) activity() Activity {
	a := Activity{Tasks: c.backgroundTasks, TurnEnd: c.turnEnd}
	switch {
	case len(c.prompts) > 0:
		a.State = "waiting"
	case c.busy:
		a.State = "answering"
	case c.backgroundTasks > 0:
		a.State = "background"
	case c.proc != nil:
		a.State = "idle"
	}
	return a
}

// reportActivity 在每次推送变化时调用：会话号或忙闲变了就告诉对话表。
func (c *Conversation) reportActivity() {
	a := c.activity()
	if a == c.reported && c.session == c.reportedSession {
		return
	}
	c.reported, c.reportedSession = a, c.session
	if c.deps.ActivityChanged != nil {
		c.deps.ActivityChanged(c, c.session, a)
	}
}

// activityMsg 是 conv.activity 推送：每次变了推整份，键是会话号。
type activityMsg struct {
	Type     string              `json:"type"`
	Sessions map[string]Activity `json:"sessions"`
}

// activityChanged：对话 c 的会话号或忙闲变了。会话号为空（新对话还没拿到）、什么都没有的不列。
func (m *Manager) activityChanged(c *Conversation, session string, a Activity) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if session == "" || a == (Activity{}) {
		delete(m.activity, c)
	} else {
		m.activity[c] = sessionActivity{session, a}
	}
	m.pushActivity()
}

type sessionActivity struct {
	session string
	Activity
}

// pushActivity 把整份忙闲推给订阅了的客户端。持着 m.mu 调用。
func (m *Manager) pushActivity() {
	if len(m.activityWatchers) == 0 {
		return
	}
	msg := m.activityMsg()
	for client := range m.activityWatchers {
		m.send(client, msg)
	}
}

func (m *Manager) activityMsg() *activityMsg {
	msg := &activityMsg{Type: "conv.activity", Sessions: map[string]Activity{}}
	for _, s := range m.activity {
		msg.Sessions[s.session] = s.Activity
	}
	return msg
}

// watchActivity：客户端 client 订阅（或不再订阅）忙闲；订阅时先给它一份整份的。
func (m *Manager) watchActivity(client int, watch bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !watch {
		delete(m.activityWatchers, client)
		return
	}
	m.activityWatchers[client] = true
	m.send(client, m.activityMsg())
}
