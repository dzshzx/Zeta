package mathtex

import (
	"math"

	"github.com/dzshzx/Zeta/hub/assets"
)

// List 是排好的一条公式：Items 的坐标以 em 为单位，原点在左上角，基线在 Height 处；Width 宽，基线上 Height、下 Depth。
// 同一条公式各次 Layout 交回同一个 *List，调用方不要改它。JSON 是 iOS 版拿去画的样子。
type List struct {
	Items  []Item  `json:"items"`
	Width  float32 `json:"width"`
	Height float32 `json:"height"`
	Depth  float32 `json:"depth"`
}

// Kind 是绘制项的种类（ratex-types 的 DisplayItem）。
type Kind uint8

const (
	Glyph Kind = iota // 一个字：Font 里的 Char，基线左端在 (X, Y)，字号是 Scale em
	Line              // 横线（分数线、上划线）：左端在 (X, Y)，Width 长、Thickness 粗，Y 是线的中间
	Rect              // 实心矩形：左上角 (X, Y)，Width × Height
	Path              // 路径（根号、可伸缩的括号和箭头）：Commands 相对 (X, Y)
)

// Item 是一个绘制项；DefaultInk 为真时用正文的颜色（没写 \color 的部分），否则用 Color（0–255 的 R、G、B、A）。
type Item struct {
	Kind       Kind      `json:"kind"`
	X          float32   `json:"x"`
	Y          float32   `json:"y"`
	Font       string    `json:"font,omitempty"`
	Char       rune      `json:"char,omitempty"`
	Scale      float32   `json:"scale,omitempty"`
	Width      float32   `json:"width,omitempty"`
	Height     float32   `json:"height,omitempty"`
	Thickness  float32   `json:"thickness,omitempty"`
	Dashed     bool      `json:"dashed,omitempty"`
	Fill       bool      `json:"fill,omitempty"`
	Commands   []Command `json:"commands,omitempty"`
	Color      [4]uint8  `json:"color"`
	DefaultInk bool      `json:"defaultInk,omitempty"`
}

// Command 是路径的一步：Op 是 MoveTo、LineTo、QuadTo、CubicTo、Close；坐标以 em 为单位。
type Command struct {
	Op string  `json:"op"`
	X1 float32 `json:"x1,omitempty"`
	Y1 float32 `json:"y1,omitempty"`
	X2 float32 `json:"x2,omitempty"`
	Y2 float32 `json:"y2,omitempty"`
	X  float32 `json:"x"`
	Y  float32 `json:"y"`
}

type rawItem struct {
	Type      string                       `json:"type"`
	X         float32                      `json:"x"`
	Y         float32                      `json:"y"`
	Scale     float32                      `json:"scale"`
	Font      string                       `json:"font"`
	CharCode  rune                         `json:"char_code"`
	Width     float32                      `json:"width"`
	Height    float32                      `json:"height"`
	Thickness float32                      `json:"thickness"`
	Dashed    bool                         `json:"dashed"`
	Fill      bool                         `json:"fill"`
	Commands  []rawCmd                     `json:"commands"`
	Color     struct{ R, G, B, A float32 } `json:"color"`
}

type rawCmd struct {
	Type string  `json:"type"`
	X1   float32 `json:"x1"`
	Y1   float32 `json:"y1"`
	X2   float32 `json:"x2"`
	Y2   float32 `json:"y2"`
	X    float32 `json:"x"`
	Y    float32 `json:"y"`
}

// item 换成 Item；认不得的种类（RaTeX 以后加的）跳过，同它网页端的做法。
func (r rawItem) item() (Item, bool) {
	it := Item{X: r.X, Y: r.Y, Font: r.Font, Char: r.CharCode, Scale: r.Scale, Width: r.Width, Height: r.Height,
		Thickness: r.Thickness, Dashed: r.Dashed, Fill: r.Fill}
	switch r.Type {
	case "GlyphPath":
		it.Kind = Glyph
	case "Line":
		it.Kind = Line
	case "Rect":
		it.Kind = Rect
	case "Path":
		it.Kind = Path
		for _, c := range r.Commands {
			it.Commands = append(it.Commands, Command{Op: c.Type, X1: c.X1, Y1: c.Y1, X2: c.X2, Y2: c.Y2, X: c.X, Y: c.Y})
		}
	default:
		return Item{}, false
	}
	c := r.Color
	it.DefaultInk = c.A == 0
	it.Color = [4]uint8{unit(c.R), unit(c.G), unit(c.B), unit(c.A)}
	return it, true
}

func unit(v float32) uint8 { return uint8(math.Round(float64(min(max(v, 0), 1)) * 255)) }

// FontIDs 是 RaTeX 用到的 KaTeX 字体（它的 FontId），各对应 assets 里的 KaTeX_<ID>.ttf；
// 不在这里的（CJK-Regular、CJK-Fallback、Emoji-Fallback）用系统字体，同它网页端的 fontIdToCss。
var FontIDs = []string{
	"AMS-Regular", "Caligraphic-Regular", "Caligraphic-Bold", "Fraktur-Regular", "Fraktur-Bold",
	"Main-Regular", "Main-Bold", "Main-Italic", "Main-BoldItalic", "Math-Italic", "Math-BoldItalic",
	"SansSerif-Regular", "SansSerif-Bold", "SansSerif-Italic", "Script-Regular",
	"Size1-Regular", "Size2-Regular", "Size3-Regular", "Size4-Regular", "Typewriter-Regular",
}

// Font 读 FontIDs 里一种字体的 TTF。
func Font(id string) ([]byte, error) { return assets.Read("ratex/fonts/KaTeX_" + id + ".ttf") }
