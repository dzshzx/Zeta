// Package diag 是设置里的「诊断」：这台电脑上 Zeta 自己的运行日志（internal/runlog，能筛、能清空）和环境，各台设备上
// Claude Code 那边的日志（核心的 diag.*，见 docs/core.md「诊断」，点开看尾部）；「导出诊断包」把这些和这台电脑上 Zeta 的
// 诊断目录打成一个 zip，token、密码这类值先打码。同手机版设置里的诊断页，只是 Claude Code 的日志看的是远程设备。
package diag

import (
	"context"
	"fmt"
	"path"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/runlog"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// Device 是一台设备。Core 为 nil 时它的「连接」关着。
type Device struct {
	ID, Name string
	Core     *coreapi.Client
}

// Line 是环境信息的一行：名字和值。
type Line struct{ Name, Value string }

// Options 是诊断页要的东西。
type Options struct {
	Devices func() []Device // 设置里的各台设备，按设置里的先后；主线程上调用
	Run     func(func())    // 在后台跑
	Post    func(func())    // 交回主线程跑（窗口的 Update）
	// Save 弹保存对话框把诊断包存下，返回存到哪；取消了是空的。阻塞，在后台调用。
	Save func(name string, data []byte) (string, error)
	Env  func() []Line // 这台电脑上 Zeta 的环境（版本、Windows 版本、设备清单），放进诊断包；主线程上调用
	Dir  string        // 这台电脑上 Zeta 自己的诊断目录（设置目录的 diag：运行日志、崩溃记录、安装记录），里面的文件放进诊断包；空是没有
	Log  *runlog.Log   // Zeta 自己的运行日志；nil 时不列这一组
	Now  func() time.Time
}

// timeout 是一次读日志最多等多久。
const timeout = 30 * time.Second

// device 是一台设备的日志清单。
type device struct {
	loading bool
	loaded  bool
	files   []coreapi.DiagFile
	err     error
}

// file 是点开在看的一个日志文件。
type file struct {
	dev     Device
	f       coreapi.DiagFile
	loading bool
	tail    coreapi.DiagTail
	err     error
}

// Page 是诊断页。只在主线程上用。
type Page struct {
	o       Options
	devices map[string]*device
	open    *file // 点开在看的，没有时为 nil
	log     logState

	exporting bool
	saved     string // 上次导出存到哪
	exportErr error
}

// New 建诊断页。
func New(o Options) *Page {
	if o.Now == nil {
		o.Now = time.Now
	}
	return &Page{o: o, devices: map[string]*device{}}
}

// Load 读一遍各台的日志清单（打开这一页、点「刷新」时）。「连接」关着的不读。
func (p *Page) Load() {
	for _, d := range p.o.Devices() {
		if d.Core != nil {
			p.loadDevice(d)
		}
	}
}

func (p *Page) state(id string) *device {
	s := p.devices[id]
	if s == nil {
		s = &device{}
		p.devices[id] = s
	}
	return s
}

func (p *Page) loadDevice(d Device) {
	s := p.state(d.ID)
	if s.loading {
		return
	}
	s.loading = true
	p.o.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		files, err := d.Core.DiagFiles(ctx)
		p.o.Post(func() {
			s.loading, s.loaded, s.err = false, true, err
			if err == nil {
				s.files = files
			}
		})
	})
}

// show 点开一个文件看尾部。
func (p *Page) show(d Device, f coreapi.DiagFile) {
	p.open = &file{dev: d, f: f}
	p.reload()
}

// reload 重读点开的那个文件。
func (p *Page) reload() {
	v := p.open
	if v == nil || v.loading {
		return
	}
	v.loading = true
	p.o.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		tail, err := v.dev.Core.ReadDiag(ctx, v.f.Path, false)
		p.o.Post(func() {
			v.loading, v.err = false, err
			if err == nil {
				v.tail = tail
			}
		})
	})
}

// Back 从看文件、看运行日志回到清单；都没在看时返回假（设置页拿 Esc 的时候用）。
func (p *Page) Back() bool {
	switch {
	case p.open != nil:
		p.open = nil
	case p.log.open:
		p.log.open = false
	default:
		return false
	}
	return true
}

// View 画诊断页：清单，或点开的那个文件、运行日志。
func (p *Page) View(c *ui.Context) {
	switch {
	case p.open != nil:
		p.fileView(c)
		return
	case p.log.open && p.o.Log != nil:
		p.logView(c)
		return
	}
	k := widget.Of(c)
	widget.TitleRow(c).Children(func() {
		ui.Text(c, "诊断").FontSize(widget.SizeHeadlineS).FontWeight(widget.Medium).TextColor(k.Text).Shrink(1)
		ui.Spacer(c)
		widget.SmallButton(c, "刷新").Disabled(p.busy()).OnClick(p.Load)
	})
	devices := p.o.Devices()
	widget.Body(c, "diag", func() {
		p.runLogGroup(c)
		p.envGroup(c)
		if len(devices) == 0 {
			widget.Group(c, "", "", func(l *widget.List) {
				l.Message("还没有设备。添加设备后，这里能看那台上 Claude Code 的日志。")
			})
		}
		for _, d := range devices {
			p.deviceGroups(c.Key("diag/"+d.ID), d, len(devices) > 1)
		}
		p.exportGroup(c)
	})
}

// busy：有设备的清单在读。
func (p *Page) busy() bool {
	for _, s := range p.devices {
		if s.loading {
			return true
		}
	}
	return false
}

// deviceGroups 是一台设备的两组：对话进程的错误输出（在跑的、结束的），Claude Code 的调试日志。几台设备时组名带设备名。
func (p *Page) deviceGroups(c *ui.Context, d Device, named bool) {
	title := func(s string) string {
		if named {
			return d.Name + " · " + s
		}
		return s
	}
	s := p.state(d.ID)
	stderr := fmt.Sprintf("正在进行的对话和最近结束的 %d 个。", finishedLimit)
	debug := fmt.Sprintf("Claude Code 开着调试时才写，只列最近 %d 个。", debugLimit)
	switch {
	case d.Core == nil:
		widget.Group(c, title("Claude Code 日志"), "", func(l *widget.List) { l.Message("这台设备的「连接」关着，打开连接后才能看。") })
	case !s.loaded:
		widget.Group(c, title("Claude Code 日志"), "", func(l *widget.List) { l.Message("正在读取…") })
	case s.err != nil:
		widget.Group(c, title("Claude Code 日志"), "", func(l *widget.List) {
			l.Row(widget.Row{Title: "读不到这台设备上的日志", Summary: s.err.Error(), SummaryColor: widget.Of(c).Error})
		})
	default:
		widget.Group(c, title("对话进程的错误输出"), stderr, func(l *widget.List) {
			p.fileRows(l, d, s.files, "对话进程没有往标准错误写过东西", coreapi.DiagRunning, coreapi.DiagFinished)
		})
		widget.Group(c, title("Claude Code 调试日志"), debug, func(l *widget.List) {
			p.fileRows(l, d, s.files, "没有调试日志", coreapi.DiagDebug)
		})
	}
}

// 和核心里的份数一致（helper/diag.go），只用在说明里。
const (
	finishedLimit = 20
	debugLimit    = 30
)

// fileRows 是 kinds 这几类的文件，一个一行；一个也没有时写 empty。
func (p *Page) fileRows(l *widget.List, d Device, files []coreapi.DiagFile, empty string, kinds ...string) {
	n := 0
	for _, f := range files {
		if !contains(kinds, f.Kind) {
			continue
		}
		n++
		l.Row(widget.Row{Title: fileTitle(f), Summary: fileSummary(f, p.o.Now()), Arrow: true, Key: f.Path,
			OnClick: func() { p.show(d, f) }})
	}
	if n == 0 {
		l.Message(empty)
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// fileTitle：在跑的写对话标题（没有时写「运行中的对话」），别的写文件名。
func fileTitle(f coreapi.DiagFile) string {
	if f.Kind == coreapi.DiagRunning {
		if f.Title != "" {
			return f.Title
		}
		return "运行中的对话"
	}
	return path.Base(f.Path)
}

// fileSummary 是一行下面的小字：类别、大小、什么时候改的。
func fileSummary(f coreapi.DiagFile, now time.Time) string {
	return kindLabel(f.Kind) + " · " + sizeText(f.Size) + " · " + when(time.UnixMilli(f.Modified), now)
}

func kindLabel(kind string) string {
	switch kind {
	case coreapi.DiagRunning:
		return "运行中"
	case coreapi.DiagFinished:
		return "已结束"
	}
	return "调试日志"
}

// sizeText 是给人看的大小。
func sizeText(n int64) string {
	switch {
	case n < 1024:
		return fmt.Sprintf("%d B", n)
	case n < 1024*1024:
		return fmt.Sprintf("%.1f KB", float64(n)/1024)
	}
	return fmt.Sprintf("%.1f MB", float64(n)/1024/1024)
}

// when 是改动时间：今天的只写时刻，今年的写月日，再早的写年月日。
func when(t, now time.Time) string {
	t, now = t.Local(), now.Local()
	switch {
	case t.YearDay() == now.YearDay() && t.Year() == now.Year():
		return t.Format("15:04")
	case t.Year() == now.Year():
		return t.Format("1 月 2 日 15:04")
	}
	return t.Format("2006 年 1 月 2 日")
}

// fileView 是点开的一个文件：顶上返回、文件名、复制、刷新；下面一行路径和大小，再下面是等宽字的内容。
func (p *Page) fileView(c *ui.Context) {
	v := p.open
	k := widget.Of(c)
	widget.TitleRow(c).Children(func() {
		widget.IconButton(c, widget.IconBack, "返回").OnClick(func() { p.open = nil })
		ui.Text(c, fileTitle(v.f)).FontSize(widget.SizeHeadlineS).FontWeight(widget.Medium).TextColor(k.Text).SingleLine().
			Ellipsis("…").Shrink(1)
		ui.Spacer(c)
		ui.Row(c).Gap(8).AlignItems(ui.Center).Shrink(0).Children(func() {
			widget.SmallButton(c, "复制").Disabled(v.loading || v.err != nil).OnClick(func() { c.Services().WriteClipboard(v.tail.Text) })
			widget.SmallButton(c, "刷新").Disabled(v.loading).OnClick(p.reload)
		})
	})
	ui.Column(c).Grow(1).MinHeight(0).FillWidth().MaxWidth(widget.PageWidth).AlignSelf(ui.Center).Padding(8, 16, 16, 16).Gap(8).
		Children(func() {
			info := v.dev.Name + " · " + v.f.Path
			if v.err == nil && !v.loading {
				info += " · " + sizeText(v.tail.Size)
				if v.tail.Truncated {
					info += " · 只显示最后 64 KB"
				}
			}
			ui.Text(c, info).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).Padding(0, 16).Selectable()
			switch {
			case v.loading && v.tail.Text == "":
				widget.Group(c, "", "", func(l *widget.List) { l.Message("正在读取…") })
			case v.err != nil:
				widget.Group(c, "", "", func(l *widget.List) {
					l.Row(widget.Row{Title: "读不出这个文件（可能已经被删掉了）", Summary: v.err.Error(), SummaryColor: k.Error})
				})
			case v.tail.Text == "":
				widget.Group(c, "", "", func(l *widget.List) { l.Message("空文件") })
			default:
				box := ui.Scroll(c.Key("diag-tail/" + v.f.Path)).Grow(1).MinHeight(0).FillWidth()
				widget.Surface{Shape: widget.ShapeCard, Fill: k.Card, Edge: k.Divider}.Paint(box)
				box.Children(func() {
					ui.Text(c, v.tail.Text).Font(widget.Mono).FontSize(12).LineHeight(1.35).TextColor(k.Text).Padding(12, 16).
						Selectable()
				})
			}
		})
}
