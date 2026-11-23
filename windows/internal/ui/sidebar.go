package ui

import (
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// allHostsLabel 是设备菜单里列全部设备那一项的名字。
const allHostsLabel = "全部设备"

// sidebarWidth 是侧栏的宽度。
const sidebarWidth = 300

// sidebar 是左边的侧栏（照手机版的 SessionPane）：灰底铺到窗口顶边，顶上一行品牌和新对话同时是标题栏（空白处拖动窗口），
// 然后是设备切换，置顶/今天/更早几组会话（搜索时换成结果，看归档时换成归档的），
// 底下左边是搜索框、右边是归档的入口和设置按钮。多选时顶上一行和底下换成多选的（sessionops.go）。
func (a *App) sidebar(c *ui.Context) {
	k := coui.Of(c)
	var chosen []hostlink.Session
	if a.ops.selecting {
		chosen = a.chosen()
	}
	ui.Column(c).Width(sidebarWidth).Shrink(0).Background(k.Sidebar).Children(func() {
		if a.ops.selecting {
			a.selectionHeader(c, chosen)
		} else {
			a.sidebarTop(c)
		}
		if len(a.hub.Hosts()) > 0 && !a.ops.selecting {
			ui.Row(c).Padding(0, 16, 8, 16).Shrink(0).Children(func() { a.hostSwitcher(c) })
		}
		ui.Scroll(c.Key("sessions")).Grow(1).MinHeight(0).Children(func() {
			ui.Column(c).Padding(0, 0, 12, 0).Children(func() {
				switch {
				case a.hub.Searching():
					a.searchResults(c)
				case a.ops.archived:
					a.archivedGroup(c)
				default:
					a.sessionGroups(c)
				}
			})
		})
		if a.ops.selecting {
			a.selectionActions(c, chosen)
			return
		}
		ui.Row(c).Padding(10, 16).Gap(8).Shrink(0).AlignItems(ui.Center).Children(func() {
			if len(a.hub.Hosts()) > 0 {
				a.searchField(c)
			} else {
				ui.Spacer(c)
			}
			if !a.hub.Searching() {
				a.archiveToggle(c)
			}
			a.settingsButton(c)
		})
	})
	a.sessionDialogs(c)
}

// sidebarTop 是侧栏顶上一行：品牌和新对话，同时是标题栏。
func (a *App) sidebarTop(c *ui.Context) {
	k := coui.Of(c)
	ui.Row(c).Height(coui.TitleBarHeight).Padding(0, 12, 0, 20).Gap(10).AlignItems(ui.Center).Shrink(0).DragWindow().Children(func() {
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
}

// settingsButton 是侧栏右下角的设置按钮，进设置页。
func (a *App) settingsButton(c *ui.Context) {
	coui.CircleButton(c, coui.IconSettings, "设置").OnClick(func() { a.open(pageSettings) })
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
			name := x.Name()
			if off := offLabel(x); off != "" {
				name += " · " + off
			}
			if m.Choice(name, !all && x.ID == h.Backend()) {
				h.ListOnly(x.ID)
			}
		}
		m.Separator()
		if m.Item("管理设备") {
			a.open(pageSettings)
		}
	})
}

// sessionGroups 是侧栏的会话：列不出来时说原因，没有时说一声；否则置顶、今天、更早几组。正开着的会话一直在里面（见 hub.Groups）。
func (a *App) sessionGroups(c *ui.Context) {
	h := a.hub
	if len(h.Hosts()) == 0 {
		paneMessage(c, "还没有设备")
		return
	}
	if why := h.Problem(); why != "" {
		paneMessage(c, why)
	}
	g := h.Groups(a.now(), a.currentSession())
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
		sectionTitle(c, group.title, first)
		for _, s := range group.list {
			a.sessionRow(c, s, "")
		}
		first = false
	}
}

// paneMessage 是侧栏里居中的一句灰字。
func paneMessage(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(coui.SizeBodyM).TextColor(coui.Of(c).Secondary).TextAlign(ui.Center).FillWidth().Padding(24, 24, 8, 24)
}

// sectionTitle 是一组会话的小标题（第一组离上面 8，别的 20）。
func sectionTitle(c *ui.Context, title string, first bool) {
	top := float32(20)
	if first {
		top = 8
	}
	ui.Text(c, title).FontSize(coui.SizeBodyXS).FontWeight(coui.Medium).TextColor(coui.Of(c).Secondary).Padding(top, 32, 6, 32)
}

// currentSession 是聊天窗格里开着的会话；新对话有了会话号以后也算，标题用核心报的。还没有会话号时是零值。
func (a *App) currentSession() hostlink.Session {
	c := a.hub.Chat()
	if c == nil {
		return hostlink.Session{}
	}
	s := c.Session
	if p := a.pane; p != nil && p.src == c {
		if p.state.Session != "" {
			s.ID = p.state.Session
		}
		if s.Title == "" {
			s.Title = p.state.Title
		}
		// 新对话起头时是设备的默认目录，核心报来的才是这个对话实际的目录
		if p.state.Cwd != "" {
			s.Cwd = p.state.Cwd
		}
	}
	return s
}

// sessionRow 是一行会话：标题（搜索时关键词标成主题色），下面是对话内容里命中的片段，或所在的目录（列着全部设备时前面是设备名）；
// 行尾是状态（回答中、等你确认……），没有状态时置顶的标个图钉。正开着的那个是白底圆角卡片。右键菜单是会话操作（sessionMenu）；
// 多选时点一行是勾选，行尾是复选框，没有右键菜单。
func (a *App) sessionRow(c *ui.Context, s hostlink.Session, snippet string) {
	k := coui.Of(c)
	pinned := a.hub.Pinned(s)
	current := a.showing(s)
	needle := strings.TrimSpace(a.hub.Query())
	row := ui.Row(c.Key(s.Backend+"/"+s.ID)).Margin(1.5, 16).MinHeight(44).Padding(10, 16).Gap(8).AlignItems(ui.Center).
		Focusable().Label(rowTitle(s)).Role(ui.RoleButton).Cursor(ui.CursorPointer)
	// 已结束（没有活着的 Claude Code 进程）的会话标题和小字都灰着：一眼看出哪些还占着机器（同手机版）
	titleInk, noteInk := k.Text, k.Secondary
	if s.Ended {
		titleInk, noteInk = k.Tertiary, k.Tertiary
		row.Description("已结束")
	}
	switch {
	case current:
		coui.Surface{Shape: coui.ShapeRow, Fill: k.Selected}.Paint(row)
	case row.Hovered():
		coui.Surface{Shape: coui.ShapeRow, Fill: k.Hover}.Paint(row)
	default:
		row.Radius(coui.ShapeRow.Radii[0]) // 焦点框跟着圆角
	}
	selecting := a.ops.selecting
	row.OnClick(func() {
		if selecting {
			a.toggle(s)
			return
		}
		a.page = pageHome
		a.query = ""
		a.hub.OpenSession(s)
	})
	if !selecting {
		row.PopoverMenu(func(m *ui.Menu) { a.sessionMenu(m, s) })
	}
	row.Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Gap(1).Children(func() {
			weight := 0
			if current {
				weight = coui.Medium
			}
			highlighted(c, rowTitle(s), needle, coui.SizeBodyL, weight, titleInk).SingleLine().Ellipsis("…")
			if snippet != "" {
				highlighted(c, snippet, needle, coui.SizeBodyXS, 0, noteInk).MaxLines(2).Ellipsis("…")
			} else {
				ui.Text(c, a.place(s, needle != "")).FontSize(coui.SizeBodyXS).TextColor(noteInk).SingleLine().Ellipsis("…")
			}
		})
		if st := a.rowStatus(s, current); st.Activity != hub.Quiet {
			statusLabel(c, st)
		} else if pinned && !selecting {
			ui.Icon(c, coui.IconPin).Size(16, 16).TextColor(k.Tertiary).Shrink(0).Label("已置顶")
		}
		if selecting {
			coui.MarkLook(c, coui.MarkCheck, a.ops.selected[hub.Key(s)])
		}
	})
}

// rowTitle 是一行会话的标题：没有标题时是第一句话，都没有时是「未命名对话」。
func rowTitle(s hostlink.Session) string {
	if t := strings.TrimSpace(s.Label()); t != "" {
		return t
	}
	return "未命名对话"
}

// rowStatus 是会话 s 行尾的状态：开着的那个加上这边对话的样子。正看着的对话确认框就在眼前，不标等你确认，照在回答标。
func (a *App) rowStatus(s hostlink.Session, current bool) hub.Status {
	var local hub.Local
	if p := a.pane; current && p != nil {
		local = hub.Local{Waiting: len(p.state.Prompts) > 0, Busy: p.state.Busy, Tasks: p.state.RunningTasks()}
	}
	st := a.hub.Status(s, local)
	if current && st.Activity == hub.Waiting {
		st.Activity = hub.Answering
	}
	return st
}

// place 是一行会话的小字：工作目录的最后一级，列着全部设备时前面是设备名，别处在运行的注明；搜索时归档的也注明。
func (a *App) place(s hostlink.Session, searching bool) string {
	place := s.Place()
	if a.hub.AllHosts() {
		place = a.hub.Name(s.Backend) + " · " + place
	}
	if s.External {
		place += " · 别处运行中"
	}
	if searching && s.Archived {
		place += " · 已归档"
	}
	return place
}

// showing：会话 s 是聊天窗格里开着的那个（新对话第一轮以后也算它）。在别的页时也算：回去就是它。
func (a *App) showing(s hostlink.Session) bool {
	cur := a.currentSession()
	return cur.ID != "" && cur.Backend == s.Backend && cur.ID == s.ID
}
