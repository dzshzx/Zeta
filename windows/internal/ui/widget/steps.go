package widget

import "time"

// Step 把一段周期 period 的循环动画分成 steps 步（同手机版 rememberIndicatorClock 的分步）：now 这一刻在第几步时的相位
// （0 到 1，取这一步的起点），和离下一步还有多久。按墙上时钟分步，同一窗口里几个动画落在同一帧；动画拿它定下一帧
// （Painter.After 或 Context.After），不用 Loop：Loop 让窗口按显示器的刷新率一直重建。
func Step(now time.Time, period time.Duration, steps int) (phase float32, next time.Duration) {
	per := period / time.Duration(steps)
	in := time.Duration(now.UnixNano()) % period
	step := in / per
	return float32(step) / float32(steps), per - in%per
}
