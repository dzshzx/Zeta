package widget

import (
	"sync/atomic"

	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/shader"
)

// Frost 是 Zeta 自己的毛玻璃：底下的界面按 Blur 模糊后透过来，再按 Tint 的不透明度叠一层 Tint 的颜色。
// 它是 mygo 的 Material。半透明开不开只看 SetTranslucent（设置「外观」里的半透明），关着时不模糊，铺不透明的
// Tint 颜色。不看 Windows 的「透明效果」：mygo 的 glass 插件在系统关了透明效果时一律画成不透明，
// 应用改不了，所以不用它。
type Frost struct {
	// Blur 是模糊程度，DIP（高斯的标准差）。
	Blur float32
	// Tint 是叠在模糊的背景上的颜色，A 是叠多少。
	Tint ui.Color
}

// translucent 是毛玻璃透不透出底下的界面，窗口每帧照设置填（SetTranslucent）。
var translucent atomic.Bool

func init() { translucent.Store(true) }

// SetTranslucent 设毛玻璃透不透出底下的界面。
func SetTranslucent(on bool) { translucent.Store(on) }

// frostShader 读元素下面已经画好的内容（mygo 按 Options.BackdropBlur 先模糊），叠上 Tint。
// 窗口的背景本来就不透明，结果按不透明画。
var frostShader = shader.MustCompile(`package main

var Tint vec4

func Fragment(dst vec4, pos vec2) vec4 {
	b := backdropSample(pos)
	return vec4(mix(b.rgb, Tint.rgb, Tint.a), 1)
}
`)

// PaintMaterial 画这块毛玻璃，四角按 radii 的第一个圆角（Zeta 的平滑圆角由 Surface 按轮廓裁）。
func (f Frost) PaintMaterial(p *ui.Painter, box ui.Rect, radii [4]float32) {
	if !translucent.Load() {
		t := f.Tint
		t.A = 255
		p.FillRadii(box, radii, t)
		return
	}
	frostShader.Draw(p, box, shader.Options{BackdropBlur: f.Blur, Radius: radii[0], Uniforms: map[string]any{"Tint": f.Tint}})
}
