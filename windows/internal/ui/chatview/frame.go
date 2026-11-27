package chatview

import (
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 列表里一行的样子：内容那一列（Width）、第一行上面的头部（Header 和「更早的记录」）、最后一行下面的 Footer、
// 条目连成卡片（Framed）。都不设时一行只是条目本身，不多分配闭包。

// column 是一行内容的那一列：居中，最宽 Width（没设时 contentWidth）。
func (ch *Chat) column(c *ui.Context) ui.Element {
	width := ch.Width
	if width == 0 {
		width = contentWidth
	}
	return ui.Column(c).FillWidth().MaxWidth(width).AlignSelf(ui.Center)
}

// row 画 rows 行里的第 row 行：第一行先画头部，最后一行接着画 Footer；Framed 时条目（和 Footer）装在卡片的一段里。
func (ch *Chat) row(c *ui.Context, row, rows int) {
	if row == 0 {
		ch.header(c)
	}
	if !ch.Framed {
		ch.item(c, row)
		if row == rows-1 && ch.Footer != nil {
			ch.Footer(c)
		}
		return
	}
	cardSegment(c, row == 0, row == rows-1).Children(func() {
		ch.item(c, row)
		if row == rows-1 && ch.Footer != nil {
			ch.Footer(c)
		}
	})
}

// header 是第一条上面的部分：Header，再是「更早的记录」。它们画在第一条所在的那一行里，不单占一行：列表按看得见的第一行记位置，
// 「更早的记录」要是单占一行，翻到最顶上时就是它，接上的一页会把正看着的那条往下推走。
func (ch *Chat) header(c *ui.Context) {
	if ch.Header != nil {
		ch.Header(c)
	}
	if ch.Earlier.shown() {
		ch.earlierRow(c)
	}
}

// cardSegment 是卡片里一行的那一段：白底，只有第一段画上面的圆角、最后一段画下面的圆角，几段连成一张卡片。
// 卡片两边让出 16，里边上 12、下 16、两边 24。
func cardSegment(c *ui.Context, first, last bool) ui.Element {
	shape := widget.ShapeCard
	var top, bottom float32
	if first {
		top = 12
	} else {
		shape.Radii[0], shape.Radii[1] = 0, 0
	}
	if last {
		bottom = 16
	} else {
		shape.Radii[2], shape.Radii[3] = 0, 0
	}
	card := widget.Surface{Shape: shape, Fill: widget.Of(c).Card}.Paint(ui.Column(c)).Margin(0, 16).Clip()
	return card.Padding(top, 24, bottom, 24)
}

// indexRounds 在条目换了以后重算各条用户消息的下标和各轮的操作排挂在哪一项下面。
func (ch *Chat) indexRounds() {
	ch.users = ch.users[:0]
	clear(ch.rounds)
	for i, it := range ch.items {
		if it.Kind != items.User {
			continue
		}
		if it.RoundLast != "" {
			ch.rounds[it.RoundLast] = len(ch.users)
		}
		ch.users = append(ch.users, i)
	}
}
