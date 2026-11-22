package main

import (
	"log"

	"github.com/egoist/mygo"
)

// desktopWindow 是界面碰的真窗口（ui.Window）。
type desktopWindow struct{ win *mygo.Window }

func (w desktopWindow) Foreground() bool { return w.win.IsFocused() }

// Notify 发系统通知，点通知回到窗口。
func (w desktopWindow) Notify(title, body string) {
	if !mygo.NotificationsSupported() {
		return
	}
	n := mygo.NewNotification(mygo.NotificationOptions{ID: "zeta-turn", Title: title, Body: body})
	n.OnClick(func() {
		w.win.Restore()
		w.win.Focus()
	})
	go func() {
		if err := n.Show(); err != nil {
			log.Printf("zeta: 通知没发出去：%v", err)
		}
	}()
}

// ButtonsOverDark：看大图是黑底，系统按钮画浅色字形；关上后回到跟着主题。
func (w desktopWindow) ButtonsOverDark(on bool) {
	if on {
		w.win.SetWindowButtonsTheme(mygo.ThemeDark)
	} else {
		w.win.SetWindowButtonsTheme(mygo.ThemeSystem)
	}
}
