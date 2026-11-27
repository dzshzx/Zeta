package mobile

import (
	"testing"

	"github.com/dzshzx/Zeta/hub/mathtex"
)

// math 交回绘制清单，Swift 照 JSON 的字段画；排不出的出错（界面照原文显示）。
func TestMath(t *testing.T) {
	c, _ := newCore(t)
	l := call[mathtex.List](t, c, "math", map[string]any{"tex": `x + \color{red} \frac{1}{2}`, "display": true})
	var glyph, line, plain, red bool
	for _, it := range l.Items {
		glyph = glyph || it.Kind == mathtex.Glyph && it.Font != "" && it.Char != 0 && it.Scale > 0
		line = line || it.Kind == mathtex.Line && it.Thickness > 0
		plain = plain || it.DefaultInk
		red = red || it.Color == [4]uint8{255, 0, 0, 255}
	}
	if l.Width <= 0 || l.Height <= 0 || !glyph || !line || !plain || !red {
		t.Fatalf("清单 %+v", l)
	}
	if failure(c, "math", `{"tex":"\\frac{"}`) == "" {
		t.Fatal("排不出的没出错")
	}
}

// math.fonts 交回每种 KaTeX 字体的 TTF。
func TestMathFonts(t *testing.T) {
	c, _ := newCore(t)
	fonts := call[[]MathFont](t, c, "math.fonts", nil)
	if len(fonts) != len(mathtex.FontIDs) {
		t.Fatalf("%d 种字体", len(fonts))
	}
	for _, f := range fonts {
		if len(f.Data) < 4 || string(f.Data[:4]) != "\x00\x01\x00\x00" {
			t.Errorf("%s 不是 TTF", f.ID)
		}
	}
}

// math.source 照 mathtex.Source 换写法（金额不算公式）。
func TestMathSource(t *testing.T) {
	c, _ := newCore(t)
	got := call[map[string]string](t, c, "math.source", map[string]string{"text": `$5 买了 $x$ 个`})
	if got["text"] != `$5 买了 \(x\) 个` {
		t.Fatalf("%q", got["text"])
	}
}
