// Package ui 是桌面版的 mygo 界面：窗口、侧栏（设备切换、置顶/今天/更早）、设备的添加与编辑。只画中枢（hub）的状态、
// 把操作交给它；要等设备的事（探测、读 SSH 配置）放到后台跑，结果经 post 回到主线程再改状态。
package ui

import (
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/runlog"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	uiacct "github.com/dzshzx/Zeta/windows/internal/ui/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/diag"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// 主区域显示哪一页。
const (
	pageHome     = ""
	pageSettings = "settings"
	pageDevices  = "devices"  // 添加远程设备
	pageDevice   = "device"   // App.device 那一台
	pageAccounts = "accounts" // App.device 那一台的账号与额度
	pageAbout    = "about"
	pageDiag     = "diag" // 诊断：Zeta 的运行日志、环境，各台设备上 Claude Code 的日志
)

// App 是窗口的状态：中枢，和界面自己的（在看哪一页、对话框、输入框里的字）。
type App struct {
	hub  *hub.Hub
	run  func(func()) // 在后台跑
	post func(func()) // 交回主线程跑（窗口的 Update）
	now  func() time.Time

	page   string
	device string // pageDevice、pageAccounts 时看的那台
	shown  string // 上一帧画的那一页（离开设备页时把改过的设置交给开着的对话）

	cfg        *claudecfg.Page         // 设备页上那台的 Claude Code 配置
	modelLists map[string]*modelSource // 各台设备的模型来源（models.go）
	acct       *uiacct.Page            // 账号与额度页
	diag       *diag.Page              // 诊断页，打开时建
	// DiagEnv 是诊断包里这台电脑的几行环境（Windows 版本），由入口给；Zeta 的版本和设备清单诊断页自己取。
	DiagEnv func() []diag.Line
	// DiagDir 是这台电脑上 Zeta 自己的诊断目录（设置目录的 diag：运行日志、崩溃记录、安装记录），由入口给；空是没有。
	DiagDir string
	// RunLog 是 Zeta 自己的运行日志（诊断页能看、筛、清空），由入口给；nil 时诊断页不列这一组。
	RunLog *runlog.Log
	// About 是关于页（版本、自动更新）。New 给的不查更新，窗口起来前由入口换成接了 update.Updater 的。
	About *about.Page
	// updateVersion 是窗口底部提示的新版本（自动查到的版本名），空是没有提示。
	updateVersion string

	notice *notice // 弹出的结果
	add    adding
	edit   editing
	pane   *chatPane  // 聊天窗格，跟着中枢的对话
	drafts *draftBook // 各对话没发的输入（drafts.go）
	query  string     // 侧栏搜索框里的字（每帧交给中枢，见 searchField）
	ops    sessionOps // 侧栏的会话操作（sessionops.go）
	layout layout     // 侧栏的宽度、收起（layout.go）
	zoom   int        // 界面缩放的百分数（zoom.go）

	pick    picker     // 选本机的文件、文件夹（系统的对话框；测试里换掉）
	path    pathAttach // 「输入路径」的对话框
	prompts promptsUI  // 常用指令的对话框
	// saveFile 弹系统的保存对话框（标题 title、默认文件名 name、可选的类型 filters），把 data 写到选好的地方，
	// 返回存到哪，取消了是空的。阻塞，在后台调用；New 给的是系统的（systemSaveFile），测试里换掉。
	saveFile func(title, name string, filters []mygo.FileFilter, data []byte) (path string, err error)

	// viewer 是开着的看大图（盖住整个窗口），没开时为 nil；buttonsOverDark 是上次交代给窗口的系统按钮深浅。
	viewer          *chatview.Viewer
	buttonsOverDark bool

	// waitingNotes 是正显示着「等你回应」的系统通知的编号（notify.go）：那个请求处理掉了就撤掉。
	waitingNotes map[string]bool

	// Window 是真窗口（在不在前台、通知、系统按钮）。New 给的是窗口起来之前的（总在前台、什么也不做），
	// 窗口起来后由入口换成真的。
	Window Window
	// Language 是界面语言的选择（设置「常规」的「语言」）。New 给的只记在内存里，入口换成 mygo 的（马上换语言、下次启动还记得）。
	Language Language
}

// Language 是界面语言的选择：Chosen 是选了哪种（BCP 47 标签，"" 是跟随系统），Choose 改成 tag。
type Language interface {
	Chosen() string
	Choose(tag string)
}

// memoryLanguage 只记着选了哪种，不真换语言（入口换成真的之前、测试里）。
type memoryLanguage struct{ tag string }

func (l *memoryLanguage) Chosen() string    { return l.tag }
func (l *memoryLanguage) Choose(tag string) { l.tag = tag }

// Window 是 App 碰真窗口的几样。
type Window interface {
	// Foreground 报告窗口在不在前台。
	Foreground() bool
	// Notify 发一条系统通知（notify.go：回答完了、等你回应而窗口不在前台时）。
	Notify(n Note)
	// Withdraw 撤掉编号 id 的系统通知（等的请求处理掉了）；已经没了的什么也不做。
	Withdraw(id string)
	// ButtonsOverDark 让系统的最小化、最大化、关闭按钮按深色底画（on，看大图时），或回到跟着主题。
	ButtonsOverDark(on bool)
}

// noWindow 是窗口起来之前的：总在前台，不发通知，系统按钮不管。
type noWindow struct{}

func (noWindow) Foreground() bool     { return true }
func (noWindow) Notify(Note)          {}
func (noWindow) Withdraw(string)      {}
func (noWindow) ButtonsOverDark(bool) {}

// notice 是一次操作的结果，弹个框说一声。
type notice struct {
	title, message string
	open           bool
}

// New 建窗口的状态。run 把要等设备的事放到后台，post 把结果交回主线程（窗口的 Update）。
func New(h *hub.Hub, run, post func(func())) *App {
	a := &App{hub: h, run: run, post: post, now: time.Now, pick: systemPicker, saveFile: systemSaveFile, Window: noWindow{}, Language: &memoryLanguage{},
		About: defaultAbout(h)}
	h.OnAlert(a.alert)
	a.loadLayout()
	return a
}

// Go 在后台跑 work，它返回的函数交回主线程跑。
func (a *App) async(work func() func()) {
	a.run(func() {
		apply := work()
		a.post(apply)
	})
}

// View 是窗口的内容：COUI 的配色（跟着系统的深浅色），左边侧栏（能拖宽、能收起，layout.go），右边是在看的那一页。
func (a *App) View(c *ui.Context) {
	c.SetTheme(widget.Theme(a.dark(c.Theme().Dark)))
	a.applyZoom(c)
	a.syncChat()
	a.saveDraftsWhenDue(c)
	if a.shown == pageDevice && a.page != pageDevice {
		a.hub.ApplyOptions(a.device) // 设备页上改过的自动压缩、自动标题
	}
	a.leftAllTasks()
	a.shown = a.page
	k := widget.Of(c)
	a.shortcuts(c)
	a.frame(c, func() {
		main := ui.Column(c).Fill().Background(k.Window)
		if a.page != pageHome {
			main.Background(k.Page)
		}
		main.Children(func() {
			switch {
			case a.page == pageSettings:
				a.settingsPage(c)
			case a.page == pageDevices:
				a.devicesPage(c)
			case a.page == pageDevice:
				a.devicePage(c)
			case a.page == pageAccounts:
				a.accountsPage(c)
			case a.page == pageAbout:
				a.aboutPage(c)
			case a.page == pageDiag:
				a.diagPage(c)
			case a.page == pageTasks:
				a.tasksPanel(c) // 后台任务面板（alltasks.go）
			case a.pane != nil:
				a.chatPage(c)
			default:
				a.home(c)
				widget.DragStrip(c)
			}
			a.updateBar(c)
		})
	})
	a.sessionDialogs(c)
	a.noticeDialog(c)
	a.viewImages(c)
}

// openImages 打开看大图：从 list 的第 index 张看起，图从发出它的列表 from 里取；保存经保存对话框。
func (a *App) openImages(from *chatview.Chat, list []items.Image, index int) {
	v := chatview.NewViewer(from, list, index)
	v.Save = func(data []byte, name string) (string, error) {
		return a.saveFile(locales.AppSaveImage(), name, nil, data)
	}
	a.viewer = v
}

// viewImages 画开着的看大图，关上了丢掉。看大图是黑底：开着时系统按钮画浅色字形，关上后回到跟着主题（只在变了时交代）。
func (a *App) viewImages(c *ui.Context) {
	if a.viewer != nil && !a.viewer.View(c) {
		a.viewer = nil
	}
	if on := a.viewer != nil; on != a.buttonsOverDark {
		a.buttonsOverDark = on
		a.Window.ButtonsOverDark(on)
	}
}

// open 换到一页。
func (a *App) open(page string) {
	a.page = page
	switch page {
	case pageSettings:
		a.startSettings()
	case pageDevices:
		a.startAdding()
	case pageDevice:
		a.startEditing()
	case pageAccounts:
		a.startAccounts()
	case pageDiag:
		a.startDiag()
	}
}

// home 是没在看别的页时的主区域：还没有设备时请用户加一台。
func (a *App) home(c *ui.Context) {
	k := widget.Of(c)
	ui.Column(c).Fill().Center().Gap(14).Padding(32).Children(func() {
		brand(c, 56)
		ui.Text(c, "Zeta").FontSize(widget.SizeHeadlineM + 4).FontWeight(600).TextColor(k.Text)
		if len(a.hub.Hosts()) > 0 {
			return
		}
		ui.Text(c, locales.DevicesFooter()).FontSize(widget.SizeBodyS).LineHeight(1.5).TextColor(k.Secondary).MaxWidth(420).
			TextAlign(ui.Center)
		widget.PrimaryButton(c, locales.SettingsAddDevice()).OnClick(func() { a.open(pageDevices) }).Margin(8, 0, 0, 0)
	})
}

// brand 是品牌标：主题蓝的圆角方块（往下渐深），里面白色的 ζ（同手机版侧栏的 BrandMark）。
func brand(c *ui.Context, size float32) {
	k := widget.Of(c)
	deep := k.Primary.Mix(ui.RGB(0, 0, 0), 0.12)
	if k.Dark {
		deep = k.Primary.Mix(ui.RGB(255, 255, 255), 0.12)
	}
	ui.Box(c).Size(size, size).Radius(size*0.29).Gradient(k.Primary, deep, 180).Center().Shrink(0).Children(func() {
		ui.Icon(c, widget.IconBrand).Size(size*0.78, size*0.78).TextColor(k.OnPrimary)
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
	widget.Alert(c, &n.open, n.title, n.message, widget.Action{Label: locales.CommonGotIt(), Style: widget.Recommend})
	if !n.open {
		a.notice = nil
	}
}
