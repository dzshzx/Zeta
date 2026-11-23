package main

import (
	"runtime/debug"
	"testing"
)

// exitWhenSessionEnds 补的是 mygo v0.10.0 收到 WM_ENDSESSION 后不结束进程的缺口。换了 mygo 版本时这里失败：
// 去看新版本的 internal/windows/app.go 收到 WM_ENDSESSION 后结不结束进程。结束的话删掉 endsession.go 和这个测试；
// 还不结束的话把下面的版本改成新的。
func TestMygoEndSession(t *testing.T) {
	const checked = "v0.10.0"
	info, ok := debug.ReadBuildInfo()
	if !ok {
		t.Fatal("读不到构建信息，核对不了 mygo 的版本")
	}
	for _, m := range info.Deps {
		if m.Path == "github.com/egoist/mygo" {
			if m.Version != checked {
				t.Errorf("mygo 是 %s，exitWhenSessionEnds 是对着 %s 核对的：见本测试的说明", m.Version, checked)
			}
			return
		}
	}
	t.Fatal("构建信息里没有 mygo")
}
