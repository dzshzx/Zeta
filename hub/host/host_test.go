package host

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
)

// box 是测试里的一台设备：sshtest 的服务器，命令在临时家目录里真跑 bash。
type box struct {
	server *sshtest.Server
	links  *sshlink.Links
	host   sshlink.Host
	shell  hostlink.Shell
}

func newBox(t *testing.T) *box {
	t.Helper()
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	h := sshlink.Host{ID: "r-test", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	links := sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute)
	return &box{server: server, links: links, host: h, shell: hostlink.NewShell(links, h, LinuxName)}
}

// file 在设备的家目录里写一个文件，mode 是权限。
func (b *box) file(t *testing.T, rel, text string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(b.server.Home, rel)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), mode); err != nil {
		t.Fatal(err)
	}
	return path
}

// fakeClaude 在 ~/.local/bin 放一个只会报版本的 claude（登录 shell 的 PATH 补上了这个目录）。
func (b *box) fakeClaude(t *testing.T) {
	b.file(t, ".local/bin/claude", "#!/bin/sh\necho '2.1.330 (Claude Code)'\n", 0o755)
}

func (b *box) fakeAgy(t *testing.T) {
	b.file(t, ".local/bin/agy", "#!/bin/sh\necho 'agy version 0.2.0'\n", 0o755)
}

// session 写一份最简单的会话记录。
func (b *box) session(t *testing.T, id, text string) {
	b.file(t, ".claude/projects/-w/"+id+".jsonl", `{"type":"user","cwd":"/w/proj","message":{"content":"`+text+`"}}`+"\n", 0o644)
}

func TestProbeReportsTheDevice(t *testing.T) {
	b := newBox(t)
	b.fakeClaude(t)
	b.file(t, ".profile", "echo 'Welcome!'\n", 0o644)
	f, err := Probe(b.links, b.host.Target, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if f.Home != b.server.Home || f.Claude != "2.1.330" || !hostlink.HelperRuns(f.System) || f.Hostname == "" || f.HostKey != b.host.HostKey {
		t.Errorf("found %+v", f)
	}
	if !strings.HasPrefix(f.Summary(), "Claude Code 2.1.330 · Linux ") {
		t.Errorf("summary %q", f.Summary())
	}
	// 记下的指纹对得上才连
	if _, err := Probe(b.links, b.host.Target, b.host.HostKey, ""); err != nil {
		t.Errorf("expected key: %v", err)
	}
	other := newBox(t)
	_, err = Probe(b.links, b.host.Target, other.host.HostKey, "")
	var changed *sshlink.HostKeyChangedError
	if !errors.As(err, &changed) || changed.Seen != b.host.HostKey {
		t.Errorf("changed: %v", err)
	}
}

func TestProbeExplainsAMissingClaude(t *testing.T) {
	b := newBox(t)
	_, err := Probe(b.links, b.host.Target, "", "")
	if err == nil || !strings.Contains(err.Error(), "未找到 Claude Code") {
		t.Errorf("err %v", err)
	}
}

func TestProbeSupportsAntigravity(t *testing.T) {
	b := newBox(t)
	b.fakeAgy(t)
	f, err := Probe(b.links, b.host.Target, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if f.Claude != "" || f.Agy != "0.2.0" || f.DefaultHarness() != "agy" || strings.Join(f.InstalledHarnesses(), ",") != "agy" {
		t.Fatalf("agy only: %+v", f)
	}
	if !strings.HasPrefix(f.Summary(), "Antigravity 0.2.0 · Linux ") {
		t.Errorf("summary %q", f.Summary())
	}
	b.fakeClaude(t)
	f, err = Probe(b.links, b.host.Target, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if f.Claude != "2.1.330" || f.Agy != "0.2.0" || f.DefaultHarness() != "claude" || strings.Join(f.InstalledHarnesses(), ",") != "claude,agy" {
		t.Fatalf("both: %+v", f)
	}
	if !strings.HasPrefix(f.Summary(), "Claude Code 2.1.330 · Antigravity 0.2.0 · Linux ") {
		t.Errorf("summary %q", f.Summary())
	}
}

func TestParseProbe(t *testing.T) {
	ok := sshlink.Result{Stdout: "banner\nzeta_home /home/z\nzeta_claude 2.1.0 (Claude Code)\nzeta_system Linux aarch64\nzeta_hostname \n"}
	if f, err := parseProbe(ok); err != nil || f.Claude != "2.1.0" || f.Hostname != "" || f.System != "Linux aarch64" {
		t.Errorf("ok: %+v %v", f, err)
	}
	for r, want := range map[*sshlink.Result]string{
		{TimedOut: true}: "连接超时",
		{Code: sshlink.ConnectionFailed, Stderr: "x\nbroken pipe\n"}:                                     "broken pipe",
		{Stdout: "zeta_home /h\nzeta_claude 2\nzeta_system Darwin arm64\n"}:                              "当前设备为 Darwin arm64",
		{Stdout: "zeta_home /h\nzeta_claude 2\nzeta_system Linux riscv64\n"}:                             "当前设备为 Linux riscv64",
		{Stdout: "zeta_home /h\nzeta_claude \nzeta_system Linux x86_64\n"}:                               "未找到 Claude Code",
		{Stdout: "zeta_home \nzeta_claude 2\nzeta_system Linux x86_64\n", Stderr: "bash: no such thing"}: "bash: no such thing",
	} {
		if _, err := parseProbe(*r); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%+v: %v, want %q", *r, err, want)
		}
	}
}

// events 收 Serve 的回调，给测试按顺序等。
type events struct {
	mu       sync.Mutex
	updates  []hostlink.SessionsUpdate
	activity []map[string]hostlink.Activity
	problems []string
	changed  chan struct{}
}

func newEvents() *events { return &events{changed: make(chan struct{}, 100)} }

func (e *events) update(u hostlink.SessionsUpdate) {
	e.mu.Lock()
	e.updates = append(e.updates, u)
	e.mu.Unlock()
	e.changed <- struct{}{}
}

func (e *events) busy(a map[string]hostlink.Activity) {
	e.mu.Lock()
	e.activity = append(e.activity, a)
	e.mu.Unlock()
	e.changed <- struct{}{}
}

func (e *events) watch() SessionWatch {
	return SessionWatch{Update: e.update, Activity: e.busy, Problem: e.problem}
}

func (e *events) problem(why string) {
	e.mu.Lock()
	e.problems = append(e.problems, why)
	e.mu.Unlock()
	e.changed <- struct{}{}
}

// waitFor 等 cond 成立，最多 20 秒（serve 合并变化要等 2 秒）。
func (e *events) waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.After(20 * time.Second)
	for {
		e.mu.Lock()
		ok := cond()
		e.mu.Unlock()
		if ok {
			return
		}
		select {
		case <-e.changed:
		case <-deadline:
			t.Fatalf("timed out waiting for %s: updates %+v, problems %q", what, e.updates, e.problems)
		}
	}
}

func TestWatchSessionsFollowsTheDevice(t *testing.T) {
	b := newBox(t)
	program, err := LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		t.Fatal(err)
	}
	b.session(t, "11111111-2222-3333-4444-555555555555", "第一句")
	ev := newEvents()
	devices := &Devices{Links: b.links, Helper: program}
	watch := devices.WatchSessions(b.host, ev.watch())
	defer watch.Close()
	ev.waitFor(t, "the full list", func() bool { return len(ev.updates) == 1 })
	// 同一条连接也订阅了各会话的忙闲：订阅上先收到整份（这时没有开着的对话，是空的）
	ev.waitFor(t, "the activity", func() bool { return len(ev.activity) == 1 })
	if len(ev.activity[0]) != 0 {
		t.Errorf("activity %+v", ev.activity[0])
	}
	full := ev.updates[0]
	if !full.Full || len(full.Upsert) != 1 || full.Upsert[0].Label() != "第一句" || full.Upsert[0].Place() != "proj" || full.Upsert[0].Backend != "r-test" {
		t.Fatalf("full %+v", full)
	}
	if len(ev.problems) != 1 || ev.problems[0] != "" {
		t.Errorf("problems %q", ev.problems)
	}
	// 新会话只推它
	b.session(t, "22222222-2222-3333-4444-555555555555", "第二个")
	ev.waitFor(t, "the new session", func() bool { return len(ev.updates) == 2 })
	if u := ev.updates[1]; u.Full || len(u.Upsert) != 1 || u.Upsert[0].ID != "22222222-2222-3333-4444-555555555555" {
		t.Errorf("update %+v", u)
	}
}

// 正式版和手机正式版同包，别的构建和手机测试版同包（docs/desktop.md）。
func TestLinuxNameFollowsBuild(t *testing.T) {
	if linuxName(true) != "zeta" || linuxName(false) != "zeta-dev" {
		t.Fatal(linuxName(true), linuxName(false))
	}
	if LinuxName != "zeta-dev" {
		t.Fatal("测试编出的不是正式版，应该是 zeta-dev：", LinuxName)
	}
}
