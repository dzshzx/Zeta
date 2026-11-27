package mathtex

import (
	"strings"
	"testing"
)

// 常见的写法都排得出：上下标、分数、根号、求和积分、矩阵、分段、\text 里的中文、\color。
func TestLayout(t *testing.T) {
	for _, tex := range []string{
		`x^2 + y^2 = z^2`, `\frac{a+b}{2}`, `\sqrt[3]{x}`, `\sum_{i=1}^{n} i = \frac{n(n+1)}{2}`, `\int_0^1 x\,dx`,
		`\lim_{x \to 0} \frac{\sin x}{x} = 1`, `\left( \frac{a}{b} \right)`, `a \le b \ne c`, `\mathbb{R}`, `\mathbf{v}`,
		`\begin{pmatrix} a & b \\ c & d \end{pmatrix}`, `f(x) = \begin{cases} 1 & x > 0 \\ 0 & x \le 0 \end{cases}`,
		`\text{面积} = \pi r^2`, `\color{red} x`, `\overline{AB}`, `\operatorname{argmax}_x f(x)`,
	} {
		for _, display := range []bool{false, true} {
			l, err := Layout(tex, display)
			if err != nil {
				t.Errorf("%q (display %v): %v", tex, display, err)
				continue
			}
			if len(l.Items) == 0 || l.Width <= 0 || l.Height <= 0 {
				t.Errorf("%q (display %v): empty layout %+v", tex, display, l)
			}
		}
	}
}

// 没写 \color 的部分用正文的颜色（排的时候默认色传的是全透明），写了的照它。
func TestLayoutInk(t *testing.T) {
	l, err := Layout(`x + \color{red} y`, false)
	if err != nil {
		t.Fatal(err)
	}
	var plain, red int
	for _, it := range l.Items {
		switch {
		case it.DefaultInk:
			plain++
		case it.Color == [4]uint8{255, 0, 0, 255}:
			red++
		}
	}
	if plain == 0 || red == 0 {
		t.Errorf("plain %d, red %d items in %+v", plain, red, l.Items)
	}
}

// 排不出的返回错误（调用方照原文显示），带 RaTeX 的说明；之后照常能排。
func TestLayoutErrors(t *testing.T) {
	for tex, want := range map[string]string{`\frac{`: "Unexpected end of input", `\notacommand`: "Undefined control sequence"} {
		if _, err := Layout(tex, false); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%q: %v, want %q", tex, err, want)
		}
	}
	if _, err := Layout(`x_1`, false); err != nil {
		t.Errorf("after the errors: %v", err)
	}
}

// 同一条公式第二次直接取缓存。
func TestLayoutCached(t *testing.T) {
	a, err := Layout(`\alpha`, false)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := Layout(`\alpha`, false)
	c, _ := Layout(`\alpha`, true)
	if a != b || a == c {
		t.Error("not cached by TeX and mode")
	}
}

func TestUnhashed(t *testing.T) {
	for name, want := range map[string]string{
		"__wbg___wbindgen_throw_4c88d8bf264fd984": "__wbg___wbindgen_throw",
		"__wbg_get_2080c49015e35254":              "__wbg_get",
		"__wbindgen_generic_0000000000000001":     "__wbindgen_generic",
		"__wbindgen_init_externref_table":         "__wbindgen_init_externref_table",
	} {
		if got := unhashed(name); got != want {
			t.Errorf("unhashed(%q) = %q, want %q", name, got, want)
		}
	}
}
