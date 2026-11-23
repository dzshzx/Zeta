package main

import (
	"log"

	zui "github.com/dzshzx/Zeta/windows/internal/ui"
	"github.com/egoist/mygo"
)

// desktopWindow 是界面碰的真窗口（ui.Window）。
type desktopWindow struct{ win *mygo.Window }

func (w desktopWindow) Foreground() bool { return w.win.IsFocused() }

// Notify 发系统通知：点通知回到窗口，再在主线程上做 n.Open。
func (w desktopWindow) Notify(n zui.Note) {
	if !mygo.NotificationsSupported() {
		return
	}
	m := mygo.NewNotification(mygo.NotificationOptions{ID: n.ID, Title: n.Title, Body: n.Body})
	m.OnClick(func() {
		w.win.Restore()
		w.win.Focus()
		if n.Open != nil {
			w.win.Update(n.Open)
		}
	})
	go func() {
		if err := m.Show(); err != nil {
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
