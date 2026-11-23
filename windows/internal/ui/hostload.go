package ui

import (
	"context"
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 输入栏上的负载标识（同手机版 HostLoad.kt）：对话所在主机整机的 CPU 使用率，经那台核心的 system.cpu 隔几秒问一次；
// 对话里有了消息、聊天窗格开着时才问。忙时变色，悬停提示里写全。

const (
	hostLoadEvery = 5 * time.Second // 隔多久问一次（Claude Code 状态栏是 15 秒，这里在眼前，快一些）
	cpuWarning    = 70              // 到这么高算忙（变黄）
	cpuCritical   = 90              // 到这么高算满（变红）
)

// hostLoad 是一个对话窗格的负载读数。
type hostLoad struct {
	cpu    *coreapi.CPU // 最近一次读到的，还没读到或读不成时为 nil
	asking bool
	next   time.Time // 下次什么时候问
}

// hostLoadChip 是输入栏右下、上下文圆环左边的负载标识：「CPU 45%」，还不知道时是「CPU –」。
func (a *App) hostLoadChip(c *ui.Context, p *chatPane) {
	if newChat(p) {
		return
	}
	a.pollHostLoad(c, p)
	k := widget.Of(c)
	text, tip := "CPU –", "设备负载：还没有读到"
	color := k.Secondary
	if cpu := p.load.cpu; cpu != nil && cpu.Percent != nil {
		n := *cpu.Percent
		text = "CPU " + strconv.Itoa(n) + "%"
		tip = a.hub.Name(p.src.Backend) + " 的 CPU 使用率 " + strconv.Itoa(n) + "%，" + strconv.Itoa(cpu.Cores) +
			" 个核的平均（整机所有进程，同 Claude Code 状态栏的 cpu%）"
		switch {
		case n >= cpuCritical:
			color = k.Error
		case n >= cpuWarning:
			color = k.Warning
		}
	} else if cpu != nil {
		tip = "设备负载：那台主机不让读 CPU 计数（/proc/stat）"
	}
	ui.Text(c, text).FontSize(widget.SizeBodyXS).FontFeatures("tnum").TextColor(color).SingleLine().Padding(0, 4).
		Tooltip(tip).Label(tip)
}

// pollHostLoad 到点时在后台问一次，下一次排在这次问的时刻之后 hostLoadEvery。
func (a *App) pollHostLoad(c *ui.Context, p *chatPane) {
	now := c.Now()
	if p.load.asking {
		return
	}
	if wait := p.load.next.Sub(now); wait > 0 {
		c.After(wait)
		return
	}
	r := a.hub.Remote(p.src.Backend)
	if r == nil {
		return
	}
	p.load.asking, p.load.next = true, now.Add(hostLoadEvery) // 界面的时钟（测试里拨得动）
	core := r.Core
	a.async(func() func() {
		ctx, cancel := context.WithTimeout(context.Background(), hostLoadEvery)
		defer cancel()
		cpu, err := core.CPU(ctx)
		return func() {
			p.load.asking = false
			p.load.cpu = nil
			if err == nil {
				p.load.cpu = &cpu
			}
		}
	})
}
