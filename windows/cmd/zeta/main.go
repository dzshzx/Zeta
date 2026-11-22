// Command zeta 是 Zeta 的 Windows 桌面版：经 SSH 用远程设备上的 Claude Code（见 docs/desktop.md）。
package main

import (
	"log"
	"path/filepath"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	zui "github.com/dzshzx/Zeta/windows/internal/ui"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

func main() {
	dir, err := settings.Dir()
	if err != nil {
		log.Fatalf("zeta: 找不到放设置的目录：%v", err)
	}
	prefs, err := settings.Load(dir)
	if err != nil {
		log.Printf("zeta: %v", err)
	}
	helper, err := host.LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		log.Fatalf("zeta: %v", err)
	}
	switcher, err := host.LoadProgram(hostlink.ClaudeSwitch)
	if err != nil {
		log.Fatalf("zeta: %v", err)
	}
	config := sshlink.NewOpenSSH()
	devices := &host.Devices{Links: sshlink.NewLinks(config, time.Minute), Config: config, Helper: helper, Switch: switcher}

	// 后台 goroutine 的结果经窗口的 Update 回到主线程，改完重画
	var win *mygo.Window
	post := func(f func()) { win.Update(f) }
	run := func(f func()) { go f() }
	h := hub.New(prefs, devices, run, post)
	app := zui.New(h, run, post)
	// 自动更新：只有正式版查（Updater.Enabled），关于页能下载安装
	version := update.Current()
	updater := &update.Updater{
		Version: version,
		Source:  &update.Client{UserAgent: "Zeta-Desktop/" + version.Name},
		Dir:     update.DownloadDir(dir),
		Install: update.Installer{Log: filepath.Join(dir, "diag", "msi-install.log")}.Install,
		Prefs:   prefs,
	}
	app.About = &about.Page{Version: version, Updates: updater, Prefs: prefs}
	mygo.App.WhenReady(func() {
		// 界面画出来之前先铺 COUI 的页面底色，打开时不闪一下白。没有系统标题栏：侧栏和各页的顶栏就是标题栏，
		// 系统自己的最小化、最大化、关闭按钮浮在右上角，和 COUI 顶栏一样高（见 coui/titlebar.go）
		win = mygo.NewWindow(mygo.WindowOptions{Title: "Zeta", Width: 1280, Height: 860, BackgroundColor: windowBackground(prefs),
			TitleBarStyle: mygo.TitleBarHidden, TitleBarHeight: coui.TitleBarHeight, Content: ui.View(app.View)})
		// 回答完了而窗口不在前台时发通知，点通知回到窗口
		app.Foreground = func() bool { return win.IsFocused() }
		// 看大图是黑底：系统按钮画浅色字形，关上后回到跟着主题
		app.WindowButtonsOverDark = func(on bool) {
			if on {
				win.SetWindowButtonsTheme(mygo.ThemeDark)
			} else {
				win.SetWindowButtonsTheme(mygo.ThemeSystem)
			}
		}
		app.Notify = func(title, body string) {
			if !mygo.NotificationsSupported() {
				return
			}
			n := mygo.NewNotification(mygo.NotificationOptions{ID: "zeta-turn", Title: title, Body: body})
			n.OnClick(func() {
				win.Restore()
				win.Focus()
			})
			go func() {
				if err := n.Show(); err != nil {
					log.Printf("zeta: 通知没发出去：%v", err)
				}
			}()
		}
		// 更新的状态在后台变：重画；查到新版本时窗口底部提示；安装程序起好后退出
		updater.Changed = func() { win.Update(func() {}) }
		updater.Notify = func(text string) { win.Update(func() { app.ShowUpdate(text) }) }
		updater.Exit = func() { win.Update(mygo.App.Quit) }
		h.Start()
		updater.Start()
	})
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
