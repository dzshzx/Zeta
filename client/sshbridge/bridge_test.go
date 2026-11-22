package sshbridge

import (
	"bufio"
	"crypto/rand"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"golang.org/x/crypto/ssh"
)

// msg 是 zeta-ssh 发给应用的一行（各种 type 的字段并在一起）。
type msg struct {
	Type     string `json:"type"`
	Protocol int    `json:"protocol"`
	P        int64  `json:"p"`
	ID       int64  `json:"id"`
	Stream   string `json:"stream"`
	Data     []byte `json:"data"`
	Code     int    `json:"code"`
	Problem  string `json:"problem"`
	Error    string `json:"error"`
	Method   string `json:"method"`
	Host     string `json:"host"`
	HostKey  string `json:"host_key"`
	Changed  bool   `json:"changed"`
	Stdout   string `json:"stdout"`
	Stderr   string `json:"stderr"`
	TimedOut bool   `json:"timed_out"`
}

// app 是测试里的应用一侧：经管道和 Run 说话，替它签名（内存里的密钥）、解析域名。
type app struct {
	t   *testing.T
	in  *io.PipeWriter
	key ssh.Signer
	// keyError 不是空时取公钥答出错
	keyError string
	resolve  func(host string) ([]string, string)

	signs, resolves atomic.Int32

	mu      sync.Mutex
	writing sync.Mutex
	procs   map[int64]chan msg
	replies map[int64]chan msg
	nextP   int64
	done    chan struct{}
}

func newApp(t *testing.T, opts Options) *app {
	t.Helper()
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	a := &app{t: t, in: inW, key: sshtest.NewKey(t), procs: map[int64]chan msg{}, replies: map[int64]chan msg{}, done: make(chan struct{})}
	a.resolve = func(host string) ([]string, string) {
		if host == "localhost" {
			return []string{"127.0.0.1"}, ""
		}
		return nil, "Unable to resolve host \"" + host + "\""
	}
	go func() {
		_ = Run(inR, outW, opts)
		_ = outW.Close()
	}()
	hello := make(chan msg, 1)
	go a.read(outR, hello)
	select {
	case h := <-hello:
		if h.Type != "hello" || h.Protocol != Protocol {
			t.Fatalf("first message %+v", h)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no hello")
	}
	t.Cleanup(a.close)
	return a
}

// close 关掉到 zeta-ssh 的输入，等它退出。
func (a *app) close() {
	_ = a.in.Close()
	select {
	case <-a.done:
	case <-time.After(10 * time.Second):
		a.t.Error("zeta-ssh did not exit after its input ended")
	}
}

func (a *app) read(r io.Reader, hello chan<- msg) {
	defer close(a.done)
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	first := true
	for scanner.Scan() {
		var m msg
		if err := json.Unmarshal(scanner.Bytes(), &m); err != nil {
			a.t.Errorf("bad line %q", scanner.Text())
			continue
		}
		switch {
		case first:
			first = false
			hello <- m
		case m.Type == "call":
			go a.answer(m)
		case m.Type == "reply":
			a.mu.Lock()
			ch := a.replies[m.ID]
			a.mu.Unlock()
			ch <- m
		default:
			a.mu.Lock()
			ch := a.procs[m.P]
			a.mu.Unlock()
			if ch == nil {
				a.t.Errorf("message for unknown process: %+v", m)
				continue
			}
			ch <- m
		}
	}
}

// answer 做 zeta-ssh 请应用做的事。
func (a *app) answer(m msg) {
	reply := map[string]any{"method": "answer", "id": m.ID}
	switch m.Method {
	case callKey:
		if a.keyError != "" {
			reply["error"] = a.keyError
		} else {
			reply["data"] = a.key.PublicKey().Marshal()
		}
	case callSign:
		a.signs.Add(1)
		sig, err := a.key.Sign(rand.Reader, m.Data)
		if err != nil {
			reply["error"] = err.Error()
		} else {
			reply["data"] = sig.Blob
		}
	case callResolve:
		a.resolves.Add(1)
		addrs, err := a.resolve(m.Host)
		reply["addrs"], reply["error"] = addrs, err
	}
	a.send(reply)
}

func (a *app) send(v any) {
	line, err := json.Marshal(v)
	if err != nil {
		a.t.Fatal(err)
	}
	a.writing.Lock()
	defer a.writing.Unlock()
	if _, err := a.in.Write(append(line, '\n')); err != nil {
		a.t.Errorf("send: %v", err)
	}
}

// start 起一个进程，返回它的编号和收消息的口子。
func (a *app) start(h sshlink.Host, command string, login bool) (int64, chan msg) {
	ch := make(chan msg, 1024)
	a.mu.Lock()
	a.nextP++
	p := a.nextP
	a.procs[p] = ch
	a.mu.Unlock()
	a.send(map[string]any{
		"method": "start", "p": p, "command": command, "login": login, "name": "zeta-test", "tz": "UTC",
		"host": map[string]string{"id": h.ID, "target": h.Target, "host_key": h.HostKey},
	})
	return p, ch
}

// result 是收齐的一个进程。
type result struct {
	stdout, stderr string
	code           int
	problem        string
}

// collect 收 p 的输出直到 exit，收到就确认；stdin 不是 nil 时先写进去（等 wrote）再关掉输入。
func (a *app) collect(p int64, ch chan msg) result {
	a.t.Helper()
	var r result
	var out, errs strings.Builder
	for {
		select {
		case m := <-ch:
			switch m.Type {
			case "out":
				if m.Stream == "stdout" {
					out.Write(m.Data)
				} else {
					errs.Write(m.Data)
				}
				a.send(map[string]any{"method": "ack", "p": p, "stream": m.Stream, "n": len(m.Data)})
			case "exit":
				r.stdout, r.stderr, r.code, r.problem = out.String(), errs.String(), m.Code, m.Problem
				return r
			}
		case <-time.After(20 * time.Second):
			a.t.Fatalf("process %d did not exit", p)
		}
	}
}

func (a *app) run(h sshlink.Host, command string, login bool) result {
	a.t.Helper()
	p, ch := a.start(h, command, login)
	a.send(map[string]any{"method": "stdin", "p": p, "eof": true})
	return a.collect(p, ch)
}

// next 是 p 的下一条消息。
func (a *app) next(ch chan msg) msg {
	a.t.Helper()
	select {
	case m := <-ch:
		return m
	case <-time.After(20 * time.Second):
		a.t.Fatal("no message")
	}
	return msg{}
}

// expect 等 p 的下一条 kind 消息，别的跳过。
func (a *app) expect(ch chan msg, kind string) msg {
	a.t.Helper()
	for {
		if m := a.next(ch); m.Type == kind {
			return m
		}
	}
}

func (a *app) probe(target, expected, command string, timeoutMS int) msg {
	a.t.Helper()
	ch := make(chan msg, 1)
	a.mu.Lock()
	a.nextP++
	id := a.nextP
	a.replies[id] = ch
	a.mu.Unlock()
	a.send(map[string]any{"method": "probe", "id": id, "target": target, "host_key": expected, "command": command,
		"name": "zeta-test", "tz": "UTC", "timeout_ms": timeoutMS})
	select {
	case m := <-ch:
		return m
	case <-time.After(30 * time.Second):
		a.t.Fatal("no probe reply")
	}
	return msg{}
}

// device 是一台测试服务器（只认 a 的密钥）和直连它的设备。
func device(t *testing.T, a *app) (*sshtest.Server, sshlink.Host) {
	t.Helper()
	server := sshtest.Start(t, t.TempDir(), a.key.PublicKey())
	return server, sshlink.Host{ID: "r-1", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Via: sshlink.ViaDirect,
		HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
}

func TestCommandsShareOneConnectionAndSignOnce(t *testing.T) {
	a := newApp(t, Options{})
	server, h := device(t, a)
	r := a.run(h, `echo "$HOME|$ZETA_NAME|$TZ"; echo err >&2; exit 3`, false)
	if r.code != 3 || r.stdout != server.Home+"|zeta-test|UTC\n" || r.stderr != "err\n" || r.problem != "" {
		t.Errorf("result %+v", r)
	}
	p, ch := a.start(h, "cat", false)
	a.send(map[string]any{"method": "stdin", "p": p, "data": []byte("from stdin")})
	if w := a.expect(ch, "wrote"); w.Error != "" {
		t.Errorf("wrote %+v", w)
	}
	a.send(map[string]any{"method": "stdin", "p": p, "eof": true})
	if r := a.collect(p, ch); r.stdout != "from stdin" || r.code != 0 {
		t.Errorf("stdin %+v", r)
	}
	if server.Dialed() != 1 || a.signs.Load() != 1 {
		t.Errorf("dialed %d, signed %d times", server.Dialed(), a.signs.Load())
	}
}

func TestLoginShellReadsProfileAndPlainDoesNot(t *testing.T) {
	a := newApp(t, Options{})
	server, h := device(t, a)
	if err := os.WriteFile(filepath.Join(server.Home, ".profile"), []byte("export FROM_PROFILE=yes\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	command := `echo "${FROM_PROFILE:-no}"; case ":$PATH:" in *":$HOME/.local/bin:"*) echo local;; *) echo none;; esac`
	if r := a.run(h, command, true); r.stdout != "yes\nlocal\n" {
		t.Errorf("login %+v", r)
	}
	if r := a.run(h, command, false); r.stdout != "no\nnone\n" {
		t.Errorf("plain %+v", r)
	}
}

// 应用不确认时，没确认的输出不超过 Window：慢慢确认，最后一个字节不少。
func TestOutputWaitsForTheAppToRead(t *testing.T) {
	a := newApp(t, Options{})
	_, h := device(t, a)
	const total = 3_000_000
	p, ch := a.start(h, "head -c "+strconv.Itoa(total)+" /dev/zero", false)
	a.send(map[string]any{"method": "stdin", "p": p, "eof": true})
	received, acked, most := 0, 0, 0
	for {
		m := a.next(ch)
		if m.Type == "exit" {
			if m.Code != 0 || received != total {
				t.Fatalf("exit %+v after %d bytes", m, received)
			}
			break
		}
		if m.Type != "out" {
			continue
		}
		received += len(m.Data)
		most = max(most, received-acked)
		if received-acked > Window {
			t.Fatalf("%d bytes outstanding", received-acked)
		}
		// 收满一个窗口才确认一半：zeta-ssh 只能停下来等
		if received-acked == Window {
			a.send(map[string]any{"method": "ack", "p": p, "stream": "stdout", "n": Window / 2})
			acked += Window / 2
		}
	}
	if most != Window {
		t.Errorf("never filled the window (at most %d outstanding)", most)
	}
}

func TestDroppedConnectionEndsCommandsAndTheNextOneReconnects(t *testing.T) {
	a := newApp(t, Options{})
	server, h := device(t, a)
	p, ch := a.start(h, "echo up; cat", false)
	if m := a.expect(ch, "out"); string(m.Data) != "up\n" {
		t.Fatalf("out %+v", m)
	}
	server.DropAll()
	if r := a.collect(p, ch); r.code != sshlink.ConnectionFailed {
		t.Errorf("exit %+v", r)
	}
	if r := a.run(h, "echo back", false); r.stdout != "back\n" || server.Dialed() != 2 {
		t.Errorf("result %+v, dialed %d", r, server.Dialed())
	}
}

// 换网络时应用叫 drop_all：开着的命令马上以 255 结束；删了设备时 retain 不留它，也一样。
func TestAppDropsConnections(t *testing.T) {
	a := newApp(t, Options{})
	_, h := device(t, a)
	for _, request := range []map[string]any{{"method": "drop_all"}, {"method": "retain", "ids": []string{"r-other"}}} {
		p, ch := a.start(h, "echo up; cat", false)
		a.expect(ch, "out")
		a.send(request)
		if r := a.collect(p, ch); r.code != sshlink.ConnectionFailed {
			t.Errorf("%v: exit %+v", request, r)
		}
	}
}

func TestChangedHostKeyIsRefused(t *testing.T) {
	a := newApp(t, Options{})
	server, h := device(t, a)
	expected := h.HostKey
	h.HostKey = sshlink.EncodeHostKey(sshtest.NewKey(t).PublicKey())
	if r := a.run(h, "true", false); r.code != sshlink.ConnectionFailed || !strings.Contains(r.problem, "主机指纹和加设备时记下的不一样") {
		t.Errorf("start %+v", r)
	}
	reply := a.probe(h.Target, h.HostKey, "true", 10000)
	if !reply.Changed || reply.HostKey != expected || !strings.Contains(reply.Problem, "主机指纹") {
		t.Errorf("probe %+v", reply)
	}
	if server.Dialed() != 2 {
		t.Errorf("dialed %d", server.Dialed())
	}
}

// 加设备那一次谁都认，把对方的主机公钥带回来；命令在登录 shell 里跑。
func TestProbeReportsTheHostKey(t *testing.T) {
	a := newApp(t, Options{})
	server, h := device(t, a)
	reply := a.probe(h.Target, "", `echo "hi $ZETA_NAME"; echo oops >&2; exit 3`, 10000)
	if reply.HostKey != sshlink.EncodeHostKey(server.HostKey.PublicKey()) || reply.Changed || reply.Problem != "" ||
		reply.Code != 3 || reply.Stdout != "hi zeta-test\n" || reply.Stderr != "oops\n" {
		t.Errorf("probe %+v", reply)
	}
	if reply := a.probe(h.Target, "", "sleep 30", 200); !reply.TimedOut {
		t.Errorf("slow probe %+v", reply)
	}
}

func TestKeyProblemsAreExplained(t *testing.T) {
	a := newApp(t, Options{})
	stranger := sshtest.Start(t, t.TempDir(), sshtest.NewKey(t).PublicKey())
	h := sshlink.Host{ID: "r-2", Target: "zeta@127.0.0.1:" + strconv.Itoa(stranger.Port), Via: sshlink.ViaDirect,
		HostKey: sshlink.EncodeHostKey(stranger.HostKey.PublicKey())}
	if r := a.run(h, "true", false); r.problem != "对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys" {
		t.Errorf("stranger %+v", r)
	}
	b := newApp(t, Options{})
	b.keyError = "密钥库打不开"
	_, h = device(t, b)
	if r := b.run(h, "true", false); r.code != sshlink.ConnectionFailed || r.problem != "取不到 Zeta 的密钥：密钥库打不开" {
		t.Errorf("no key %+v", r)
	}
	h.Target = "zeta@host:99999"
	if r := b.run(h, "true", false); r.problem != directHint {
		t.Errorf("bad target %+v", r)
	}
}

// 域名交给应用解析（Android 上 Go 自己解析不了）；IP 地址不问。
func TestNamesAreResolvedByTheApp(t *testing.T) {
	a := newApp(t, Options{})
	_, h := device(t, a)
	a.run(h, "true", false)
	if a.resolves.Load() != 0 {
		t.Errorf("asked to resolve an IP address")
	}
	h.ID, h.Target = "r-2", strings.Replace(h.Target, "127.0.0.1", "localhost", 1)
	if r := a.run(h, "echo named", false); r.stdout != "named\n" || a.resolves.Load() != 1 {
		t.Errorf("result %+v, resolved %d times", r, a.resolves.Load())
	}
	h.ID, h.Target = "r-3", "zeta@nope.invalid"
	if r := a.run(h, "true", false); r.problem != "找不到这台设备（地址解析失败）" {
		t.Errorf("unknown %+v", r)
	}
}

// 还在连（对方接了 TCP 不说话）时应用要结束它：不等握手超时，马上 exit。
func TestCloseWhileConnectingGivesUp(t *testing.T) {
	a := newApp(t, Options{})
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		if c, err := listener.Accept(); err == nil {
			accepted <- c
		}
	}()
	h := sshlink.Host{ID: "r-silent", Target: "zeta@" + listener.Addr().String(), Via: sshlink.ViaDirect,
		HostKey: sshlink.EncodeHostKey(sshtest.NewKey(t).PublicKey())}
	p, ch := a.start(h, "true", false)
	a.send(map[string]any{"method": "stdin", "p": p, "data": []byte("queued")})
	select {
	case c := <-accepted:
		defer c.Close()
	case <-time.After(10 * time.Second):
		t.Fatal("never connected")
	}
	a.send(map[string]any{"method": "close", "p": p})
	// 排着的输入答出错、进程结束，两条谁先到都行
	for seen := 0; seen < 2; seen++ {
		switch m := a.next(ch); m.Type {
		case "wrote":
			if m.Error == "" {
				t.Errorf("queued input written: %+v", m)
			}
		case "exit":
			if m.Code != sshlink.ConnectionFailed || m.Problem != "已经结束" {
				t.Errorf("exit %+v", m)
			}
		default:
			t.Errorf("unexpected %+v", m)
		}
	}
}

func TestInputAfterExitFails(t *testing.T) {
	a := newApp(t, Options{})
	_, h := device(t, a)
	p, ch := a.start(h, "true", false)
	a.collect(p, ch)
	a.send(map[string]any{"method": "stdin", "p": p, "data": []byte("late")})
	if w := a.expect(ch, "wrote"); w.Error == "" {
		t.Errorf("wrote %+v", w)
	}
}

func TestIdleConnectionClosesAndInputEndStopsEverything(t *testing.T) {
	a := newApp(t, Options{Idle: 50 * time.Millisecond})
	server, h := device(t, a)
	a.run(h, "true", false)
	waitFor(t, func() bool { return server.Open() == 0 })
	_, ch := a.start(h, "echo up; cat", false)
	a.expect(ch, "out")
	a.close()
	waitFor(t, func() bool { return server.Open() == 0 })
}

// waitFor 等 cond 成立，最多 5 秒。
func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
