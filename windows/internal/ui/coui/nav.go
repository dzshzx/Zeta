package coui

import "github.com/egoist/mygo/ui"

// NavItem 是侧栏里的一行入口（新对话、设置、设置里的分类）：34 高、8 的圆角，图标 18（可以没有）、字 14，
// 右边是快捷键提示（可以空，13 淡色）。selected 时是选中的底色，悬停、按下时淡淡一层。返回这一行，点了做什么由调用方 OnClick。
func NavItem(c *ui.Context, icon *ui.SVG, label, hint string, selected bool) ui.Element {
	k := Of(c)
	row := ui.ButtonBase(c).Label(label).FillWidth().Height(34).Padding(0, 10).Gap(10).AlignItems(ui.Center).Radius(8).
		Cursor(ui.CursorPointer).Shrink(0)
	switch {
	case selected:
		row.Background(k.Selected)
	case row.Pressed():
		row.Background(k.Press)
	case row.Hovered():
		row.Background(k.Hover)
	}
	if hint != "" {
		row.Tooltip(label + " (" + hint + ")")
	}
	row.Children(func() {
		if icon != nil {
			ui.Icon(c, icon).Size(18, 18).TextColor(k.Text).Shrink(0)
		}
		weight := 0
		if selected {
			weight = Medium
		}
		ui.Text(c, label).FontSize(SizeBodyM).FontWeight(weight).TextColor(k.Text).SingleLine().Ellipsis("…").Grow(1).MinWidth(0)
		if hint != "" {
			ui.Text(c, hint).FontSize(SizeBodyXS).TextColor(k.Tertiary).SingleLine().Shrink(0)
		}
	})
	return row
}
