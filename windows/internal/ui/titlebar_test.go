package ui

import (
	"image"
	"image/color"
	"image/draw"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// captionButtonWidth 是 Windows 11 一个系统按钮的宽（mygo 的 internal/windows/titlebar.go 同样是 46）。
const captionButtonWidth = 46

// windowTitleBar 是真窗口里 Context.TitleBar 的数值：最小化、最大化、关闭三个按钮，和标题栏一样高。
var windowTitleBar = ui.TitleBar{Height: coui.TitleBarHeight, Right: 3 * captionButtonWidth, Bottom: coui.TitleBarHeight}

// withCaptionButtons 在预览图的右上角按 Windows 11 的样子画出最小化、最大化、关闭三个按钮的示意（正式程序里是系统画的）：
// 46 宽、标题栏那么高，10 见方的字形，浅色是 89% 的黑，深色是白，底是透明的。
func withCaptionButtons(src *image.RGBA, dark bool) *image.RGBA {
	img := image.NewRGBA(src.Bounds())
	draw.Draw(img, img.Bounds(), src, src.Bounds().Min, draw.Src)
	scale := float32(img.Bounds().Dx()) / float32(previewWidth)
	ink := color.NRGBA{0, 0, 0, 0xE4}
	if dark {
		ink = color.NRGBA{0xFF, 0xFF, 0xFF, 0xFF}
	}
	px := func(v float32) int { return int(v*scale + 0.5) }
	dot := func(x, y float32) {
		x0, y0 := px(x), px(y)
		for dx := range max(px(1), 1) {
			for dy := range max(px(1), 1) {
				p := image.Pt(x0+dx, y0+dy)
				draw.Draw(img, image.Rectangle{Min: p, Max: p.Add(image.Pt(1, 1))}, image.NewUniform(ink), image.Point{}, draw.Over)
			}
		}
	}
	const glyph = 10
	right := float32(previewWidth)
	cy := float32(coui.TitleBarHeight) / 2
	for i := range 3 {
		cx := right - float32(3-i)*captionButtonWidth + captionButtonWidth/2
		x0, y0 := cx-glyph/2, cy-glyph/2
		step := 1 / scale
		switch i {
		case 0: // 最小化：一横
			for x := x0; x <= x0+glyph; x += step {
				dot(x, cy)
			}
		case 1: // 最大化：方框
			for t := float32(0); t <= glyph; t += step {
				dot(x0+t, y0)
				dot(x0+t, y0+glyph)
				dot(x0, y0+t)
				dot(x0+glyph, y0+t)
			}
		case 2: // 关闭：叉
			for t := float32(0); t <= glyph; t += step {
				dot(x0+t, y0+t)
				dot(x0+t, y0+glyph-t)
			}
		}
	}
	return img
}

// previewWidth 是测试窗口的宽（newFixture）。
const previewWidth = 1280

// TestHeadersClearWindowButtons：系统按钮占着右上角时，聊天页的标题仍按聊天区居中、不压到按钮，窗口窄了截断；
// 整页的顶栏也让出按钮。
func TestHeadersClearWindowButtons(t *testing.T) {
	f := newChatFixture(t)
	f.showTurn(t, false)
	f.tt.SetTitleBar(windowTitleBar)
	buttons := float32(previewWidth) - windowTitleBar.Right
	name := f.app.chatTitle(f.app.pane)
	title, ok := f.tt.Find(name)
	if !ok {
		t.Fatalf("聊天页没有标题 %q", name)
	}
	if title.X+title.W > buttons {
		t.Fatalf("标题 %v 压到了系统按钮（从 %v 起）", title, buttons)
	}
	center := float32(sidebarWidth+previewWidth) / 2
	if mid := title.X + title.W/2; mid < center-1 || mid > center+1 {
		t.Fatalf("标题中线 %v，聊天区中线 %v", mid, center)
	}

	long := strings.Repeat("把构建命令换成仓里的脚本", 6)
	f.push(`"title":"` + long + `"`)
	if title, ok = f.tt.Find(long); !ok {
		t.Fatal("聊天页没有长标题")
	}
	if title.X+title.W > buttons-coui.ButtonsGap {
		t.Fatalf("长标题 %v 没截断，压到了系统按钮（从 %v 起）", title, buttons)
	}
}
