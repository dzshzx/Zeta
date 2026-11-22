// Command zeta 是 Zeta 的 Windows 桌面版：经 SSH 用远程设备上的 Claude Code（见 docs/desktop.md）。
package main

import (
	"log"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	zui "github.com/dzshzx/Zeta/windows/internal/ui"
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
	config := sshlink.NewOpenSSH()
	devices := &host.Devices{Links: sshlink.NewLinks(config, time.Minute), Config: config, Helper: helper}

	// 后台 goroutine 的结果经窗口的 Update 回到主线程，改完重画
	var win *mygo.Window
	post := func(f func()) { win.Update(f) }
	run := func(f func()) { go f() }
	h := hub.New(prefs, devices, run, post)
	app := zui.New(h, run, post)
	mygo.App.WhenReady(func() {
		// 界面画出来之前先铺 COUI 的页面底色，打开时不闪一下白
		win = mygo.NewWindow(mygo.WindowOptions{Title: "Zeta", Width: 1280, Height: 860, BackgroundColor: "light-dark(#ffffff, #000000)",
			Content: ui.View(app.View)})
		// 回答完了而窗口不在前台时发通知，点通知回到窗口
		app.Foreground = func() bool { return win.IsFocused() }
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
		h.Start()
	})
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}
