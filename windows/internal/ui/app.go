// Package ui 是桌面版的 mygo 界面：窗口、侧栏（设备切换、置顶/今天/更早）、设备的添加与编辑。只画中枢（hub）的状态、
// 把操作交给它；要等设备的事（探测、读 SSH 配置）放到后台跑，结果经 post 回到主线程再改状态。
package ui

import (
	"time"

	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/egoist/mygo/ui"
)

// 主区域显示哪一页。
const (
	pageHome    = ""
	pageDevices = "devices"
	pageDevice  = "device" // App.device 那一台
)

// App 是窗口的状态：中枢，和界面自己的（在看哪一页、对话框、输入框里的字）。
type App struct {
	hub  *hub.Hub
	run  func(func()) // 在后台跑
	post func(func()) // 交回主线程跑（窗口的 Update）
	now  func() time.Time

	page   string
	device string // pageDevice 时看的那台

	notice *notice // 弹出的结果
	add    adding
	edit   editing
}

// notice 是一次操作的结果，弹个框说一声。
type notice struct {
	title, message string
	open           bool
}

// New 建窗口的状态。run 把要等设备的事放到后台，post 把结果交回主线程（窗口的 Update）。
func New(h *hub.Hub, run, post func(func())) *App {
	return &App{hub: h, run: run, post: post, now: time.Now}
}

// Go 在后台跑 work，它返回的函数交回主线程跑。
func (a *App) async(work func() func()) {
	a.run(func() {
		apply := work()
		a.post(apply)
	})
}

// View 是窗口的内容。
func (a *App) View(c *ui.Context) {
	t := c.Theme()
	ui.Row(c).Fill().AlignItems(ui.Stretch).Children(func() {
		a.sidebar(c)
		ui.Box(c).Width(1).Background(t.Border)
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			switch a.page {
			case pageDevices:
				a.devicesPage(c)
			case pageDevice:
				a.devicePage(c)
			default:
				a.home(c)
			}
		})
	})
	a.noticeDialog(c)
}

// open 换到一页。
func (a *App) open(page string) {
	a.page = page
	switch page {
	case pageDevices:
		a.startAdding()
	case pageDevice:
		a.startEditing()
	}
}

// home 是没在看别的页时的主区域：还没有设备时请用户加一台。
func (a *App) home(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Fill().Center().Gap(12).Padding(32).Children(func() {
		ui.Text(c, "Zeta").FontSize(28).Bold()
		if len(a.hub.Hosts()) > 0 {
			return
		}
		ui.Text(c, desktopFooter).TextColor(t.TextMuted).MaxWidth(420)
		ui.PrimaryButton(c, "添加设备").OnClick(func() { a.open(pageDevices) })
	})
}

// say 弹框说一声结果。
func (a *App) say(title, message string) {
	a.notice = &notice{title: title, message: message, open: true}
}

func (a *App) noticeDialog(c *ui.Context) {
	n := a.notice
	if n == nil {
		return
	}
	ui.AlertDialog(c, &n.open, n.title, n.message, "好")
	if !n.open {
		a.notice = nil
	}
}

// pageTitle 是一页顶上的标题，有 back 时左边是返回。
func pageTitle(c *ui.Context, title string, back func()) {
	ui.Row(c).Height(56).Padding(0, 24).Gap(8).AlignItems(ui.Center).Children(func() {
		if back != nil {
			ui.Button(c, "返回").OnClick(back)
		}
		ui.Text(c, title).FontSize(20).Bold().SingleLine()
	})
}

// section 是一组：小标题、内容，下面一行说明（空的不写）。
func section(c *ui.Context, title, footer string, fn func()) {
	t := c.Theme()
	ui.Column(c).Gap(8).Children(func() {
		if title != "" {
			ui.Text(c, title).FontSize(13).Bold().TextColor(t.TextMuted)
		}
		ui.Column(c).Gap(2).Padding(4).Radius(10).Background(t.Surface).Children(fn)
		if footer != "" {
			ui.Text(c, footer).FontSize(12).TextColor(t.TextMuted)
		}
	})
}

// item 是一组里的一行：左边名字，右边 value 或 fn 画的东西。
func item(c *ui.Context, label string, fn func()) {
	ui.Row(c).MinHeight(40).Padding(6, 12).Gap(12).AlignItems(ui.Center).Children(func() {
		ui.Text(c, label).Shrink(0)
		ui.Spacer(c)
		fn()
	})
}

// valueItem 是右边只是一段字的一行。
func valueItem(c *ui.Context, label, value string) {
	item(c, label, func() { ui.Text(c, value).TextColor(c.Theme().TextMuted).SingleLine().MinWidth(0).Shrink(1) })
}
