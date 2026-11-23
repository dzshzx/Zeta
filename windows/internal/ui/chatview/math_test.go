package chatview

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// mathAnswer 是一段带公式的回答：行内的、块的（$$ 和 \[ 两种）、矩阵、排不出的、金额。
const mathAnswer = "勾股定理：直角三角形里 $a^2 + b^2 = c^2$，斜边是 \\(c = \\sqrt{a^2+b^2}\\)。\n\n" +
	"求和公式：\n\n$$\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n$$\n\n" +
	"旋转矩阵：\n\n\\[\nR(\\theta) = \\begin{pmatrix} \\cos\\theta & -\\sin\\theta \\\\ \\sin\\theta & \\cos\\theta \\end{pmatrix}\n\\]\n\n" +
	"写错的 $\\frac{a$ 照原样显示，金额 $4 和 $20 也不是公式。"

func mathItems(*testing.T) []items.Item {
	return []items.Item{
		{Kind: items.User, ID: "u", Text: "讲讲这几个公式"},
		{Kind: items.Answer, ID: "a", Text: mathAnswer},
	}
}

func TestMathRenders(t *testing.T) {
	ch := New()
	ch.SetItems(mathItems(t))
	tt := newTester(ch, 900, 1600, false)
	tt.Frame()
	all := strings.Join(tt.Texts(), "\n")
	// 行内公式是文字里的对象（U+FFFC），不再露出 TeX
	for _, want := range []string{"直角三角形里 \uFFFC，斜边是 \uFFFC。", "金额 $4 和 $20 也不是公式"} {
		if !strings.Contains(all, want) {
			t.Errorf("no %q in %q", want, all)
		}
	}
	// 块公式画成图，读屏读它的 TeX
	for _, label := range []string{`\sum_{i=1}^{n} i = \frac{n(n+1)}{2}`, `R(\theta) = \begin{pmatrix}`} {
		if !strings.Contains(all, label) {
			t.Errorf("no block math labelled %q in %q", label, all)
		}
	}
	// 排不出的照原文
	if !strings.Contains(all, `\frac{a`) {
		t.Errorf("the broken formula is not shown as TeX: %q", all)
	}
	if strings.Contains(all, "a^2 + b^2") {
		t.Errorf("inline math shown as TeX: %q", all)
	}
	// 块公式在正文那一栏里居中
	text, _ := tt.Find("求和公式：")
	sum, ok := tt.Find(`\sum_{i=1}^{n} i = \frac{n(n+1)}{2}`)
	if mid := text.X + text.W/2; !ok || sum.W <= 0 || abs(sum.X+sum.W/2-mid) > 1 {
		t.Errorf("block math at %+v, not centred in the text column %+v", sum, text)
	}
}

// 宽过一行的块公式从左边开始，横着滚，不伸出去。
func TestWideMathScrolls(t *testing.T) {
	wide := strings.Repeat(`a_1 + `, 60) + "z"
	ch := New()
	ch.SetItems([]items.Item{{Kind: items.Answer, ID: "a", Text: "开头\n\n$$\n" + wide + "\n$$\n"}})
	tt := newTester(ch, 500, 600, false)
	tt.Frame()
	text, _ := tt.Find("开头")
	r, ok := tt.Find(wide) // 看得见的部分：裁在正文那一栏里
	if !ok || abs(r.W-text.W) > 1 || abs(r.X-text.X) > 1 {
		t.Errorf("wide block math at %+v, text at %+v", r, text)
	}
}

func abs(v float32) float32 { return max(v, -v) }
