package ui

import (
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// allHostsLabel 是设备菜单里列全部设备那一项的名字。
const allHostsLabel = "全部设备"

// sidebar 是左边的侧栏（布局照 ChatGPT 的 Windows 客户端）：灰底铺到窗口顶边，顶上一行品牌和收起侧栏的按钮同时是
// 标题栏（空白处拖动窗口）；然后是「新对话」、搜索框、设备切换，置顶/今天/更早几组会话（搜索时换成结果，看归档时换成
// 归档的）；底下是「设置」和归档的入口。宽度能拖（App.View 里的 ui.Split）。多选时顶上一行和底下换成多选的（sessionops.go）。
func (a *App) sidebar(c *ui.Context) {
	if a.inSettings() {
		a.settingsNav(c)
		return
	}
	k := widget.Of(c)
	hosts := len(a.hub.Hosts()) > 0
	ui.Column(c).Fill().Background(k.Sidebar).Children(func() {
		a.sidebarTop(c)
		if hosts {
			ui.Column(c).Padding(0, 10, 8, 10).Gap(6).Shrink(0).Children(func() {
				widget.NavItem(c, widget.IconNewChat, "新对话", "Ctrl+N", false).OnClick(a.newChat)
				ui.Row(c).FillWidth().Children(func() { a.searchField(c) })
				ui.Row(c).FillWidth().Padding(2, 0, 0, 0).Children(func() { a.hostSwitcher(c) })
			})
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
		ui.Row(c).Padding(8, 10).Gap(4).Shrink(0).AlignItems(ui.Center).BorderWidth(1, 0, 0, 0).BorderColor(k.Divider).
			Children(func() {
				widget.NavItem(c, widget.IconSettings, "设置", "Ctrl+,", false).OnClick(func() { a.open(pageSettings) }).Grow(1).
					Width(0)
				if hosts && !a.hub.Searching() {
					a.archiveToggle(c)
				}
			})
	})
}

// sidebarTop 是侧栏顶上一行：品牌和收起侧栏的按钮，同时是标题栏。
func (a *App) sidebarTop(c *ui.Context) {
	k := widget.Of(c)
	ui.Row(c).Height(widget.TitleBarHeight).Padding(0, 10, 0, 16).Gap(8).AlignItems(ui.Center).Shrink(0).DragWindow().Children(func() {
		brand(c, 22)
		ui.Text(c, "Zeta").FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
		ui.Spacer(c)
		widget.IconButton(c, widget.IconSidebar, "收起侧栏 (Ctrl+B)").OnClick(a.toggleSidebar)
	})
}

// newChat 开一个新对话（侧栏的「新对话」、Ctrl+N）：回到聊天窗格。还没有设备时什么也不做。
func (a *App) newChat() {
	if len(a.hub.Hosts()) == 0 {
		return
	}
	a.page = pageHome
	a.hub.NewChat()
}

// hostSwitcher 是换设备的胶囊和菜单：先是「全部设备」，然后是各台，最后是管理设备。
func (a *App) hostSwitcher(c *ui.Context) {
	h := a.hub
	all := h.AllHosts()
	label := allHostsLabel
	if !all {
		label = h.Name(h.Backend())
	}
	widget.MenuButton(c, func(t ui.Element) { widget.Pill(c, t, widget.IconServer, label) }, func(m *widget.Menu) {
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
	ui.Text(c, text).FontSize(widget.SizeBodyM).TextColor(widget.Of(c).Secondary).TextAlign(ui.Center).FillWidth().Padding(24, 24, 8, 24)
}

// sectionTitle 是一组会话的小标题（第一组离上面 6，别的 16）。
func sectionTitle(c *ui.Context, title string, first bool) {
	top := float32(16)
	if first {
		top = 6
	}
	ui.Text(c, title).FontSize(widget.SizeBodyXS).FontWeight(widget.Medium).TextColor(widget.Of(c).Tertiary).Padding(top, 20, 4, 20)
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

// sessionRow 是一行会话（照 ChatGPT 的侧栏）：36 高、一行标题（搜索时关键词标成主题色），所在的目录（列着全部设备时
// 前面是设备名）放在悬停提示里；搜索时标题下面多一行对话内容里命中的片段。有没发出的草稿时行尾标「草稿」，草稿的开头
// 接在悬停提示里（正开着的那个草稿就在输入框里，不标）。行尾是状态（回答中、等你确认……），
// 没有状态时置顶的标个图钉；鼠标移上去或正开着时多一个「…」，点开和右键一样是会话操作（sessionMenu）。
// 正开着的那个和多选选中的是选中的底色；点击带 Ctrl、Shift 是多选（clickRow）。
func (a *App) sessionRow(c *ui.Context, s hostlink.Session, snippet string) {
	k := widget.Of(c)
	pinned := a.hub.Pinned(s)
	current := a.showing(s)
	needle := strings.TrimSpace(a.hub.Query())
	place := a.place(s, needle != "")
	draft, hasDraft := a.rowDraft(s)
	if hasDraft {
		place += "\n草稿：" + draft
	}
	row := ui.Row(c.Key(s.Backend+"/"+s.ID)).Margin(0.5, 10).MinHeight(36).Padding(0, 6, 0, 10).Gap(8).AlignItems(ui.Center).
		Focusable().Label(rowTitle(s)).Tooltip(place).Role(ui.RoleButton).Cursor(ui.CursorPointer)
	if snippet != "" {
		row.Padding(6, 6, 6, 10)
	}
	// 已结束（没有活着的 Claude Code 进程）的会话标题灰着：一眼看出哪些还占着机器（同手机版）
	titleInk := k.Text
	if s.Ended {
		titleInk = k.Tertiary
		row.Description("已结束")
	}
	hovered := row.Hovered()
	chosen := a.ops.selected[hub.Key(s)]
	switch {
	case current, chosen:
		widget.Surface{Shape: widget.ShapeRow, Fill: k.Selected}.Paint(row)
	case hovered:
		widget.Surface{Shape: widget.ShapeRow, Fill: k.Hover}.Paint(row)
	default:
		row.Radius(widget.ShapeRow.Radii[0]) // 焦点框跟着圆角
	}
	if row.Clicked() {
		a.clickRow(s, row.ClickModifiers())
	}
	row.PopoverMenu(func(m *ui.Menu) { a.sessionMenu(m, s) })
	row.Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Gap(2).Children(func() {
			highlighted(c, rowTitle(s), needle, widget.SizeBodyM, 0, titleInk).SingleLine().Ellipsis("…")
			if snippet != "" {
				highlighted(c, snippet, needle, widget.SizeBodyXS, 0, k.Secondary).MaxLines(2).Ellipsis("…")
			}
		})
		if tag := a.rowTag(s); tag != "" {
			ui.Text(c, tag).FontSize(widget.SizeBodyXS).TextColor(k.Tertiary).SingleLine().Shrink(0)
		}
		// 有没发出的草稿：行尾标「草稿」（配色只有危险操作用红，这里用次要文字色）；草稿的开头在悬停提示里
		if hasDraft {
			ui.Text(c, "草稿").FontSize(widget.SizeBodyXS).TextColor(k.Secondary).SingleLine().Shrink(0)
		}
		if st := a.rowStatus(s, current); st.Activity != hub.Quiet {
			statusLabel(c, st)
		} else if pinned && !hovered {
			ui.Icon(c, widget.IconPin).Size(14, 14).TextColor(k.Tertiary).Shrink(0).Label("已置顶")
		}
		if hovered || current || chosen {
			more := ui.ButtonBase(c.Key("more")).PopoverMenuButton(func(m *ui.Menu) { a.sessionMenu(m, s) })
			widget.FlatButtonOf(c, more, widget.IconMore, "会话操作", 24, 16)
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

// rowTag 是一行会话标题后面的小字：列着全部设备时是设备名，别处在运行的注明（目录只在悬停提示里）。
func (a *App) rowTag(s hostlink.Session) string {
	var tags []string
	if a.hub.AllHosts() {
		tags = append(tags, a.hub.Name(s.Backend))
	}
	if s.External {
		tags = append(tags, "别处运行中")
	}
	return strings.Join(tags, " · ")
}

// place 是一行会话的悬停提示：工作目录的最后一级，列着全部设备时前面是设备名，别处在运行的注明；搜索时归档的也注明。
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
