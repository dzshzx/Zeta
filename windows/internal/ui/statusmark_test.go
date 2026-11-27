package ui

import (
	"testing"
	"time"

	"github.com/dzshzx/Zeta/hub"
	"github.com/egoist/mygo/ui"
)

// 回答中的记号转着，但一秒只重画 markSteps 次左右：按显示器刷新率一直重画会让桌面版在有会话回答时一直占着 CPU。
func TestAnsweringMarkRepaintsInSteps(t *testing.T) {
	draws := 0
	tt := ui.NewTester(func(c *ui.Context) {
		ui.Box(c).Size(markSize, markSize).Draw(func(p *ui.Painter, r ui.Rect) {
			draws++
			drawMark(p, r, hub.Answering, ui.Color{A: 255})
		})
	}, 100, 100)
	tt.Frame()
	start := draws
	tt.Advance(time.Second)
	if got := draws - start; got < markSteps-1 || got > markSteps+1 {
		t.Fatalf("一秒重画了 %d 次，要约 %d 次", got, markSteps)
	}
}
