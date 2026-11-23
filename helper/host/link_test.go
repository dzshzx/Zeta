package host

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// 这些测试在临时 HOME 里真跑嵌进来的脚本，claude 换成下面的替身。

// testName 是测试用的 ZETA_NAME，托管目录在 <临时 HOME>/.cache/zeta-gotest/hosts 下。
const testName = "zeta-gotest"

// standIn 是 claude 的替身：起来先打一行 hello（带参数、环境变量 ZETA_T、ZETA_LOGIN 和名叫 bad-name 的变量有几个），
// 之后每收到一行回一行 echo（原样带着收到的那行）；收到 end_session 时往标准错误输出写一句就退出，同真 Claude Code。
const standIn = `#!/bin/bash
printf '{"type":"hello","args":"%s","t":"%s","login":"%s","bad":%s}\n' "$*" "${ZETA_T-}" "${ZETA_LOGIN-}" "$(env | grep -c '^bad-name=')"
while IFS= read -r line; do
  case $line in *'"subtype":"end_session"'*) echo "bye 再见" >&2; exit 0 ;; esac
  printf '{"type":"echo","got":%s}\n' "$line"
done
`

// profile 是临时 HOME 的 ~/.bash_profile，只有登录 shell 读：设 ZETA_LOGIN（替身报出来，证明起进程用的是登录 shell 的环境），
// 往标准输出打一句杂字（连接要丢掉它），~/hold 在时等它被删掉（最多 30 秒），让测试在接上之前先发消息。
const profile = `export ZETA_LOGIN=yes
echo "login noise"
for _ in $(seq 1500); do [ -e "$HOME/hold" ] || break; sleep 0.02; done
`

// waitLimit 是等一个事件、一个条件的上限，只防卡死。
const waitLimit = 15 * time.Second

// setupHome 建临时 HOME，摆好替身和登录配置，设好环境变量；测试结束时结束留下的托管进程。
func setupHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	writeFile(t, filepath.Join(home, "bin", "claude"), standIn, 0o755)
	writeFile(t, filepath.Join(home, ".bash_profile"), profile, 0o644)
	t.Setenv("HOME", home)
	t.Setenv("PATH", filepath.Join(home, "bin")+":"+os.Getenv("PATH"))
	t.Setenv("ZETA_NAME", testName)
	// 看门狗每秒看一次：进程没了它很快退出，不留后台的 sleep
	t.Setenv("ZETA_IDLE_STEP", "1")
	for _, name := range []string{"BASH_ENV", "ZETA_HOST", "ZETA_STOP_GRACE", "ZETA_IDLE_LIMIT"} {
		t.Setenv(name, "")
		os.Unsetenv(name)
	}
	t.Cleanup(func() { cleanHosts(home) })
	return home
}

func writeFile(t *testing.T, path, content string, mode os.FileMode) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatal(err)
	}
}

// hostsDir 是临时 HOME 里的托管目录的上一级。
func hostsDir(home string) string { return filepath.Join(home, ".cache", testName, "hosts") }

// hostDirs 是还在的托管目录名。
func hostDirs(home string) []string {
	entries, _ := os.ReadDir(hostsDir(home))
	var names []string
	for _, e := range entries {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	return names
}

// cleanHosts：照 stop 结束还在的托管进程，等目录都没了；到点还在的直接杀掉。
func cleanHosts(home string) {
	for _, name := range hostDirs(home) {
		_ = scriptCommand(context.Background(), "stop", name).Run()
	}
	if waitUntil(func() bool { return len(hostDirs(home)) == 0 }) {
		return
	}
	for _, name := range hostDirs(home) {
		if pid := hostPid(home, name); pid > 0 {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	}
	waitUntil(func() bool { return len(hostDirs(home)) == 0 })
}

// hostPid 是托管目录 key 的 pid 文件记的进程号；没有时是 0。
func hostPid(home, key string) int {
	b, _ := os.ReadFile(filepath.Join(hostsDir(home), key, "pid"))
	pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	return pid
}

// running：进程 pid 还在跑（僵尸不算，同脚本的 running）。
func running(pid int) bool {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return false
	}
	s := string(b)
	rest := s[strings.LastIndex(s, ") ")+2:]
	return !strings.HasPrefix(rest, "Z")
}

// waitUntil 限时等 cond 成立，到点还不成立返回 false。
func waitUntil(cond func() bool) bool {
	deadline := time.Now().Add(waitLimit)
	for !cond() {
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(20 * time.Millisecond)
	}
	return true
}

// next 等连接的下一个事件。
func next(t *testing.T, l *Link) Event {
	t.Helper()
	select {
	case ev, ok := <-l.Events():
		if !ok {
			t.Fatal("事件通道已经关了")
		}
		return ev
	case <-time.After(waitLimit):
		t.Fatal("等事件超时")
	}
	return nil
}

func nextAttached(t *testing.T, l *Link) Attached {
	t.Helper()
	ev := next(t, l)
	a, ok := ev.(Attached)
	if !ok {
		t.Fatalf("要 Attached，收到 %#v", ev)
	}
	return a
}

// echoed 是替身回的一行。
type echoed struct {
	Type  string          `json:"type"`
	Got   json.RawMessage `json:"got"`
	Args  string          `json:"args"`
	T     string          `json:"t"`
	Login string          `json:"login"`
	Bad   int             `json:"bad"`
}

// nextLine 等下一个事件，它要是第 n 行，返回解出来的内容。
func nextLine(t *testing.T, l *Link, n int) echoed {
	t.Helper()
	ev := next(t, l)
	line, ok := ev.(Line)
	if !ok {
		t.Fatalf("要第 %d 行，收到 %#v", n, ev)
	}
	if line.N != n {
		t.Fatalf("行号是 %d，要 %d：%s", line.N, n, line.Data)
	}
	var e echoed
	if err := json.Unmarshal(line.Data, &e); err != nil {
		t.Fatalf("第 %d 行不是 JSON：%s", n, line.Data)
	}
	return e
}

// expectEcho 等第 n 行，它要是替身对 msg 的回话。
func expectEcho(t *testing.T, l *Link, n int, msg string) {
	t.Helper()
	e := nextLine(t, l, n)
	if e.Type != "echo" || string(e.Got) != msg {
		t.Fatalf("第 %d 行要回 %s，是 %+v", n, msg, e)
	}
}

// expectSilentEnd：连接（已经 Stop 或 Detach）不再交出事件，连接进程退出后通道关上。
func expectSilentEnd(t *testing.T, l *Link) {
	t.Helper()
	select {
	case ev, ok := <-l.Events():
		if ok {
			t.Fatalf("Stop、Leave 之后还收到 %#v", ev)
		}
	case <-time.After(waitLimit):
		t.Fatal("连接没有结束")
	}
}

func startLink(t *testing.T, home string, opts StartOptions) *Link {
	t.Helper()
	if opts.Cwd == "" {
		opts.Cwd = home
	}
	l, err := Start(opts)
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func attachLink(t *testing.T, key string, from int) *Link {
	t.Helper()
	l, err := Attach(key, from)
	if err != nil {
		t.Fatal(err)
	}
	return l
}

// 起进程：登录 shell 的杂字丢掉，先 Attached 再是替身的输出；接上之前发的消息按序写进去；环境变量带到 Claude Code，
// 不像变量名的丢掉；Stop 之后不再有事件，进程结束、目录删掉。
func TestStartRelaysOutputAndQueuedMessages(t *testing.T) {
	home := setupHome(t)
	writeFile(t, filepath.Join(home, "hold"), "", 0o644)
	l := startLink(t, home, StartOptions{
		Key:   "k1",
		Title: "标题",
		Args:  []string{"-p", "带 空格"},
		Env:   map[string]string{"ZETA_T": "it's $HOME; * `x`", "bad-name": "x"},
	})
	l.Send([]byte(`{"n":1}`))
	l.Send([]byte(`{"n":2}`))
	if err := os.Remove(filepath.Join(home, "hold")); err != nil {
		t.Fatal(err)
	}

	a := nextAttached(t, l)
	if a.From != 1 || a.Key != "k1" || a.Joined {
		t.Fatalf("Attached 不对：%+v", a)
	}
	hello := nextLine(t, l, 1)
	if hello.Type != "hello" || hello.Args != "-p 带 空格" || hello.T != "it's $HOME; * `x`" || hello.Login != "yes" || hello.Bad != 0 {
		t.Fatalf("hello 不对：%+v", hello)
	}
	expectEcho(t, l, 2, `{"n":1}`)
	expectEcho(t, l, 3, `{"n":2}`)
	l.Send([]byte(`{"n":3}`))
	expectEcho(t, l, 4, `{"n":3}`)

	pid := hostPid(home, "k1")
	if !running(pid) {
		t.Fatal("托管进程不在")
	}
	<-l.Stop()
	expectSilentEnd(t, l)
	if !waitUntil(func() bool { return !running(pid) && len(hostDirs(home)) == 0 }) {
		t.Fatal("Stop 之后进程或托管目录还在")
	}
}

// 起一个进程备用：start、等到 hello，返回连接和进程号。
func startReady(t *testing.T, home, key string, args ...string) (*Link, int) {
	t.Helper()
	l := startLink(t, home, StartOptions{Key: key, Title: "t", Args: args})
	nextAttached(t, l)
	nextLine(t, l, 1)
	return l, hostPid(home, key)
}

// attach 从指定的行接着读；两个连接都看到对方发的消息的回话。
func TestAttachReadsFromLine(t *testing.T) {
	home := setupHome(t)
	a, _ := startReady(t, home, "k")
	a.Send([]byte(`{"n":1}`))
	expectEcho(t, a, 2, `{"n":1}`)
	a.Send([]byte(`{"n":2}`))
	expectEcho(t, a, 3, `{"n":2}`)

	b := attachLink(t, "k", 3)
	if got := nextAttached(t, b); got.From != 3 || got.Key != "k" || got.Joined {
		t.Fatalf("Attached 不对：%+v", got)
	}
	expectEcho(t, b, 3, `{"n":2}`)
	b.Send([]byte(`{"n":3}`))
	expectEcho(t, b, 4, `{"n":3}`)
	expectEcho(t, a, 4, `{"n":3}`)
	<-a.Stop()
	expectSilentEnd(t, a)
}

// 一方 stop 时，另一方收到 zeta_exit：退出码和 Claude Code 的标准错误输出。
func TestStopReportsExitToOtherAttacher(t *testing.T) {
	home := setupHome(t)
	a, pid := startReady(t, home, "k")
	b := attachLink(t, "k", 1)
	nextAttached(t, b)
	nextLine(t, b, 1)

	<-a.Stop()
	ev := next(t, b)
	exit, ok := ev.(Exit)
	if !ok {
		t.Fatalf("要 Exit，收到 %#v", ev)
	}
	if exit.Code != 0 || exit.Stderr != "bye 再见\n" || exit.Reason != "" {
		t.Fatalf("Exit 不对：%+v", exit)
	}
	if _, ok := <-b.Events(); ok {
		t.Fatal("Exit 之后通道没关")
	}
	expectSilentEnd(t, a)
	if !waitUntil(func() bool { return !running(pid) && len(hostDirs(home)) == 0 }) {
		t.Fatal("进程或托管目录还在")
	}
}

// Leave 只断开这边：进程照常跑，别的一方照样能用；最后一个也离开以后进程还在，留给看门狗按闲置超时收。
func TestLeaveKeepsTheProcess(t *testing.T) {
	home := setupHome(t)
	a, pid := startReady(t, home, "k")
	b := attachLink(t, "k", 2)
	nextAttached(t, b)

	a.Leave()
	expectSilentEnd(t, a)
	if !running(pid) {
		t.Fatal("还有人接着，进程却结束了")
	}
	b.Send([]byte(`{"n":1}`))
	expectEcho(t, b, 2, `{"n":1}`)

	b.Leave()
	expectSilentEnd(t, b)
	if !running(pid) || len(hostDirs(home)) != 1 {
		t.Fatal("都离开以后进程或托管目录没了")
	}
}

// 两个连接 start 同一个会话（--resume，不分支）：第二个接上第一个的进程，key 换成它的，
// OnJoin 排在接上之前发的消息前面。
func TestStartJoinsRunningSession(t *testing.T) {
	home := setupHome(t)
	a, pid := startReady(t, home, "ka", "--resume", "S1")

	writeFile(t, filepath.Join(home, "hold"), "", 0o644)
	b := startLink(t, home, StartOptions{
		Key:    "kb",
		Title:  "t",
		Args:   []string{"--resume", "S1"},
		OnJoin: [][]byte{[]byte(`{"join":1}`), []byte(`{"join":2}`)},
	})
	b.Send([]byte(`{"n":1}`))
	if err := os.Remove(filepath.Join(home, "hold")); err != nil {
		t.Fatal(err)
	}
	got := nextAttached(t, b)
	if !got.Joined || got.Key != "ka" || got.From != 2 || b.Key() != "ka" {
		t.Fatalf("没接上现成的进程：%+v，Key() = %s", got, b.Key())
	}
	for _, l := range []*Link{b, a} {
		expectEcho(t, l, 2, `{"join":1}`)
		expectEcho(t, l, 3, `{"join":2}`)
		expectEcho(t, l, 4, `{"n":1}`)
	}
	if _, err := os.Stat(filepath.Join(hostsDir(home), "kb")); !os.IsNotExist(err) {
		t.Fatal("接上现成的进程时另建了托管目录")
	}
	if hostPid(home, "ka") != pid {
		t.Fatal("托管进程换了")
	}
	<-b.Stop()
	expectSilentEnd(t, b)
	if ev := next(t, a); ev != (Exit{Code: 0, Stderr: "bye 再见\n"}) {
		t.Fatalf("要 Exit，收到 %#v", ev)
	}
}

// 连接进程自己结束、没报 zeta_exit 时，结局是它的退出码和标准错误输出。
func TestExitWithoutReport(t *testing.T) {
	setupHome(t)
	l := attachLink(t, "nothing", 1)
	ev := next(t, l)
	exit, ok := ev.(Exit)
	if !ok || exit.Code != 98 || !strings.Contains(exit.Stderr, "zeta: 进程没有启动") || exit.Reason != "" {
		t.Fatalf("结局不对：%#v", ev)
	}
}

func TestDecodeStderr(t *testing.T) {
	cut := append([]byte("再见")[1:], "ok\xffz"...) // 开头截在“再”字中间，中间一个坏字节
	cases := map[string]string{
		base64.StdEncoding.EncodeToString(cut):             "见ok�z",
		base64.StdEncoding.EncodeToString([]byte("bye\n")): "bye\n",
		"":         "",
		"不是base64": "",
	}
	for in, want := range cases {
		if got := decodeStderr(in); got != want {
			t.Errorf("decodeStderr(%q) = %q，要 %q", in, got, want)
		}
	}
}

func TestStderrTailKeepsLastChars(t *testing.T) {
	var tl tail
	for range 3000 {
		_, _ = tl.Write([]byte("字a"))
	}
	got := tl.String()
	if n := len([]rune(got)); n != stderrKeep || !strings.HasSuffix(got, "字a") {
		t.Fatalf("留了 %d 个字，结尾 %q", n, got[len(got)-4:])
	}
}

func TestExitCode(t *testing.T) {
	err := exec.Command("bash", "-c", "exit 3").Run()
	if got := exitCode(err); got != 3 {
		t.Errorf("exit 3 得 %d", got)
	}
	err = exec.Command("bash", "-c", "kill -TERM $$").Run()
	if got := exitCode(err); got != 128+15 {
		t.Errorf("SIGTERM 得 %d", got)
	}
}
