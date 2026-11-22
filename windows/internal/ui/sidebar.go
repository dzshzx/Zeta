package ui

import (
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/egoist/mygo/ui"
)

// allHostsLabel 是设备菜单里列全部设备那一项的名字。
const allHostsLabel = "全部设备"

const sidebarWidth = 280

// sidebar 是左边的侧栏：品牌、设备切换，置顶/今天/更早几组会话，底下是设备的入口。
func (a *App) sidebar(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Width(sidebarWidth).Shrink(0).Background(t.Surface).Children(func() {
		ui.Row(c).Height(56).Padding(0, 12, 0, 16).AlignItems(ui.Center).Children(func() {
			ui.Text(c, "Zeta").FontSize(18).Bold()
			ui.Spacer(c)
			if len(a.hub.Hosts()) > 0 {
				ui.Button(c, "新对话").OnClick(func() {
					a.page = pageHome
					a.hub.NewChat()
				})
			}
		})
		if len(a.hub.Hosts()) > 0 {
			ui.Row(c).Padding(0, 12, 8, 12).Children(func() { a.hostSwitcher(c) })
		}
		ui.Scroll(c.Key("sessions")).Grow(1).Children(func() {
			ui.Column(c).Padding(0, 8, 12, 8).Gap(2).Children(func() { a.sessionGroups(c) })
		})
		ui.Box(c).Height(1).Background(t.Border)
		ui.Row(c).Padding(8, 12).Children(func() {
			ui.Button(c, "设备").OnClick(func() { a.open(pageDevices) })
		})
	})
}

// hostSwitcher 是换设备的菜单：先是「全部设备」，然后是各台，最后是管理设备。
func (a *App) hostSwitcher(c *ui.Context) {
	h := a.hub
	all := h.AllHosts()
	label := allHostsLabel
	if !all {
		label = h.Name(h.Backend())
	}
	ui.PopoverMenuButton(c, label, func(m *ui.Menu) {
		if m.Item(allHostsLabel).Checked(all).Chosen() {
			h.ListAll()
		}
		m.Separator()
		for _, x := range h.Hosts() {
			if m.Item(x.Name()).Checked(!all && x.ID == h.Backend()).Chosen() {
				h.ListOnly(x.ID)
			}
		}
		m.Separator()
		if m.Item("管理设备").Chosen() {
			a.open(pageDevices)
		}
	}).Label("设备").MaxWidth(sidebarWidth - 24)
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
	a.sessionGroup(c, "置顶", g.Pinned)
	a.sessionGroup(c, "今天", g.Today)
	a.sessionGroup(c, "更早", g.Earlier)
}

func paneMessage(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(13).TextColor(c.Theme().TextMuted).Padding(8)
}

// sessionGroup 是一组会话：标题一行，下面各个会话；空的不列。
func (a *App) sessionGroup(c *ui.Context, title string, list []host.Session) {
	if len(list) == 0 {
		return
	}
	t := c.Theme()
	ui.Text(c, title).FontSize(12).Bold().TextColor(t.TextMuted).Padding(12, 8, 4, 8)
	for _, s := range list {
		a.sessionRow(c, s)
	}
}

// sessionRow 是一行会话：标题，下面是所在的目录（列着全部设备时前面是设备名）。右键菜单里置顶。
func (a *App) sessionRow(c *ui.Context, s host.Session) {
	t := c.Theme()
	pinned := a.hub.Pinned(s)
	row := ui.Column(c.Key(s.Backend+"/"+s.ID)).Padding(6, 8).Radius(8).Gap(2).Focusable().Label(s.Label()).
		Role(ui.RoleButton).Cursor(ui.CursorPointer)
	switch {
	case a.showing(s):
		row.Background(t.SurfacePressed)
	case row.Hovered():
		row.Background(t.SurfaceHover)
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
		ui.Text(c, s.Label()).SingleLine()
		ui.Text(c, a.place(s)).FontSize(12).TextColor(t.TextMuted).SingleLine()
	})
}

// place 是一行会话的小字：工作目录的最后一级，列着全部设备时前面是设备名，别处在运行的注明。
func (a *App) place(s host.Session) string {
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
func (a *App) showing(s host.Session) bool {
	c := a.hub.Chat()
	if c == nil || a.page != pageHome || c.Backend != s.Backend {
		return false
	}
	return c.Session.ID == s.ID || a.pane != nil && a.pane.src == c && a.pane.state.Session == s.ID
}
