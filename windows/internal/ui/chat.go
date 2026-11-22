package ui

import (
	"context"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/egoist/mygo/ui"
)

// chatPane 是聊天窗格的界面状态：跟着中枢的一个对话（src），换了对话就换一个新的。对话的状态和条目每一帧开头从
// 协议层 Take 下来（主线程上），条目的变化依次套给聊天列表。
type chatPane struct {
	src   *hub.Chat
	list  *chatview.Chat
	state chat.State
	// connected：对话开着、连接在；断线重连期间为假，照旧显示，操作会失败。
	connected bool

	draft string
	// failed 是上次操作没成的原因，下次操作成了或换了对话时清掉。
	failed string
	// taking：正在取撤回放回输入框的那条；took 是取过的最后一条（uuid）。
	taking bool
	took   string
	form   promptForm
	usage  bool // 用量弹出框开着
	// earlier 是载入更早记录的情况：这边发出了请求、上次没读成的原因、那个原因能不能重试。
	earlier struct {
		asked  bool
		failed string
		stale  bool
	}
}

func newChatPane(c *hub.Chat, loadImage func(file, ref string) ([]byte, error)) *chatPane {
	list := chatview.New()
	list.LoadImage = loadImage
	return &chatPane{src: c, list: list}
}

// syncChat 在每一帧开头跟上中枢的对话：换了对话换一个新的窗格；取走协议层的变化，套给聊天列表；
// 一轮结束而窗口不在前台时发通知；有撤回放回输入框的消息时取过来。
func (a *App) syncChat() {
	c := a.hub.Chat()
	if c == nil {
		a.pane = nil
		return
	}
	if a.pane == nil || a.pane.src != c {
		p := newChatPane(c, a.hub.ImageLoader(c.Backend))
		p.list.LoadEarlier = func() { a.loadEarlier(p) }
		a.pane = p
	}
	p := a.pane
	if c.Conv == nil {
		return
	}
	u := c.Conv.Take()
	p.state, p.connected = u.State, u.Connected
	for _, ch := range u.Items {
		if ch.Full {
			p.list.SetItems(ch.Items)
		} else if p.list.Splice(ch.At, ch.Remove, ch.Items) != nil {
			p.list.SetItems(c.Conv.Items()) // 对不上了（不该发生）：整份重来
		}
	}
	p.list.Earlier = chatview.Earlier{Skipped: u.State.Earlier, Loading: p.earlier.asked || u.State.EarlierLoading,
		Failed: p.earlier.failed, Retry: !p.earlier.stale}
	for _, t := range u.Turns {
		if t.Phase == chat.TurnFinished && !a.Foreground() {
			a.Notify(a.chatTitle(p), t.Text)
		}
	}
	// 同一条只取一次：取走以后核心推来 recalled 为空之前，这一条还挂在状态里
	if r := u.State.Recalled; r != nil && !p.taking && r.UUID != p.took {
		p.taking, p.took = true, r.UUID
		a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
			m, err := conv.TakeRecalled(ctx)
			a.post(func() {
				p.taking = false
				if m != nil {
					p.recall(m.Text)
				}
			})
			return err
		}, p.fail)
	}
}

// recall 把撤回的一条放回输入框：输入框里已经有字时放在前面。
func (p *chatPane) recall(text string) {
	if strings.TrimSpace(p.draft) != "" {
		text += "\n" + p.draft
	}
	p.draft = text
}

func (p *chatPane) fail(err error) { p.failed = err.Error() }

// act 在后台对对话做一件事；没成时原因显示在输入栏上方。
func (a *App) act(do func(ctx context.Context, conv *chat.Conversation) error) {
	p := a.pane
	if p == nil {
		return
	}
	p.failed = ""
	a.hub.RunChat(do, p.fail)
}

// chatTitle 是对话的标题：核心报的，接着旧会话时是侧栏上那个，新对话是「新对话」。
func (a *App) chatTitle(p *chatPane) string {
	switch {
	case strings.TrimSpace(p.state.Title) != "":
		return p.state.Title
	case strings.TrimSpace(p.src.Session.Label()) != "":
		return p.src.Session.Label()
	}
	return "新对话"
}

// chatPage 是聊天窗格：顶上标题，中间聊天列表，底下输入栏一带。
func (a *App) chatPage(c *ui.Context) {
	p := a.pane
	t := c.Theme()
	ui.Column(c).Fill().Children(func() {
		a.chatHeader(c, p)
		ui.Box(c).Height(1).Background(t.Border)
		switch {
		case p.src.Opening:
			ui.Row(c).Grow(1).Center().Gap(8).Children(func() {
				ui.Spinner(c).Size(16, 16)
				ui.Text(c, "正在打开").TextColor(t.TextMuted)
			})
		case p.src.Conv == nil:
			ui.Column(c).Grow(1).Center().Padding(32).Children(func() {
				ui.Text(c, p.src.Problem).TextColor(t.TextMuted).MaxWidth(520).TextAlign(ui.Center)
			})
		default:
			p.list.View(c).Grow(1).MinHeight(0)
		}
		ui.Column(c).FillWidth().MaxWidth(chatWidth).AlignSelf(ui.Center).Padding(0, 24, 16, 24).Gap(8).Children(func() {
			a.chatNotices(c, p)
			if p.src.Conv != nil {
				a.promptCard(c, p)
				a.queueStrip(c, p)
				a.chatStatus(c, p)
			}
			a.composer(c, p)
		})
	})
}

// chatWidth 是输入栏一带最宽多少，和聊天列表的内容一样宽。
const chatWidth = 860 + 48

// chatHeader 是顶上的标题：对话的标题，下面一行小字是设备和工作目录。
func (a *App) chatHeader(c *ui.Context, p *chatPane) {
	t := c.Theme()
	cwd := p.state.Cwd
	if cwd == "" {
		cwd = p.src.Session.Cwd
	}
	ui.Column(c).Height(56).Padding(0, 24).Justify(ui.Center).Gap(2).Children(func() {
		ui.Text(c, a.chatTitle(p)).FontSize(16).Bold().SingleLine()
		ui.Text(c, a.hub.Name(p.src.Backend)+" · "+cwd).FontSize(12).TextColor(t.TextMuted).SingleLine()
	})
}

// chatNotices 是输入栏上方的几句说明：工作目录进不去、连接断了、上次操作没成。
func (a *App) chatNotices(c *ui.Context, p *chatPane) {
	if why := p.src.CwdProblem; why != "" {
		noticeText(c, "Claude Code 起不来："+why+"。在「设备」页改默认工作目录后再开新对话。", true)
	}
	if why := a.hub.ChatLinkProblem(); why != "" && p.src.Conv != nil {
		noticeText(c, "正在重连："+why, false)
	}
	if p.state.Blocked {
		noticeText(c, "这个会话正在别处运行", false)
	}
	if p.failed != "" {
		noticeText(c, p.failed, true)
	}
}

func noticeText(c *ui.Context, text string, bad bool) {
	t := c.Theme()
	color := t.TextMuted
	if bad {
		color = t.Danger
	}
	ui.Text(c, text).FontSize(13).TextColor(color).MaxLines(3)
}

// chatStatus 是等待指示一行：在回答时转圈和核心给的一句，有后台任务时说几个。
func (a *App) chatStatus(c *ui.Context, p *chatPane) {
	s := p.state
	tasks := s.RunningTasks()
	if !s.Busy && tasks == 0 {
		return
	}
	t := c.Theme()
	ui.Row(c).Gap(8).AlignItems(ui.Center).Padding(0, 4).Children(func() {
		if s.Busy {
			ui.Spinner(c).Size(14, 14)
			status := s.Status
			if status == "" {
				status = "正在回答"
			}
			ui.Text(c, status).FontSize(13).TextColor(t.TextMuted).SingleLine()
		}
		ui.Spacer(c)
		if tasks > 0 {
			ui.Text(c, "后台任务 "+strconv.Itoa(tasks)+" 个").FontSize(13).TextColor(t.TextMuted)
		}
	})
}

// queueStrip 是输入栏上方的排队条：回答中发的消息排在这里。点正文撤回到输入框接着改，「插话」把排队的马上送进这一轮，
// 「删除」撤回不要。
func (a *App) queueStrip(c *ui.Context, p *chatPane) {
	queued := p.state.Queued
	if len(queued) == 0 {
		return
	}
	t := c.Theme()
	panel(c, func() {
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			title := "排队中"
			switch {
			case p.state.Steering:
				title = "插话中"
			case len(queued) > 1:
				title += " · " + strconv.Itoa(len(queued))
			}
			ui.Text(c, title).FontSize(13).Bold().TextColor(t.Accent)
			ui.Spacer(c)
			if !p.state.Steering {
				ui.Button(c, "立即插话").OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Steer(ctx) })
				})
			}
		})
		for _, m := range queued {
			ui.Row(c.Key("queued/" + m.UUID)).Gap(8).AlignItems(ui.Center).Children(func() {
				preview := ui.Text(c, queuedPreview(m)).SingleLine().Grow(1).MinWidth(0).Cursor(ui.CursorPointer).
					Role(ui.RoleButton).Tooltip("撤回到输入框")
				preview.OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Cancel(ctx, m.UUID, true) })
				})
				ui.Button(c, "删除").OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Cancel(ctx, m.UUID, false) })
				})
			})
		}
	})
}

// queuedPreview 是一条排队消息的一行：正文（空白并成一个空格），没有字时说带了几张图、几个文件。
func queuedPreview(m chat.Message) string {
	line := strings.Join(strings.Fields(m.Text), " ")
	extra := len(m.Images) + len(m.Files)
	switch {
	case line != "" && extra > 0:
		return line + " +" + strconv.Itoa(extra)
	case line != "":
		return line
	case len(m.Images) > 0:
		return "[" + strconv.Itoa(len(m.Images)) + " 张图片]"
	}
	return "[" + strconv.Itoa(len(m.Files)) + " 个文件]"
}

// panel 是输入栏一带的一块面板：浅底、细边、圆角。
func panel(c *ui.Context, fn func()) {
	t := c.Theme()
	ui.Column(c).FillWidth().Padding(10, 14).Gap(6).Radius(12).Background(t.Surface).Border(1, t.Border).Children(fn)
}

// loadEarlier 载入更早的一页（列表往上翻到顶附近、或点了「载入更早的」时）。新的一页经推送从最前面插进来，
// 列表保住正看着的位置。没读成时说原因；会话记录在别处改过的重试没用，不给重试。
func (a *App) loadEarlier(p *chatPane) {
	if p.earlier.asked || p.earlier.stale {
		return
	}
	p.earlier.asked, p.earlier.failed = true, ""
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		err := conv.LoadEarlier(ctx, false)
		a.post(func() {
			p.earlier.asked = false
			if err != nil {
				p.earlier.failed = "没读到更早的记录：" + err.Error()
				p.earlier.stale = chat.IsEarlierStale(err)
			}
		})
		return nil
	}, nil)
}
