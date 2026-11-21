package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// stub 起一个命令行里带 claude 的脚本 body（第一个参数是 dir），返回 pid 和它退出后关闭的通道。
func stub(t *testing.T, body, dir string) (int, <-chan struct{}) {
	t.Helper()
	script := filepath.Join(t.TempDir(), "claude")
	writeFile(t, script, "#!/bin/bash\n"+body)
	if err := os.Chmod(script, 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(script, dir)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); close(done) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-done })
	return cmd.Process.Pid, done
}

// tui 是终端里的 Claude Code：收到 SIGINT 就退出（照 /exit），[stubborn] 时不理它。设好 trap 后才返回。
func tui(t *testing.T, stubborn bool) (int, <-chan struct{}) {
	t.Helper()
	ready := filepath.Join(t.TempDir(), "ready")
	trap := "trap 'exit 0' INT"
	if stubborn {
		trap = "trap '' INT"
	}
	pid, done := stub(t, trap+"\n: > \"$1\"\nwhile :; do sleep 0.05; done\n", ready)
	waitFile(t, ready)
	return pid, done
}

func waitFile(t *testing.T, path string) {
	t.Helper()
	for deadline := time.Now().Add(3 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s did not appear", path)
		}
	}
}

// registerStatus 像 Claude Code 那样登记，带上忙闲状态（空串时不写这一项）。
func registerStatus(t *testing.T, e *env, pid int, session, status, waitingFor string) {
	t.Helper()
	start, ok := procStart(pid)
	if !ok {
		t.Fatalf("pid %d is not running", pid)
	}
	extra := ""
	if status != "" {
		extra += `,"status":"` + status + `"`
	}
	if waitingFor != "" {
		extra += `,"waitingFor":"` + waitingFor + `"`
	}
	writeFile(t, filepath.Join(e.sessions, strconv.Itoa(pid)+".json"),
		`{"pid":`+strconv.Itoa(pid)+`,"sessionId":"`+session+`","procStart":"`+start+`"`+extra+`}`)
}

func stillUp(done <-chan struct{}) bool {
	select {
	case <-done:
		return false
	default:
		return true
	}
}

const handoffID = "eeeeeeee-1111-4222-8333-444444444444"

func TestOccupantReportsTheMostPressingStatus(t *testing.T) {
	e := testEnv(t)
	out := mustCall(t, e, "occupant", handoffID)
	if out[0]["status"] != nil {
		t.Fatalf("nobody holds it: %v", out)
	}
	idle, _ := tui(t, false)
	registerStatus(t, e, idle, handoffID, "idle", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "idle" || got["waiting_for"] != nil {
		t.Fatalf("idle holder: %v", got)
	}
	waiting, _ := tui(t, false)
	registerStatus(t, e, waiting, handoffID, "waiting", "approve Bash")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "waiting" || got["waiting_for"] != "approve Bash" {
		t.Fatalf("waiting holder: %v", got)
	}
	busy, _ := tui(t, false)
	registerStatus(t, e, busy, handoffID, "busy", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "busy" {
		t.Fatalf("busy holder: %v", got)
	}
}

func TestOccupantWithoutStatusIsUnknown(t *testing.T) {
	e := testEnv(t)
	pid, _ := tui(t, false)
	registerStatus(t, e, pid, handoffID, "", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "unknown" {
		t.Fatalf("got %v", got)
	}
}

func TestHandoffInterruptsAnIdleTerminal(t *testing.T) {
	e := testEnv(t)
	pid, done := tui(t, false)
	registerStatus(t, e, pid, handoffID, "idle", "")
	other, otherDone := tui(t, false)
	registerStatus(t, e, other, "ffffffff-1111-4222-8333-444444444444", "idle", "")
	got := mustCall(t, e, "handoff", handoffID)[0]
	if got["handed"] != true || !exited(done) {
		t.Fatalf("got %v, exited %v", got, !stillUp(done))
	}
	if !stillUp(otherDone) || other == 0 {
		t.Fatal("another session's process was interrupted")
	}
}

func TestHandoffLeavesABusyTerminalAlone(t *testing.T) {
	e := testEnv(t)
	for _, status := range []string{"busy", "shell"} {
		pid, done := tui(t, false)
		registerStatus(t, e, pid, handoffID, status, "")
		got := mustCall(t, e, "handoff", handoffID)[0]
		if got["handed"] != false || got["status"] != status {
			t.Fatalf("%s: got %v", status, got)
		}
		time.Sleep(100 * time.Millisecond)
		if !stillUp(done) {
			t.Fatalf("%s terminal was interrupted", status)
		}
		_ = syscall.Kill(pid, syscall.SIGKILL)
		<-done
	}
}

func TestHandoffReportsATerminalThatWillNotExit(t *testing.T) {
	old := handoffGrace
	handoffGrace = 300 * time.Millisecond
	t.Cleanup(func() { handoffGrace = old })
	e := testEnv(t)
	pid, done := tui(t, true)
	registerStatus(t, e, pid, handoffID, "waiting", "approve Bash")
	got := mustCall(t, e, "handoff", handoffID)[0]
	if got["handed"] != false || got["status"] != "stuck" || !stillUp(done) {
		t.Fatalf("got %v", got)
	}
}

func TestHandoffWithNobodyHoldingIt(t *testing.T) {
	e := testEnv(t)
	if got := mustCall(t, e, "handoff", handoffID)[0]; got["handed"] != true {
		t.Fatalf("got %v", got)
	}
}

// hosted 照 zeta_host.sh 在包 name 的托管目录里摆一个 Claude Code：输入管道读到 end_session 就退出，out.jsonl 是 lines。
func hosted(t *testing.T, e *env, name, session string, lines ...string) (string, int, <-chan struct{}) {
	t.Helper()
	dir := filepath.Join(newEnvAt(e.home, name).hosts, "k-"+name+"-"+strconv.Itoa(len(lines)))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := syscall.Mkfifo(filepath.Join(dir, "in"), 0o600); err != nil {
		t.Fatal(err)
	}
	writeLines(t, filepath.Join(dir, "out.jsonl"), lines...)
	ready := filepath.Join(dir, "ready")
	pid, done := stub(t, `D=$(dirname "$1"); exec 3<>"$D/in"; : > "$1"
while read -r l <&3; do case $l in *end_session*) exit 0;; esac; done
`, ready)
	waitFile(t, ready)
	writeFile(t, filepath.Join(dir, "pid"), strconv.Itoa(pid))
	registerStatus(t, e, pid, session, "idle", "")
	return dir, pid, done
}

var (
	turnDone = []string{`{"type":"system","subtype":"init","session_id":"` + handoffID + `"}`, `{"type":"result","subtype":"success"}`}
	turnOn   = []string{`{"type":"system","subtype":"init","session_id":"` + handoffID + `"}`}
)

func TestReleaseEndsAnIdleHostedProcessOfEitherPackage(t *testing.T) {
	e := testEnv(t)
	dir, _, done := hosted(t, e, "zeta-dev", handoffID, turnDone...)
	other, _, otherDone := hosted(t, e, "zeta", "ffffffff-1111-4222-8333-444444444444", turnDone...)
	got := mustCall(t, e, "release", handoffID)[0]
	if num(t, got["released"]) != 1 || got["busy"] != false || !exited(done) {
		t.Fatalf("got %v", got)
	}
	if _, err := os.Stat(filepath.Join(dir, releasedMark)); err != nil {
		t.Fatal("no released mark for zeta_host.sh")
	}
	if !stillUp(otherDone) {
		t.Fatalf("the process of another session (%s) was ended", other)
	}
}

func TestReleaseLeavesABusyHostedProcessAlone(t *testing.T) {
	e := testEnv(t)
	dir, _, done := hosted(t, e, "zeta", handoffID, turnOn...)
	got := mustCall(t, e, "release", handoffID)[0]
	if num(t, got["released"]) != 0 || got["busy"] != true || !stillUp(done) {
		t.Fatalf("got %v", got)
	}
	if _, err := os.Stat(filepath.Join(dir, releasedMark)); err == nil {
		t.Fatal("marked a process that was left running")
	}
}

func TestReleaseWaitsForTheTurnToEnd(t *testing.T) {
	e := testEnv(t)
	dir, _, done := hosted(t, e, "zeta", handoffID, turnOn...)
	go func() {
		time.Sleep(1200 * time.Millisecond)
		f, err := os.OpenFile(filepath.Join(dir, "out.jsonl"), os.O_APPEND|os.O_WRONLY, 0)
		if err == nil {
			_, _ = f.WriteString(`{"type":"result","subtype":"success"}` + "\n")
			_ = f.Close()
		}
	}()
	got := mustCall(t, e, "release", handoffID, "5")
	if num(t, got[0]["released"]) != 1 || !exited(done) {
		t.Fatalf("got %v", got)
	}
}

func TestReleaseFallsBackToSigtermWhenTheProcessIgnoresTheRequest(t *testing.T) {
	old := releaseGrace
	releaseGrace = 300 * time.Millisecond
	t.Cleanup(func() { releaseGrace = old })
	e := testEnv(t)
	dir := filepath.Join(e.hosts, "k")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := syscall.Mkfifo(filepath.Join(dir, "in"), 0o600); err != nil {
		t.Fatal(err)
	}
	writeLines(t, filepath.Join(dir, "out.jsonl"), turnDone...)
	ready := filepath.Join(dir, "ready")
	// 开着管道、什么都不读：end_session 没人理
	pid, done := stub(t, `D=$(dirname "$1"); exec 3<>"$D/in"; : > "$1"; while :; do sleep 0.05; done`+"\n", ready)
	waitFile(t, ready)
	writeFile(t, filepath.Join(dir, "pid"), strconv.Itoa(pid))
	registerStatus(t, e, pid, handoffID, "idle", "")
	if got := mustCall(t, e, "release", handoffID)[0]; num(t, got["released"]) != 1 || !exited(done) {
		t.Fatalf("got %v", got)
	}
}

func TestHandoffCommandsRejectMalformedIDs(t *testing.T) {
	e := testEnv(t)
	for _, args := range [][]string{
		{"occupant", "../x"}, {"handoff"}, {"release", handoffID, "soon"}, {"desk-open", handoffID, "/tmp"},
		{"desk-close", handoffID, "../x"}, {"desk", handoffID},
	} {
		if _, err := call(t, e, args...); err == nil {
			t.Fatalf("%v was accepted", args)
		}
	}
}

// fakeTmux 换掉 tmux：记下每条命令，按 answer 作答。
func fakeTmux(t *testing.T, present bool, answer func(args []string) (string, error)) *[]string {
	t.Helper()
	var calls []string
	oldRun, oldPresent := tmuxRun, tmuxPresent
	tmuxRun = func(args ...string) (string, error) {
		calls = append(calls, strings.Join(args, " "))
		return answer(args)
	}
	tmuxPresent = func() bool { return present }
	t.Cleanup(func() { tmuxRun, tmuxPresent = oldRun, oldPresent })
	return &calls
}

func TestDeskOpenStartsTheZetaSessionThenAddsWindows(t *testing.T) {
	e := testEnv(t)
	hasSession := false
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		switch args[0] {
		case "list-windows":
			return "", nil
		case "has-session":
			if !hasSession {
				return "", os.ErrNotExist
			}
		case "new-session", "new-window":
			hasSession = true
			return "@7", nil
		}
		return "", nil
	})
	got := mustCall(t, e, "desk-open", handoffID, "/work/a b", "修 'bug' 的会话")[0]
	if got["window"] != "@7" {
		t.Fatalf("got %v", got)
	}
	joined := strings.Join(*calls, "\n")
	want := "new-session -d -s zeta -P -F #{window_id} -n 修 'bug' 的会话 -c /work/a b '" + e.home +
		"/.cache/zeta/bin/zeta-helper' desk '" + handoffID + "' '/work/a b'"
	if !strings.Contains(joined, want) || !strings.Contains(joined, "set-option -w -t @7 @zeta_session "+handoffID) {
		t.Fatalf("calls:\n%s", joined)
	}
	mustCall(t, e, "desk-open", "ffffffff-1111-4222-8333-444444444444", "/w", "")
	if last := strings.Join(*calls, "\n"); !strings.Contains(last, "new-window -d -t =zeta: -P -F #{window_id} -n ffffffff -c /w") {
		t.Fatalf("calls:\n%s", last)
	}
}

func TestDeskOpenRenamesAnExistingWindow(t *testing.T) {
	e := testEnv(t)
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		if args[0] == "list-windows" {
			return "@1 \n@3 " + handoffID, nil
		}
		return "", nil
	})
	if got := mustCall(t, e, "desk-open", handoffID, "/w", "新标题")[0]; got["window"] != "@3" {
		t.Fatalf("got %v", got)
	}
	if joined := strings.Join(*calls, "\n"); !strings.Contains(joined, "rename-window -t @3 新标题") || strings.Contains(joined, "new-") {
		t.Fatalf("calls:\n%s", joined)
	}
}

func TestDeskOpenWithoutTmux(t *testing.T) {
	e := testEnv(t)
	fakeTmux(t, false, func([]string) (string, error) { t.Fatal("ran tmux"); return "", nil })
	if got := mustCall(t, e, "desk-open", handoffID, "/w", "x")[0]; got["window"] != nil || got["reason"] != "no-tmux" {
		t.Fatalf("got %v", got)
	}
}

func TestDeskCloseKillsOnlyThoseWindows(t *testing.T) {
	e := testEnv(t)
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		if args[0] == "list-windows" {
			return "@1 " + handoffID + "\n@2 ffffffff-1111-4222-8333-444444444444", nil
		}
		return "", nil
	})
	if got := mustCall(t, e, "desk-close", handoffID, "aaaaaaaa-1111-4222-8333-444444444444")[0]; num(t, got["closed"]) != 1 {
		t.Fatalf("got %v", got)
	}
	if joined := strings.Join(*calls, "\n"); !strings.Contains(joined, "kill-window -t @1") || strings.Contains(joined, "@2") {
		t.Fatalf("calls:\n%s", joined)
	}
}

func TestDeskGoneWhenTheSessionIsDeletedOrStale(t *testing.T) {
	e := testEnv(t)
	seen, opened := false, time.Now()
	if e.deskGone(handoffID, &seen, opened) {
		t.Fatal("a window opened before the record was written is gone")
	}
	if !e.deskGone(handoffID, &seen, opened.Add(-deskCheck-time.Minute)) {
		t.Fatal("a record that never showed up is kept")
	}
	file := filepath.Join(e.root, "-w", handoffID+".jsonl")
	writeLines(t, file, `{"type":"user"}`)
	if e.deskGone(handoffID, &seen, opened) || !seen {
		t.Fatal("a fresh session is gone")
	}
	age(t, file, deskStale+time.Hour)
	if !e.deskGone(handoffID, &seen, opened) {
		t.Fatal("a stale session is kept")
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	if !e.deskGone(handoffID, &seen, opened) {
		t.Fatal("a deleted session is kept")
	}
}

func TestDeskTitle(t *testing.T) {
	if got := deskTitle("  一行\n两行  ", handoffID); got != "一行 两行" {
		t.Fatalf("got %q", got)
	}
	if got := deskTitle("", handoffID); got != "eeeeeeee" {
		t.Fatalf("got %q", got)
	}
}

func TestHandoffEndsTheOtherPackagesProcessTheZetaWay(t *testing.T) {
	e := testEnv(t)
	dir, _, done := hosted(t, e, "zeta-dev", handoffID, turnDone...)
	if got := mustCall(t, e, "handoff", handoffID)[0]; got["handed"] != true || !exited(done) {
		t.Fatalf("got %v", got)
	}
	if _, err := os.Stat(filepath.Join(dir, releasedMark)); err != nil {
		t.Fatal("no released mark")
	}
}

func TestSessionStartHookReleasesOnResume(t *testing.T) {
	e := testEnv(t)
	_, _, done := hosted(t, e, "zeta", handoffID, turnDone...)
	run := func(input string) string {
		var buf strings.Builder
		o := newEmitter(&buf)
		if err := cmdSessionStart(e, o, strings.NewReader(input)); err != nil {
			t.Fatal(err)
		}
		o.flush()
		return buf.String()
	}
	if out := run(`{"session_id":"` + handoffID + `","source":"startup"}`); out != "" || !stillUp(done) {
		t.Fatalf("a startup session touched Zeta: %q", out)
	}
	if out := run(`not json`); out != "" {
		t.Fatalf("bad input printed %q", out)
	}
	if out := run(`{"session_id":"` + handoffID + `","source":"resume"}`); out != "" || !exited(done) {
		t.Fatalf("resume did not release the idle process: %q", out)
	}
	_, _, busyDone := hosted(t, e, "zeta", handoffID, turnOn...)
	if out := run(`{"session_id":"` + handoffID + `","source":"resume"}`); !strings.Contains(out, "systemMessage") || !stillUp(busyDone) {
		t.Fatalf("busy: %q", out)
	}
}

func TestSessionStartDoesNotEndTheClaudeThatIsResuming(t *testing.T) {
	e := testEnv(t)
	_, pid, done := hosted(t, e, "zeta", handoffID, turnDone...)
	// Zeta 自己起的 claude --resume 跑这个 hook 时，它是 hook 的祖先：不结束它
	if released, busy := e.releaseSession(handoffID, 0, map[int]bool{pid: true}); released != 0 || busy || !stillUp(done) {
		t.Fatalf("released %d busy %v, still up %v", released, busy, stillUp(done))
	}
	if !ancestors(os.Getpid())[os.Getppid()] {
		t.Fatal("the parent is not among the ancestors")
	}
	if ancestors(pid)[pid] {
		t.Fatal("a process is its own ancestor")
	}
}
