package widget

import "github.com/egoist/mygo/ui"

// 对话框是桌面的写法（配色同 COUI）：宽 440，16 的圆角，标题 16 左对齐，说明 13 次要文字色，四周留 24；
// 底下一排按钮靠右、宽度跟着字，主按钮在最右。Esc 关上；Alert 里推荐的那个按钮（没有就是危险操作那个）开着时带焦点，
// 按 Enter 就是点它。后面一层遮罩。

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

// DialogWidth 是对话框的宽度。
const DialogWidth = 440

// DialogPadding 是对话框里内容离两边的距离。
const DialogPadding = 24

// Alert 在 *open 为真时显示对话框：标题、说明（可以空）和按钮。返回这一帧点了第几个按钮（从 0 数），没点是 -1；
// 点了按钮、点遮罩或按 Esc 都关上。
func Alert(c *ui.Context, open *bool, title, message string, actions ...Action) int {
	chosen := -1
	Dialog(c, open, func() {
		DialogTitle(c, title, message)
		if i := Actions(c, actions...); i >= 0 {
			chosen = i
			*open = false
		}
	})
	return chosen
}

// DialogTitle 是对话框顶上的标题和说明（说明可以空，可以多行），左对齐。
func DialogTitle(c *ui.Context, title, message string) {
	k := Of(c)
	ui.Column(c).FillWidth().Padding(DialogPadding, DialogPadding, 12, DialogPadding).Gap(6).Shrink(0).Children(func() {
		ui.Text(c, title).FontSize(SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
		if message != "" {
			ui.Text(c, message).FontSize(SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).Selectable()
		}
	})
}

// Dialog 在 *open 为真时显示一个对话框面板，内容由 fn 排（标题用 DialogTitle，按钮用 Actions 或 ButtonBar）。
func Dialog(c *ui.Context, open *bool, fn func()) {
	k := Of(c)
	ui.DialogBase(c, open, func(backdrop, panel ui.Element) {
		backdrop.Background(k.Mask)
		Surface{Shape: ShapeDialog, Fill: k.Dialog}.Paint(panel).Width(DialogWidth).Clip().
			Shadow(0, 12, 48, 0, popupShadow(k)).Children(fn)
	})
}

// Actions 是对话框底下一排按钮，返回这一帧点了第几个（没点是 -1）。推荐的那个（没有时是危险操作那个）带焦点，Enter 点它。
func Actions(c *ui.Context, actions ...Action) int {
	chosen := -1
	focus := -1
	for i, a := range actions {
		if a.Style == Recommend || (a.Style == Destructive && focus < 0) {
			focus = i
		}
	}
	ButtonBar(c, func() {
		for i, a := range actions {
			b := DialogButton(c.Key(i), a.Label, a.Style)
			if i == focus {
				b.AutoFocus()
			}
			if b.Clicked() {
				chosen = i
			}
		}
	})
	return chosen
}

// Sheet 是一直开着的对话框（确认、提问这类）：点遮罩、按 Esc 都不关，只有按钮能回应。
// 标题和说明（可以空，可以多行）左对齐；body 是中间的内容，超过 maxBody 高时滚动；buttons 是底下那排（用 ButtonBar 里的按钮）。
func Sheet(c *ui.Context, title, summary string, maxBody float32, body, buttons func()) {
	open := true
	Dialog(c, &open, func() {
		DialogTitle(c, title, summary)
		ui.Scroll(c.Key("body")).FillWidth().MaxHeight(maxBody).Shrink(1).Children(func() {
			ui.Column(c).FillWidth().Gap(4).Children(body)
		})
		ButtonBar(c, buttons)
	})
}

// ButtonBar 是对话框底下的一排按钮：靠右，彼此 8，离两边 24、离底 20；按钮宽度跟着字。按钮在 fn 里从左往右加，
// 主按钮最后加（在最右）。
func ButtonBar(c *ui.Context, fn func()) {
	ui.Row(c).FillWidth().Padding(12, DialogPadding, 20, DialogPadding).Gap(8).Justify(ui.End).Wrap().Shrink(0).Children(fn)
}

// InputDialog 在 *open 为真时显示一个填一行字的对话框（Zeta 的 TextInputDialog）：标题、说明（填什么、或者哪里不对），
// 一个输入框（label 是占位和读屏名字），下面「取消」和 confirm；ok 为假时 confirm 点不了。点了 confirm（或在框里按 Enter）
// 返回 true 并关上；取消、点遮罩、按 Esc 只关上。
func InputDialog(c *ui.Context, open *bool, title, summary string, value *string, label, confirm string, ok bool) bool {
	done := false
	Dialog(c, open, func() {
		DialogTitle(c, title, summary)
		ui.Box(c).FillWidth().Padding(0, DialogPadding).Children(func() {
			LineInput(c.Key("value"), value, 1).Placeholder(label).Label(label).AutoFocus().OnSubmit(func() {
				if ok {
					done = true
				}
			})
		})
		ButtonBar(c, func() {
			if DialogButton(c, "取消", Plain).Clicked() {
				*open = false
			}
			if DialogButton(c, confirm, Recommend).Disabled(!ok).Clicked() {
				done = true
			}
		})
	})
	if done {
		*open = false
	}
	return done
}

// Panel 是嵌在页面里的卡片（输入栏位置上的确认、提问，照 ChatGPT 客户端，不遮住对话）：样子同输入栏，标题和说明左对齐；
// body 超过 maxBody 高（也不超过窗口高的四成半）时滚动；buttons 是底下靠右的一排（用 DialogButton）。
func Panel(c *ui.Context, title, summary string, maxBody float32, body, buttons func()) {
	k := Of(c)
	_, winH := c.Size()
	if limit := winH * 0.45; winH > 0 && limit < maxBody {
		maxBody = limit
	}
	Surface{Shape: ShapeComposer, Fill: k.Field, Edge: k.Edge}.Paint(ui.Column(c)).FillWidth().Clip().
		Shadow(0, 2, 16, 0, k.Shadow).Children(func() {
		DialogTitle(c, title, summary)
		ui.Scroll(c.Key("body")).FillWidth().MaxHeight(maxBody).Shrink(1).Children(func() {
			ui.Column(c).FillWidth().Gap(4).Children(body)
		})
		ButtonBar(c, buttons)
	})
}
