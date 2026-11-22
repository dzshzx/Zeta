// Package ui 是桌面版的 mygo 界面：窗口、侧栏（设备切换、置顶/今天/更早）、设备的添加与编辑。只画中枢（hub）的状态、
// 把操作交给它；要等设备的事（探测、读 SSH 配置）放到后台跑，结果经 post 回到主线程再改状态。
package ui

import (
	"time"

	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
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
	pane   *chatPane // 聊天窗格，跟着中枢的对话
	query  string    // 侧栏搜索框里的字（每帧交给中枢，见 searchField）

	pick    picker     // 选本机的文件、文件夹（系统的对话框；测试里换掉）
	path    pathAttach // 「输入路径」的对话框
	prompts promptsUI  // 常用指令的对话框

	// Notify 发一条系统通知（回答完了而窗口不在前台时），Foreground 报告窗口在不在前台。New 给的是不发、总在前台，
	// 窗口起来后由入口换成真的。
	Notify     func(title, body string)
	Foreground func() bool
}

// notice 是一次操作的结果，弹个框说一声。
type notice struct {
	title, message string
	open           bool
}

// New 建窗口的状态。run 把要等设备的事放到后台，post 把结果交回主线程（窗口的 Update）。
func New(h *hub.Hub, run, post func(func())) *App {
	return &App{hub: h, run: run, post: post, now: time.Now, pick: systemPicker, Notify: func(string, string) {}, Foreground: func() bool { return true }}
}

// Go 在后台跑 work，它返回的函数交回主线程跑。
func (a *App) async(work func() func()) {
	a.run(func() {
		apply := work()
		a.post(apply)
	})
}

// View 是窗口的内容：COUI 的配色（跟着系统的深浅色），左边侧栏，右边是在看的那一页。
func (a *App) View(c *ui.Context) {
	c.SetTheme(coui.Theme(c.Theme().Dark))
	a.syncChat()
	k := coui.Of(c)
	ui.Row(c).Fill().AlignItems(ui.Stretch).Children(func() {
		a.sidebar(c)
		main := ui.Column(c).Grow(1).MinWidth(0).Background(k.Window)
		if a.page == pageDevices || a.page == pageDevice {
			main.Background(k.Page)
		}
		main.Children(func() {
			switch {
			case a.page == pageDevices:
				a.devicesPage(c)
			case a.page == pageDevice:
				a.devicePage(c)
			case a.pane != nil:
				a.chatPage(c)
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
	k := coui.Of(c)
	ui.Column(c).Fill().Center().Gap(14).Padding(32).Children(func() {
		brand(c, 56)
		ui.Text(c, "Zeta").FontSize(coui.SizeHeadlineM + 4).FontWeight(600).TextColor(k.Text)
		if len(a.hub.Hosts()) > 0 {
			return
		}
		ui.Text(c, desktopFooter).FontSize(coui.SizeBodyS).LineHeight(1.5).TextColor(k.Secondary).MaxWidth(420).
			TextAlign(ui.Center)
		coui.PrimaryButton(c, "添加设备").OnClick(func() { a.open(pageDevices) }).Margin(8, 0, 0, 0)
	})
}

// brand 是品牌标：主题蓝的圆角方块（往下渐深），里面白色的 ζ（同手机版侧栏的 BrandMark）。
func brand(c *ui.Context, size float32) {
	k := coui.Of(c)
	deep := k.Primary.Mix(ui.RGB(0, 0, 0), 0.12)
	if k.Dark {
		deep = k.Primary.Mix(ui.RGB(255, 255, 255), 0.12)
	}
	ui.Box(c).Size(size, size).Radius(size*0.29).Gradient(k.Primary, deep, 180).Center().Shrink(0).Children(func() {
		ui.Icon(c, coui.IconBrand).Size(size*0.78, size*0.78).TextColor(k.OnPrimary)
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
	coui.Alert(c, &n.open, n.title, n.message, coui.Action{Label: "好", Style: coui.Recommend})
	if !n.open {
		a.notice = nil
	}
}
