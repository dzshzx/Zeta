package main

import (
	_ "embed"
	"log"

	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo"
)

// trayIcon、trayDevIcon 是通知区（托盘）里正式版、测试版的图标：安装包图标 installer/icon.png、icon-dev.png 缩到 32×32
// （magick installer/icon.png -resize 32x32 -strip PNG32:cmd/zeta/tray.png，测试版同样；换了安装包图标时照这条重做）。
var (
	//go:embed tray.png
	trayIcon []byte
	//go:embed tray-dev.png
	trayDevIcon []byte
)

// background 让 Zeta 关了窗口也在后台跑：点窗口的关闭只是藏到托盘，对话、会话列表的连接和系统通知照旧；
// 左键点托盘图标、点通知、再打开一次 Zeta 都把窗口叫回来，托盘右键菜单的「退出」才真退出。
// 托盘起不来时（closes 报告为假）关闭照旧是退出，免得窗口藏起来就再也叫不回来。
type background struct {
	win *mygo.Window
	// version 定托盘的图标和提示文字：测试版是橙色图标、Zeta Dev，和同时开着的正式版分得清。
	version appversion.Version
	// tray 是起好的托盘图标；起不来时为 nil。
	tray *mygo.Tray
	// quitting：正在退出（托盘的退出、更新前的退出、关机注销都先经 mygo 的 OnBeforeQuit），这时关窗口就是关。
	quitting bool
}

// start 起托盘图标，并在退出开始时记下（之后关窗口不再拦）。在 mygo 就绪后、主线程上调。
func (b *background) start() {
	mygo.App.OnBeforeQuit(func(*mygo.QuitEvent) { b.quitting = true })
	tray, err := mygo.NewTray(trayOptions(b.version))
	if err != nil {
		log.Printf("zeta: 托盘图标起不来，关窗口就退出：%v", err)
		return
	}
	b.tray = tray
	tray.OnClick(b.show)
	tray.SetMenu(b.menu())
	// 换了界面语言：菜单是一次性交给系统的，照新语言再做一份
	mygo.App.OnLocaleChanged(func(string) { tray.SetMenu(b.menu()) })
}

// menu 是托盘右键菜单：打开 Zeta、退出（mygo 的 RoleQuit，字跟着界面语言：「退出 Zeta」「Quit Zeta」）。
func (b *background) menu() *mygo.Menu {
	return mygo.NewMenu([]*mygo.MenuItem{
		{Label: locales.TrayOpen(), Click: func(*mygo.MenuItem, *mygo.Window) { b.show() }},
		mygo.Separator(),
		{Role: mygo.RoleQuit},
	})
}

// hideOnClose 报告点了窗口的关闭时是不是藏到托盘：托盘起好了、又不是在退出。
func (b *background) hideOnClose() bool { return b.tray != nil && !b.quitting }

// show 把窗口叫回来：藏着的显示出来，最小化的还原，再拿到焦点。
func (b *background) show() {
	if !b.win.IsVisible() {
		b.win.Show()
	}
	if b.win.IsMinimized() {
		b.win.Restore()
	}
	b.win.Focus()
}

// trayOptions 是托盘图标的样子。ID 两个版本都是 zeta：Windows 按 exe 的路径和这个号记用户把图标放在任务栏上还是收起来，
// 测试版装在另一个目录，不会和正式版的混在一起；正式版的 ID 改了会丢掉用户的这个选择。
func trayOptions(v appversion.Version) mygo.TrayOptions {
	icon := trayIcon
	if !v.Release() {
		icon = trayDevIcon
	}
	return mygo.TrayOptions{ID: "zeta", Icon: icon, ToolTip: v.AppName()}
}
