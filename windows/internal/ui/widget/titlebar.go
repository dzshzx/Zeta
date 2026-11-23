package widget

import "github.com/egoist/mygo/ui"

// 窗口没有系统标题栏（mygo.TitleBarHidden）：页面铺满窗口，系统自己的最小化、最大化、关闭按钮浮在右上角，
// Windows 11 的贴靠布局、阴影、圆角、四边调大小照旧。侧栏顶上一行、聊天页和整页的顶栏同时是标题栏：
// 空白处拖动窗口、双击最大化（DragWindow），右边给系统按钮让出位置（ui.Context.TitleBar；全屏时没有按钮，是 0）。

// TitleBarHeight 是标题栏的高度，系统按钮也这么高（mygo.WindowOptions.TitleBarHeight）：COUI 小顶栏的高度。
const TitleBarHeight = 52

// ButtonsGap 是顶栏上的东西离系统按钮至少多远。
const ButtonsGap = 12

// EndInset 是顶栏右边至少空多宽：pad，系统按钮占着右上角时让出按钮再空 ButtonsGap。
func EndInset(c *ui.Context, pad float32) float32 {
	if r := c.TitleBar().Right; r > 0 {
		return max(pad, r+ButtonsGap)
	}
	return pad
}

// TitleRow 是整页顶上的一行，同时是标题栏：TitleBarHeight 高，左边空 40，右边空 40 或让出系统按钮，空白处拖动窗口。
func TitleRow(c *ui.Context) ui.Element {
	return ui.Row(c).Height(TitleBarHeight).Padding(0, EndInset(c, 40), 0, 40).Gap(10).AlignItems(ui.Center).Shrink(0).
		DragWindow()
}

// DragStrip 是没有顶栏的页面顶上一条透明的标题栏：拖动窗口、双击最大化。放在页面内容之后（盖在上面）。
func DragStrip(c *ui.Context) {
	ui.Box(c).Absolute().Top(0).Left(0).Right(0).Height(TitleBarHeight).DragWindow()
}
