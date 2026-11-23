package chatview

import (
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/mathtex"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
	"github.com/egoist/mygo/ui/markdown/parser"
)

// 公式的字号（同手机版 MarkdownMathContent、RaTeXMath）：行内的是四周文字的 1.06 倍；块公式回答里 17（正文 16），思考里 14.25（思考 14）。
const (
	inlineMathScale   = 1.06
	answerMathScale   = 17.0 / 16
	thinkingMathScale = 14.25 / 14
)

// setMath 让 o 把公式排成公式（RaTeX，见 mathtex）：块公式居中，宽过一行时横着滚；行内的跟着文字折行。
// 排不出的照原文显示（mygo 的默认样子：块公式是淡色等宽字）。颜色每次画的时候从 th 取，跟着深浅色变。
// 交给 o 的原文要先过 mathSource（哪些算公式照手机版）。
func setMath(o *markdown.Options, th *markdown.Theme, blockScale float32) {
	o.InlineMath = func(tex string, size float32) (markdown.InlineObject, bool) {
		l, err := mathtex.Layout(tex, false)
		if err != nil || l.Width <= 0 {
			return markdown.InlineObject{}, false
		}
		fs := size * inlineMathScale
		return markdown.InlineObject{Width: l.Width * fs, Height: l.Height * fs, Depth: l.Depth * fs,
			Draw: func(p *ui.Painter, r ui.Rect) { l.Draw(p, r.X, r.Y, fs, th.Text) }}, true
	}
	// 块公式经 Block 建（不用 Options.Math：mygo 把它包在和内容一样宽的列里，居中不了）；排不出时交回 mygo，照原文显示
	o.Block = func(c *ui.Context, d *parser.Document, i int) bool {
		if d.Block(i).Kind != parser.MathBlock {
			return false
		}
		tex := strings.TrimRight(d.Lines(i), "\n")
		l, err := mathtex.Layout(tex, true)
		if err != nil || l.Width <= 0 {
			return false
		}
		fs := th.FontSize * blockScale
		// 占满一行的列让公式居中；滚动的那层最宽一行，宽过一行的横着滚
		ui.Column(c).AlignSelf(ui.Stretch).AlignItems(ui.Center).Children(func() {
			ui.ScrollHorizontal(c).MaxWidthPercent(100).Children(func() {
				ui.Box(c).Size(l.Width*fs, (l.Height+l.Depth)*fs).Shrink(0).MarginY(2).
					Role(ui.RoleImage).Label(strings.TrimSpace(tex)).
					Draw(func(p *ui.Painter, r ui.Rect) { l.Draw(p, r.X, r.Y, fs, th.Text) })
			})
		})
		return true
	}
}
