package ui

import (
	"cmp"
	"encoding/json"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/egoist/mygo/i18n"
)

// 系统通知：窗口不在前台时，对话答完了一轮或停下来等你回应就发一条（同手机版的完成提醒和确认提醒）。聊天窗格开着的对话
// 照它自己的推送（conv.turn、新来的等回应的请求）发，别的会话照各台核心报的忙闲（hub 的 Alert）发。窗口在前台时不发：
// 开着的对话就在眼前，别的会话侧栏行尾有标记。每个会话一个通知编号，同一个会话后一条换掉前一条；点通知回到窗口、打开那个会话。
// 等回应的那条在请求处理掉以后撤掉（这边或别处答了、被撤回、对话结束），窗口在不在前台都撤。
// 通知上没有手机版那样的「允许」「拒绝」、选项按钮：mygo 在 Windows 上发的是通知区气泡（Shell_NotifyIcon），系统显示成
// 不带按钮的 toast，mygo 的 NotificationOptions 也没有按钮这一项；要回应点通知回到窗口，在确认卡片上答。

// Note 是一条系统通知。ID 相同的后一条换掉前一条；Open 是点了通知、窗口回到前台以后在主线程上做的事，可以为 nil。
type Note struct {
	ID, Title, Body string
	Open            func()
}

// noteID 是会话的通知编号；还没有会话号的新对话共用一个。
func noteID(backend, session string) string {
	if session == "" {
		return "zeta-new"
	}
	return "zeta-" + backend + "/" + session
}

// alert 是 hub 报来的、聊天窗格没开着的会话的提醒。答完了一轮也说明等的请求处理掉了：不发通知时（窗口在前台）撤掉等回应的那条，
// 发了就是换掉它。
// 别的会话的提醒只知道忙闲，不知道结局和等的是什么，说得笼统些。
func (a *App) alert(x hub.Alert) {
	s := x.Session
	id := noteID(s.Backend, s.ID)
	switch {
	case x.Kind == hub.AlertSettled:
		a.withdraw(id)
	case a.Window.Foreground():
		if x.Kind == hub.AlertFinished {
			a.withdraw(id)
		}
	case x.Kind == hub.AlertWaiting:
		a.notify(Note{ID: id, Title: rowTitle(s), Body: locales.NotifyWaiting(), Open: func() { a.openListed(s) }}, true)
	default:
		a.notify(Note{ID: id, Title: rowTitle(s), Body: locales.NotifyFinished(), Open: func() { a.openListed(s) }}, false)
	}
}

// paneAlerts 是聊天窗格开着的对话的提醒：turns 里结束的一轮，和新来的等回应的请求（每个请求只提醒一次）；
// 提醒过的请求不在等着的里面了（这边或别处答了、被撤回、对话结束），撤掉它的通知。
func (a *App) paneAlerts(p *chatPane, turns []chat.Turn) {
	if p.alertNote != "" && !slices.ContainsFunc(p.state.Prompts, func(pr prompt.Prompt) bool { return pr.RequestID == p.alerted }) {
		a.withdraw(p.alertNote)
		p.alertNote = ""
	}
	var bodies []string
	for _, t := range turns {
		if t.Phase == chat.TurnFinished {
			bodies = append(bodies, t.Said(i18n.Locale()))
		}
	}
	waiting := false
	if len(p.state.Prompts) > 0 {
		if pr := p.state.Prompts[0]; pr.RequestID != p.alerted {
			p.alerted = pr.RequestID
			bodies = append(bodies, waitingText(pr))
			waiting = true
		}
	}
	if len(bodies) == 0 || a.Window.Foreground() {
		return
	}
	s := hostlink.Session{Backend: p.src.Backend, ID: cmp.Or(p.src.Session.ID, p.state.Session), Cwd: p.src.Session.Cwd}
	open := func() { a.openListed(s) }
	if s.ID == "" {
		open = nil // 还没发过消息的新对话：只回到窗口
	}
	id := noteID(s.Backend, s.ID)
	a.notify(Note{ID: id, Title: a.chatTitle(p), Body: bodies[len(bodies)-1], Open: open}, waiting)
	p.alertNote = ""
	if waiting {
		p.alertNote = id
	}
}

// notify 发通知 n；waiting：它说的是等你回应，请求处理掉了要撤（withdraw）。同编号的后一条换掉前一条，换成答完了的那句就不必再撤。
func (a *App) notify(n Note, waiting bool) {
	a.Window.Notify(n)
	if !waiting {
		delete(a.waitingNotes, n.ID)
		return
	}
	if a.waitingNotes == nil {
		a.waitingNotes = map[string]bool{}
	}
	a.waitingNotes[n.ID] = true
}

// withdraw 撤掉编号 id 的通知，如果它还是等你回应的那条。
func (a *App) withdraw(id string) {
	if a.waitingNotes[id] {
		delete(a.waitingNotes, id)
		a.Window.Withdraw(id)
	}
}

// openListed 打开会话 s：侧栏列表里有它时用列表里的那份（带记录文件、标题），已经开着时什么也不做。
func (a *App) openListed(s hostlink.Session) {
	for _, x := range a.hub.Sessions() {
		if x.Backend == s.Backend && x.ID == s.ID {
			s = x
			break
		}
	}
	a.openSession(s)
}

// waitingText 是等回应的请求在通知上的一句（同手机版 AlertNotice：提问和 MCP 表单要你回答，别的要你确认）。
func waitingText(pr prompt.Prompt) string {
	switch {
	case pr.Kind == prompt.KindElicitation && pr.URL != "":
		return locales.NotifyElicitationUrl(pr.Server)
	case pr.Kind == prompt.KindElicitation:
		return locales.NotifyElicitation(pr.Server)
	case len(pr.Questions) == 1 && strings.TrimSpace(pr.Questions[0].Question) != "":
		return locales.NotifyQuestion(strings.TrimSpace(pr.Questions[0].Question))
	case len(pr.Questions) > 0:
		return locales.NotifyQuestions()
	case pr.IsPlan:
		return locales.NotifyPlan()
	}
	var input map[string]any
	_ = json.Unmarshal(pr.Input, &input)
	title, _ := chatview.DescribeTool(pr.ToolName, input)
	return locales.NotifyApprove(title)
}
