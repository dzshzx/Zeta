package ui

import (
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 新对话的空白页（功能和文案同手机版 ChatScreen.kt 的 EmptyState，样子照 ChatGPT 客户端的新对话）：聊天区中间一行
// 「有什么可以帮你？」。工作目录在顶栏第二行换（chatHeader），空白页上不重复。侧栏列着全部设备时，标题下面多一个
// 设备胶囊（newChatHost，同手机版空白页上的 HostSwitcher）：点开换这个新对话开在哪台，不改侧栏列哪些；只列一台时
// 设备在侧栏的设备切换里换。

// emptyChat：窗格里是还没有任何条目（也没有更早的记录可载入）、也没在回答的新对话。
func emptyChat(p *chatPane) bool {
	return newChat(p) && len(p.list.Items()) == 0 && p.state.Earlier == 0 && !p.state.Busy
}

// emptyTitleSize 是空白页标题的字号（ChatGPT 客户端新对话的标题是 28）。
const emptyTitleSize = 28

// emptyState 画新对话的空白页。对话起不来（工作目录进不去）时原因在输入栏上方。
func (a *App) emptyState(c *ui.Context, p *chatPane) {
	k := widget.Of(c)
	ui.Column(c).Grow(1).Center().AlignItems(ui.Center).Gap(4).Padding(32).Margin(headerHeight, 0, 0, 0).Children(func() {
		ui.Text(c, locales.EmptyChatTitle()).FontSize(emptyTitleSize).FontWeight(widget.Medium).TextColor(k.Text)
		a.newChatHost(c, p)
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
