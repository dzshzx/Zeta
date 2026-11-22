package coui

import "github.com/egoist/mygo/ui"

// 按钮。COUI 的按钮都是胶囊（basic/Button.kt）：主按钮蓝底白字，次按钮灰底黑字；大的最小 132×44、字 16 Medium，
// 小的（Zeta 的 CompactButton）最小 52×36、字 14 Medium。不能点时主按钮换淡蓝底、白字变淡，次按钮整个变淡。

// PrimaryButton 是大号主按钮（对话框、确认卡的推荐操作）。
func PrimaryButton(c *ui.Context, label string) ui.Element {
	return button(c, label, kindPrimary, true)
}

// Button 是大号次按钮。
func Button(c *ui.Context, label string) ui.Element { return button(c, label, kindPlain, true) }

// DangerButton 是大号的危险操作按钮：灰底红字。
func DangerButton(c *ui.Context, label string) ui.Element { return button(c, label, kindDanger, true) }

// SmallPrimaryButton 是小号主按钮（行里的「添加」「保存」这类）。
func SmallPrimaryButton(c *ui.Context, label string) ui.Element {
	return button(c, label, kindPrimary, false)
}

// SmallButton 是小号次按钮。
func SmallButton(c *ui.Context, label string) ui.Element { return button(c, label, kindPlain, false) }

// SmallDangerButton 是小号的危险操作按钮。
func SmallDangerButton(c *ui.Context, label string) ui.Element {
	return button(c, label, kindDanger, false)
}

type buttonKind int

const (
	kindPlain buttonKind = iota
	kindPrimary
	kindDanger
	kindDialog       // 对话框里的普通按钮
	kindDialogDanger // 对话框里的危险操作
)

// DialogButton 是对话框底下的按钮：Recommend 是主按钮，Destructive 是红字，Plain 是比对话框亮一点的灰底。
func DialogButton(c *ui.Context, label string, style Style) ui.Element {
	switch style {
	case Recommend:
		return button(c, label, kindPrimary, true)
	case Destructive:
		return button(c, label, kindDialogDanger, true)
	}
	return button(c, label, kindDialog, true)
}

func button(c *ui.Context, label string, kind buttonKind, large bool) ui.Element {
	k := Of(c)
	// 不能点时 mygo 在画的时候把整个按钮调到一半不透明（同 COUI 的 disabled 透明度）：调用方往往是建好按钮以后才设
	// Disabled，这里建按钮时还看不出来
	b := ui.ButtonBase(c).Label(label).Radius(999).Justify(ui.Center).AlignItems(ui.Center).Cursor(ui.CursorPointer)
	size := float32(SizeBodyM)
	if large {
		b.MinWidth(96).MinHeight(44).Padding(8, 20)
		size = SizeHeadlineXS
	} else {
		b.MinWidth(52).MinHeight(34).Padding(6, 16)
	}
	fill, text := k.Button, k.Text
	switch kind {
	case kindPrimary:
		fill, text = k.Primary, k.OnPrimary
	case kindDanger:
		text = k.Error
	case kindDialog:
		fill = k.DialogButton
	case kindDialogDanger:
		fill, text = k.DialogButton, k.Error
	}
	switch {
	case b.Pressed():
		fill = fill.Mix(k.Text, 0.12)
	case b.Hovered():
		fill = fill.Mix(k.Text, 0.06)
	}
	b.Background(fill)
	b.Children(func() { ui.Text(c, label).FontSize(size).FontWeight(Medium).TextColor(text).SingleLine() })
	return b
}

// TextColor 换按钮上字的颜色（比如危险操作用 Error）：对 button 返回的元素调。
// mygo 的文字颜色会传给子元素，这里只是写明用法。
func TextColor(b ui.Element, color ui.Color) ui.Element { return b.TextColor(color) }

// CircleButton 是浮起的圆按钮（COUI 的 IconActionButton、侧栏的 PaneCircleButton）：40 的圆，bar 色底，下面有一层柔和的阴影，
// 图标 22、正文色。label 是读屏名字和悬停提示。
func CircleButton(c *ui.Context, icon *ui.SVG, label string) ui.Element {
	k := Of(c)
	b := ui.ButtonBase(c).Size(40, 40).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0)
	fill := k.BarGlass
	switch {
	case b.Pressed():
		fill = fill.Mix(k.Text, 0.12)
	case b.Hovered():
		fill = fill.Mix(k.Text, 0.05)
	}
	Shadow(Surface{Shape: Rounded(20, 2), Fill: fill, Blur: BlurBar}.Paint(b), k)
	b.Children(func() { ui.Icon(c, icon).Size(22, 22).TextColor(k.Text) })
	return b
}

// ChipButton 是输入栏下面那排小圆钮（COUI 的 IconChip，BarButtons.kt）：28 的圆，container8 底，图标 16、次要文字色。
// tint 不是零值时图标用它、底是它的淡色（权限模式的盾牌）。
func ChipButton(c *ui.Context, icon *ui.SVG, label string, tint ui.Color) ui.Element {
	return Chip(c, ui.ButtonBase(c), icon, label, tint)
}

// Chip 给按钮 b（ButtonBase，或菜单的触发器）ChipButton 的样子和图标。
func Chip(c *ui.Context, b ui.Element, icon *ui.SVG, label string, tint ui.Color) ui.Element {
	k := Of(c)
	fill, ink := k.Chip, k.Secondary
	if tint != (ui.Color{}) {
		fill, ink = tint.Alpha(chipTint(k)), tint
	}
	b.Size(28, 28).Radius(14).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0)
	switch {
	case b.Pressed():
		fill = fill.Mix(k.Text, 0.12)
	case b.Hovered():
		fill = fill.Mix(k.Text, 0.06)
	}
	b.Background(fill)
	b.Children(func() { ui.Icon(c, icon).Size(16, 16).TextColor(ink) })
	return b
}

func chipTint(k *Colors) float32 {
	if k.Dark {
		return 0.25
	}
	return 0.15
}

// FlatButton 是平的圆形图标按钮（消息下面的复制这类，FlatIconButton）：size 的圆，没有底色，悬停时淡淡一层；
// 图标 icon 大小 iconSize、次要文字色。
func FlatButton(c *ui.Context, icon *ui.SVG, label string, size, iconSize float32) ui.Element {
	return FlatButtonOf(c, ui.ButtonBase(c), icon, label, size, iconSize)
}

// FlatButtonOf 给按钮 b（ButtonBase，或菜单的触发器）FlatButton 的样子和图标。
func FlatButtonOf(c *ui.Context, b ui.Element, icon *ui.SVG, label string, size, iconSize float32) ui.Element {
	k := Of(c)
	b.Size(size, size).Radius(size / 2).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0)
	switch {
	case b.Pressed():
		b.Background(k.Press)
	case b.Hovered():
		b.Background(k.Hover)
	}
	b.Children(func() { ui.Icon(c, icon).Size(iconSize, iconSize).TextColor(k.Secondary) })
	return b
}

// SendButton 是输入栏右下的发送键（Composer.kt）：32 的圆，能发时蓝底白箭头，不能发时淡灰底、淡箭头。
func SendButton(c *ui.Context, label string, ready bool) ui.Element {
	k := Of(c)
	fill, ink := k.Primary, k.OnPrimary
	if !ready {
		fill, ink = k.Chip, k.Tertiary
	}
	b := ui.ButtonBase(c).Size(32, 32).Radius(16).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0).Disabled(!ready).DimDisabled(false)
	if ready && b.Hovered() {
		fill = fill.Mix(k.OnPrimary, 0.12)
	}
	b.Background(fill)
	b.Children(func() { ui.Icon(c, IconArrowUp).Size(17, 17).TextColor(ink) })
	return b
}

// StopButton 是回答中的停止键：32 的圆，正文色底，里面一个背景色的小方块。
func StopButton(c *ui.Context, label string) ui.Element {
	k := Of(c)
	b := ui.ButtonBase(c).Size(32, 32).Radius(16).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0).Background(k.Text)
	b.Children(func() { ui.Icon(c, IconStop).Size(12, 12).TextColor(k.Window) })
	return b
}

// Pill 是带下拉箭头的胶囊（侧栏的设备切换、HostSwitcher.kt）：淡淡一层底，图标 15 主题色，字 14 Medium，箭头 14。
// trigger 是调用方的触发器（MenuBase 的 Trigger），这里给它样子和内容。
func Pill(c *ui.Context, trigger ui.Element, icon *ui.SVG, label string) {
	k := Of(c)
	trigger.Radius(999).Padding(6, 8, 6, 10).Gap(6).AlignItems(ui.Center).Cursor(ui.CursorPointer).Background(k.Tint)
	if trigger.Hovered() {
		trigger.Background(k.Hover)
	}
	trigger.Children(func() {
		if icon != nil {
			ui.Icon(c, icon).Size(15, 15).TextColor(k.Primary)
		}
		ui.Text(c, label).FontSize(SizeBodyM).FontWeight(Medium).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1).
			MaxWidth(180)
		ui.Icon(c, IconChevronDown).Size(14, 14).TextColor(k.Secondary)
	})
}

// Switch 是 COUI 的开关（basic/Switch.kt）：44×24 的轨道，18 的白色滑块离边 3；开着蓝色，关着灰色。
func Switch(c *ui.Context, on *bool) ui.Element {
	k := Of(c)
	s := ui.SwitchBase(c, on).Size(44, 24).Radius(12).Shrink(0).Cursor(ui.CursorPointer)
	track := k.Track
	if *on {
		track = k.Primary
	}
	s.Background(track)
	x := s.AnimateTo("thumb", map[bool]float32{false: 3, true: 23}[*on], ui.Smooth)
	s.Children(func() {
		ui.Box(c).Absolute().Top(3).Left(x).Size(18, 18).Radius(9).Background(k.OnPrimary).
			Shadow(0, 1.14, 2.29, 0, ui.RGBA(0, 0, 0, 0.1))
	})
	return s
}

// Input 是一行输入框（COUI TextField 的 Rectangle 模式）：淡灰底、10 的平滑圆角，有焦点时一圈主题色细边。
// 占位、读屏名字、提交照 mygo 的写法接着调。对话框里用 LineInput。
func Input(c *ui.Context, value *string) ui.Element {
	k := Of(c)
	in := ui.TextInputBase(c, value).Padding(8, 12).MinHeight(36).FontSize(SizeBodyM).PlaceholderColor(k.Tertiary).
		FocusRing(false)
	surface := Surface{Shape: ShapeField, Fill: k.Edge}
	if in.Focused() {
		surface.Edge = k.Primary
	}
	return surface.Paint(in)
}

// Ring 给按钮 b 画成 28 的圆环（输入栏右边的上下文用量，UsageControls.kt）：灰色的圈上，fraction（0 到 1）那么长一段
// 是 color 色，从正上方顺时针走。
func Ring(c *ui.Context, b ui.Element, label string, fraction float32, color ui.Color) ui.Element {
	k := Of(c)
	fraction = max(0, min(1, fraction))
	b.Size(28, 28).Radius(14).Label(label).Tooltip(label).Cursor(ui.CursorPointer).Shrink(0).Justify(ui.Center).
		AlignItems(ui.Center)
	if b.Hovered() {
		b.Background(k.Hover)
	}
	b.Children(func() {
		ring := ui.Box(c).Size(18, 18).Radius(9).Border(2.2, k.Chip)
		if fraction > 0 {
			ring.BorderConicGradient(ui.ConicGradient{Stops: []ui.GradientStop{
				{Color: color, At: 0}, {Color: color, At: fraction}, {Color: k.Track, At: fraction}, {Color: k.Track, At: 1},
			}})
		} else {
			ring.Border(2.2, k.Track)
		}
	})
	return b
}

// Dropdown 给菜单的触发器 trigger 设置行右边下拉值的样子（COUI 的 OverlayDropdownPreference）：
// 值 13 次要文字色，旁边上下两个小箭头。
func Dropdown(c *ui.Context, trigger ui.Element, value string) {
	k := Of(c)
	trigger.Gap(2).AlignItems(ui.Center).Padding(4, 2, 4, 8).Radius(8).Cursor(ui.CursorPointer)
	if trigger.Hovered() {
		trigger.Background(k.Hover)
	}
	trigger.Children(func() {
		ui.Text(c, value).FontSize(SizeBodyS).TextColor(k.Secondary).SingleLine().Ellipsis("…").Shrink(1)
		ui.Icon(c, IconUnfold).Size(16, 16).TextColor(k.Tertiary)
	})
}

// LineInput 是对话框里的输入框（COUI TextField 的 Line 模式，Zeta 的 DialogTextField）：没有底色，下面一条分隔线，
// 有焦点时线变成主题色；字 16，最少 48 高。lines 大于 1 时是多行，最多 lines 行。
func LineInput(c *ui.Context, value *string, lines int) ui.Element {
	k := Of(c)
	var in ui.Element
	if lines > 1 {
		in = ui.TextAreaBase(c, value).Lines(1, lines)
	} else {
		in = ui.TextInputBase(c, value)
	}
	in.Padding(13, 0).MinHeight(48).FontSize(SizeBodyXL).PlaceholderColor(k.Tertiary).FocusRing(false).FillWidth()
	line := k.Divider
	if in.Focused() {
		line = k.Primary
	}
	return in.BorderWidth(0, 0, 1, 0).BorderColor(line)
}
