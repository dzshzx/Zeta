package main

import (
	"os"

	"github.com/egoist/mygo"
)

// exitWhenSessionEnds 让系统要结束 Zeta 时进程真的结束。
//
// Windows 发 WM_ENDSESSION 的不只是注销、关机，还有安装程序的 Restart Manager：装 MSI 时它请占着 Zeta.exe 的进程关掉
// 好换文件。mygo（v0.10.0）收到后跑完退出流程（OnBeforeQuit、关窗口、OnQuit）就停在那里，等系统来结束进程；
// 注销关机时系统会结束，Restart Manager 不会。进程于是留着：没有窗口，却占着单实例锁和 exe，之后打开 Zeta
// 一直无声地等着，装 MSI 写不进文件、回滚失败（错误 1603）。所以退出流程的最后一步自己结束进程。
//
// mygo 收到 WM_ENDSESSION 后自己结束进程时，这一段可以删；升级 mygo 时 TestMygoEndSession 会提醒去核对。
func exitWhenSessionEnds(logf func(string)) {
	ending := false
	mygo.App.OnBeforeQuit(func(e *mygo.QuitEvent) {
		if e.SessionEnding {
			ending = true
		}
	})
	// OnQuit 是退出流程的最后一步，窗口位置已经存好；这时还在注册的别的 OnQuit 都在这之前注册、先跑完
	mygo.App.OnQuit(func() {
		if ending {
			logf("系统要求结束（注销、关机或安装程序要换文件），退出")
			os.Exit(0)
		}
	})
}
