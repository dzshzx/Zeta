package sshlink

import (
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshtest"
)

// device 是测试服务器和一台记好指纹、直连它的设备。
func device(t *testing.T) (*sshtest.Server, KeyAuth, Host) {
	t.Helper()
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	h := Host{ID: "r-1", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home, DefaultCwd: server.Home,
		Via: ViaDirect, HostKey: EncodeHostKey(server.HostKey.PublicKey())}
	return server, KeyAuth{Signer: key}, h
}

func run(t *testing.T, links *Links, h Host, command string) Result {
	t.Helper()
	p, err := links.Start(h, command)
	if err != nil {
		t.Fatal(err)
	}
	return p.Capture(nil, 10*time.Second)
}

func TestCommandsShareOneConnection(t *testing.T) {
	server, auth, h := device(t)
	links := NewLinks(auth, time.Minute)
	if r := run(t, links, h, "echo $HOME; echo err >&2; exit 3"); r.Code != 3 || r.Stdout != server.Home+"\n" || r.Stderr != "err\n" {
		t.Errorf("result %+v", r)
	}
	p, err := links.Start(h, "cat")
	if err != nil {
		t.Fatal(err)
	}
	if r := p.Capture([]byte("from stdin"), 10*time.Second); r.Stdout != "from stdin" || r.Code != 0 {
		t.Errorf("stdin %+v", r)
	}
	if server.Dialed() != 1 {
		t.Errorf("dialed %d times", server.Dialed())
	}
}

func TestIdleConnectionCloses(t *testing.T) {
	server, auth, h := device(t)
	links := NewLinks(auth, 100*time.Millisecond)
	run(t, links, h, "true")
	waitFor(t, func() bool { return server.Open() == 0 })
	// 下一个命令重新连
	if r := run(t, links, h, "echo again"); r.Stdout != "again\n" || server.Dialed() != 2 {
		t.Errorf("result %+v, dialed %d", r, server.Dialed())
	}
}

func TestBusyConnectionStaysOpen(t *testing.T) {
	server, auth, h := device(t)
	links := NewLinks(auth, 50*time.Millisecond)
	p, err := links.Start(h, "cat")
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)
	if server.Open() != 1 {
		t.Fatal("closed while a command was running")
	}
	p.Close()
	waitFor(t, func() bool { return server.Open() == 0 })
}

func TestDroppedConnectionEndsCommandsAndReconnects(t *testing.T) {
	server, auth, h := device(t)
	links := NewLinks(auth, time.Minute)
	p, err := links.Start(h, "cat")
	if err != nil {
		t.Fatal(err)
	}
	go func() { _, _ = io.Copy(io.Discard, p.Stdout) }()
	go func() { _, _ = io.Copy(io.Discard, p.Stderr) }()
	server.DropAll()
	if code := p.Wait(); code != ConnectionFailed {
		t.Errorf("exit %d", code)
	}
	if r := run(t, links, h, "echo back"); r.Stdout != "back\n" || server.Dialed() != 2 {
		t.Errorf("result %+v, dialed %d", r, server.Dialed())
	}
}

func TestChangedHostKeyIsRefused(t *testing.T) {
	_, auth, h := device(t)
	other, _, _ := device(t)
	h.HostKey = EncodeHostKey(other.HostKey.PublicKey())
	links := NewLinks(auth, time.Minute)
	if _, err := links.Start(h, "true"); err == nil || !strings.Contains(err.Error(), "主机指纹和加设备时记下的不一样") {
		t.Errorf("start: %v", err)
	}
	// 重新检查时看得到对方现在的公钥，等用户决定认不认
	_, _, err := links.Probe(h.Target, "", h.HostKey, "true", 10*time.Second)
	var changed *HostKeyChangedError
	if !errors.As(err, &changed) || len(changed.Seen) == 0 {
		t.Errorf("probe: %v", err)
	}
}

func TestProbeTrustsAnyKeyAndReportsIt(t *testing.T) {
	server, auth, h := device(t)
	seen, r, err := NewLinks(auth, time.Minute).Probe(h.Target, "", "", "echo hi", 10*time.Second)
	if err != nil || r.Stdout != "hi\n" || seen != EncodeHostKey(server.HostKey.PublicKey()) {
		t.Errorf("probe %v %+v", err, r)
	}
}

func TestProbeTimesOut(t *testing.T) {
	_, auth, h := device(t)
	_, r, err := NewLinks(auth, time.Minute).Probe(h.Target, "", "", "sleep 5", 200*time.Millisecond)
	if err != nil || !r.TimedOut {
		t.Errorf("probe %v %+v", err, r)
	}
}

func TestConnectionProblemsAreDescribed(t *testing.T) {
	_, auth, h := device(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	h.Target = "zeta@127.0.0.1:" + strconv.Itoa(port)
	if _, err := NewLinks(auth, time.Minute).Start(h, "true"); err == nil || err.Error() != "对方拒绝连接（端口上没有 SSH 服务）" {
		t.Errorf("refused: %v", err)
	}
	stranger := KeyAuth{Signer: sshtest.NewKey(t)}
	_, _, h2 := device(t)
	if _, err := NewLinks(stranger, time.Minute).Start(h2, "true"); err == nil || err.Error() != "服务器拒绝了给定的密钥" {
		t.Errorf("rejected: %v", err)
	}
	h2.Via = ViaDebian
	if _, err := NewLinks(auth, time.Minute).Start(h2, "true"); err == nil || !strings.Contains(err.Error(), "手机的 Debian") {
		t.Errorf("debian: %v", err)
	}
}

func TestRetainDropsRemovedHosts(t *testing.T) {
	server, auth, h := device(t)
	links := NewLinks(auth, time.Minute)
	run(t, links, h, "true")
	links.Retain([]string{"r-other"})
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
