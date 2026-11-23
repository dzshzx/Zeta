package widget

import "github.com/egoist/mygo/ui"

// Mark 是选项行的标记。
type Mark int

const (
	// MarkCheck 是复选框（CheckboxPreference）：圆角方块，选中时蓝底白勾。
	MarkCheck Mark = iota
	// MarkRadio 是单选：圆，选中时蓝底白点。
	MarkRadio
	// MarkSwitch 是开关（SwitchPreference）。
	MarkSwitch
)

// Choice 是对话框、设置里能点选的一行：标题（14），下面一行说明（13，可以空）。复选框和单选的标记在标题左边，
// 开关在右边（同 Windows 的设置）。整行是按钮，读屏名字是标题；点了做什么由调用方 OnClick。左右留 DialogPadding，
// 和对话框标题对齐。
func Choice(c *ui.Context, title, summary string, mark Mark, on bool) ui.Element {
	k := Of(c)
	row := ui.ButtonBase(c).Label(title).FillWidth().MinHeight(36).Padding(7, DialogPadding).Gap(10).AlignItems(ui.Center).
		Cursor(ui.CursorPointer).FocusRingOffset(-2)
	if row.Hovered() {
		row.Background(k.Hover)
	}
	row.Children(func() {
		if mark != MarkSwitch {
			MarkLook(c, mark, on)
		}
		ui.Column(c).Grow(1).MinWidth(0).Gap(1).Children(func() {
			ui.Text(c, title).FontSize(SizeBodyM).TextColor(k.Text).MaxLines(2)
			if summary != "" {
				ui.Text(c, summary).FontSize(SizeBodyS).TextColor(k.Secondary).MaxLines(4)
			}
		})
		if mark == MarkSwitch {
			MarkLook(c, mark, on)
		}
	})
	return row
}

// MarkLook 画一个标记的样子（不接收点击，点的是整行）：复选框是 18 的圆角方块，选中时蓝底白勾；单选是圆，选中时蓝底白点；开关见 switchLook。
func MarkLook(c *ui.Context, mark Mark, on bool) {
	if mark == MarkSwitch {
		switchLook(c, on)
		return
	}
	k := Of(c)
	box := ui.Box(c).Size(18, 18).Shrink(0).Center().Radius(9).Border(1.5, k.Tertiary)
	switch {
	case !on:
		if mark == MarkCheck {
			box.Radius(4)
		}
	case mark == MarkCheck:
		box.Radius(4).Background(k.Primary).Border(0, k.Primary).Children(func() {
			ui.Icon(c, IconOK).Size(12, 12).TextColor(k.OnPrimary)
		})
	default:
		box.Background(k.Primary).Border(0, k.Primary).Children(func() {
			ui.Box(c).Size(7, 7).Radius(3.5).Background(k.OnPrimary)
		})
	}
}

// switchLook 画一个开关的样子（不接收点击，点的是整行）：同 Switch，40×20 的轨道，14 的白色滑块。
func switchLook(c *ui.Context, on bool) {
	k := Of(c)
	track, left := k.Track, float32(3)
	if on {
		track, left = k.Primary, 23
	}
	ui.Box(c).Size(40, 20).Radius(10).Shrink(0).Background(track).Children(func() {
		ui.Box(c).Absolute().Top(3).Left(left).Size(14, 14).Radius(7).Background(k.OnPrimary).
			Shadow(0, 1.14, 2.29, 0, ui.RGBA(0, 0, 0, 0.1))
	})
}
