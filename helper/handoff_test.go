package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

const handoffID = "eeeeeeee-1111-4222-8333-444444444444"

func TestOccupantReportsTheMostPressingStatus(t *testing.T) {
	e := testEnv(t)
	out := mustCall(t, e, "occupant", handoffID)
	if out[0]["status"] != nil || out[0]["hands_over"] != true {
		t.Fatalf("nobody holds it: %v", out)
	}
	idle, _ := tui(t, false)
	registerStatus(t, e, idle, handoffID, "idle", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "idle" || got["waiting_for"] != nil || got["hands_over"] != true {
		t.Fatalf("idle holder: %v", got)
	}
	waiting, _ := tui(t, false)
	registerStatus(t, e, waiting, handoffID, "waiting", "approve Bash")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "waiting" || got["waiting_for"] != "approve Bash" {
		t.Fatalf("waiting holder: %v", got)
	}
	busy, _ := tui(t, false)
	registerStatus(t, e, busy, handoffID, "busy", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "busy" || got["hands_over"] != false {
		t.Fatalf("busy holder: %v", got)
	}
}

func TestOccupantWithoutStatusIsUnknown(t *testing.T) {
	e := testEnv(t)
	pid, _ := tui(t, false)
	registerStatus(t, e, pid, handoffID, "", "")
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != "unknown" || got["hands_over"] != true {
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

// hosted 照 zeta_host.sh 在包 name 的托管目录里摆一个 Claude Code：身上带着这个目录的记号（hostMark），
// 输入管道读到 end_session 就退出，out.jsonl 是 lines。托管目录的 pid 文件记的是外面那层（这里用测试进程自己代替），
// 和 claude 被包成 shell 函数时一样不是登记的那个进程：认人只能靠记号。
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
`, ready, hostMark+"="+name+"/"+filepath.Base(dir))
	waitFile(t, ready)
	writeFile(t, filepath.Join(dir, "pid"), strconv.Itoa(os.Getpid()))
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
	if released, busy := e.releaseSession(handoffID, 0); released != 1 || busy || !exited(done) {
		t.Fatalf("released %d, busy %v", released, busy)
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
	if released, busy := e.releaseSession(handoffID, 0); released != 0 || !busy || !stillUp(done) {
		t.Fatalf("released %d, busy %v", released, busy)
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
	// 上限只防卡死：这一轮一结束它就返回
	if released, _ := e.releaseSession(handoffID, 30*time.Second); released != 1 || !exited(done) {
		t.Fatalf("released %d", released)
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
	pid, done := stub(t, `D=$(dirname "$1"); exec 3<>"$D/in"; : > "$1"; while :; do sleep 0.05; done`+"\n", ready, hostMark+"=zeta/k")
	waitFile(t, ready)
	writeFile(t, filepath.Join(dir, "pid"), strconv.Itoa(os.Getpid()))
	registerStatus(t, e, pid, handoffID, "idle", "")
	if released, _ := e.releaseSession(handoffID, 0); released != 1 || !exited(done) {
		t.Fatalf("released %d", released)
	}
}

func TestHandoffCommandsRejectMalformedIDs(t *testing.T) {
	e := testEnv(t)
	for _, args := range [][]string{
		{"occupant", "../x"}, {"handoff"}, {"release", handoffID}, {"desk-open", handoffID}, {"desk-open", handoffID, "/tmp", "x"},
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
	got := mustCall(t, e, "desk-open", handoffID, "/work/a b")[0]
	if got["window"] != "@7" {
		t.Fatalf("got %v", got)
	}
	joined := strings.Join(*calls, "\n")
	want := "new-session -d -s zeta -P -F #{window_id} -n eeeeeeee -c /work/a b '" + e.home +
		"/.cache/zeta/bin/zeta-helper' desk '" + handoffID + "' '/work/a b'"
	if !strings.Contains(joined, want) || !strings.Contains(joined, "set-option -w -t @7 @zeta_session "+handoffID) {
		t.Fatalf("calls:\n%s", joined)
	}
	mustCall(t, e, "desk-open", "ffffffff-1111-4222-8333-444444444444", "/w")
	if last := strings.Join(*calls, "\n"); !strings.Contains(last, "new-window -d -t =zeta: -P -F #{window_id} -n ffffffff -c /w") {
		t.Fatalf("calls:\n%s", last)
	}
}

func TestDeskOpenLeavesAnExistingWindow(t *testing.T) {
	e := testEnv(t)
	calls := fakeTmux(t, true, func(args []string) (string, error) {
		if args[0] == "list-windows" {
			return "@1 \n@3 " + handoffID, nil
		}
		return "", nil
	})
	if got := mustCall(t, e, "desk-open", handoffID, "/w")[0]; got["window"] != "@3" {
		t.Fatalf("got %v", got)
	}
	if joined := strings.Join(*calls, "\n"); joined != "list-windows -a -F #{window_id} #{@zeta_session}" {
		t.Fatalf("calls:\n%s", joined)
	}
}

func TestRenameOwnWindowTargetsItsPane(t *testing.T) {
	calls := fakeTmux(t, true, func([]string) (string, error) { return "", nil })
	t.Setenv("TMUX_PANE", "")
	renameOwnWindow("x")
	t.Setenv("TMUX_PANE", "%5")
	renameOwnWindow("修 bug")
	if joined := strings.Join(*calls, "\n"); joined != "rename-window -t %5 修 bug" {
		t.Fatalf("calls:\n%s", joined)
	}
}

func TestTitleFollowerReadsOnlyNewLines(t *testing.T) {
	file := filepath.Join(t.TempDir(), handoffID+".jsonl")
	var f titleFollower
	if got := f.read(""); got != "" {
		t.Fatalf("no file: %q", got)
	}
	writeLines(t, file, `{"type":"user"}`, `{"type":"ai-title","aiTitle":"自动的"}`)
	if got := f.read(file); got != "自动的" {
		t.Fatalf("got %q", got)
	}
	read := f.off
	appendLine := func(line string) {
		w, err := os.OpenFile(file, os.O_APPEND|os.O_WRONLY, 0)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = w.WriteString(line)
		_ = w.Close()
	}
	appendLine(`{"type":"custom-title","customTitle":"改过的"}` + "\n" + `{"type":"custom-title","customTitle":"半行`)
	if got := f.read(file); got != "改过的" || f.off <= read {
		t.Fatalf("got %q at %d", got, f.off)
	}
	appendLine(`后半"}` + "\n")
	if got := f.read(file); got != "半行后半" {
		t.Fatalf("the half line: %q", got)
	}
	writeLines(t, file, `{"type":"ai-title","aiTitle":"重写"}`)
	if got := f.read(file); got != "重写" {
		t.Fatalf("a shorter file: %q", got)
	}
}

func TestDeskOpenWithoutTmux(t *testing.T) {
	e := testEnv(t)
	fakeTmux(t, false, func([]string) (string, error) { t.Fatal("ran tmux"); return "", nil })
	if got := mustCall(t, e, "desk-open", handoffID, "/w")[0]; got["window"] != nil {
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
	gone := func(opened time.Time) bool { _, g := e.deskGone(handoffID, &seen, opened); return g }
	if gone(opened) {
		t.Fatal("a window opened before the record was written is gone")
	}
	if !gone(opened.Add(-deskFirstRecord - time.Minute)) {
		t.Fatal("a record that never showed up is kept")
	}
	file := filepath.Join(e.root, "-w", handoffID+".jsonl")
	writeLines(t, file, `{"type":"user"}`)
	if got, g := e.deskGone(handoffID, &seen, opened); g || !seen || got != file {
		t.Fatal("a fresh session is gone")
	}
	age(t, file, deskStale+time.Hour)
	if !gone(opened) {
		t.Fatal("a stale session is kept")
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	if !gone(opened) {
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

// 认 Zeta 托管的进程只看它身上的记号：pid 文件记的是外面那层也认得（claude 是 shell 函数时就是这样），
// 本包的不算别处占用，另一个包的算；记号的样子不对的当终端里的。
func TestHostedProcessesAreKnownByTheirMark(t *testing.T) {
	e := testEnv(t)
	hosted(t, e, "zeta", handoffID, turnDone...)
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != nil {
		t.Fatalf("our own process counts as elsewhere: %v", got)
	}
	if got := mustCall(t, newEnvAt(e.home, "zeta-dev"), "occupant", handoffID)[0]; got["status"] != "idle" {
		t.Fatalf("the other package does not see our process: %v", got)
	}
	for _, mark := range []string{"zeta", "zeta/", "zeta/..", "other/k", "zeta/a/b"} {
		pid, _ := tui(t, false, hostMark+"="+mark)
		if dir, ok := e.hostDirOf(pid, handoffID); ok {
			t.Fatalf("mark %q was taken as %s", mark, dir)
		}
	}
}

// 托管的进程里 /clear 换了新会话：输出开头是旧会话，登记的是新会话，照样认得是自己的（看输出里最新的会话）。
func TestAHostedProcessIsKnownAfterClearChangedItsSession(t *testing.T) {
	e := testEnv(t)
	const old = "ffffffff-1111-4222-8333-444444444444"
	hosted(t, e, "zeta", handoffID, `{"type":"system","subtype":"init","session_id":"`+old+`"}`,
		`{"type":"result","subtype":"success","session_id":"`+old+`"}`, turnDone[0], turnDone[1])
	if got := mustCall(t, e, "occupant", handoffID)[0]; got["status"] != nil {
		t.Fatalf("our own process counts as elsewhere after /clear: %v", got)
	}
}

// Zeta 托管的 Claude Code 起的子进程（Bash 工具里再起的 claude）继承了记号，但登记的是别的会话：它不是那个托管目录的进程。
// 对本包来说它占着的会话在别处；终端接回它的会话时，不去结束它所在的那个 Zeta 对话。
func TestAChildThatInheritedTheMarkIsNotTheHostedProcess(t *testing.T) {
	e := testEnv(t)
	dir, _, done := hosted(t, e, "zeta", handoffID, turnDone...)
	const nested = "ffffffff-1111-4222-8333-444444444444"
	pid, nestedDone := tui(t, false, hostMark+"=zeta/"+filepath.Base(dir))
	registerStatus(t, e, pid, nested, "idle", "")
	if got := mustCall(t, e, "occupant", nested)[0]; got["status"] != "idle" {
		t.Fatalf("the nested claude is not elsewhere: %v", got)
	}
	if released, _ := e.releaseSession(nested, 0); released != 0 {
		t.Fatalf("released %d hosted processes for the nested session", released)
	}
	if _, err := os.Stat(filepath.Join(dir, releasedMark)); err == nil || !stillUp(done) || !stillUp(nestedDone) {
		t.Fatal("resuming the nested session touched the Zeta conversation it runs in")
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
	old := selfAncestors
	selfAncestors = func() map[int]bool { return map[int]bool{pid: true} }
	t.Cleanup(func() { selfAncestors = old })
	if released, busy := e.releaseSession(handoffID, 0); released != 0 || busy || !stillUp(done) {
		t.Fatalf("released %d busy %v, still up %v", released, busy, stillUp(done))
	}
	if !ancestors(os.Getpid())[os.Getppid()] {
		t.Fatal("the parent is not among the ancestors")
	}
	if ancestors(pid)[pid] {
		t.Fatal("a process is its own ancestor")
	}
}
