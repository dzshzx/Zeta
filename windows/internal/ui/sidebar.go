package ui

import (
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// allHostsLabel 是设备菜单里列全部设备那一项的名字。
const allHostsLabel = "全部设备"

// sidebarWidth 同 Compose 桌面版的侧栏（DesktopApp.kt 的 PaneWidth）。
const sidebarWidth = 300

// sidebar 是左边的侧栏（照手机版的 SessionPane）：品牌和新对话、设备切换，置顶/今天/更早几组会话，底下是设备的入口。
func (a *App) sidebar(c *ui.Context) {
	k := coui.Of(c)
	ui.Column(c).Width(sidebarWidth).Shrink(0).Background(k.Sidebar).Children(func() {
		ui.Row(c).Height(56).Padding(0, 12, 0, 20).Gap(10).AlignItems(ui.Center).Shrink(0).Children(func() {
			brand(c, 28)
			ui.Text(c, "Zeta").FontSize(coui.SizeHeadlineM).FontWeight(600).TextColor(k.Text)
			ui.Spacer(c)
			if len(a.hub.Hosts()) > 0 {
				coui.CircleButton(c, coui.IconNewChat, "新对话").OnClick(func() {
					a.page = pageHome
					a.hub.NewChat()
				})
			}
		})
		if len(a.hub.Hosts()) > 0 {
			ui.Row(c).Padding(0, 16, 8, 16).Shrink(0).Children(func() { a.hostSwitcher(c) })
		}
		ui.Scroll(c.Key("sessions")).Grow(1).MinHeight(0).Children(func() {
			ui.Column(c).Padding(0, 0, 12, 0).Children(func() { a.sessionGroups(c) })
		})
		ui.Row(c).Padding(10, 16).Shrink(0).Children(func() { a.devicesEntry(c) })
	})
}

// devicesEntry 是侧栏底下的设备入口：和手机版底栏的搜索框一样的浮起胶囊。
func (a *App) devicesEntry(c *ui.Context) {
	k := coui.Of(c)
	b := ui.ButtonBase(c).Label("设备").Grow(1).Height(40).Radius(20).Padding(0, 16, 0, 12).Gap(8).AlignItems(ui.Center).
		Cursor(ui.CursorPointer).Background(k.Bar).Shadow(0, 3.2, 16, 0, k.Shadow)
	if b.Hovered() {
		b.Background(k.Bar.Mix(k.Text, 0.05))
	}
	b.OnClick(func() { a.open(pageDevices) })
	b.Children(func() {
		ui.Icon(c, coui.IconServer).Size(20, 20).TextColor(k.Secondary)
		ui.Text(c, "设备").FontSize(coui.SizeBodyL).TextColor(k.Secondary).Grow(1)
		ui.Icon(c, coui.IconForward).Size(16, 16).TextColor(k.Tertiary)
	})
}

// hostSwitcher 是换设备的胶囊和菜单：先是「全部设备」，然后是各台，最后是管理设备。
func (a *App) hostSwitcher(c *ui.Context) {
	h := a.hub
	all := h.AllHosts()
	label := allHostsLabel
	if !all {
		label = h.Name(h.Backend())
	}
	coui.MenuButton(c, func(t ui.Element) { coui.Pill(c, t, coui.IconServer, label) }, func(m *coui.Menu) {
		if m.Choice(allHostsLabel, all) {
			h.ListAll()
		}
		m.Separator()
		for _, x := range h.Hosts() {
			if m.Choice(x.Name(), !all && x.ID == h.Backend()) {
				h.ListOnly(x.ID)
			}
		}
		m.Separator()
		if m.Item("管理设备") {
			a.open(pageDevices)
		}
	})
}

// sessionGroups 是侧栏的会话：列不出来时说原因，没有时说一声；否则置顶、今天、更早几组。
func (a *App) sessionGroups(c *ui.Context) {
	h := a.hub
	if len(h.Hosts()) == 0 {
		paneMessage(c, "还没有设备")
		return
	}
	if why := h.Problem(); why != "" {
		paneMessage(c, why)
	}
	g := h.Groups(a.now())
	if len(g.Pinned)+len(g.Today)+len(g.Earlier) == 0 {
		if h.Problem() == "" {
			paneMessage(c, "还没有对话")
		}
		return
	}
	first := true
	for _, group := range []struct {
		title string
		list  []hostlink.Session
	}{{"置顶", g.Pinned}, {"今天", g.Today}, {"更早", g.Earlier}} {
		if len(group.list) == 0 {
			continue
		}
		a.sessionGroup(c, group.title, group.list, first)
		first = false
	}
}

// paneMessage 是侧栏里居中的一句灰字。
func paneMessage(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(coui.SizeBodyM).TextColor(coui.Of(c).Secondary).TextAlign(ui.Center).FillWidth().Padding(24, 24, 8, 24)
}

// sessionGroup 是一组会话：小标题（第一组离上面 8，别的 20），下面各个会话。
func (a *App) sessionGroup(c *ui.Context, title string, list []hostlink.Session, first bool) {
	top := float32(20)
	if first {
		top = 8
	}
	ui.Text(c, title).FontSize(coui.SizeBodyXS).FontWeight(coui.Medium).TextColor(coui.Of(c).Secondary).Padding(top, 32, 6, 32)
	for _, s := range list {
		a.sessionRow(c, s)
	}
}

// sessionRow 是一行会话：标题，下面是所在的目录（列着全部设备时前面是设备名）。选中的那行是白底圆角卡片。右键菜单里置顶。
func (a *App) sessionRow(c *ui.Context, s hostlink.Session) {
	k := coui.Of(c)
	pinned := a.hub.Pinned(s)
	showing := a.showing(s)
	row := ui.Column(c.Key(s.Backend+"/"+s.ID)).Margin(1.5, 16).MinHeight(44).Padding(10, 16).Radius(16).Gap(1).
		Focusable().Label(s.Label()).Role(ui.RoleButton).Cursor(ui.CursorPointer)
	switch {
	case showing:
		row.Background(k.Selected)
	case row.Hovered():
		row.Background(k.Hover)
	}
	row.OnClick(func() {
		a.page = pageHome
		a.hub.OpenSession(s)
	})
	row.PopoverMenu(func(m *ui.Menu) {
		pin := "置顶"
		if pinned {
			pin = "取消置顶"
		}
		if m.Item(pin).Chosen() {
			a.hub.TogglePin(s)
		}
	})
	row.Children(func() {
		title := ui.Text(c, s.Label()).FontSize(coui.SizeBodyL).TextColor(k.Text).SingleLine().Ellipsis("…")
		if showing {
			title.FontWeight(coui.Medium)
		}
		ui.Text(c, a.place(s)).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).SingleLine().Ellipsis("…")
	})
}

// place 是一行会话的小字：工作目录的最后一级，列着全部设备时前面是设备名，别处在运行的注明。
func (a *App) place(s hostlink.Session) string {
	place := s.Place()
	if a.hub.AllHosts() {
		place = a.hub.Name(s.Backend) + " · " + place
	}
	if s.External {
		place += " · 别处运行中"
	}
	return place
}

// showing：聊天窗格正开着会话 s（新对话第一轮以后也算它）。
func (a *App) showing(s hostlink.Session) bool {
	c := a.hub.Chat()
	if c == nil || a.page != pageHome || c.Backend != s.Backend {
		return false
	}
	return c.Session.ID == s.ID || a.pane != nil && a.pane.src == c && a.pane.state.Session == s.ID
}
