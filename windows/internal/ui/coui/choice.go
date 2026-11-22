package coui

import "github.com/egoist/mygo/ui"

// Mark 是选项行右边的标记。
type Mark int

const (
	// MarkCheck 是复选框（CheckboxPreference）：圆角方块，选中时蓝底白勾。
	MarkCheck Mark = iota
	// MarkRadio 是单选（RadioButtonPreference，标记在右）：圆，选中时蓝底白点。
	MarkRadio
	// MarkSwitch 是开关（SwitchPreference）。
	MarkSwitch
)

// Choice 是对话框、设置里能点选的一行：标题（16），下面一行说明（13，可以空），右边是标记。整行是按钮，读屏名字是标题；
// 点了做什么由调用方 OnClick。左右留 DialogPadding，和对话框标题对齐。
func Choice(c *ui.Context, title, summary string, mark Mark, on bool) ui.Element {
	k := Of(c)
	row := ui.ButtonBase(c).Label(title).FillWidth().MinHeight(48).Padding(10, DialogPadding).Gap(16).AlignItems(ui.Center).
		Cursor(ui.CursorPointer).FocusRingOffset(-2)
	if row.Hovered() {
		row.Background(k.Hover)
	}
	row.Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Gap(1).Children(func() {
			ui.Text(c, title).FontSize(SizeHeadlineXS).TextColor(k.Text).MaxLines(2)
			if summary != "" {
				ui.Text(c, summary).FontSize(SizeBodyS).TextColor(k.Secondary).MaxLines(4)
			}
		})
		MarkLook(c, mark, on)
	})
	return row
}

// MarkLook 画一个标记的样子（不接收点击，点的是整行）：复选框是 20 的圆角方块，选中时蓝底白勾；单选是圆，选中时蓝底白点；开关见 switchLook。
func MarkLook(c *ui.Context, mark Mark, on bool) {
	if mark == MarkSwitch {
		switchLook(c, on)
		return
	}
	k := Of(c)
	box := ui.Box(c).Size(20, 20).Shrink(0).Center().Radius(10).Border(1.5, k.Tertiary)
	switch {
	case !on:
		if mark == MarkCheck {
			box.Radius(6)
		}
	case mark == MarkCheck:
		box.Radius(6).Background(k.Primary).Border(0, k.Primary).Children(func() {
			ui.Icon(c, IconOK).Size(13, 13).TextColor(k.OnPrimary)
		})
	default:
		box.Background(k.Primary).Border(0, k.Primary).Children(func() {
			ui.Box(c).Size(8, 8).Radius(4).Background(k.OnPrimary)
		})
	}
}

// switchLook 画一个开关的样子（不接收点击，点的是整行）：44×24 的轨道，18 的白色滑块。
func switchLook(c *ui.Context, on bool) {
	k := Of(c)
	track, left := k.Track, float32(3)
	if on {
		track, left = k.Primary, 23
	}
	ui.Box(c).Size(44, 24).Radius(12).Shrink(0).Background(track).Children(func() {
		ui.Box(c).Absolute().Top(3).Left(left).Size(18, 18).Radius(9).Background(k.OnPrimary).
			Shadow(0, 1.14, 2.29, 0, ui.RGBA(0, 0, 0, 0.1))
	})
}
