// Package mathtex 在桌面版上画公式：排版在 hub/mathtex（RaTeX），这里用 KaTeX 字体经 mygo 画出来。
package mathtex

import (
	"sync"

	"github.com/dzshzx/Zeta/hub/mathtex"
	"github.com/egoist/mygo/ui"
)

// List 是排好的一条公式（hub/mathtex 的 List）加上画它时的缓存。
type List struct {
	*mathtex.List

	mu     sync.Mutex
	shaped []shapedGlyph // 上一次画的字号排好的字形（Shape 不缓存，每帧重排太费）
	paths  []*ui.Path    // 同上，Path 项在这个字号下的路径
	size   float32
}

var lists struct {
	mu sync.Mutex
	m  map[*mathtex.List]*List
}

// Layout 排一条公式（hub/mathtex.Layout）：display 为真是块公式。同一条公式交回同一个 *List，画的缓存跟着它。
func Layout(tex string, display bool) (*List, error) {
	l, err := mathtex.Layout(tex, display)
	if err != nil {
		return nil, err
	}
	lists.mu.Lock()
	defer lists.mu.Unlock()
	if w, ok := lists.m[l]; ok {
		return w, nil
	}
	if lists.m == nil || len(lists.m) >= 2048 { // 同 hub/mathtex 的缓存上限，满了整个清掉
		lists.m = map[*mathtex.List]*List{}
	}
	w := &List{List: l}
	lists.m[l] = w
	return w, nil
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
		for _, id := range mathtex.FontIDs {
			data, err := mathtex.Font(id)
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

// Draw 把公式画在 (x, y)（左上角）：1 em 是 size 个 DIP，没写 \color 的部分用 c0。在主线程上调用。
func (l *List) Draw(p *ui.Painter, x, y, size float32, c0 ui.Color) {
	registerFonts()
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.size != size || (l.shaped == nil && l.paths == nil) {
		l.prepare(size)
	}
	for _, g := range l.shaped {
		p.Glyphs(g.glyphs, x+g.x, y+g.y, ink(l.Items[g.item], c0))
	}
	pixel := 1 / max(p.Scale(), 1)
	for i, it := range l.Items {
		c := ink(it, c0)
		switch it.Kind {
		case mathtex.Line:
			t := max(it.Thickness*size, pixel)
			lx, ly, w := x+it.X*size, y+it.Y*size, it.Width*size
			if !it.Dashed {
				p.Fill(ui.Rect{X: lx, Y: ly - t/2, W: w, H: t}, c, 0)
				continue
			}
			for d := float32(0); d < w; d += 6 * t { // 同网页端：实、虚各 3 倍线宽
				p.Fill(ui.Rect{X: lx + d, Y: ly - t/2, W: min(3*t, w-d), H: t}, c, 0)
			}
		case mathtex.Rect:
			p.Fill(ui.Rect{X: x + it.X*size, Y: y + it.Y*size, W: it.Width * size, H: it.Height * size}, c, 0)
		case mathtex.Path:
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

func ink(it mathtex.Item, ink ui.Color) ui.Color {
	if it.DefaultInk {
		return ink
	}
	return ui.RGBA(it.Color[0], it.Color[1], it.Color[2], float32(it.Color[3])/255)
}

// prepare 按字号 size 排好字形、建好路径。
func (l *List) prepare(size float32) {
	l.size, l.shaped, l.paths = size, nil, make([]*ui.Path, len(l.Items))
	for i, it := range l.Items {
		switch it.Kind {
		case mathtex.Glyph:
			f := ui.Font{Family: fonts.family[it.Font], Size: it.Scale * size}
			gs := ui.Shape(string(it.Char), f)
			if len(gs) > 0 {
				l.shaped = append(l.shaped, shapedGlyph{glyphs: gs, x: it.X * size, y: it.Y * size, item: i})
			}
		case mathtex.Path:
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
