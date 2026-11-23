package chatview

import (
	"math"

	"github.com/egoist/mygo/ui"
)

// 看大图时缩放一张图（同手机版 ImageViewer.kt 的 ZoomState，手势换成鼠标的）：滚轮一格放大或缩小 wheelStep 倍，
// 以指针所在的那一点为中心；触控板双指捏合同样缩放；放大后按住拖动平移，触控板双指滑动也平移；双击回到原大，
// 已经是原大时以双击处为中心放大到 doubleClickZoom 倍。倍数在 minZoom 到 maxZoom 之间，离原大不到 snapToFit 就落回原大；
// 没放大时不平移。

const (
	minZoom         = 0.5
	maxZoom         = 6
	wheelStep       = 1.25
	doubleClickZoom = 2.5
	snapToFit       = 0.05
)

// zoom 是一张图的缩放：scale 是倍数（零值当 1），x、y 是缩放之后再平移多少（DIP）。
type zoom struct {
	scale, x, y float32
	// dragging：按住了在拖；lastX、lastY 是上一次指针的位置（相对图所在的框）。
	dragging     bool
	lastX, lastY float32
}

func (z *zoom) factor() float32 {
	if z.scale == 0 {
		return 1
	}
	return z.scale
}

// zoomed：放大了（这时才能平移）。
func (z *zoom) zoomed() bool { return z.factor() > 1 }

// by 缩放 f 倍，指针在 (px, py)、图的中心在 (cx, cy)（都相对图所在的框）：指针下的那一点留在原处。
func (z *zoom) by(f, px, py, cx, cy float32) {
	s := z.factor()
	next := min(max(s*f, minZoom), maxZoom)
	if math.Abs(float64(next-1)) < snapToFit {
		next = 1
	}
	if next <= 1 {
		z.scale, z.x, z.y = next, 0, 0
		return
	}
	// 指针下的那一点在图上的位置（以图的中心为原点、按原大算），缩放后照样放在指针下
	qx, qy := (px-cx-z.x)/s, (py-cy-z.y)/s
	z.scale, z.x, z.y = next, px-cx-next*qx, py-cy-next*qy
}

// handle 接图所在的框（宽 w、高 h，图在正中）上的输入，返回接没接。
func (z *zoom) handle(ev ui.InputEvent, w, h float32) bool {
	cx, cy := w/2, h/2
	switch ev.Kind {
	case ui.InputScroll:
		return z.scroll(ev, cx, cy)
	case ui.InputZoom:
		if ev.Scale > 0 {
			z.by(ev.Scale, ev.X, ev.Y, cx, cy)
		}
		return true
	case ui.InputPointerDown:
		return z.press(ev, cx, cy)
	case ui.InputPointerMove:
		if !z.dragging || ev.Button != 0 {
			return false
		}
		z.x, z.y = z.x+ev.X-z.lastX, z.y+ev.Y-z.lastY
		z.lastX, z.lastY = ev.X, ev.Y
		return true
	case ui.InputPointerUp:
		was := z.dragging
		z.dragging = false
		return was
	}
	return false
}

// scroll：滚轮（和按着 Ctrl 的触控板滑动）一格缩放一级；触控板的双指滑动在放大时平移，没放大时不接。
func (z *zoom) scroll(ev ui.InputEvent, cx, cy float32) bool {
	if ev.Precise && ev.Mods&ui.Ctrl == 0 {
		if !z.zoomed() {
			return false
		}
		z.x, z.y = z.x-ev.DX, z.y-ev.DY
		return true
	}
	switch {
	case ev.DY < 0:
		z.by(wheelStep, ev.X, ev.Y, cx, cy)
	case ev.DY > 0:
		z.by(1/wheelStep, ev.X, ev.Y, cx, cy)
	}
	return true
}

// press：双击在原大和放大之间切换；放大了时按下开始拖动。
func (z *zoom) press(ev ui.InputEvent, cx, cy float32) bool {
	if ev.Button != 0 {
		return false
	}
	if ev.Clicks == 2 {
		z.dragging = false
		if z.factor() != 1 {
			z.scale, z.x, z.y = 1, 0, 0
		} else {
			z.by(doubleClickZoom, ev.X, ev.Y, cx, cy)
		}
		return true
	}
	z.dragging, z.lastX, z.lastY = z.zoomed(), ev.X, ev.Y
	return z.dragging
}
