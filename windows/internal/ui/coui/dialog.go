package coui

import "github.com/egoist/mygo/ui"

// 对话框照 COUI 的 DialogContentLayout 和 DialogButtonBar：最宽 360，32 的圆角（手机上 45 的平滑圆角、毛玻璃），
// 标题 18 居中，说明 13 次要文字色，四周留 24；下面一排胶囊按钮，最少 44 高，离两边 16、彼此 8、离底 16，
// 两个能并排时并排，多了竖排。后面一层遮罩。

// Style 是对话框按钮的样子。
type Style int

const (
	// Plain 是普通按钮：灰底黑字。
	Plain Style = iota
	// Recommend 是推荐的那个：蓝底白字。
	Recommend
	// Destructive 是危险操作：灰底红字。
	Destructive
)

// Action 是对话框的一个按钮。
type Action struct {
	Label string
	Style Style
}

// Alert 在 *open 为真时显示对话框：标题、说明（可以空）和按钮。返回这一帧点了第几个按钮（从 0 数），没点是 -1；
// 点了按钮、点遮罩或按 Esc 都关上。
func Alert(c *ui.Context, open *bool, title, message string, actions ...Action) int {
	chosen := -1
	Dialog(c, open, func() {
		k := Of(c)
		ui.Column(c).FillWidth().Padding(24, 24, 16, 24).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Text(c, title).FontSize(SizeHeadlineS).FontWeight(600).TextColor(k.Text).TextAlign(ui.Center)
			if message != "" {
				ui.Text(c, message).FontSize(SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).TextAlign(ui.Center).
					Selectable()
			}
		})
		if i := Actions(c, actions...); i >= 0 {
			chosen = i
			*open = false
		}
	})
	return chosen
}

// Dialog 在 *open 为真时显示一个对话框面板，内容由 fn 排（标题、说明自己写，按钮用 Actions）。
func Dialog(c *ui.Context, open *bool, fn func()) {
	k := Of(c)
	ui.DialogBase(c, open, func(backdrop, panel ui.Element) {
		backdrop.Background(k.Mask)
		Surface{Shape: ShapeDialog, Fill: k.DialogGlass, Blur: BlurDialog}.Paint(panel).Width(360).Clip().
			Shadow(0, 12, 48, 0, popupShadow(k)).Children(fn)
	})
}

// Actions 是对话框底下一排按钮，返回这一帧点了第几个（没点是 -1）。
func Actions(c *ui.Context, actions ...Action) int {
	chosen := -1
	row := ui.Row(c)
	if len(actions) > 2 {
		row = ui.Column(c)
	}
	row.FillWidth().Padding(4, 16, 16, 16).Gap(8).Children(func() {
		for i, a := range actions {
			b := DialogButton(c.Key(i), a.Label, a.Style)
			if len(actions) <= 2 {
				b.Grow(1).Basis(0)
			}
			if b.Clicked() {
				chosen = i
			}
		}
	})
	return chosen
}

// DialogPadding 是对话框里内容离两边的距离（DialogContentLayout 的 24）。
const DialogPadding = 24

// Sheet 是一直开着的对话框（确认、提问这类，同手机版的 PageDialog 不给关）：点遮罩、按 Esc 都不关，只有按钮能回应。
// 标题和说明（可以空，可以多行）居中；body 是中间的内容，超过 maxBody 高时滚动；buttons 是底下那排（用 ButtonBar 里的按钮）。
func Sheet(c *ui.Context, title, summary string, maxBody float32, body, buttons func()) {
	open := true
	Dialog(c, &open, func() {
		k := Of(c)
		ui.Column(c).FillWidth().Padding(DialogPadding, DialogPadding, 12, DialogPadding).Gap(6).AlignItems(ui.Center).
			Shrink(0).Children(func() {
			ui.Text(c, title).FontSize(SizeHeadlineS).FontWeight(600).TextColor(k.Text).TextAlign(ui.Center)
			if summary != "" {
				ui.Text(c, summary).FontSize(SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).TextAlign(ui.Center).
					Selectable()
			}
		})
		ui.Scroll(c.Key("body")).FillWidth().MaxHeight(maxBody).Shrink(1).Children(func() {
			ui.Column(c).FillWidth().Gap(4).Children(body)
		})
		ButtonBar(c, buttons)
	})
}

// ButtonBar 是对话框底下的一排按钮（DialogButtonBar）：离两边 16、彼此 8、离底 16，一样宽。按钮在 fn 里用
// Button、PrimaryButton 加，各自 Grow(1).Basis(0)。
func ButtonBar(c *ui.Context, fn func()) {
	ui.Row(c).FillWidth().Padding(8, 16, 16, 16).Gap(8).Shrink(0).Children(fn)
}

// InputDialog 在 *open 为真时显示一个填一行字的对话框（Zeta 的 TextInputDialog）：标题、说明（填什么、或者哪里不对），
// 一个输入框（label 是占位和读屏名字），下面「取消」和 confirm；ok 为假时 confirm 点不了。点了 confirm（或在框里按 Enter）
// 返回 true 并关上；取消、点遮罩、按 Esc 只关上。
func InputDialog(c *ui.Context, open *bool, title, summary string, value *string, label, confirm string, ok bool) bool {
	done := false
	Dialog(c, open, func() {
		k := Of(c)
		ui.Column(c).FillWidth().Padding(DialogPadding, DialogPadding, 4, DialogPadding).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Text(c, title).FontSize(SizeHeadlineS).FontWeight(600).TextColor(k.Text).TextAlign(ui.Center)
			if summary != "" {
				ui.Text(c, summary).FontSize(SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).TextAlign(ui.Center)
			}
			LineInput(c.Key("value"), value, 1).Placeholder(label).Label(label).AutoFocus().OnSubmit(func() {
				if ok {
					done = true
				}
			})
		})
		ButtonBar(c, func() {
			if DialogButton(c, "取消", Plain).Grow(1).Basis(0).Clicked() {
				*open = false
			}
			if DialogButton(c, confirm, Recommend).Grow(1).Basis(0).Disabled(!ok).Clicked() {
				done = true
			}
		})
	})
	if done {
		*open = false
	}
	return done
}
