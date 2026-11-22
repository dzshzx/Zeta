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
		panel.Width(360).Radius(32).Background(k.Dialog).Shadow(0, 12, 48, 0, k.Shadow).Clip().Children(fn)
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
			var b ui.Element
			switch a.Style {
			case Recommend:
				b = PrimaryButton(c.Key(i), a.Label)
			case Destructive:
				b = DangerButton(c.Key(i), a.Label)
			default:
				b = Button(c.Key(i), a.Label)
			}
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
