package ui

import (
	"context"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 新对话的空白页（功能和文案同手机版 ChatScreen.kt 的 EmptyState，样子照 ChatGPT 客户端的新对话）：聊天区中间一行
// 「有什么可以帮你？」，下面几张建议卡片，点一张直接把它的提问发出去，输入栏里没发的字和附件不动（归这个对话）。
// 手机版空白页上的设备胶囊和工作目录这里不放：工作目录在顶栏第二行换（chatHeader），设备在侧栏的设备切换里换。

// suggestion 是空白页上的一张建议卡片：卡片上的字、图标，点了发出去的提问（悬停时也显示它）。
type suggestion struct {
	title  string
	icon   *ui.SVG
	prompt string
}

// suggestions 同手机版的 Suggestions。
var suggestions = []suggestion{
	{"看看这个项目", widget.IconFolderOpen, "先浏览当前目录，概括这个项目是做什么的、结构如何。"},
	{"检查 Git 状态", widget.IconBranch, "查看当前目录的 git 状态和最近的提交，告诉我有哪些未提交的改动。"},
	{"接着上次做", widget.IconHistory, "回顾这个目录里最近的改动，告诉我上次做到哪了、接下来该做什么。"},
	{"设备状况", widget.IconChip, "检查这台设备的内存、存储和负载情况，有异常就指出来。"},
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
		ui.Text(c, "有什么可以帮你？").FontSize(emptyTitleSize).FontWeight(widget.Medium).TextColor(k.Text)
		ui.Column(c).FillWidth().MaxWidth(560).Gap(10).Children(func() {
			for i := 0; i < len(suggestions); i += 2 {
				ui.Row(c.Key(i)).FillWidth().Gap(10).Children(func() {
					for _, s := range suggestions[i:min(i+2, len(suggestions))] {
						a.suggestionCard(c.Key(s.title), p, s, on)
					}
				})
			}
		})
	})
}

// suggestionCard 是一张建议卡片：一圈细边的圆角块，左边图标、右边字，悬停变底色、提示整句提问，点了发出去。
func (a *App) suggestionCard(c *ui.Context, p *chatPane, s suggestion, on bool) {
	k := widget.Of(c)
	b := ui.ButtonBase(c).Label(s.title).Tooltip(s.prompt).Grow(1).Basis(0).MinWidth(0).Padding(12, 14).Gap(10).
		AlignItems(ui.Center).Radius(14).Border(1, k.Edge).Cursor(ui.CursorPointer).Disabled(!on)
	if b.Hovered() && on {
		b.Background(k.Hover)
	}
	b.OnClick(func() { a.suggest(p, s.prompt) })
	b.Children(func() {
		ui.Icon(c, s.icon).Size(16, 16).TextColor(k.Secondary)
		ui.Text(c, s.title).FontSize(widget.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1)
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
		p.failed = "没发出去：" + err.Error()
	})
}
