package main

import (
	"syscall"
	"testing"
	"time"
)

// 会话列表每一项的 live：有活着的 Claude Code 进程登记着它时为真。进程被杀、登记文件留着（目录不变，inotify 看不出）时，
// 订阅按 poll 核对登记的进程，推一份 live 为假的这一项。
func TestSessionsWatchLiveFollowsProcesses(t *testing.T) {
	e := testEnv(t)
	writeSession(t, e, sessionA, "开着的")
	writeSession(t, e, sessionB, "结束了的")
	pid, done := fakeClaude(t, false)
	register(t, e, pid, sessionA, "")

	inProcessCore(t, e, watchOptions{debounce: 20 * time.Millisecond, poll: 50 * time.Millisecond})
	c := startServe(t, e, testIdle)
	c.ok("sessions.watch", "")
	full := c.nextWhere("zeta_sessions", func(m obj) bool { return m["full"] == true })
	live := map[string]any{}
	for _, it := range asList(full["upsert"]) {
		item := asObj(it)
		live[item["id"].(string)] = item["live"]
	}
	if live[sessionA] != true || live[sessionB] != false {
		t.Fatalf("整份里的 live：%v", live)
	}

	if err := syscall.Kill(pid, syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	if !exited(done) {
		t.Fatal("进程没退出")
	}
	c.nextWhere("zeta_sessions", upserted(sessionA, func(it obj) bool { return it["live"] == false }))
}
