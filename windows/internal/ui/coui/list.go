package coui

import "github.com/egoist/mygo/ui"

// 设置页的骨架：页面顶上一行（返回键、标题），下面是一组组白色圆角卡片，卡片里是一行行设置。
// 数值照 COUI 的 SmallTopAppBar、PreferenceCategory、Card、BasicComponent、ArrowPreference、PagerFooterPreference
// 和 Zeta 的 PreferenceGroup（卡片离两边 16、离下一张 16）。

// PageWidth 是设置页内容最宽多少（卡片 768，两边各留 16），窗口再宽也居中。
const PageWidth = 768 + 32

// Header 是页面顶上一行：有 back 时左边是浮起的圆形返回键，然后是标题（18 Medium）。
func Header(c *ui.Context, title string, back func()) {
	k := Of(c)
	ui.Row(c).Height(52).Padding(0, 40).Gap(10).AlignItems(ui.Center).Shrink(0).Children(func() {
		if back != nil {
			CircleButton(c, IconBack, "返回").OnClick(back)
		}
		ui.Text(c, title).FontSize(SizeHeadlineS).FontWeight(Medium).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1)
	})
}

// Body 是设置页的内容：卡片后面的浅灰底，居中、最宽 PageWidth，竖着排、能滚动。
func Body(c *ui.Context, key string, fn func()) ui.Element {
	return ui.Scroll(c.Key(key)).Grow(1).MinHeight(0).Children(func() {
		ui.Column(c).FillWidth().MaxWidth(PageWidth).AlignSelf(ui.Center).Padding(8, 0, 32, 0).Children(fn)
	})
}

// Group 是一组：分组标题（可以空）、白色圆角卡片里的各行、卡片下面的说明（可以空）。rows 用 l 一行行加。
func Group(c *ui.Context, title, footer string, rows func(l *List)) {
	k := Of(c)
	ui.Column(c).FillWidth().Children(func() {
		if title != "" {
			ui.Text(c, title).FontSize(SizeBodyXS).FontWeight(Medium).TextColor(k.Secondary).Padding(8, 32, 8, 32)
		}
		l := &List{c: c}
		card := Surface{Shape: ShapeCard, Fill: k.Card}.Paint(ui.Column(c)).Margin(0, 16).Clip().Padding(2, 0)
		card.Children(func() { rows(l) })
		if footer != "" {
			ui.Text(c, footer).FontSize(SizeBodyXS).LineHeight(1.4).TextColor(k.Secondary).Padding(8, 32, 0, 32)
		}
		ui.Box(c).Height(16)
	})
}

// List 是一张卡片里的行：每加一行，和上一行之间画一条分隔线。
type List struct {
	c *ui.Context
	n int
}

// divider 在第二行起的每一行前面画分隔线：从标题开头（start）画到离卡片右边 16 处，细到一个像素。
func (l *List) divider(start float32) {
	if l.n > 0 {
		ui.Box(l.c).Height(1).Margin(0, 16, 0, start).Background(Of(l.c).Divider)
	}
	l.n++
}

// Row 是一行设置（COUI 的 BasicComponent 和 ArrowPreference）的内容。
type Row struct {
	// Icon 是行首的图标（24，颜色 IconColor），没有就不画。
	Icon      *ui.SVG
	IconColor ui.Color
	// Title 是标题（16），Summary 是下面一行小字（13，次要文字色；SummaryColor 不是零值时用它，像出错原因用 Error）。
	Title, Summary string
	SummaryColor   ui.Color
	// Value 是右边的值（14，淡）。
	Value string
	// Arrow：右边画箭头（点进下一页）。
	Arrow bool
	// Danger：标题用红色（删除这类）。
	Danger bool
	// End 画右边自己的控件（开关、按钮、下拉），在 Value 之后。
	End func()
	// OnClick 不为空时整行能点，悬停、按下时变色。
	OnClick func()
	// Key 区分同一张卡片里的行（行会增减时给）。
	Key any
}

// Row 加一行，返回这一行的元素（可以再加读屏名字之类）。
func (l *List) Row(r Row) ui.Element {
	c := l.c
	k := Of(c)
	start := float32(16)
	if r.Icon != nil {
		start += 24 + 16
	}
	l.divider(start)
	if r.Key != nil {
		c = c.Key(r.Key)
	}
	var row ui.Element
	if r.OnClick != nil {
		row = ui.ButtonBase(c).Label(r.Title).Cursor(ui.CursorPointer).FocusRingOffset(-2)
		switch {
		case row.Pressed():
			row.Background(k.Press)
		case row.Hovered():
			row.Background(k.Hover)
		}
		row.OnClick(r.OnClick)
	} else {
		row = ui.Row(c)
	}
	row.FillWidth().MinHeight(48).Padding(10, 16).Gap(16).AlignItems(ui.Center)
	row.Children(func() {
		if r.Icon != nil {
			ui.Icon(c, r.Icon).Size(24, 24).TextColor(r.IconColor).Shrink(0)
		}
		r.texts(c, k)
		if r.Value != "" || r.End != nil || r.Arrow {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Shrink(1).MinWidth(0).Children(func() {
				if r.Value != "" {
					ui.Text(c, r.Value).FontSize(SizeBodyM).TextColor(k.Tertiary).SingleLine().Ellipsis("…").Shrink(1).MinWidth(0)
				}
				if r.End != nil {
					r.End()
				}
				if r.Arrow {
					ui.Icon(c, IconForward).Size(16, 16).TextColor(k.Tertiary).Margin(0, -4, 0, -4).Shrink(0)
				}
			})
		}
	})
	return row
}

// texts 是一行左边的标题和下面的小字。
func (r Row) texts(c *ui.Context, k *Colors) {
	title, summary := k.Text, k.Secondary
	if r.Danger {
		title = k.Error
	}
	if r.SummaryColor.A > 0 {
		summary = r.SummaryColor
	}
	ui.Column(c).Grow(1).MinWidth(0).Gap(1).Children(func() {
		ui.Text(c, r.Title).FontSize(SizeHeadlineXS).TextColor(title).SingleLine().Ellipsis("…")
		if r.Summary != "" {
			ui.Text(c, r.Summary).FontSize(SizeBodyS).TextColor(summary).MaxLines(3).Selectable()
		}
	})
}

// Custom 加一行自己排的内容（比如一行输入框和按钮），左右留 16、上下留 8，前面照样画分隔线。
func (l *List) Custom(fn func()) {
	l.divider(16)
	ui.Row(l.c).FillWidth().MinHeight(48).Padding(8, 16).Gap(12).AlignItems(ui.Center).Children(fn)
}

// Message 加一行灰字（列表空着、正在读之类的说明）。
func (l *List) Message(text string) {
	l.divider(16)
	ui.Text(l.c, text).FontSize(SizeBodyS).TextColor(Of(l.c).Secondary).Padding(14, 16)
}

// Context 是建这张卡片的界面上下文（行里要自己画东西时用）。
func (l *List) Context() *ui.Context { return l.c }
