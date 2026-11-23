package ui

import (
	"context"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 新对话的空白页（功能和文案同手机版 ChatScreen.kt 的 EmptyState，样子照 ChatGPT 客户端的新对话）：聊天区中间一行
// 「有什么可以帮你？」，下面几张建议卡片，点一张直接把它的提问发出去，输入栏里没发的字和附件不动（归这个对话）。
// 工作目录在顶栏第二行换（chatHeader），空白页上不重复。侧栏列着全部设备时，标题下面多一个设备胶囊（newChatHost，同手机版
// 空白页上的 HostSwitcher）：点开换这个新对话开在哪台，不改侧栏列哪些；只列一台时设备在侧栏的设备切换里换。

// suggestion 是空白页上的一张建议卡片：卡片上的字、图标，点了发出去的提问（悬停时也显示它）。字按界面当前的语言出，
// 提问也按界面的语言发出去（是替用户说的话）。
type suggestion struct {
	title  func() string
	icon   *ui.SVG
	prompt func() string
}

// suggestions 同手机版的 Suggestions。
var suggestions = []suggestion{
	{locales.EmptyChatExplore, widget.IconFolderOpen, locales.EmptyChatExplorePrompt},
	{locales.EmptyChatGit, widget.IconBranch, locales.EmptyChatGitPrompt},
	{locales.EmptyChatResume, widget.IconHistory, locales.EmptyChatResumePrompt},
	{locales.EmptyChatHealth, widget.IconChip, locales.EmptyChatHealthPrompt},
}

// emptyChat：窗格里是还没有任何条目（也没有更早的记录可载入）、也没在回答的新对话，也没刚点过建议（发出去到条目推来之间
// 不再显示，免得点两次）。
func emptyChat(p *chatPane) bool {
	return newChat(p) && len(p.list.Items()) == 0 && p.state.Earlier == 0 && !p.state.Busy && !p.suggested
}

// emptyTitleSize 是空白页标题的字号（ChatGPT 客户端新对话的标题是 28）。
const emptyTitleSize = 28

// emptyState 画新对话的空白页。对话起不来（工作目录进不去）时卡片灰着，原因在输入栏上方。
func (a *App) emptyState(c *ui.Context, p *chatPane) {
	k := widget.Of(c)
	on := p.src.Conv != nil && p.src.CwdProblem == ""
	ui.Column(c).Grow(1).Center().Gap(28).Padding(32).Margin(headerHeight, 0, 0, 0).Children(func() {
		ui.Column(c).AlignItems(ui.Center).Gap(4).Children(func() {
			ui.Text(c, locales.EmptyChatTitle()).FontSize(emptyTitleSize).FontWeight(widget.Medium).TextColor(k.Text)
			a.newChatHost(c, p)
		})
		ui.Column(c).FillWidth().MaxWidth(560).Gap(10).Children(func() {
			for i := 0; i < len(suggestions); i += 2 {
				ui.Row(c.Key(i)).FillWidth().Gap(10).Children(func() {
					for j, s := range suggestions[i:min(i+2, len(suggestions))] {
						a.suggestionCard(c.Key(i+j), p, s, on)
					}
				})
			}
		})
	})
}

// newChatHost 是新对话开在哪台设备的胶囊和菜单：只在侧栏列着全部设备、有不止一台、窗格里是还没发消息的新对话时有
// （空白页上，和这台已断开、打不开时的说明下面）。选另一台就在那台的默认工作目录重开新对话，以后的新对话也开在那台，
// 侧栏照旧列全部（hub.NewChatOn）。
func (a *App) newChatHost(c *ui.Context, p *chatPane) {
	h := a.hub
	if !h.AllHosts() || len(h.Hosts()) < 2 || !newChat(p) {
		return
	}
	at := p.src.Backend
	widget.MenuButton(c, func(t ui.Element) {
		t.Tooltip(locales.EmptyChatHostTip()).Label(locales.EmptyChatHostLabel(h.Name(at)))
		widget.Pill(c, t, widget.IconServer, h.Name(at))
	}, func(m *widget.Menu) {
		for _, x := range h.Hosts() {
			name := x.Name()
			if off := offLabel(x); off != "" {
				name += " · " + off
			}
			if m.Choice(name, x.ID == at) && x.ID != at {
				h.NewChatOn(x.ID)
			}
		}
	}).Margin(12, 0, 0, 0)
}

// suggestionCard 是一张建议卡片：一圈细边的圆角块，左边图标、右边字，悬停变底色、提示整句提问，点了发出去。
func (a *App) suggestionCard(c *ui.Context, p *chatPane, s suggestion, on bool) {
	k := widget.Of(c)
	b := ui.ButtonBase(c).Label(s.title()).Tooltip(s.prompt()).Grow(1).Basis(0).MinWidth(0).Padding(12, 14).Gap(10).
		AlignItems(ui.Center).Radius(14).Border(1, k.Edge).Cursor(ui.CursorPointer).Disabled(!on)
	if b.Hovered() && on {
		b.Background(k.Hover)
	}
	b.OnClick(func() { a.suggest(p, s.prompt()) })
	b.Children(func() {
		ui.Icon(c, s.icon).Size(16, 16).TextColor(k.Secondary)
		ui.Text(c, s.title()).FontSize(widget.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1)
	})
}

// suggest 把建议的提问发出去（只发这一句，输入栏不动）；没发出去时原因显示在输入栏上方，空白页回来，可以再点。
func (a *App) suggest(p *chatPane, prompt string) {
	if p.src.Conv == nil || p.src.CwdProblem != "" {
		return
	}
	p.suggested, p.failed = true, ""
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, coreapi.Outgoing{Text: prompt})
	}, func(err error) {
		p.suggested = false
		p.failed = locales.ChatSendFailed(err.Error())
	})
}
