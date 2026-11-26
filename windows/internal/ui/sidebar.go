package ui

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// sidebar 是左边的侧栏（布局照 ChatGPT 的 Windows 客户端）：灰底铺到窗口顶边，顶上一行品牌和收起侧栏的按钮同时是
// 标题栏（空白处拖动窗口）；然后是「新对话」、搜索框、设备切换，几组会话（照所选的排序分组，排序按钮在第一组标题的右边，
// 见 sidebarsort.go；搜索时换成结果，看归档时换成归档的）；底下是「设置」和归档的入口。宽度能拖（App.View 里的 ui.Split）。多选照资源管理器，操作在右键菜单里（sessionops.go）。
func (a *App) sidebar(c *ui.Context) {
	if a.inSettings() {
		a.settingsNav(c)
		return
	}
	k := widget.Of(c)
	hosts := len(a.hub.Hosts()) > 0
	pane := ui.Column(c).Fill().Background(k.Sidebar)
	a.order.hovered = pane.Hovered() // 排序按钮只在鼠标在侧栏上时显示
	pane.Children(func() {
		a.sidebarTop(c)
		if hosts {
			ui.Column(c).Padding(0, 10, 8, 10).Gap(6).Shrink(0).Children(func() {
				widget.NavItem(c, widget.IconNewChat, locales.CommonNewChat(), "Ctrl+N", false).OnClick(a.newChat)
				a.tasksEntry(c)
				ui.Row(c).FillWidth().Children(func() { a.searchField(c) })
				ui.Row(c).FillWidth().Padding(2, 0, 0, 0).Children(func() { a.hostSwitcher(c) })
			})
		}
		// 焦点在会话列表上（点过、Tab 到某一行）时 Ctrl+A 全选眼前列着的；在输入框里时归输入框（sessionops.go）
		ui.Scroll(c.Key("sessions")).Grow(1).MinHeight(0).OnShortcut(ui.Cmd, ui.KeyA, a.selectAll).Children(func() {
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
				widget.NavItem(c, widget.IconSettings, locales.CommonSettings(), "Ctrl+,", false).OnClick(func() { a.open(pageSettings) }).Grow(1).
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
		widget.IconButton(c, widget.IconSidebar, locales.SidebarCollapse()).OnClick(a.toggleSidebar)
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
	label := locales.SidebarAllHosts()
	if !all {
		label = h.Name(h.Backend())
	}
	widget.MenuButton(c, func(t ui.Element) { widget.Pill(c, t, widget.IconServer, label) }, func(m *widget.Menu) {
		if m.Choice(locales.SidebarAllHosts(), all) {
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
		if m.Item(locales.SidebarManageDevices()) {
			a.open(pageSettings)
		}
	})
}

// sessionGroups 是侧栏的会话：列不出来时说原因，没有时说一声；否则照所选的排序分几组（置顶、今天、更早，手动排序时是置顶和
// 「对话」，见 hub.Groups）。正开着的会话一直在里面。手动排序时各行能拖动改位置（sidebarsort.go）。
func (a *App) sessionGroups(c *ui.Context) {
	h := a.hub
	if len(h.Hosts()) == 0 {
		paneMessage(c, locales.SidebarNoDevices())
		return
	}
	if why := h.Problem(); why != "" {
		paneMessage(c, why)
	}
	g := h.Groups(a.now(), a.currentSession())
	if len(g.Pinned)+len(g.Today)+len(g.Earlier)+len(g.Chats) == 0 {
		if h.Problem() == "" {
			paneMessage(c, locales.SidebarNoChats())
		}
		return
	}
	movable := a.canDrag()
	first := true
	for _, group := range []struct {
		title string
		list  []hostlink.Session
	}{{locales.SidebarGroupPinned(), g.Pinned}, {locales.SidebarGroupToday(), g.Today}, {locales.SidebarGroupEarlier(), g.Earlier},
		{locales.SidebarGroupChats(), g.Chats}} {
		if len(group.list) == 0 {
			continue
		}
		a.groupTitle(c, group.title, first)
		for _, s := range group.list {
			a.sessionRow(c, s, "", movable)
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
	sectionText(c, title).Padding(top, 20, 4, 20)
}

// sectionText 是会话分组小标题的字（sectionTitle、带排序按钮的 groupTitle 共用）。
func sectionText(c *ui.Context, title string) ui.Element {
	return ui.Text(c, title).FontSize(widget.SizeBodyXS).FontWeight(widget.Medium).TextColor(widget.Of(c).Tertiary)
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
// 正开着的那个和多选选中的是选中的底色；点击带 Ctrl、Shift 是多选（clickRow）。movable 时能拖动改位置（手动排序，sidebarsort.go）。
func (a *App) sessionRow(c *ui.Context, s hostlink.Session, snippet string, movable bool) {
	k := widget.Of(c)
	pinned := a.hub.Pinned(s)
	current := a.showing(s)
	needle := strings.TrimSpace(a.hub.Query())
	place := a.place(s, needle != "")
	draft, hasDraft := a.rowDraft(s)
	if hasDraft {
		place += "\n" + locales.SidebarDraftLine(draft)
	}
	key := hub.Key(s)
	row := ui.Row(c.Key(key)).Margin(0.5, 10).MinHeight(36).Padding(0, 6, 0, 10).Gap(8).AlignItems(ui.Center).
		Focusable().Label(rowTitle(s)).Description(place).Role(ui.RoleButton).Cursor(ui.CursorPointer)
	a.rowCard(c, row, s, draft, hasDraft, needle != "")
	line := noLine
	if movable {
		line = a.movable(row, s)
	}
	if snippet != "" {
		row.Padding(6, 6, 6, 10)
	}
	// 标题照 ChatGPT 客户端用次要色，开着的、选中的用正文色，一眼找到当前那个；
	// 已结束（没有活着的 Claude Code 进程）的再浅一级：一眼看出哪些还占着机器（同手机版）
	hovered := row.Hovered()
	chosen := a.ops.selected[key]
	titleInk := k.Secondary
	switch {
	case s.Ended:
		titleInk = k.Tertiary
		row.Description(locales.SidebarEnded())
	case current || chosen:
		titleInk = k.Text
	}
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
		// 行尾只放小图标（参考 ChatGPT 客户端，不每行写一串字）：读屏和测试照旧读到它们的字，说明在悬停卡片里
		if a.hub.AllHosts() {
			ui.Text(c, a.hub.Name(s.Backend)).FontSize(widget.SizeBodyXS).TextColor(k.Tertiary).SingleLine().Shrink(0)
		}
		if s.External {
			ui.Icon(c, widget.IconTerminal).Size(14, 14).TextColor(k.Tertiary).Shrink(0).Label(locales.SidebarRunningElsewhere())
		}
		// 有没发出的草稿：行尾一支笔（配色只有危险操作用红，这里用次要文字色）；草稿的开头在悬停卡片里
		if hasDraft {
			ui.Icon(c, widget.IconEdit).Size(14, 14).TextColor(k.Secondary).Shrink(0).Label(locales.SidebarDraft())
		}
		if st := a.rowStatus(s, current); st.Activity != hub.Quiet {
			statusLabel(c, st)
		} else if pinned && !hovered {
			ui.Icon(c, widget.IconPin).Size(14, 14).TextColor(k.Tertiary).Shrink(0).Label(locales.SidebarPinned())
		}
		// 菜单开着时照建按钮（见 sessionOps.moreOpen）
		if open := a.ops.moreOpen == key; hovered || current || chosen || open {
			more := ui.ButtonBase(c.Key("more")).PopoverMenuButton(func(m *ui.Menu) { a.sessionMenu(m, s) },
				ui.PopoverMenuButtonOptions{Open: &open})
			if open {
				a.ops.moreOpen, a.ops.moreSeen = key, true
			}
			widget.FlatButtonOf(c, more, widget.IconMore, locales.SidebarRowActions(), 24, 16)
		}
		line.draw(c)
	})
}

// rowTitle 是一行会话的标题：没有标题时是第一句话，都没有时是「未命名对话」。
func rowTitle(s hostlink.Session) string {
	if t := strings.TrimSpace(hub.SessionLabel(s, i18n.Locale())); t != "" {
		return t
	}
	return locales.SidebarUntitled()
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

// rowCard 是会话行的悬停卡片（参考 ChatGPT 客户端）：标题和多久以前动过，下面一行一项——目录、设备、
// 别处在运行或已结束、草稿的开头、搜索时的已归档。贴在行的右边，读屏读行的 Description（同样这些字）。
func (a *App) rowCard(c *ui.Context, row ui.Element, s hostlink.Session, draft string, hasDraft, searching bool) {
	ui.TooltipBase(c, row, func(tip ui.Element) {
		k := widget.Of(c)
		tip.AttachTo(row, ui.AnchorEnd, ui.AnchorStart).MarginStart(8)
		widget.Shadow(widget.Surface{Shape: widget.ShapePopup, Fill: k.PopupGlass, Edge: widget.PopupEdge(k)}.Paint(tip), k)
		tip.Width(300).Padding(10, 14)
		line := func(icon *ui.SVG, text string, ink ui.Color) {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Icon(c, icon).Size(15, 15).TextColor(k.Tertiary).Shrink(0)
				ui.Text(c, text).FontSize(widget.SizeBodyS).TextColor(ink).SingleLine().Ellipsis("…").MinWidth(0)
			})
		}
		tip.Children(func() {
			ui.Column(c).FillWidth().Gap(6).Children(func() {
				ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
					ui.Text(c, rowTitle(s)).FontSize(widget.SizeBodyM).FontWeight(600).TextColor(k.Text).
						SingleLine().Ellipsis("…").Grow(1).MinWidth(0)
					if s.Updated > 0 {
						ui.Text(c, ago(time.UnixMilli(s.Updated), time.Now())).FontSize(widget.SizeBodyXS).
							TextColor(k.Tertiary).SingleLine().Shrink(0)
					}
				})
				line(widget.IconFolder, s.Place(), k.Secondary)
				line(widget.IconServer, a.hub.Name(s.Backend), k.Secondary)
				switch {
				case s.External:
					line(widget.IconTerminal, locales.SidebarRunningElsewhere(), k.Secondary)
				case s.Ended:
					line(widget.IconClock, locales.SidebarEnded(), k.Tertiary)
				}
				if hasDraft {
					line(widget.IconEdit, locales.SidebarDraftLine(draft), k.Secondary)
				}
				if searching && s.Archived {
					line(widget.IconArchive, locales.SidebarArchived(), k.Secondary)
				}
			})
		})
	})
}

// ago 是 t 离 now 多久：刚刚、几分钟前、几小时前、几天前、几周前，再早写日期。
func ago(t, now time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return locales.SidebarAgoJustNow()
	case d < time.Hour:
		return locales.SidebarAgoMinutes(int(d / time.Minute))
	case d < 24*time.Hour:
		return locales.SidebarAgoHours(int(d / time.Hour))
	case d < 7*24*time.Hour:
		return locales.SidebarAgoDays(int(d / (24 * time.Hour)))
	case d < 5*7*24*time.Hour:
		return locales.SidebarAgoWeeks(int(d / (7 * 24 * time.Hour)))
	case t.Year() == now.Year():
		return locales.SidebarAgoDate(t)
	}
	return locales.SidebarAgoDateYear(t)
}

// place 是一行会话读屏用的说明（悬停卡片里是同样这些）：工作目录的最后一级，列着全部设备时前面是设备名，别处在运行的注明；搜索时归档的也注明。
func (a *App) place(s hostlink.Session, searching bool) string {
	place := s.Place()
	if a.hub.AllHosts() {
		place = a.hub.Name(s.Backend) + " · " + place
	}
	if s.External {
		place = locales.SidebarPlaceRunningElsewhere(place)
	}
	if searching && s.Archived {
		place = locales.SidebarPlaceArchived(place)
	}
	return place
}

// showing：会话 s 是聊天窗格里开着的那个（新对话第一轮以后也算它）。在别的页时也算：回去就是它。
func (a *App) showing(s hostlink.Session) bool {
	cur := a.currentSession()
	return cur.ID != "" && cur.Backend == s.Backend && cur.ID == s.ID
}
