package coui

import (
	"math"
	"sync"

	"github.com/egoist/mygo/plugins/glass"
	"github.com/egoist/mygo/ui"
)

// Shape 是 COUI 的平滑圆角（ColorOS 的 addSmoothRoundRect，COUI 2 的 coui-squircle）：每个角是一段
// 超椭圆 |x|^n + |y|^n = r^n（n 是 Weight），和两条直边在半径 r 处相切。n 为 2 是普通圆角，越大角越方、
// 过渡越平滑。Radii 依次是左上、右上、右下、左下，每个各自不超过短边的一半。
type Shape struct {
	Radii  [4]float32
	Weight float32
}

// Rounded 是四个角一样的平滑圆角。
func Rounded(radius, weight float32) Shape {
	return Shape{Radii: [4]float32{radius, radius, radius, radius}, Weight: weight}
}

// COUI 各组件用的形状（COUIRoundCorner 的 Normal 档平滑权重 2.5、Large 档 3）。胶囊按钮、圆按钮在 COUI 里是
// 权重 1 的「软胶囊」，这里画成普通的半圆头（权重 2），两者只差在直边接半圆的那一小段。
var (
	ShapeCard     = Rounded(24, 2.5) // 卡片（CardDefaults.CornerRadius = Normal.XL）
	ShapeDialog   = Rounded(45, 3)   // 对话框（DialogContentLayout）
	ShapePopup    = Rounded(28, 2.5) // 弹出菜单（ListPopup）
	ShapeComposer = Rounded(24, 2.5) // 输入栏、排队条（Composer.kt）
	ShapeRow      = Rounded(16, 2.5) // 侧栏的会话行、聊天里的步骤卡（SessionPane、ChatItems）
	ShapeBlock    = Rounded(12, 2.5) // 代码块、命令块（MarkdownContent、DetailBlock）
	ShapeField    = Rounded(10, 2.5) // 输入框（TextField 的 Rectangle 模式）
	ShapeBubble   = Shape{Radii: [4]float32{22, 22, 4, 22}, Weight: 2.5}
)

// shapeKey 是缓存路径用的键：形状和尺寸。
type shapeKey struct {
	s    Shape
	w, h float32
}

// paths 按形状和尺寸缓存算好的路径（原点在左上角），画的时候平移过去。
var paths = struct {
	sync.Mutex
	m map[shapeKey]*ui.Path
}{m: map[shapeKey]*ui.Path{}}

// cornerSteps 是一个角分几段折线：半径 45 时每段不到 5 DIP，画出来看不出折角。
const cornerSteps = 16

// path 返回这个形状在 w×h 的框里的轮廓，原点在框的左上角。
func (s Shape) path(w, h float32) *ui.Path {
	key := shapeKey{s, w, h}
	paths.Lock()
	defer paths.Unlock()
	if p, ok := paths.m[key]; ok {
		return p
	}
	if len(paths.m) > 512 { // 窗口拖来拖去时尺寸很多，满了就从头记
		clear(paths.m)
	}
	p := s.build(w, h)
	paths.m[key] = p
	return p
}

func (s Shape) build(w, h float32) *ui.Path {
	p := &ui.Path{}
	limit := min(w, h) / 2
	var r [4]float32
	for i, v := range s.Radii {
		r[i] = max(0, min(v, limit))
	}
	n := float64(max(s.Weight, 2))
	// 一个角：从第一个切点走到第二个切点。center 是角的圆心，(ux, uy) 指向第一条边外侧，(vx, vy) 指向第二条边外侧。
	corner := func(cx, cy, rad, ux, uy, vx, vy float32) {
		if rad <= 0 {
			p.LineTo(cx+rad*(ux+vx), cy+rad*(uy+vy))
			return
		}
		for i := 1; i <= cornerSteps; i++ {
			t := float64(i) / cornerSteps * math.Pi / 2
			a := float32(math.Pow(math.Sin(t), 2/n)) // 往第二条边走
			b := float32(math.Pow(math.Cos(t), 2/n)) // 离第一条边
			p.LineTo(cx+rad*(a*vx+b*ux), cy+rad*(a*vy+b*uy))
		}
	}
	// 顺时针，从上边开始（同 SquirclePath.kt 的 appendSquircleRect）
	p.MoveTo(r[0], 0)
	p.LineTo(w-r[1], 0)
	corner(w-r[1], r[1], r[1], 0, -1, 1, 0)
	p.LineTo(w, h-r[2])
	corner(w-r[2], h-r[2], r[2], 1, 0, 0, 1)
	p.LineTo(r[3], h)
	corner(r[3], h-r[3], r[3], 0, 1, -1, 0)
	p.LineTo(0, r[0])
	corner(r[0], r[0], r[0], -1, 0, 0, -1)
	p.Close()
	return p
}

// Surface 是一块平滑圆角的面：底色、可选的细边和毛玻璃。它是 mygo 的 Material，用 Paint 挂到元素上。
type Surface struct {
	Shape Shape
	// Fill 是底色；有毛玻璃时是叠在模糊的背景上的那层（半透明）。
	Fill ui.Color
	// Edge 不是零值时沿轮廓描一圈细边（Width 宽，默认 1）。
	Edge  ui.Color
	Width float32
	// Blur 不是 0 时，元素下面的内容透过来、按这么多 DIP 模糊（高斯的标准差，mygo 的 glass.Blur）。
	Blur float32
}

// PaintMaterial 画这块面：先模糊下面的内容（按轮廓裁），再铺底色、描边。
func (s Surface) PaintMaterial(p *ui.Painter, box ui.Rect, _ [4]float32) {
	path := s.Shape.path(box.W, box.H)
	p.Transform(ui.Translation(box.X, box.Y), 0, 0, func() {
		local := ui.Rect{W: box.W, H: box.H}
		if s.Blur > 0 {
			p.ClipPath(path, func() { glass.Blur{Radius: s.Blur}.PaintMaterial(p, local, [4]float32{}) })
		}
		if s.Fill.A > 0 {
			p.FillPath(path, s.Fill)
		}
		if s.Edge.A > 0 {
			width := s.Width
			if width == 0 {
				width = 1
			}
			p.StrokePath(path, width, s.Edge)
		}
	})
}

// Paint 给元素 e 挂上这块面，并把元素的圆角设成同样的大小：mygo 按它裁里面的内容（Clip 时）、画阴影和焦点框。
// 普通圆角落在平滑圆角里面，所以裁出来的内容不会超出面。返回 e，好接着调。
func (s Surface) Paint(e ui.Element) ui.Element {
	r := s.Shape.Radii
	return e.Material(s).Radius(r[0], r[1], r[2], r[3])
}

// Shadow 是 COUI 浮起元素的阴影（COUIMaterialEffect）：往下偏 3.2、模糊 16，黑色约 12% 的不透明度。
func Shadow(e ui.Element, k *Colors) ui.Element { return e.Shadow(0, 3.2, 16, 0, k.Shadow) }

// BlurDialog、BlurPopup、BlurBar 是毛玻璃的模糊程度（DIP）。COUI 手机上对话框是 200、菜单是 160 个原始像素的 Kawase 模糊，换成高斯大约是这么多。
const (
	BlurDialog = 30
	BlurPopup  = 24
	BlurBar    = 20
)
