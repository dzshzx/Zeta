package ui

import (
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 界面缩放（手机版没有，桌面应用的常规功能）：Ctrl++ 放大、Ctrl+- 缩小、Ctrl+0 回到 100%，设置「常规」里也能选，存在设置里。
// 键同 ChatGPT 的 Windows 客户端（Electron 的 zoomIn、zoomOut、resetZoom）；档位用 Chromium 浏览器的预设，
// 不用 Electron 菜单那样每次乘 1.2 的平方根（110%、120%、131%…），设置里写得出整数。
//
// 缩放交给 mygo 的 Context.SetZoom：它在系统的 DPI 缩放之上再乘这个倍数，按放大后的分辨率重新排版、绘制，
// 所以 widget 里按 100% 写的尺寸不用改，也不另乘系统缩放（150% 的屏上选 125%，实际是 1.5×1.25）。

// zoomLevels 是能选的档位（百分数），从小到大；100 是默认。
var zoomLevels = []int{67, 75, 80, 90, 100, 110, 125, 150, 175, 200}

// zoomLevel 是 percent 落在哪一档：存着的不是档位上的数时取最近的一档。
func zoomLevel(percent int) int {
	best := 100
	for _, z := range zoomLevels {
		if abs(z-percent) < abs(best-percent) {
			best = z
		}
	}
	return best
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// zoomStep 是 percent 往上（step 1）或往下（-1）的下一档，到头了不动。
func zoomStep(percent, step int) int {
	at := 0
	for i, z := range zoomLevels {
		if z == percent {
			at = i
		}
	}
	at = min(max(at+step, 0), len(zoomLevels)-1)
	return zoomLevels[at]
}

// setZoom 换到 percent 那一档，马上存；下一帧 View 交给 mygo。
func (a *App) setZoom(percent int) {
	if percent == a.zoom {
		return
	}
	a.zoom = percent
	a.hub.Settings().SetZoom(percent)
}

// applyZoom 每帧把缩放交给窗口（没变时 mygo 什么也不做）。
func (a *App) applyZoom(c *ui.Context) { c.SetZoom(float32(a.zoom) / 100) }

// zoomRow 是设置「常规」外观一组里的界面缩放：下拉选一档。
func (a *App) zoomRow(l *widget.List) {
	c := l.Context()
	l.Row(widget.Row{Icon: widget.IconSearch, Title: "界面缩放", End: func() {
		widget.MenuButton(c.Key("zoom"), func(t ui.Element) {
			widget.Dropdown(c, t, percentText(a.zoom))
			t.Label("界面缩放")
		}, func(m *widget.Menu) {
			for _, z := range zoomLevels {
				if m.Choice(percentText(z), z == a.zoom) {
					a.setZoom(z)
				}
			}
		})
	}})
}
