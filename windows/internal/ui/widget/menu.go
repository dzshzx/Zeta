package widget

import "github.com/egoist/mygo/ui"

// 弹出菜单是桌面的尺寸：12 圆角的面板，上下留 4；一行 30 高、字 14、图标 16；选中的那项右边一个对勾；
// 悬停、键盘移到时一层淡灰，左右缩进 4。

// Menu 是正在建的菜单：一项项加。
type Menu struct {
	c     *ui.Context
	parts ui.MenuParts
}

// MenuButton 是点开一个菜单的按钮：trigger 给按钮样子和内容（比如 Pill、ChipButton 那样的），build 加菜单项。
// 菜单开着没有，由它自己记。
func MenuButton(c *ui.Context, trigger func(t ui.Element), build func(m *Menu)) ui.Element {
	holder := ui.Box(c).Shrink(0)
	open := ui.Local(holder, "open", func() bool { return false })
	holder.Children(func() {
		parts := ui.MenuBase(c, open)
		trigger(parts.Trigger)
		parts.Popup(func(panel ui.Element) {
			k := Of(c)
			// 阴影照 ListPopup：浅色是很淡、很散的一圈；浅色不描边（照 ChatGPT 客户端，只靠阴影和底分开）
			Surface{Shape: ShapePopup, Fill: k.PopupGlass, Edge: PopupEdge(k)}.Paint(panel).MinWidth(180).MaxWidth(280).Padding(4, 0).
				Margin(6, 0, 0, 0).Shadow(0, 6, 24, 0, popupShadow(k))
			build(&Menu{c: c, parts: parts})
		})
	})
	return holder
}

// Item 加一项，返回这一次是不是选了它（选了菜单就关上）。
func (m *Menu) Item(label string) bool { return m.item(label, m.parts.Item(), false) }

// Choice 加单选的一项：on 时右边打勾。返回这一次是不是选了它。
func (m *Menu) Choice(label string, on bool) bool { return m.item(label, m.parts.RadioItem(on), on) }

// Separator 加一条分隔线。
func (m *Menu) Separator() {
	ui.Box(m.c).Height(1).Margin(4, 10).Background(Of(m.c).Divider)
}

// Entry 是菜单的一项：前面的图标（可以没有）和名字；Disabled 时灰着点不了，Danger 时红字（删除这类）。
type Entry struct {
	Icon     *ui.SVG
	Label    string
	Disabled bool
	Danger   bool
}

// Add 加一项，返回这一次是不是选了它。
func (m *Menu) Add(e Entry) bool {
	it := m.parts.Item().Disabled(e.Disabled)
	return m.entry(e, it, false)
}

func (m *Menu) item(label string, it ui.Element, on bool) bool {
	return m.entry(Entry{Label: label}, it, on)
}

func (m *Menu) entry(e Entry, it ui.Element, on bool) bool {
	c := m.c
	k := Of(c)
	it.Label(e.Label).MinHeight(30).Margin(0, 4).Radius(6).Padding(5, 10).Gap(10).AlignItems(ui.Center).Cursor(ui.CursorPointer)
	if it.Highlighted() && !e.Disabled {
		it.Background(k.Hover)
	}
	color := k.Text
	if e.Danger {
		color = k.Error
	}
	it.Children(func() {
		if e.Icon != nil {
			ui.Icon(c, e.Icon).Size(16, 16).TextColor(color).Shrink(0)
		}
		ui.Text(c, e.Label).FontSize(SizeBodyM).TextColor(color).Grow(1).SingleLine()
		if on {
			ui.Icon(c, IconOK).Size(16, 16).TextColor(k.Primary)
		}
	})
	return it.Clicked()
}

// popupShadow 是菜单的阴影色（ListPopup 的光斑层：浅色约 4%，深色约 11%，再加一点让它在白底上看得出来）。
func popupShadow(k *Colors) ui.Color {
	if k.Dark {
		return ui.RGBA(0, 0, 0, 0.3)
	}
	return ui.RGBA(0, 0, 0, 0.1)
}

// PopupEdge 是弹出面板（菜单、悬停卡片）的描边：浅色没有，只靠阴影和白底分开（照 ChatGPT 客户端）；
// 深色的底是纯黑、阴影看不出来，描一圈细边。
func PopupEdge(k *Colors) ui.Color {
	if k.Dark {
		return k.Divider
	}
	return ui.Color{}
}
