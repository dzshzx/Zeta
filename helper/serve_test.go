package main

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/core"
)

// serve：连上先打招呼，请求一一回复；会话列表先整份、再只推变了的；客户端走了就退出。

var fastServe = serveOptions{idle: time.Minute, debounce: 20 * time.Millisecond, poll: time.Hour}

// serveClient 是测试里的客户端：往 serve 的输入写请求，从输出一行行读。
type serveClient struct {
	t    *testing.T
	in   *io.PipeWriter
	got  chan obj
	done chan error
}

func startServe(t *testing.T, e *env, opts serveOptions) *serveClient {
	t.Helper()
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	c := &serveClient{t: t, in: inW, got: make(chan obj, 100), done: make(chan error, 1)}
	go func() {
		err := cmdServe(e, opts, inR, outW)
		_ = outW.Close()
		c.done <- err
	}()
	go func() {
		dec := json.NewDecoder(outR)
		dec.UseNumber()
		for {
			var m obj
			if dec.Decode(&m) != nil {
				close(c.got)
				return
			}
			c.got <- m
		}
	}()
	t.Cleanup(func() { _ = inW.Close() })
	if hello := c.next("zeta_hello"); hello["protocol"] != json.Number("1") {
		t.Fatalf("hello %v", hello)
	}
	return c
}

func (c *serveClient) send(line string) {
	c.t.Helper()
	if _, err := io.WriteString(c.in, line+"\n"); err != nil {
		c.t.Fatal(err)
	}
}

// next 等下一行类型是 kind 的消息（别的类型跳过）。
func (c *serveClient) next(kind string) obj {
	c.t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case m, ok := <-c.got:
			if !ok {
				c.t.Fatalf("serve 没等到 %s 就关了输出", kind)
			}
			if m["type"] == kind {
				return m
			}
		case <-deadline:
			c.t.Fatalf("5 秒内没等到 %s", kind)
		}
	}
}

// quiet 确认这段时间里没有推来会话列表。
func (c *serveClient) quiet(d time.Duration) {
	c.t.Helper()
	deadline := time.After(d)
	for {
		select {
		case m := <-c.got:
			if m["type"] == "zeta_sessions" {
				c.t.Fatalf("没有变化却推来了 %v", m)
			}
		case <-deadline:
			return
		}
	}
}

func (c *serveClient) exited() {
	c.t.Helper()
	select {
	case err := <-c.done:
		if err != nil {
			c.t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		c.t.Fatal("serve 5 秒内没有退出")
	}
}

func ids(items any) []string {
	var out []string
	for _, it := range asList(items) {
		out = append(out, asString(asObj(it)["id"]))
	}
	return out
}

func strs(v any) []string {
	var out []string
	for _, s := range asList(v) {
		out = append(out, asString(s))
	}
	return out
}

const (
	sessionA = "aaaaaaaa-0000-0000-0000-000000000001"
	sessionB = "bbbbbbbb-0000-0000-0000-000000000002"
)

func writeSession(t *testing.T, e *env, id, text string) string {
	t.Helper()
	path := e.root + "/-w/" + id + ".jsonl"
	writeLines(t, path, `{"type":"user","cwd":"/w","message":{"content":"`+text+`"}}`)
	return path
}

func TestServeRepliesAndExitsWhenInputEnds(t *testing.T) {
	c := startServe(t, testEnv(t), fastServe)
	c.send(`{"id":7,"method":"ping"}`)
	if r := c.next("zeta_reply"); r["id"] != json.Number("7") || r["ok"] != true {
		t.Errorf("ping %v", r)
	}
	c.send(`{"id":"x","method":"nope"}`)
	if r := c.next("zeta_reply"); r["id"] != "x" || r["ok"] != false || !strings.Contains(asString(r["error"]), "nope") {
		t.Errorf("unknown %v", r)
	}
	c.send(`not json`)
	if r := c.next("zeta_reply"); r["id"] != nil || r["ok"] != false {
		t.Errorf("bad line %v", r)
	}
	_ = c.in.Close()
	c.exited()
}

// 网络断了、输入却没结束：idle 内一行都没收到就自己退出。
func TestServeExitsWhenTheClientGoesSilent(t *testing.T) {
	opts := fastServe
	opts.idle = 100 * time.Millisecond
	c := startServe(t, testEnv(t), opts)
	c.send(`{"id":1,"method":"sessions.watch"}`)
	c.next("zeta_sessions")
	c.exited()
}

func TestServeWatchPushesOnlyWhatChanged(t *testing.T) {
	e := testEnv(t)
	a := writeSession(t, e, sessionA, "第一句")
	age(t, a, time.Hour)
	c := startServe(t, e, fastServe)
	c.send(`{"id":1,"method":"sessions.watch"}`)
	full := c.next("zeta_sessions")
	if full["full"] != true || !slices.Equal(ids(full["upsert"]), []string{sessionA}) {
		t.Fatalf("full %v", full)
	}
	c.quiet(100 * time.Millisecond)

	// 新会话：只推它
	writeSession(t, e, sessionB, "另一个")
	got := c.next("zeta_sessions")
	if got["full"] != false || !slices.Equal(ids(got["upsert"]), []string{sessionB}) || len(asList(got["remove"])) != 0 {
		t.Fatalf("new %v", got)
	}

	// 改名（和 rename 一样追加一行）：推那一项，带新标题
	mustCall(t, e, "rename", a, "新名字")
	got = c.next("zeta_sessions")
	if up := asList(got["upsert"]); len(up) != 1 || asObj(up[0])["title"] != "新名字" {
		t.Fatalf("rename %v", got)
	}

	// 归档：标记在 Zeta 自己的目录里，第一次归档时那个目录才建出来
	mustCall(t, e, "archive", sessionB, "1")
	got = c.next("zeta_sessions")
	if up := asList(got["upsert"]); len(up) != 1 || asObj(up[0])["id"] != sessionB || asObj(up[0])["archived"] != true {
		t.Fatalf("archive %v", got)
	}

	// 删除：推 remove
	mustCall(t, e, "delete", a)
	got = c.next("zeta_sessions")
	if !slices.Equal(strs(got["remove"]), []string{sessionA}) || len(asList(got["upsert"])) != 0 {
		t.Fatalf("delete %v", got)
	}

	// 再要一次：整份
	c.send(`{"id":2,"method":"sessions.watch"}`)
	got = c.next("zeta_sessions")
	if got["full"] != true || !slices.Equal(ids(got["upsert"]), []string{sessionB}) {
		t.Fatalf("again %v", got)
	}
}

// 连上时连 ~/.claude 都还没有（主机上还没跑过 Claude Code）：等它建出来，第一个会话照样推来。
func TestServeWatchWaitsForTheProjectsDirectory(t *testing.T) {
	e := testEnv(t)
	c := startServe(t, e, fastServe)
	c.send(`{"id":1,"method":"sessions.watch"}`)
	if full := c.next("zeta_sessions"); len(asList(full["upsert"])) != 0 {
		t.Fatalf("full %v", full)
	}
	writeSession(t, e, sessionA, "第一句")
	got := c.next("zeta_sessions")
	if !slices.Equal(ids(got["upsert"]), []string{sessionA}) {
		t.Fatalf("first %v", got)
	}
	// 新项目目录里的会话也看得到
	path := e.root + "/-other/" + sessionB + ".jsonl"
	writeLines(t, path, `{"type":"user","cwd":"/o","message":{"content":"别处"}}`)
	got = c.next("zeta_sessions")
	if !slices.Equal(ids(got["upsert"]), []string{sessionB}) {
		t.Fatalf("other project %v", got)
	}
}

// 一份记录在写个不停（正在回答）：这期间的变化合成一次推送，不是每写一行推一次。
func TestServeWatchCoalescesBursts(t *testing.T) {
	e := testEnv(t)
	a := writeSession(t, e, sessionA, "第一句")
	opts := fastServe
	opts.debounce = 300 * time.Millisecond
	c := startServe(t, e, opts)
	c.send(`{"id":1,"method":"sessions.watch"}`)
	c.next("zeta_sessions")
	f, err := os.OpenFile(a, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	for range 10 {
		if _, err := f.WriteString(filler(10) + "\n"); err != nil {
			t.Fatal(err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	c.next("zeta_sessions")
	c.quiet(500 * time.Millisecond)
}

// 测试程序带上 testMainEnv=1 运行时就是 zeta-helper 本身：serve 拉起核心时执行的是测试程序自己（os.Executable），
// 参数 core 走到 main 里真的核心子命令。
const testMainEnv = "ZETA_HELPER_TEST_MAIN"

func TestMain(m *testing.M) {
	if os.Getenv(testMainEnv) == "1" {
		main()
	}
	os.Exit(m.Run())
}

// coreEnv 给这个测试一个临时家目录和一个短的核心目录（套接字路径有长度上限），返回 env 和这个版本核心的文件。
// 测试结束时结束还持着锁的核心。
func coreEnv(t *testing.T) (*env, core.Paths) {
	t.Helper()
	e := testEnv(t)
	t.Setenv("HOME", e.home)
	t.Setenv("ZETA_NAME", e.name)
	dir, err := os.MkdirTemp("", "zs")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	t.Setenv(core.DirEnv, dir+"/core")
	t.Setenv(testMainEnv, "1")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	p, err := core.Locate(e.home, e.name, exe)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if pid := corePid(p); pid > 0 {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	})
	return e, p
}

// corePid 是持着锁的核心的进程号；没有核心持着锁时是 0。
func corePid(p core.Paths) int {
	f, err := os.Open(p.Lock)
	if err != nil {
		return 0
	}
	defer f.Close()
	if syscall.Flock(int(f.Fd()), syscall.LOCK_SH|syscall.LOCK_NB) == nil {
		return 0
	}
	data, _ := os.ReadFile(p.Lock)
	pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
	return pid
}

func listening(p core.Paths) bool {
	c, err := net.Dial("unix", p.Socket)
	if err != nil {
		return false
	}
	_ = c.Close()
	return true
}

// 两个客户端各起一个 serve：conv. 请求经 serve 到同一个核心再回来，ping 照旧由 serve 答；核心没了 serve 都退出。
func TestServeForwardsConversationsToOneCore(t *testing.T) {
	e, p := coreEnv(t)
	a := startServe(t, e, fastServe)
	b := startServe(t, e, fastServe)
	if listening(p) {
		t.Fatal("还没有 conv. 请求就有了核心")
	}

	a.send(`{"id":1,"method":"conv.open","cwd":"/w"}`)
	r := a.next("zeta_reply")
	if r["id"] != json.Number("1") || r["ok"] != false || r["error"] != "no such method yet: conv.open" {
		t.Fatalf("经核心的应答 %v", r)
	}
	pid := corePid(p)
	if pid == 0 {
		t.Fatal("核心没持锁")
	}
	if sid, _, errno := syscall.RawSyscall(syscall.SYS_GETSID, uintptr(pid), 0, 0); errno != 0 || int(sid) != pid {
		t.Errorf("核心不在自己的会话里：sid %d %v", sid, errno)
	}

	b.send(`{"id":"x","method":"conv.send","conv":"c1","text":"你好"}`)
	if r := b.next("zeta_reply"); r["id"] != "x" || r["error"] != "no such method yet: conv.send" {
		t.Fatalf("b 经核心的应答 %v", r)
	}
	if corePid(p) != pid {
		t.Error("第二个 serve 又起了一个核心")
	}
	a.send(`{"id":2,"method":"ping"}`)
	if r := a.next("zeta_reply"); r["id"] != json.Number("2") || r["ok"] != true {
		t.Errorf("ping %v", r)
	}
	if log, err := os.ReadFile(p.Log); err != nil || !strings.Contains(string(log), "listening on "+p.Socket) {
		t.Errorf("日志 %q %v", log, err)
	}

	if err := syscall.Kill(pid, syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	a.exited()
	b.exited()
}

// 核心起不来（这里是套接字路径太长）：这个请求答出错，serve 照常在。
func TestServeReportsWhenTheCoreIsUnavailable(t *testing.T) {
	e, _ := coreEnv(t)
	t.Setenv(core.DirEnv, "/tmp/"+strings.Repeat("d", 120))
	c := startServe(t, e, fastServe)
	c.send(`{"id":1,"method":"conv.open"}`)
	if r := c.next("zeta_reply"); r["ok"] != false || !strings.HasPrefix(asString(r["error"]), "core unavailable: ") {
		t.Fatalf("%v", r)
	}
	c.send(`{"id":2,"method":"ping"}`)
	if r := c.next("zeta_reply"); r["ok"] != true {
		t.Fatalf("ping %v", r)
	}
}

// zeta-helper core：已有核心在听时新起的直接退出；收到 SIGTERM 时退出码 0、删掉套接字文件。
func TestCoreSubcommandIsSingleAndExitsOnSigterm(t *testing.T) {
	_, p := coreEnv(t)
	exe, _ := os.Executable()
	first := exec.Command(exe, "core")
	if err := first.Start(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, func() bool { return listening(p) })

	second := exec.Command(exe, "core")
	if out, err := second.CombinedOutput(); err != nil {
		t.Fatalf("第二个核心：%v %s", err, out)
	}
	if corePid(p) != first.Process.Pid || !listening(p) {
		t.Fatal("第二个核心顶掉了第一个")
	}

	if err := first.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- first.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("SIGTERM 后 %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("SIGTERM 后 5 秒没退出")
	}
	if _, err := os.Lstat(p.Socket); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("套接字文件还在：%v", err)
	}
}
