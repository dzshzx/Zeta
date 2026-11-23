package main

import (
	"log"

	zui "github.com/dzshzx/Zeta/windows/internal/ui"
	"github.com/egoist/mygo"
)

// desktopWindow 是界面碰的真窗口（ui.Window）。
type desktopWindow struct{ win *mygo.Window }

func (w desktopWindow) Foreground() bool { return w.win.IsFocused() }

// Notify 发系统通知：点通知回到窗口，再在主线程上做 n.Open。界面在主线程上调它，就在主线程上发：mygo 在 Windows 上
// 当场把气泡交给系统（internal/windows/tray.go 的 ShowNotification 同步调 done，Show 不转事件循环），
// 这样之后的 Withdraw 一定在它后面，不会先撤后发、留下一条过时的。
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
	if err := m.Show(); err != nil {
		log.Printf("zeta: 通知没发出去：%v", err)
	}
}

// Withdraw 撤掉编号 id 的通知：mygo 的 Close 按编号撤（Windows 上去掉显示它的那个通知区图标，气泡随之消失）。
func (w desktopWindow) Withdraw(id string) {
	mygo.NewNotification(mygo.NotificationOptions{ID: id}).Close()
}

// ButtonsOverDark：看大图是黑底，系统按钮画浅色字形；关上后回到跟着主题。
func (w desktopWindow) ButtonsOverDark(on bool) {
	if on {
		w.win.SetWindowButtonsTheme(mygo.ThemeDark)
	} else {
		w.win.SetWindowButtonsTheme(mygo.ThemeSystem)
	}
}

// mygoLanguage 是界面语言的选择（设置「常规」的「语言」）：mygo 选了就换（窗口重画、OnLocaleChanged），记在用户数据目录里下次照旧。
type mygoLanguage struct{}

func (mygoLanguage) Chosen() string    { return mygo.App.ChosenLocale() }
func (mygoLanguage) Choose(tag string) { mygo.App.SetLocale(tag) }
