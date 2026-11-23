package core

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/dzshzx/Zeta/helper/locales"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

// childEnv 设成 1 时，测试程序带参数 core 运行就是一个假的核心（见 TestMain），Connect 的测试拉起的就是它。
const childEnv = "ZETA_CORE_TEST_CHILD"

func TestMain(m *testing.M) {
	if os.Getenv(childEnv) == "1" && len(os.Args) == 2 && os.Args[1] == "core" {
		os.Exit(runChild())
	}
	os.Exit(m.Run())
}

// runChild 是假核心：每个请求答自己的进程号。
func runChild() int {
	p, err := Locate(os.Getenv("HOME"), "zeta")
	if err != nil {
		return 1
	}
	s, err := Listen(p, Info{Build: 7, Version: "v7"})
	if errors.Is(err, ErrRunning) {
		return 0
	}
	if err != nil {
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM)
	defer stop()
	if s.Serve(ctx, pidHandler{}, time.Minute) != nil {
		return 1
	}
	return 0
}

type pidHandler struct{}

func (pidHandler) Request(*Client, Request) (any, error) { return os.Getpid(), nil }
func (pidHandler) Gone(*Client)                          {}
func (pidHandler) Idle() bool                            { return true }

// fakeHandler 记下收到的请求和走了的客户端，按方法做事：
// conv.echo 把原始那一行当结果答回，conv.fail 答出错，conv.push 先给所有客户端推一行再答。
type fakeHandler struct {
	mu      sync.Mutex
	clients []*Client
	busy    atomic.Bool
	gone    chan *Client
}

func newFakeHandler() *fakeHandler { return &fakeHandler{gone: make(chan *Client, 10)} }

func (h *fakeHandler) Request(c *Client, r Request) (any, error) {
	h.mu.Lock()
	if !contains(h.clients, c) {
		h.clients = append(h.clients, c)
	}
	all := append([]*Client(nil), h.clients...)
	h.mu.Unlock()
	switch r.Method {
	case "conv.echo":
		return r.Raw, nil
	case "conv.fail":
		return nil, errors.New("no conversation " + r.Conv)
	case "conv.said":
		return nil, fmt.Errorf("包一层：%w", locales.Err("noticeStopped"))
	case "conv.push":
		if err := SendAll(all, map[string]any{"type": "conv.state", "conv": r.Conv, "from": c.ID()}); err != nil {
			return nil, err
		}
		return nil, nil
	case "conv.bad":
		return func() {}, nil
	case "conv.device":
		device, ssh, ok := c.Introduced()
		if !ok {
			return "-", nil
		}
		return device + "|" + ssh, nil
	}
	return nil, errors.New("unknown method: " + r.Method)
}

func (h *fakeHandler) Gone(c *Client) { h.gone <- c }
func (h *fakeHandler) Idle() bool     { return !h.busy.Load() }

func contains(cs []*Client, c *Client) bool {
	for _, x := range cs {
		if x == c {
			return true
		}
	}
	return false
}

// testPaths 让核心的文件落在一个短的临时目录里（套接字路径有长度上限），家目录也是临时的。
func testPaths(t *testing.T) Paths {
	t.Helper()
	dir, err := os.MkdirTemp("", "zc")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	t.Setenv("HOME", t.TempDir())
	t.Setenv(DirEnv, dir+"/core")
	p, err := Locate(os.Getenv("HOME"), "zeta")
	if err != nil {
		t.Fatal(err)
	}
	return p
}

// running 是测试进程里跑着的一个核心。
type running struct {
	t    *testing.T
	p    Paths
	stop context.CancelFunc
	done chan error
}

func serve(t *testing.T, p Paths, h Handler, idle time.Duration) *running {
	t.Helper()
	s, err := Listen(p, Info{Build: 7, Version: "v7"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, stop := context.WithCancel(context.Background())
	r := &running{t: t, p: p, stop: stop, done: make(chan error, 1)}
	go func() { r.done <- s.Serve(ctx, h, idle) }()
	t.Cleanup(func() {
		stop()
		select {
		case <-r.done:
		case <-time.After(5 * time.Second):
		}
	})
	return r
}

// exited 等 Serve 返回，限时 5 秒。
func (r *running) exited() {
	r.t.Helper()
	select {
	case err := <-r.done:
		if err != nil {
			r.t.Fatal(err)
		}
		r.done <- nil // 留给 Cleanup
	case <-time.After(5 * time.Second):
		r.t.Fatal("核心 5 秒内没有退出")
	}
}

// client 是测试里的一个客户端连接。
type client struct {
	t    *testing.T
	conn net.Conn
	got  chan map[string]any
}

func dial(t *testing.T, p Paths) *client {
	t.Helper()
	conn, err := net.Dial("unix", p.Socket)
	if err != nil {
		t.Fatal(err)
	}
	c := &client{t: t, conn: conn, got: make(chan map[string]any, 100)}
	go func() {
		defer close(c.got)
		sc := bufio.NewScanner(conn)
		sc.Buffer(nil, MaxLine)
		for sc.Scan() {
			var m map[string]any
			if json.Unmarshal(sc.Bytes(), &m) != nil {
				m = map[string]any{"type": "not json", "line": sc.Text()}
			}
			c.got <- m
		}
	}()
	t.Cleanup(func() { _ = conn.Close() })
	if hello := c.next(); hello["type"] != "core_hello" || hello["build"] != float64(7) || hello["version"] != "v7" {
		t.Fatalf("连上先收到的：%v", hello)
	}
	return c
}

func (c *client) send(line string) {
	c.t.Helper()
	if _, err := c.conn.Write([]byte(line + "\n")); err != nil {
		c.t.Fatal(err)
	}
}

func (c *client) next() map[string]any {
	c.t.Helper()
	select {
	case m, ok := <-c.got:
		if !ok {
			c.t.Fatal("连接没等到下一行就断了")
		}
		return m
	case <-time.After(5 * time.Second):
		c.t.Fatal("5 秒内没等到下一行")
	}
	return nil
}

func (c *client) call(line string) map[string]any {
	c.t.Helper()
	c.send(line)
	return c.next()
}

func TestRepliesPushesAndGone(t *testing.T) {
	p := testPaths(t)
	h := newFakeHandler()
	serve(t, p, h, time.Minute)
	a, b := dial(t, p), dial(t, p)

	r := a.call(`{"id":1,"method":"conv.echo","conv":"c1","text":"<你好>"}`)
	if r["type"] != "zeta_reply" || r["id"] != 1.0 || r["ok"] != true {
		t.Fatalf("echo %v", r)
	}
	if res, _ := r["result"].(map[string]any); res["text"] != "<你好>" || res["conv"] != "c1" {
		t.Errorf("处理器拿到的原始一行不对：%v", r["result"])
	}
	r = b.call(`{"id":"x","method":"conv.fail","conv":"c9"}`)
	if r["id"] != "x" || r["ok"] != false || r["error"] != "no conversation c9" {
		t.Errorf("fail %v", r)
	}
	if _, has := r["result"]; has {
		t.Errorf("出错的应答带了 result：%v", r)
	}
	r = a.call(`not json`)
	if r["id"] != nil || r["ok"] != false || !strings.HasPrefix(r["error"].(string), "not a JSON request") {
		t.Errorf("bad line %v", r)
	}
	r = a.call(`{"id":2,"method":"conv.bad"}`)
	if r["ok"] != false || !strings.Contains(r["error"].(string), "cannot encode") {
		t.Errorf("bad result %v", r)
	}

	// 推送：两个客户端都收到；发起的那个先收到推送、再收到应答
	a.send(`{"id":3,"method":"conv.push","conv":"c1"}`)
	if m := a.next(); m["type"] != "conv.state" || m["from"] != 1.0 {
		t.Fatalf("a 先收到 %v", m)
	}
	if m := a.next(); m["type"] != "zeta_reply" || m["id"] != 3.0 || m["ok"] != true {
		t.Fatalf("a 的应答 %v", m)
	} else if _, has := m["result"]; has {
		t.Errorf("没有结果的应答带了 result：%v", m)
	}
	if m := b.next(); m["type"] != "conv.state" || m["conv"] != "c1" {
		t.Fatalf("b 收到 %v", m)
	}

	// 一个客户端断开：处理器知道是哪个，之后往它发只得到 ErrGone
	_ = b.conn.Close()
	select {
	case c := <-h.gone:
		if c.ID() != 2 {
			t.Errorf("走的是 %d", c.ID())
		}
		if err := c.Send(map[string]any{"type": "late"}); err != ErrGone {
			t.Errorf("往走了的客户端发：%v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("5 秒内没收到 Gone")
	}
	// 另一个照常
	if r := a.call(`{"id":4,"method":"conv.echo"}`); r["ok"] != true {
		t.Errorf("a 之后 %v", r)
	}
}

// core.client 由核心自己答、记在这条连接上，不交给处理器；各连接各记各的，空白压成一个、控制字符去掉、太长截断。
func TestCoreClientRemembersTheDeviceOfThatConnection(t *testing.T) {
	p := testPaths(t)
	h := newFakeHandler()
	serve(t, p, h, time.Minute)
	a, b := dial(t, p), dial(t, p)
	if r := a.call(`{"id":1,"method":"core.client","device":"  Xiaomi\t 17\u0007 "}`); r["ok"] != true {
		t.Fatalf("core.client %v", r)
	}
	if r := a.call(`{"id":2,"method":"conv.device"}`); r["result"] != "Xiaomi 17|" {
		t.Fatalf("a 的设备 %v", r)
	}
	if r := b.call(`{"id":3,"method":"conv.device"}`); r["result"] != "-" {
		t.Fatalf("没报过的 b：%v", r)
	}
	b.call(`{"id":4,"method":"core.client","device":"` + strings.Repeat("长", deviceMax+5) + `","ssh":"100.64.0.4 1 100.64.0.2 22"}`)
	if r := b.call(`{"id":5,"method":"conv.device"}`); r["result"] != strings.Repeat("长", deviceMax)+"|100.64.0.4 1 100.64.0.2 22" {
		t.Fatalf("太长的设备名 %v", r)
	}
	if r := a.call(`{"id":6,"method":"core.client","device":3}`); r["ok"] != false {
		t.Fatalf("设备名不是字符串也收了：%v", r)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.clients) != 2 {
		t.Fatalf("处理器只该收到 conv.device：%d 个客户端", len(h.clients))
	}
}

func TestShutdownRemovesSocketAndTellsHandler(t *testing.T) {
	p := testPaths(t)
	h := newFakeHandler()
	r := serve(t, p, h, time.Minute)
	if st, err := os.Stat(p.dir()); err != nil || st.Mode().Perm() != 0o700 {
		t.Fatalf("目录 %v %v", st, err)
	}
	c := dial(t, p)
	c.call(`{"id":1,"method":"conv.echo"}`)
	r.stop() // 同收到 SIGTERM
	r.exited()
	if _, err := os.Lstat(p.Socket); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("套接字文件还在：%v", err)
	}
	select {
	case <-h.gone:
	default:
		t.Error("退出前没告诉处理器客户端走了")
	}
	if _, ok := <-c.got; ok {
		t.Error("客户端的连接没断")
	}
	// 锁放开了：同一个版本能再起一个
	serve(t, p, h, time.Minute)
}

func TestExitsWhenIdleLongEnough(t *testing.T) {
	p := testPaths(t)
	h := newFakeHandler()
	h.busy.Store(true)
	const idle = 200 * time.Millisecond
	r := serve(t, p, h, idle)
	c := dial(t, p)
	c.call(`{"id":1,"method":"conv.echo"}`)
	_ = c.conn.Close()
	select {
	case <-h.gone:
	case <-time.After(5 * time.Second):
		t.Fatal("5 秒内没收到 Gone")
	}
	// 处理器还忙着（比如有接着的对话），没有连接也不退
	select {
	case <-r.done:
		t.Fatal("处理器不闲时退出了")
	case <-time.After(3 * idle):
	}
	start := time.Now()
	h.busy.Store(false)
	r.exited()
	// 隔 idle/10 看一次闲不闲，所以可能早一个间隔
	if d := time.Since(start); d < idle*9/10 {
		t.Errorf("闲了 %v 就退出了，不到 %v", d, idle)
	}
	if _, err := os.Lstat(p.Socket); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("套接字文件还在：%v", err)
	}
}

func TestConnectedClientKeepsCoreAlive(t *testing.T) {
	p := testPaths(t)
	const idle = 100 * time.Millisecond
	r := serve(t, p, newFakeHandler(), idle)
	dial(t, p).call(`{"id":1,"method":"conv.echo"}`)
	select {
	case <-r.done:
		t.Fatal("有客户端连着时退出了")
	case <-time.After(5 * idle):
	}
}

func TestOnlyOneCoreListens(t *testing.T) {
	p := testPaths(t)
	serve(t, p, newFakeHandler(), time.Minute)
	if _, err := Listen(p, Info{Build: 7, Version: "v7"}); !errors.Is(err, ErrRunning) {
		t.Fatalf("第二个核心：%v", err)
	}
	holder, err := os.ReadFile(p.Lock)
	if err != nil || strings.TrimSpace(string(holder)) != strconv.Itoa(os.Getpid()) {
		t.Errorf("锁文件里的进程号 %q %v", holder, err)
	}
}

// 上一个核心崩溃了：锁随进程放开，套接字文件留着、连不上。新起的清掉它再听。
func TestStaleSocketIsReplaced(t *testing.T) {
	p := testPaths(t)
	if err := p.makeDir(); err != nil {
		t.Fatal(err)
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: p.Socket, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	ln.SetUnlinkOnClose(false)
	_ = ln.Close()
	if _, err := os.Lstat(p.Socket); err != nil {
		t.Fatal("没留下残留的套接字文件")
	}
	serve(t, p, newFakeHandler(), time.Minute)
	if r := dial(t, p).call(`{"id":1,"method":"conv.echo"}`); r["ok"] != true {
		t.Errorf("清掉残留后 %v", r)
	}
}

// 锁被占着、套接字却没人听（那个核心正在起或正在退出）：等它放开锁再接手。
func TestListenWaitsForAHolderThatIsNotListening(t *testing.T) {
	p := testPaths(t)
	if err := p.makeDir(); err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(p.Lock, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		t.Fatal(err)
	}
	time.AfterFunc(100*time.Millisecond, func() { _ = f.Close() })
	serve(t, p, newFakeHandler(), time.Minute)

	// 一直不放开：等满 lockWait 报错，不是 ErrRunning
	g, err := os.OpenFile(p.Lock+"2", os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	if err := syscall.Flock(int(g.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		t.Fatal(err)
	}
	q := Paths{Socket: p.Socket + "2", Lock: p.Lock + "2", Log: p.Log}
	saved := lockWait
	lockWait = 100 * time.Millisecond
	defer func() { lockWait = saved }()
	if _, err := Listen(q, Info{}); err == nil || errors.Is(err, ErrRunning) || !strings.Contains(err.Error(), "nobody listens") {
		t.Errorf("一直占着锁不听：%v", err)
	}
}

func TestLocate(t *testing.T) {
	t.Setenv(DirEnv, "")
	p, err := Locate("/home/u/", "zeta-dev")
	if err != nil {
		t.Fatal(err)
	}
	want := Paths{
		Socket: "/home/u/.cache/zeta-dev/core/core.sock",
		Lock:   "/home/u/.cache/zeta-dev/core/core.lock",
		Log:    "/home/u/.cache/zeta-dev/core/core.log",
	}
	if p != want {
		t.Errorf("%+v", p)
	}
	t.Setenv(DirEnv, "/short")
	if p, err := Locate("/h", "zeta"); err != nil || p.Socket != "/short/core.sock" {
		t.Errorf("%+v %v", p, err)
	}
	t.Setenv(DirEnv, "")
	long := "/home/" + strings.Repeat("u", 80)
	if _, err := Locate(long, "zeta"); err == nil || !strings.Contains(err.Error(), "limit of unix sockets") {
		t.Errorf("路径太长：%v", err)
	}
}

// 几个 serve 同时要连：都拉起核心，最后只有一个在听，大家连上的是它。
func TestConnectStartsExactlyOneCore(t *testing.T) {
	testPaths(t)
	t.Setenv(childEnv, "1")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	p, err := Locate(os.Getenv("HOME"), "zeta")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { killHolder(p) })
	const n = 4
	pids := make(chan string, n)
	errs := make(chan error, n)
	for range n {
		go func() {
			conn, info, err := Connect(p, exe)
			if err != nil {
				errs <- err
				return
			}
			defer conn.Close()
			if info.Build != 7 {
				errs <- fmt.Errorf("core_hello：%+v", info)
				return
			}
			if _, err := conn.Write([]byte(`{"id":1,"method":"conv.pid"}` + "\n")); err != nil {
				errs <- err
				return
			}
			line, err := bufio.NewReader(conn).ReadString('\n')
			if err != nil {
				errs <- err
				return
			}
			var r struct {
				Result json.Number `json:"result"`
			}
			if err := json.Unmarshal([]byte(line), &r); err != nil {
				errs <- err
				return
			}
			pids <- r.Result.String()
		}()
	}
	seen := map[string]bool{}
	for range n {
		select {
		case pid := <-pids:
			seen[pid] = true
		case err := <-errs:
			t.Fatal(err)
		case <-time.After(10 * time.Second):
			t.Fatal("10 秒内没连上")
		}
	}
	holder, _ := os.ReadFile(p.Lock)
	if len(seen) != 1 || !seen[strings.TrimSpace(string(holder))] {
		t.Errorf("答话的核心 %v，持锁的 %q", seen, holder)
	}
}

// killHolder 结束持锁的那个核心（测试拉起的子进程）；锁已经没人持着时不动。
func killHolder(p Paths) {
	f, err := os.Open(p.Lock)
	if err != nil {
		return
	}
	defer f.Close()
	if syscall.Flock(int(f.Fd()), syscall.LOCK_SH|syscall.LOCK_NB) == nil {
		return
	}
	data, err := os.ReadFile(p.Lock)
	if err != nil {
		return
	}
	if pid, err := strconv.Atoi(strings.TrimSpace(string(data))); err == nil && pid != os.Getpid() {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
}

// 客户端在 core.client 里报界面语言：给它的 locales.Error 按这个语言出字，带种类码；没报的连接用源语言；换语言再报一次就换。
func TestReplySpeaksTheClientLanguage(t *testing.T) {
	p := testPaths(t)
	serve(t, p, newFakeHandler(), time.Minute)
	a, b := dial(t, p), dial(t, p)
	a.call(`{"id":1,"method":"core.client","device":"pc","lang":"en-US"}`)
	if r := a.call(`{"id":2,"method":"conv.said"}`); r["ok"] != false || r["error"] != "Stopped" || r["code"] != "noticeStopped" {
		t.Fatalf("英文的 a：%v", r)
	}
	if r := b.call(`{"id":3,"method":"conv.said"}`); r["error"] != "已停止" || r["code"] != "noticeStopped" {
		t.Fatalf("没报语言的 b：%v", r)
	}
	if r := b.call(`{"id":6,"method":"core.client","lang":"en"}`); r["ok"] != true {
		t.Fatalf("只报语言：%v", r)
	}
	if r := b.call(`{"id":7,"method":"conv.device"}`); r["result"] != "-" {
		t.Fatalf("只报了语言的 b 不算报过设备：%v", r)
	}
	if r := b.call(`{"id":8,"method":"conv.said"}`); r["error"] != "Stopped" {
		t.Fatalf("只报了语言的 b：%v", r)
	}
	a.call(`{"id":4,"method":"core.client","device":"pc","lang":"zh-Hans"}`)
	if r := a.call(`{"id":5,"method":"conv.said"}`); r["error"] != "已停止" {
		t.Fatalf("换回中文的 a：%v", r)
	}
}
