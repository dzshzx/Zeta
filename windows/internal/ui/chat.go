package ui

import (
	"cmp"
	"context"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
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
	// att 是输入栏里待发的图和文件；insert 是要插进输入框光标处的字（常用指令），下一帧建输入框时插进去。
	att    attachments
	insert string
	// acts 是消息操作（编辑、确认框，msgactions.go）。
	acts msgActions
	// todo 是输入栏上方的任务清单条（todostrip.go）。
	todo todoStrip
	// pages 是盖在聊天窗格上的后台任务页、子代理页（taskpages.go）。
	pages chatPages
	// slash 是斜杠命令提示（slash.go）。
	slash slashMenu
	// find 是对话内查找的查找栏（find.go）。
	find findBar
	// load 是输入栏上的负载标识（hostload.go）。
	load hostLoad
	// alerted 是发过通知的最后一个等回应的请求（notify.go）。
	alerted string
	// suggested：在新对话的空白页上点了建议，正等它发出去（emptychat.go）。
	suggested bool
	// cwd 是新对话顶栏上换工作目录的对话框。
	cwd struct {
		open  bool
		value string
	}
	// earlier 是载入更早记录的情况：这边发出了请求、上次没读成的原因、那个原因能不能重试。
	earlier struct {
		asked  bool
		failed string
		stale  bool
	}
}

// newChatPane 是对话 c 的窗格：会话记录里的图经 loadImage 取，回答里写的图（主机上的文件、网址）经 loadLinked 取。
func newChatPane(c *hub.Chat, loadImage func(file, ref string) ([]byte, error), loadLinked func(url string) ([]byte, error)) *chatPane {
	list := chatview.New()
	list.LoadImage, list.LoadMarkdownImage = loadImage, loadLinked
	return &chatPane{src: c, list: list}
}

// syncChat 在每一帧开头跟上中枢的对话：换了对话换一个新的窗格（开着的看大图一起关上）；取走协议层的变化，套给聊天列表；
// 一轮结束而窗口不在前台时发通知；有撤回放回输入框的消息时取过来。
func (a *App) syncChat() {
	c := a.hub.Chat()
	if c == nil {
		a.leaveDraft(a.pane)
		a.pane, a.viewer = nil, nil
		return
	}
	if a.pane == nil || a.pane.src != c {
		a.leaveDraft(a.pane)
		p := newChatPane(c, a.hub.ImageLoader(c.Backend), a.hub.LinkedImageLoader(c.Backend))
		p.list.LoadEarlier = func() { a.loadEarlier(p, false) }
		p.list.OpenSubagent = func(id string) { a.openSubagent(p, id) }
		p.list.OpenImages = func(list []items.Image, index int) { a.openImages(p.list, list, index) }
		a.messageCallbacks(p)
		a.pane, a.viewer = p, nil
		a.enterDraft(p)
	}
	p := a.pane
	if c.Conv == nil {
		return
	}
	if p.list.LoadInlineImage == nil {
		p.list.LoadInlineImage = a.hub.InlineImageLoader(c.Backend, c.Conv)
	}
	u := c.Conv.Take()
	p.state, p.connected = u.State, u.Connected
	// 回答里图片的相对路径按对话现在的工作目录解析；还没收到时按会话记录的
	p.list.Cwd = cmp.Or(u.State.Cwd, c.Session.Cwd)
	a.trackDraft(p)
	for _, ch := range u.Items {
		if ch.Full {
			p.list.SetItems(ch.Items)
		} else if p.list.Splice(ch.At, ch.Remove, ch.Items) != nil {
			p.list.SetItems(c.Conv.Items()) // 对不上了（不该发生）：整份重来
		}
	}
	a.syncMessageActions(p)
	p.list.Earlier = chatview.Earlier{Skipped: u.State.Earlier, Loading: p.earlier.asked || u.State.EarlierLoading,
		Failed: p.earlier.failed, Retry: !p.earlier.stale}
	a.paneAlerts(p, u.Turns)
	// 同一条只取一次：取走以后核心推来 recalled 为空之前，这一条还挂在状态里
	if r := u.State.Recalled; r != nil && !p.taking && r.UUID != p.took {
		p.taking, p.took = true, r.UUID
		a.hub.RunChatThen(func(ctx context.Context, conv *chat.Conversation) func() {
			m, err := conv.TakeRecalled(ctx)
			return func() {
				p.taking = false
				if m != nil {
					p.putBack(*m)
				}
				if err != nil {
					p.fail(err)
				}
			}
		})
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

// disconnected 是对话所在的设备「连接」关着时聊天窗格中间的说明，点「连接」马上连回去、接着看这个对话。
func (a *App) disconnected(c *ui.Context, p *chatPane) {
	k := widget.Of(c)
	id := p.src.Backend
	ui.Column(c).Grow(1).Center().Gap(10).Padding(32).Margin(headerHeight, 0, 0, 0).Children(func() {
		ui.Text(c, "「"+a.hub.Name(id)+"」已断开").FontSize(widget.SizeHeadlineS).TextColor(k.Text)
		ui.Text(c, offNote+"。连上后接着看。").FontSize(widget.SizeBodyM).
			TextColor(k.Secondary).MaxWidth(520).TextAlign(ui.Center)
		widget.PrimaryButton(c, "连接").OnClick(func() { a.setConnected(id, true) }).Margin(12, 0, 0, 0)
	})
}

// chatPage 是聊天窗格：中间聊天列表，顶上浮着标题栏（列表从它底下滚过去，透出来的是模糊的），底下输入栏一带。
func (a *App) chatPage(c *ui.Context) {
	p := a.pane
	if a.chatSubpage(c, p) {
		return
	}
	k := widget.Of(c)
	page := ui.Column(c).Fill()
	// 拖进窗口的文件、文件夹收成附件
	if files := page.DroppedFiles(); len(files) > 0 && p.src.Conv != nil && p.src.CwdProblem == "" {
		a.addPaths(p, files, false)
	}
	page.Children(func() {
		a.attachProblems(c, p)
		a.findShortcuts(c, p)
		a.pathDialog(c, p)
		a.savedPrompts(c, p)
		a.messageDialogs(c, p)
		a.cwdDialog(c, p)
		ui.Column(c).Grow(1).MinHeight(0).Children(func() {
			switch {
			case a.hub.Off(p.src.Backend):
				a.disconnected(c, p)
			case p.src.Opening:
				ui.Row(c).Grow(1).Center().Gap(8).Margin(headerHeight, 0, 0, 0).Children(func() {
					ui.Spinner(c).Size(16, 16)
					ui.Text(c, "正在打开").FontSize(widget.SizeBodyM).TextColor(k.Secondary)
				})
			case p.src.Conv == nil:
				ui.Column(c).Grow(1).Center().Padding(32).Margin(headerHeight, 0, 0, 0).Children(func() {
					ui.Text(c, p.src.Problem).FontSize(widget.SizeBodyM).TextColor(k.Secondary).MaxWidth(520).TextAlign(ui.Center)
				})
			case emptyChat(p):
				a.emptyState(c, p) // 新对话的空白页：标题和建议（emptychat.go）
			default:
				p.list.Top = headerHeight
				p.list.View(c).Grow(1).MinHeight(0)
			}
			a.chatHeader(c, p)
			if p.find.open && p.src.Conv != nil {
				a.findBar(c, p) // 浮在右上角
			}
		})
		ui.Column(c).FillWidth().MaxWidth(chatWidth).AlignSelf(ui.Center).Padding(4, 12, 12, 12).Gap(6).Children(func() {
			a.chatNotices(c, p)
			if p.src.Conv != nil {
				a.taskStrip(c, p)
				a.todoStrip(c, p)
				a.queueStrip(c, p)
				a.editStrip(c, p)
				a.chatStatus(c, p)
			}
			switch {
			case p.src.Conv != nil && len(p.state.Prompts) > 0:
				a.promptCard(c, p) // 等你回应：卡片顶替输入栏（prompts.go）
			case p.state.Blocked && p.src.Conv != nil:
				a.handoffCard(c, p) // 别处运行：输入栏换成接管的卡片（handoff.go）
			default:
				a.composer(c, p)
			}
		})
	})
}

// chatWidth 是输入栏一带最宽多少，和聊天列表的内容一样宽。
const chatWidth = 768 + 24

// headerHeight 是聊天页顶栏的高度：它同时是标题栏。
const headerHeight = widget.TitleBarHeight

// chatHeader 是顶上的标题（照 Codex 客户端）：左上角的对话标题，下面一行小字是设备和工作目录（模型在输入栏右下）。
// 它浮在聊天列表上面，是一层毛玻璃（CouiBlur.kt 的 TopAppBarGlow）：底色是窗口的颜色，
// 列表没翻动时是透明的，内容滚到它底下时才透出模糊的一层。它同时是标题栏：空白处拖动窗口，系统按钮浮在右边的毛玻璃上；
// 右边让出按钮，窄了截断。
func (a *App) chatHeader(c *ui.Context, p *chatPane) {
	k := widget.Of(c)
	cwd := p.state.Cwd
	if cwd == "" {
		cwd = p.src.Session.Cwd
	}
	bar := ui.Column(c)
	if p.list.Scrolled() {
		widget.Surface{Fill: k.Window.Alpha(0.8), Blur: widget.BlurBar}.Paint(bar)
	}
	start := float32(20)
	if a.layout.hidden {
		start = 96 // 让出左上角浮着的展开侧栏、新对话两个按钮
	}
	bar.Absolute().Top(0).Left(0).Right(0).Height(headerHeight).Padding(0, widget.EndInset(c, 20), 0, start).Justify(ui.Center).
		Gap(1).DragWindow().Children(func() {
		ui.Text(c, a.chatTitle(p)).FontSize(widget.SizeBodyM).FontWeight(600).TextColor(k.Text).SingleLine().Ellipsis("…").
			MaxWidthPercent(100)
		if !newChat(p) {
			ui.Text(c, a.hub.Name(p.src.Backend)+" · "+cwd).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).SingleLine().
				Ellipsis("…").MaxWidthPercent(100)
			return
		}
		// 新对话还没发消息：这一行能点，换一个工作目录重开
		b := ui.ButtonBase(c).Label("换工作目录").Tooltip("换工作目录").Cursor(ui.CursorPointer).Radius(4).Padding(0, 3).
			Margin(0, 0, 0, -3).MaxWidthPercent(100)
		if b.Hovered() {
			b.Background(k.Hover)
		}
		b.Children(func() {
			ui.Text(c, a.hub.Name(p.src.Backend)+" · "+cwd).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).SingleLine().
				Ellipsis("…").Shrink(1)
		})
		b.OnClick(func() { p.cwd.open, p.cwd.value = true, cwd })
	})
}

// newChat：窗格里是还没发过消息的新对话（没有会话号），这时还能换工作目录。
func newChat(p *chatPane) bool {
	return p.src.Session.ID == "" && p.state.Session == "" && !p.src.Opening
}

// cwdDialog 是新对话换工作目录的对话框：确定后在那个目录重开新对话（进不去时输入栏上方说原因）。
func (a *App) cwdDialog(c *ui.Context, p *chatPane) {
	if !p.cwd.open {
		return
	}
	path := strings.TrimSpace(p.cwd.value)
	summary := "这个对话在「" + a.hub.Name(p.src.Backend) + "」上的哪个目录下进行"
	if widget.InputDialog(c, &p.cwd.open, "工作目录", summary, &p.cwd.value, "/home/…", "确定", strings.HasPrefix(path, "/")) {
		a.hub.NewChatIn(path)
	}
}

// chatNotices 是输入栏上方的几句说明：工作目录进不去、连接断了、上次操作没成。会话在别处运行时输入栏换成卡片（handoffCard）。
func (a *App) chatNotices(c *ui.Context, p *chatPane) {
	if why := p.src.CwdProblem; why != "" {
		widget.Notice(c, "Claude Code 起不来："+why+"。点上面的工作目录换一个，或在「设备」页改默认工作目录。", widget.Failure)
	}
	if why := a.hub.ChatLinkProblem(); why != "" && p.src.Conv != nil {
		widget.Notice(c, "正在重连："+why, widget.Caution)
	}
	if p.failed != "" {
		widget.Notice(c, p.failed, widget.Failure)
	}
}

// chatStatus 是等待指示一行：在回答时转圈和核心给的一句（后台任务在输入栏上方的 taskStrip 里）。
func (a *App) chatStatus(c *ui.Context, p *chatPane) {
	s := p.state
	if !s.Busy {
		return
	}
	k := widget.Of(c)
	status := s.Status
	if status == "" {
		status = "正在回答"
	}
	ui.Row(c).Gap(8).AlignItems(ui.Center).Padding(0, 8).Children(func() {
		ui.Spinner(c).Size(14, 14)
		ui.Text(c, status).FontSize(widget.SizeBodyS).TextColor(k.Secondary).SingleLine()
	})
}

// queueStrip 是输入栏上方的排队条（同手机版 QueueStrip.kt）：回答中发的消息排在这里。点正文撤回到输入框接着改，
// 「立即插话」把排队的马上送进这一轮，「删除」撤回不要。
func (a *App) queueStrip(c *ui.Context, p *chatPane) {
	queued := p.state.Queued
	if len(queued) == 0 {
		return
	}
	k := widget.Of(c)
	panel(c, func() {
		ui.Row(c).Padding(3, 4, 3, 12).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Box(c).Size(6, 6).Radius(3).Background(k.Primary)
			title := "排队中"
			switch {
			case p.state.Steering:
				title = "插话中"
			case len(queued) > 1:
				title += " · " + strconv.Itoa(len(queued))
			}
			ui.Text(c, title).FontSize(widget.SizeBodyS).TextColor(k.PrimaryText)
			ui.Spacer(c)
			if !p.state.Steering {
				steer := ui.ButtonBase(c).Label("立即插话").Padding(5, 10).Radius(999).Gap(4).AlignItems(ui.Center).
					Cursor(ui.CursorPointer)
				if steer.Hovered() {
					steer.Background(k.Halftone)
				}
				steer.Children(func() {
					ui.Icon(c, widget.IconBolt).Size(14, 14).TextColor(k.PrimaryText)
					ui.Text(c, "立即插话").FontSize(widget.SizeBodyS).TextColor(k.PrimaryText)
				})
				steer.OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Steer(ctx) })
				})
			}
		})
		for i, m := range queued {
			if i > 0 {
				ui.Box(c).Height(1).Margin(0, 12).Background(k.Divider)
			}
			ui.Row(c.Key("queued/"+m.UUID)).Padding(2, 4, 2, 14).Gap(8).AlignItems(ui.Center).Children(func() {
				preview := ui.Text(c, queuedPreview(m)).FontSize(widget.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").
					Grow(1).MinWidth(0).Cursor(ui.CursorPointer).Role(ui.RoleButton).Tooltip("撤回到输入框")
				preview.OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Cancel(ctx, m.UUID, true) })
				})
				widget.FlatButton(c, widget.IconDelete, "删除", 32, 16).OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Cancel(ctx, m.UUID, false) })
				})
			})
		}
	})
}

// queuedPreview 是一条排队消息的一行：正文（空白并成一个空格），没有字时说带了几张图、几个文件。
func queuedPreview(m coreapi.Message) string {
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

// panel 是输入栏一带的一块面板，和输入栏一样（Composer.kt）：淡灰底、细边、24 的平滑圆角、淡淡的阴影。
func panel(c *ui.Context, fn func()) {
	k := widget.Of(c)
	widget.Surface{Shape: widget.ShapeComposer, Fill: k.Field, Edge: k.Edge}.Paint(ui.Column(c)).FillWidth().Padding(6, 0).Gap(2).
		Shadow(0, 2, 16, 0, composerShadow(k)).Children(fn)
}

// composerShadow 是输入栏的阴影色：浅色 4.5% 的黑，深色 22%。
func composerShadow(k *widget.Colors) ui.Color {
	if k.Dark {
		return ui.RGBA(0, 0, 0, 0.22)
	}
	return ui.RGBA(0, 0, 0, 0.045)
}

// loadEarlier 载入更早的一页（列表往上翻到顶附近、或点了「载入更早的」时），rest 时一次载完（查找时）。新的经推送从最前面插进来，
// 列表保住正看着的位置。没读成时说原因；会话记录在别处改过的重试没用，不给重试。正在载或不用再试时不发，返回 false。
func (a *App) loadEarlier(p *chatPane, rest bool) bool {
	if p.earlier.asked || p.earlier.stale {
		return false
	}
	p.earlier.asked, p.earlier.failed = true, ""
	a.hub.RunChatThen(func(ctx context.Context, conv *chat.Conversation) func() {
		err := conv.LoadEarlier(ctx, rest)
		return func() {
			p.earlier.asked = false
			if err != nil {
				p.earlier.failed = "没读到更早的记录：" + err.Error()
				p.earlier.stale = chat.IsEarlierStale(err)
			}
		}
	})
	return true
}
