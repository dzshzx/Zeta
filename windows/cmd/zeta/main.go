// Command zeta 是 Zeta 的 Windows 桌面版：经 SSH 用远程设备上的 Claude Code（见 docs/desktop.md）。
package main

import (
	"log"
	"os"
	"path/filepath"
	"runtime/debug"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/host"

	// 界面的文字表：注册后 mygo 的语言就是简体中文（源语言）和英文，它自带的文字（菜单、输入框菜单）跟着用同一种
	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/hub/runlog"
	"github.com/dzshzx/Zeta/hub/settings"
	_ "github.com/dzshzx/Zeta/windows/internal/locales"
	zui "github.com/dzshzx/Zeta/windows/internal/ui"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

func main() {
	// 只跑一个：关了窗口的 Zeta 还在托盘里跑，再打开一次是把它的窗口叫回来（OnSecondInstance），不另起一个
	if !mygo.App.RequestSingleInstanceLock() {
		return
	}
	// 换了界面语言（设置里改，或跟随的系统语言变了）：开着的连接都把新语言报给核心，核心之后的应答用它。
	// 写连接可能卡在网络上，不占主线程
	mygo.App.OnLocaleChanged(func(string) { go hostlink.IntroduceAll() })
	dir, err := settings.Dir()
	if err != nil {
		log.Fatalf("zeta: 找不到放设置的目录：%v", err)
	}
	// 界面程序没有控制台：标准库 log 的输出（各处出岔子时的 log.Printf）记进设置目录 diag 下的运行日志，诊断页能看、
	// 诊断包里带着；崩溃时的调用栈另写进同一目录的 crash.log
	diagDir := filepath.Join(dir, "diag")
	runLog := runlog.Open(diagDir, nil)
	log.SetFlags(0)
	log.SetOutput(runLog)
	keepCrashes(diagDir)
	version := appversion.Current()
	runLog.Add(runlog.Info, runlog.App, "Zeta "+version.Name+" 启动")
	fatal := func(err error) {
		runLog.Add(runlog.Error, runlog.App, "起不来："+err.Error())
		os.Exit(1)
	}
	prefs, err := settings.Load(dir)
	if err != nil {
		log.Printf("zeta: %v", err)
	}
	config := sshlink.NewOpenSSH()
	devices, err := host.NewDevices(config)
	if err != nil {
		fatal(err)
	}
	devices.Config, devices.Device = config, host.ThisDevice()

	// 后台 goroutine 的结果经窗口的 Update 回到主线程，改完重画
	var win *mygo.Window
	post := func(f func()) { win.Update(f) }
	run := func(f func()) { go f() }
	h := hub.New(prefs, devices, run, post)
	app := zui.New(h, run, post)
	// 自动更新：只有正式版查（Updater.Enabled），关于页能下载安装
	updater := &update.Updater{
		Version: version,
		Source:  &update.Client{UserAgent: "Zeta-Desktop/" + version.Name},
		Dir:     update.DownloadDir(dir),
		Install: update.Installer{Log: filepath.Join(diagDir, "msi-install.log")}.Install,
		Prefs:   prefs,
	}
	app.About = &about.Page{Version: version, Updates: updater, Prefs: prefs}
	// 诊断页的运行日志和环境；诊断包里带上 Windows 版本和 diag 下的文件（运行日志、崩溃记录、安装记录）
	app.DiagEnv = osLines
	app.DiagDir = diagDir
	app.RunLog = runLog
	mygo.App.WhenReady(func() {
		// 界面画出来之前先铺 COUI 的页面底色，打开时不闪一下白。没有系统标题栏：侧栏和各页的顶栏就是标题栏，
		// 系统自己的最小化、最大化、关闭按钮浮在右上角，和顶栏一样高（见 widget/titlebar.go）。窗口的位置、大小、
		// 最大化记在 StateKey 下，下次打开时照旧；1280×860 只是第一次打开的大小。标题（任务栏、Alt+Tab 里的名字）测试版是 Zeta Dev
		win = mygo.NewWindow(mygo.WindowOptions{Title: version.AppName(), Width: 1280, Height: 860, MinWidth: 720, MinHeight: 480,
			StateKey: "main", BackgroundColor: windowBackground(prefs), TitleBarStyle: mygo.TitleBarHidden,
			TitleBarHeight: widget.TitleBarHeight, Content: ui.View(app.View)})
		// 关窗口只是藏到托盘，后台照旧连着、发通知；托盘右键「退出」才退出（tray.go）。藏起来或真关时，
		// 没存的草稿马上存盘（平时输入停下约一秒才存），拖过的侧栏宽度也存上
		bg := &background{win: win, version: version}
		bg.start()
		win.OnClose(func(e *mygo.CloseEvent) {
			app.SaveDrafts()
			app.SaveLayout()
			if bg.hideOnClose() {
				e.PreventDefault()
				win.Hide()
			}
		})
		mygo.App.OnSecondInstance(func([]string, string) { bg.show() })
		// 回答完了而窗口不在前台时发通知（点通知把窗口叫回来），看大图时系统按钮换深浅（window.go）
		app.Window = desktopWindow{win: win, show: bg.show}
		app.Language = mygoLanguage{}
		// 更新的状态在后台变：重画；查到新版本时窗口底部提示；安装程序起好后退出
		updater.Changed = func() { win.Update(func() {}) }
		updater.Notify = func(version string) { win.Update(func() { app.ShowUpdate(version) }) }
		updater.Exit = func() { win.Update(mygo.App.Quit) }
		// 运行日志记了新的一条：诊断页开着时跟着重画
		runLog.OnChange(func() { win.Update(func() {}) })
		h.Start()
		updater.Start()
	})
	// 最后注册：系统结束会话时等别的退出回调都跑完再结束进程（endsession.go）
	exitWhenSessionEnds(func(s string) { runLog.Add(runlog.Info, runlog.App, s) })
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}

// windowBackground 是窗口在界面画出来之前的底色：设置里选了浅色或深色就照它，没选时跟着系统（同 App 的主题）。
func windowBackground(prefs *settings.Settings) string {
	dark, set := prefs.Dark()
	switch {
	case !set:
		return "light-dark(#ffffff, #000000)"
	case dark:
		return "#000000"
	default:
		return "#ffffff"
	}
}

// crashLimit 是 crash.log 长到多大就在启动时清空重来（崩溃很少，留着最近的几次就够）。
const crashLimit = 1 << 20

// keepCrashes 让程序崩溃（没接住的 panic、运行时的致命错误）时的调用栈追加到 dir 下的 crash.log：界面程序没有控制台，
// 不接时这些就丢了。打不开时不管。
func keepCrashes(dir string) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	path := filepath.Join(dir, "crash.log")
	flags := os.O_CREATE | os.O_WRONLY | os.O_APPEND
	if st, err := os.Stat(path); err == nil && st.Size() > crashLimit {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(path, flags, 0o644)
	if err != nil {
		return
	}
	defer f.Close() // SetCrashOutput 自己留着一份
	_ = debug.SetCrashOutput(f, debug.CrashOptions{})
}
