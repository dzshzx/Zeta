package conv

// modes 是一个对话的权限模式。Claude Code 的模式跟着进程走，这里分两样记：
//   - user：用户在这个对话里的模式，用户自己的消息按它跑。用户选的、Claude Code 在它上面自己换的（计划获批后退出计划模式）
//     都记在这里。空是还没选过、也还没报过：起进程时不指定，用 Claude Code 设置里的默认。
//   - process：进程现在用的模式：起进程时带的、之后切到的，或 Claude Code 报来的；起进程时没指定、还没报过时为空。
//
// 每条消息发出前把进程切到发的人的模式（switchTo）：后台运行（定时任务、通知触发）的按它自己的，用户的按 forUser，
// 所以后台运行之后用户自己发下一条时自动切回，不用记上一条是谁发的。
//
// user 也记在托管进程上（托管目录的 user_mode，经 Deps.KeepMode）：核心重启后接回进程时照它恢复（Hosted.UserMode），
// 否则只知道进程现在的模式，分不出那是用户的还是后台运行的。
type modes struct {
	user    string
	process string
	// claudeDefault 是 Claude Code 设置里的默认模式（settings.json 的 permissions.defaultMode），不知道时为空
	claudeDefault func() string
	// kept 是上次记到托管进程上的：哪个进程（托管标识）、什么模式
	kept struct{ key, mode string }
}

// modeView 是 conv.state 的 mode：用户的、进程现在用的，和输入栏显示的（shown：用户的，没有时是 Claude Code 的默认）。
// 不知道的是空串。
type modeView struct {
	User    string `json:"user"`
	Process string `json:"process"`
	Shown   string `json:"shown"`
}

func (m *modes) view() modeView {
	shown := m.user
	if shown == "" {
		shown = m.defaultMode()
	}
	return modeView{User: m.user, Process: m.process, Shown: shown}
}

// keepUserMode 把用户的模式记到接着的托管进程上，变了才写（进程换了、接上了本包现成的进程也算变）；没写成的下次推送时再写：
// 刚起的进程，托管脚本可能还没建好目录。
func (c *Conversation) keepUserMode() {
	if c.proc == nil || c.modes.user == "" || c.deps.KeepMode == nil {
		return
	}
	key := c.proc.link.Key()
	if c.modes.kept.key == key && c.modes.kept.mode == c.modes.user {
		return
	}
	if c.deps.KeepMode(key, c.modes.user) == nil {
		c.modes.kept.key, c.modes.kept.mode = key, c.modes.user
	}
}

// forUser 是用户的消息用的模式。这个对话没有时：进程没指定模式就不指定（用 Claude Code 的默认）；
// 进程在后台运行的模式上时切到 Claude Code 的默认，还不知道是什么就用逐项确认，宁可多问。
func (m *modes) forUser() string {
	if m.user != "" || m.process == "" {
		return m.user
	}
	if d := m.defaultMode(); d != "" {
		return d
	}
	return "default"
}

func (m *modes) defaultMode() string {
	if m.claudeDefault == nil {
		return ""
	}
	return m.claudeDefault()
}

// switchTo：要把进程切到 mode 时返回 true（要发 set_permission_mode）；mode 为空或进程已经是了返回 false。
func (m *modes) switchTo(mode string) bool {
	if mode == "" || mode == m.process {
		return false
	}
	m.process = mode
	return true
}

// reported：Claude Code 报来进程现在的模式（status 行）。进程在用户的模式上时用户的跟着变，用户没选过的从此定下来。
func (m *modes) reported(mode string) {
	if m.process == m.forUser() {
		m.user = mode
	}
	m.process = mode
}

// reportedAtStart：init 和 initialize 应答报的。起进程时没指定模式才要，之后的变化看 status 行。
func (m *modes) reportedAtStart(mode string) {
	if m.process == "" {
		m.reported(mode)
	}
}
