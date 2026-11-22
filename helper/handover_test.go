package main

import (
	"os"
	"syscall"
	"testing"

	"github.com/dzshzx/Zeta/helper/core"
)

// startCoreBuilt 拉起一个构建序号为 n 的核心子进程（测试程序扮的 zeta-helper core），返回它的进程号。
func startCoreBuilt(t *testing.T, p core.Paths, n string) int {
	t.Helper()
	t.Setenv(testBuildEnv, n)
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	conn, info, err := core.Connect(p, exe)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if info.Build != mustInt(t, n) {
		t.Fatalf("核心报的构建序号：%+v", info)
	}
	return corePid(p)
}

func mustInt(t *testing.T, s string) int64 {
	t.Helper()
	var n int64
	for _, r := range s {
		n = n*10 + int64(r-'0')
	}
	return n
}

// useBuild 让测试进程里的 serve 扮构建序号为 n 的版本，它拉起的核心也是 n。
func useBuild(t *testing.T, n string) {
	t.Helper()
	saved := build
	build = n
	t.Setenv(testBuildEnv, n)
	t.Cleanup(func() { build = saved })
}

// 新版本的 serve 连上旧核心：让它交班，拉起自己这个版本的；旧版本的 serve 再连上来照用新核心，不交班。
func TestNewerServeTakesOverTheCore(t *testing.T) {
	e, p := coreEnv(t)
	old := startCoreBuilt(t, p, "5")

	useBuild(t, "9")
	c := startServe(t, e, testIdle)
	c.send(`{"id":1,"method":"sessions.watch"}`)
	if r := c.next("zeta_reply"); r["ok"] != true {
		t.Fatalf("交班后的应答：%v", r)
	}
	fresh := corePid(p)
	if fresh == 0 || fresh == old {
		t.Fatalf("核心没换：旧 %d，现在 %d", old, fresh)
	}
	if syscall.Kill(old, 0) == nil {
		t.Fatalf("旧核心 %d 还在", old)
	}

	useBuild(t, "5")
	older := startServe(t, e, testIdle)
	older.send(`{"id":1,"method":"sessions.watch"}`)
	if r := older.next("zeta_reply"); r["ok"] != true {
		t.Fatalf("旧 serve 的应答：%v", r)
	}
	if corePid(p) != fresh {
		t.Fatal("旧 serve 让新核心交班了")
	}
}
