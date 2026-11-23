package mathtex

import (
	"math"
	"sync"

	"github.com/dzshzx/Zeta/windows/internal/assets"
	"github.com/egoist/mygo/ui"
)

// List 是排好的一条公式：Items 的坐标以 em 为单位，原点在左上角，基线在 Height 处；Width 宽，基线上 Height、下 Depth。
type List struct {
	Items                []Item
	Width, Height, Depth float32

	mu     sync.Mutex
	shaped []shapedGlyph // 上一次画的字号排好的字形（Shape 不缓存，每帧重排太费）
	paths  []*ui.Path    // 同上，Path 项在这个字号下的路径
	size   float32
}

// Kind 是绘制项的种类（ratex-types 的 DisplayItem）。
type Kind uint8

const (
	Glyph Kind = iota // 一个字：Font 里的 Char，基线左端在 (X, Y)，字号是 Scale em
	Line              // 横线（分数线、上划线）：左端在 (X, Y)，Width 长、Thickness 粗，Y 是线的中间
	Rect              // 实心矩形：左上角 (X, Y)，Width × Height
	Path              // 路径（根号、可伸缩的括号和箭头）：Commands 相对 (X, Y)
)

// Item 是一个绘制项；Color 的 A 为 0 时用正文的颜色（没写 \color 的部分）。
type Item struct {
	Kind          Kind
	X, Y          float32
	Font          string
	Char          rune
	Scale         float32
	Width, Height float32
	Thickness     float32
	Dashed, Fill  bool
	Commands      []Command
	Color         ui.Color
	defaultInk    bool
}

// Command 是路径的一步：Op 是 MoveTo、LineTo、QuadTo、CubicTo、Close；坐标以 em 为单位。
type Command struct {
	Op                   string
	X1, Y1, X2, Y2, X, Y float32
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
	it.defaultInk = c.A == 0
	it.Color = ui.RGBA(unit(c.R), unit(c.G), unit(c.B), c.A)
	return it, true
}

func unit(v float32) uint8 { return uint8(math.Round(float64(min(max(v, 0), 1)) * 255)) }

// fontIDs 是 RaTeX 用到的 KaTeX 字体（它的 FontId），各对应 assets 里的 KaTeX_<ID>.ttf；
// 不在这里的（CJK-Regular、CJK-Fallback、Emoji-Fallback）用系统字体，同它网页端的 fontIdToCss。
var fontIDs = []string{
	"AMS-Regular", "Caligraphic-Regular", "Caligraphic-Bold", "Fraktur-Regular", "Fraktur-Bold",
	"Main-Regular", "Main-Bold", "Main-Italic", "Main-BoldItalic", "Math-Italic", "Math-BoldItalic",
	"SansSerif-Regular", "SansSerif-Bold", "SansSerif-Italic", "Script-Regular",
	"Size1-Regular", "Size2-Regular", "Size3-Regular", "Size4-Regular", "Typewriter-Regular",
}

var fonts struct {
	once   sync.Once
	family map[string]string // FontId → 注册的字体族名
}

// registerFonts 把 KaTeX 字体注册给 mygo 的文字系统，每个文件一个字体族（Zeta KaTeX <FontId>）。
// 读不出或注册不了的那个用系统字体顶上（画出来不像 TeX，但字还在）。
func registerFonts() {
	fonts.once.Do(func() {
		fonts.family = map[string]string{}
		for _, id := range fontIDs {
			data, err := assets.Read("ratex/fonts/KaTeX_" + id + ".ttf")
			if err != nil {
				continue
			}
			family := "Zeta KaTeX " + id
			if ui.RegisterFont(data, family) == nil {
				fonts.family[id] = family
			}
		}
	})
}

type shapedGlyph struct {
	glyphs []ui.Glyph
	x, y   float32 // 基线左端，相对公式的左上角，DIP
	item   int
}

// Draw 把公式画在 (x, y)（左上角）：1 em 是 size 个 DIP，没写 \color 的部分用 ink。在主线程上调用。
func (l *List) Draw(p *ui.Painter, x, y, size float32, ink ui.Color) {
	registerFonts()
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.size != size || (l.shaped == nil && l.paths == nil) {
		l.prepare(size)
	}
	for _, g := range l.shaped {
		p.Glyphs(g.glyphs, x+g.x, y+g.y, l.Items[g.item].ink(ink))
	}
	pixel := 1 / max(p.Scale(), 1)
	for i, it := range l.Items {
		c := it.ink(ink)
		switch it.Kind {
		case Line:
			t := max(it.Thickness*size, pixel)
			lx, ly, w := x+it.X*size, y+it.Y*size, it.Width*size
			if !it.Dashed {
				p.Fill(ui.Rect{X: lx, Y: ly - t/2, W: w, H: t}, c, 0)
				continue
			}
			for d := float32(0); d < w; d += 6 * t { // 同网页端：实、虚各 3 倍线宽
				p.Fill(ui.Rect{X: lx + d, Y: ly - t/2, W: min(3*t, w-d), H: t}, c, 0)
			}
		case Rect:
			p.Fill(ui.Rect{X: x + it.X*size, Y: y + it.Y*size, W: it.Width * size, H: it.Height * size}, c, 0)
		case Path:
			path := l.paths[i]
			p.Transform(ui.Translation(x+it.X*size, y+it.Y*size), 0, 0, func() {
				if it.Fill {
					p.FillPath(path, c)
				} else {
					p.StrokePath(path, pixel, c)
				}
			})
		}
	}
}

func (it Item) ink(ink ui.Color) ui.Color {
	if it.defaultInk {
		return ink
	}
	return it.Color
}

// prepare 按字号 size 排好字形、建好路径。
func (l *List) prepare(size float32) {
	l.size, l.shaped, l.paths = size, nil, make([]*ui.Path, len(l.Items))
	for i, it := range l.Items {
		switch it.Kind {
		case Glyph:
			f := ui.Font{Family: fonts.family[it.Font], Size: it.Scale * size}
			gs := ui.Shape(string(it.Char), f)
			if len(gs) > 0 {
				l.shaped = append(l.shaped, shapedGlyph{glyphs: gs, x: it.X * size, y: it.Y * size, item: i})
			}
		case Path:
			path := new(ui.Path)
			for _, c := range it.Commands {
				switch c.Op {
				case "MoveTo":
					path.MoveTo(c.X*size, c.Y*size)
				case "LineTo":
					path.LineTo(c.X*size, c.Y*size)
				case "QuadTo":
					path.QuadTo(c.X1*size, c.Y1*size, c.X*size, c.Y*size)
				case "CubicTo":
					path.CubeTo(c.X1*size, c.Y1*size, c.X2*size, c.Y2*size, c.X*size, c.Y*size)
				case "Close":
					path.Close()
				}
			}
			l.paths[i] = path
		}
	}
}
