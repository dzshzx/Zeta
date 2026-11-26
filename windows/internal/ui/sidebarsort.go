package ui

import (
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 侧栏的排序（docs/desktop.md「界面」，排法在 hub/order.go）：第一个分组标题那一行的右边是排序按钮，鼠标在侧栏上
// 或菜单开着时才显示（照 ChatGPT 客户端列表标题行的操作按钮），菜单里三种排序，当前那项打勾。手动排序时用鼠标左键按住
// 一行拖动改位置（mygo 的 Drag、Drop：移动几个像素才算拖，不拖就是单击；右键不拖），拖到哪一行的上半、下半，
// 那行的上沿、下沿就显示一条横线，只在同一组里落得下。

// sortState 是侧栏排序的界面状态。
type sortState struct {
	menu    bool // 排序菜单开着
	hovered bool // 鼠标在侧栏上（这一帧建侧栏时读的）
}

// movingRow 是拖动中的那一行（mygo 拖的值）。
type movingRow struct{ s hostlink.Session }

// dropLine 是拖动时一行上显示的插入横线：没有、在上沿、在下沿。
type dropLine int

const (
	noLine dropLine = iota
	lineAbove
	lineBelow
)

// sortModes 是排序菜单的三项，按菜单里的先后。
var sortModes = []struct {
	mode  settings.SessionSort
	label func() string
}{
	{settings.SortReplied, locales.SidebarSortReplied},
	{settings.SortCreated, locales.SidebarSortCreated},
	{settings.SortManual, locales.SidebarSortManual},
}

// groupTitle 是一组会话的小标题；first 的那一组（列表最上面）右边带排序按钮。
func (a *App) groupTitle(c *ui.Context, title string, first bool) {
	if !first {
		sectionTitle(c, title, false)
		return
	}
	// 和别的标题一样高的位置写字，按钮的高度由行吃掉，显不显示按钮这一行都一样高
	ui.Row(c).FillWidth().Height(26).Padding(2, 12, 0, 20).AlignItems(ui.Center).Children(func() {
		sectionText(c, title).Grow(1).MinWidth(0).SingleLine()
		if a.order.hovered || a.order.menu {
			a.sortButton(c)
		}
	})
}

// sortButton 是排序按钮和它的菜单。
func (a *App) sortButton(c *ui.Context) {
	h := a.hub
	widget.MenuButtonOpen(c.Key("sort"), &a.order.menu, func(t ui.Element) {
		widget.FlatButtonOf(c, t, widget.IconSort, locales.SidebarSort(), 24, 16)
	}, func(m *widget.Menu) {
		current := h.Sort()
		for _, o := range sortModes {
			if m.Choice(o.label(), o.mode == current) {
				h.SetSort(o.mode)
			}
		}
	})
}

// canDrag：侧栏的会话行这时能拖动改位置——手动排序、没在多选（搜索结果、归档里的行本来就不拖）。
func (a *App) canDrag() bool {
	return a.hub.Sort() == settings.SortManual && len(a.ops.selected) == 0
}

// movable 让会话 s 的这一行能拖、能接住拖来的行：落下时挪过去（hub.Move），拖着经过时返回要显示的横线。
func (a *App) movable(row ui.Element, s hostlink.Session) dropLine {
	row.Drag(movingRow{s})
	if row.Dragging() {
		row.Opacity(0.4)
	}
	below := func() bool {
		_, y, _ := row.PointerPosition()
		return y >= row.LayoutBounds().H/2
	}
	if m, ok := ui.Drop[movingRow](row); ok {
		a.hub.Move(m.s, s, below(), a.failed(locales.SessionMoveFailed()))
		return noLine
	}
	m, ok := ui.DragOver[movingRow](row)
	if !ok {
		return noLine
	}
	b := below()
	switch {
	case !a.hub.CanMove(m.s, s, b):
		return noLine
	case b:
		return lineBelow
	}
	return lineAbove
}

// draw 在行里画插入横线：2 高、正文色，压在行的上沿或下沿（两行之间的空隙正中）。
func (l dropLine) draw(c *ui.Context) {
	if l == noLine {
		return
	}
	line := ui.Box(c).Absolute().Left(4).Right(4).Height(2).Radius(1).Background(widget.Of(c).Text)
	if l == lineAbove {
		line.Top(-1.5)
	} else {
		line.Bottom(-1.5)
	}
}
