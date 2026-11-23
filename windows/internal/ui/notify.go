package ui

import (
	"cmp"
	"encoding/json"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
)

// 系统通知：窗口不在前台时，对话答完了一轮或停下来等你回应就发一条（同手机版的完成提醒和确认提醒）。聊天窗格开着的对话
// 照它自己的推送（conv.turn、新来的等回应的请求）发，别的会话照各台核心报的忙闲（hub 的 Alert）发。窗口在前台时不发：
// 开着的对话就在眼前，别的会话侧栏行尾有标记。每个会话一个通知编号，同一个会话后一条换掉前一条；点通知回到窗口、打开那个会话。

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

// 别的会话的提醒只知道忙闲，不知道结局和等的是什么，说得笼统些。
const (
	alertFinished = "这一轮结束了"
	alertWaiting  = "需要你回应"
)

// alert 是 hub 报来的、聊天窗格没开着的会话的提醒。
func (a *App) alert(x hub.Alert) {
	if a.Window.Foreground() {
		return
	}
	s := x.Session
	body := alertFinished
	if x.Waiting {
		body = alertWaiting
	}
	a.Window.Notify(Note{ID: noteID(s.Backend, s.ID), Title: rowTitle(s), Body: body, Open: func() { a.openListed(s) }})
}

// paneAlerts 是聊天窗格开着的对话的提醒：turns 里结束的一轮，和新来的等回应的请求（每个请求只提醒一次）。
func (a *App) paneAlerts(p *chatPane, turns []chat.Turn) {
	var bodies []string
	for _, t := range turns {
		if t.Phase == chat.TurnFinished {
			bodies = append(bodies, t.Text)
		}
	}
	if len(p.state.Prompts) > 0 {
		if pr := p.state.Prompts[0]; pr.RequestID != p.alerted {
			p.alerted = pr.RequestID
			bodies = append(bodies, waitingText(pr))
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
	a.Window.Notify(Note{ID: noteID(s.Backend, s.ID), Title: a.chatTitle(p), Body: bodies[len(bodies)-1], Open: open})
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
		return pr.Server + " 请你去网页上操作"
	case pr.Kind == prompt.KindElicitation:
		return pr.Server + " 需要你填写"
	case len(pr.Questions) == 1 && strings.TrimSpace(pr.Questions[0].Question) != "":
		return "需要你回答：" + strings.TrimSpace(pr.Questions[0].Question)
	case len(pr.Questions) > 0:
		return "需要你回答"
	case pr.IsPlan:
		return "需要你确认计划"
	}
	var input map[string]any
	_ = json.Unmarshal(pr.Input, &input)
	title, _ := chatview.DescribeTool(pr.ToolName, input)
	return "需要你确认：" + title
}
