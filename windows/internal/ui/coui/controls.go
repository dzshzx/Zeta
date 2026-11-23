package coui

import "github.com/egoist/mygo/ui"

// 按钮是胶囊（照 ChatGPT）：主按钮黑底白字（深色里白底黑字），次按钮透明底一圈细边，危险操作红底白字。尺寸是桌面的：大的最少 32 高、字 14 Medium，
// 小的最少 28 高、字 13 Medium；鼠标点，不按手指的触控尺寸放大。不能点时整个变淡。

// PrimaryButton 是大号主按钮（对话框、确认卡的推荐操作）。
func PrimaryButton(c *ui.Context, label string) ui.Element {
	return button(c, label, kindPrimary, true)
}

// Button 是大号次按钮。
func Button(c *ui.Context, label string) ui.Element { return button(c, label, kindPlain, true) }

// DangerButton 是大号的危险操作按钮：红底白字。
func DangerButton(c *ui.Context, label string) ui.Element { return button(c, label, kindDanger, true) }

// SmallPrimaryButton 是小号主按钮（行里的「添加」「保存」这类）。
func SmallPrimaryButton(c *ui.Context, label string) ui.Element {
	return button(c, label, kindPrimary, false)
}

// SmallButton 是小号次按钮。
func SmallButton(c *ui.Context, label string) ui.Element { return button(c, label, kindPlain, false) }

// SmallDangerButton 是小号的危险操作按钮：红字细边。
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

// DialogButton 是对话框底下的按钮：Recommend 是主按钮，Destructive 是红底白字，Plain 是细边的次按钮。
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
	size := float32(SizeBodyS)
	if large {
		b.MinWidth(72).MinHeight(32).Padding(5, 16)
		size = SizeBodyM
	} else {
		b.MinWidth(48).MinHeight(28).Padding(4, 12)
	}
	// 照 ChatGPT：主按钮实心（浅色黑底、深色白底），危险操作红底白字，别的透明底、一圈细边（危险的小按钮是红字细边）
	fill, text, edge := k.Button, k.Text, k.Divider
	switch {
	case kind == kindPrimary:
		fill, text, edge = k.Primary, k.OnPrimary, ui.Color{}
	case kind == kindDialogDanger, kind == kindDanger && large:
		fill, text, edge = k.Error, ui.RGB(255, 255, 255), ui.Color{}
	case kind == kindDanger:
		text = k.Error
	}
	switch {
	case b.Pressed():
		fill = fill.Mix(k.Text, 0.12)
	case b.Hovered():
		fill = fill.Mix(k.Text, 0.06)
	}
	b.Background(fill)
	if edge.A > 0 {
		b.Border(1, edge)
	}
	b.Children(func() { ui.Text(c, label).FontSize(size).FontWeight(Medium).TextColor(text).SingleLine() })
	return b
}

// TextColor 换按钮上字的颜色（比如危险操作用 Error）：对 button 返回的元素调。
// mygo 的文字颜色会传给子元素，这里只是写明用法。
func TextColor(b ui.Element, color ui.Color) ui.Element { return b.TextColor(color) }

// IconButton 是工具栏上的图标按钮（新对话、返回、侧栏开关这类）：32 的方块、8 的圆角，平时没有底色，悬停、按下时
// 淡淡一层；图标 18、正文色。label 是读屏名字和悬停提示（有快捷键时写在提示里）。
func IconButton(c *ui.Context, icon *ui.SVG, label string) ui.Element {
	k := Of(c)
	b := ui.ButtonBase(c).Size(32, 32).Radius(8).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0)
	switch {
	case b.Pressed():
		b.Background(k.Press)
	case b.Hovered():
		b.Background(k.Hover)
	}
	b.Children(func() { ui.Icon(c, icon).Size(18, 18).TextColor(k.Text) })
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

// SendButton 是输入栏右下的发送键（照 ChatGPT）：36 的圆，能发时黑底白箭头（深色里白底黑箭头），不能发时浅灰底。
func SendButton(c *ui.Context, label string, ready bool) ui.Element {
	k := Of(c)
	fill, ink := k.Primary, k.OnPrimary
	if !ready {
		fill = k.Quaternary
	}
	b := ui.ButtonBase(c).Size(36, 36).Radius(18).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0).Disabled(!ready).DimDisabled(false)
	if ready && b.Hovered() {
		fill = fill.Mix(k.OnPrimary, 0.12)
	}
	b.Background(fill)
	b.Children(func() { ui.Icon(c, IconArrowUp).Size(18, 18).TextColor(ink) })
	return b
}

// StopButton 是回答中的停止键：36 的圆，主按钮色底，里面一个小方块（同 ChatGPT）。
func StopButton(c *ui.Context, label string) ui.Element {
	k := Of(c)
	b := ui.ButtonBase(c).Size(36, 36).Radius(18).Justify(ui.Center).AlignItems(ui.Center).Label(label).Tooltip(label).
		Cursor(ui.CursorPointer).Shrink(0).Background(k.Primary)
	b.Children(func() { ui.Icon(c, IconStop).Size(12, 12).TextColor(k.OnPrimary) })
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

// Switch 是开关：40×20 的轨道（Windows 开关的大小），14 的白色滑块离边 3；开着蓝色，关着灰色。
func Switch(c *ui.Context, on *bool) ui.Element {
	k := Of(c)
	s := ui.SwitchBase(c, on).Size(40, 20).Radius(10).Shrink(0).Cursor(ui.CursorPointer)
	track := k.Track
	if *on {
		track = k.Primary
	}
	s.Background(track)
	x := s.AnimateTo("thumb", map[bool]float32{false: 3, true: 23}[*on], ui.Smooth)
	s.Children(func() {
		ui.Box(c).Absolute().Top(3).Left(x).Size(14, 14).Radius(7).Background(k.OnPrimary).
			Shadow(0, 1.14, 2.29, 0, ui.RGBA(0, 0, 0, 0.1))
	})
	return s
}

// Input 是一行输入框：白底（深色是卡片底）、8 的圆角、一圈细边，有焦点时边变主题色；字 14，34 高。
// 占位、读屏名字、提交照 mygo 的写法接着调。
func Input(c *ui.Context, value *string) ui.Element {
	return field(c, ui.TextInputBase(c, value))
}

// field 给输入框 in 一行输入框的样子（Input、LineInput 共用）。
func field(c *ui.Context, in ui.Element) ui.Element {
	k := Of(c)
	in.Padding(7, 10).MinHeight(34).FontSize(SizeBodyM).PlaceholderColor(k.Tertiary).FocusRing(false).Radius(8).
		Background(k.Card).Border(1, k.Divider)
	if in.Focused() {
		in.BorderColor(k.Primary)
	}
	return in
}

// Ring 给按钮 b 画成 28 的圆环（输入栏右边的上下文用量，UsageControls.kt）：灰色的圈上，fraction（0 到 1）那么长一段
// 是 color 色，从正上方顺时针走。
func Ring(c *ui.Context, b ui.Element, label string, fraction float32, color ui.Color) ui.Element {
	k := Of(c)
	b.Size(28, 28).Radius(14).Label(label).Tooltip(label).Cursor(ui.CursorPointer).Shrink(0).Justify(ui.Center).
		AlignItems(ui.Center)
	if b.Hovered() {
		b.Background(k.Hover)
	}
	b.Children(func() { RingArc(c, 18, fraction, color) })
	return b
}

// RingArc 是直径 size、线宽 2.2 的圆环：灰色的圈上，fraction（0 到 1）那么长一段是 color 色，从正上方顺时针走。
func RingArc(c *ui.Context, size, fraction float32, color ui.Color) ui.Element {
	k := Of(c)
	ring := ui.Box(c).Size(size, size).Radius(size/2).Border(2.2, k.Track)
	if f := max(0, min(1, fraction)); f > 0 {
		ring.BorderConicGradient(ui.ConicGradient{Stops: []ui.GradientStop{
			{Color: color, At: 0}, {Color: color, At: f}, {Color: k.Track, At: f}, {Color: k.Track, At: 1},
		}})
	}
	return ring
}

// Dropdown 给菜单的触发器 trigger 设置成下拉框的样子（Windows 的 ComboBox）：一圈细边、6 的圆角，值 13 正文色，
// 右边一个向下的箭头。
func Dropdown(c *ui.Context, trigger ui.Element, value string) {
	k := Of(c)
	trigger.Gap(6).AlignItems(ui.Center).Padding(4, 8, 4, 10).MinHeight(30).Radius(6).Border(1, k.Divider).
		Cursor(ui.CursorPointer)
	if trigger.Hovered() {
		trigger.Background(k.Hover)
	}
	trigger.Children(func() {
		ui.Text(c, value).FontSize(SizeBodyS).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1)
		ui.Icon(c, IconChevronDown).Size(14, 14).TextColor(k.Secondary)
	})
}

// LineInput 是对话框、表单里的输入框：样子同 Input，占满一行。lines 大于 1 时是多行，最多 lines 行。
func LineInput(c *ui.Context, value *string, lines int) ui.Element {
	var in ui.Element
	if lines > 1 {
		in = ui.TextAreaBase(c, value).Lines(1, lines)
	} else {
		in = ui.TextInputBase(c, value)
	}
	return field(c, in).FillWidth()
}

// SearchField 是侧栏顶上的搜索框：32 高、8 的圆角、淡淡一层底，左边放大镜，有字时右边是清除键。
// label 是占位字和读屏名字。返回输入框。
func SearchField(c *ui.Context, value *string, label string) ui.Element {
	k := Of(c)
	var in ui.Element
	box := ui.Row(c).Height(32).Grow(1).MinWidth(0).Padding(0, 4, 0, 10).Gap(6).AlignItems(ui.Center).Radius(8).
		Background(k.Chip)
	box.Children(func() {
		ui.Icon(c, IconSearch).Size(15, 15).TextColor(k.Secondary).Shrink(0)
		in = ui.TextInputBase(c, value).Grow(1).MinWidth(0).FontSize(SizeBodyM).Placeholder(label).Label(label).
			PlaceholderColor(k.Tertiary).TextColor(k.Text).FocusRing(false)
		if in.Focused() {
			box.Border(1, k.Primary)
		}
		if *value != "" {
			clear := ui.ButtonBase(c).Size(24, 24).Radius(6).Justify(ui.Center).AlignItems(ui.Center).Label("清除").
				Tooltip("清除").Cursor(ui.CursorPointer).Shrink(0)
			if clear.Hovered() {
				clear.Background(k.Hover)
			}
			clear.Children(func() { ui.Icon(c, IconClose).Size(12, 12).TextColor(k.Secondary) })
			clear.OnClick(func() { *value = "" })
		}
	})
	return in
}
