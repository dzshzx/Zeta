package main

import (
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeClaude 起一个命令行里带 claude 的进程（一个叫 claude 的脚本），[ignoreTerm] 时它不理 SIGTERM。
// 返回它的 pid 和一个在它退出后关闭的通道（测试进程替它收尸，不留僵尸）。
func fakeClaude(t *testing.T, ignoreTerm bool) (int, <-chan struct{}) {
	t.Helper()
	if ignoreTerm {
		return trapped(t, "trap '' TERM")
	}
	return trapped(t, ":")
}

// register 像 Claude Code 那样登记进程 pid 在用会话 session；start 是登记里的启动时刻，空串时取它真的启动时刻。
// extra 是另加的键（以逗号开头的 JSON 片段）。
func register(t *testing.T, e *env, pid int, session, start string, extra ...string) {
	t.Helper()
	if start == "" {
		var ok bool
		if start, ok = procStart(pid); !ok {
			t.Fatalf("pid %d is not running", pid)
		}
	}
	writeFile(t, filepath.Join(e.sessions, strconv.Itoa(pid)+".json"),
		`{"pid":`+strconv.Itoa(pid)+`,"sessionId":"`+session+`","procStart":"`+start+`","kind":"interactive"`+strings.Join(extra, "")+`}`)
}

func exited(done <-chan struct{}) bool {
	select {
	case <-done:
		return true
	case <-time.After(3 * time.Second):
		return false
	}
}

func TestEndStopsOnlyOutsideProcessesOfThatSession(t *testing.T) {
	e := testEnv(t)
	const id = "aaaaaaaa-1111-4222-8333-444444444444"
	outside, outsideDone := fakeClaude(t, false)
	register(t, e, outside, id, "")
	// Zeta 自己托管的同一会话：留给 zeta_host.sh stop
	hosted, hostedDone := fakeClaude(t, false)
	register(t, e, hosted, id, "")
	writeFile(t, filepath.Join(e.hosts, "k1", "pid"), strconv.Itoa(hosted))
	// 别的会话
	other, otherDone := fakeClaude(t, false)
	register(t, e, other, "bbbbbbbb-1111-4222-8333-444444444444", "")
	// 登记的启动时刻对不上（pid 被复用了）：不动
	reused, reusedDone := fakeClaude(t, false)
	register(t, e, reused, id, "1")

	out := mustCall(t, e, "end", id)
	if len(out) != 1 || num(t, out[0]["ended"]) != 1 {
		t.Fatalf("out = %v, want ended 1", out)
	}
	if !exited(outsideDone) {
		t.Fatal("the outside process of the session is still running")
	}
	for name, done := range map[string]<-chan struct{}{"hosted": hostedDone, "other": otherDone, "reused": reusedDone} {
		select {
		case <-done:
			t.Fatalf("%s process was ended", name)
		default:
		}
	}
}

func TestEndKillsProcessThatIgnoresTerm(t *testing.T) {
	old := endGrace
	endGrace = 300 * time.Millisecond
	t.Cleanup(func() { endGrace = old })
	e := testEnv(t)
	const id = "cccccccc-1111-4222-8333-444444444444"
	pid, done := fakeClaude(t, true)
	register(t, e, pid, id, "")
	out := mustCall(t, e, "end", id)
	if num(t, out[0]["ended"]) != 1 || !exited(done) {
		t.Fatalf("out = %v, process exited = false", out)
	}
}

func TestEndWithNothingToEndReportsZero(t *testing.T) {
	e := testEnv(t)
	out := mustCall(t, e, "end", "dddddddd-1111-4222-8333-444444444444")
	if len(out) != 1 || num(t, out[0]["ended"]) != 0 {
		t.Fatalf("out = %v, want ended 0", out)
	}
	if _, err := call(t, e, "end", "../x"); err == nil {
		t.Fatal("a malformed id was accepted")
	}
}
