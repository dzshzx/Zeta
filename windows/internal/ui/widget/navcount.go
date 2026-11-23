package widget

import (
	"strconv"

	"github.com/egoist/mygo/ui"
)

// NavCount 是侧栏顶上带个数的一项（样子同 NavItem）：行尾是 count（0 时不写），tip 是悬停提示和读屏的说明（空时没有）。
// 个数用次要文字色，比 NavItem 行尾的快捷键提示显眼一档。
func NavCount(c *ui.Context, icon *ui.SVG, label string, count int, tip string, selected bool) ui.Element {
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
	if tip != "" {
		row.Description(tip).Tooltip(tip)
	}
	row.Children(func() {
		ui.Icon(c, icon).Size(18, 18).TextColor(k.Text).Shrink(0)
		weight := 0
		if selected {
			weight = Medium
		}
		ui.Text(c, label).FontSize(SizeBodyM).FontWeight(weight).TextColor(k.Text).SingleLine().Ellipsis("…").Grow(1).MinWidth(0)
		if count > 0 {
			ui.Text(c, strconv.Itoa(count)).FontSize(SizeBodyS).FontWeight(Medium).TextColor(k.Secondary).
				SingleLine().Shrink(0)
		}
	})
	return row
}
