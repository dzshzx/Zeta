package hostlink

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
)

// box 是测试里的一台设备：sshtest 的服务器，命令在临时家目录里真跑 bash。
type box struct {
	server *sshtest.Server
	links  *sshlink.Links
	host   sshlink.Host
	shell  Shell
}

func newBox(t *testing.T) *box {
	t.Helper()
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	h := sshlink.Host{ID: "r-test", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	links := sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute)
	return &box{server: server, links: links, host: h, shell: NewShell(links, h, testName)}
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

// session 写一份最简单的会话记录。
func (b *box) session(t *testing.T, id, text string) {
	b.file(t, ".claude/projects/-w/"+id+".jsonl", `{"type":"user","cwd":"/w/proj","message":{"content":"`+text+`"}}`+"\n", 0o644)
}

func TestRemoteCommandQuotesForBash(t *testing.T) {
	got := RemoteCommand(`echo 'a b' "$HOME"`, "zeta", "Asia/Shanghai", true)
	want := `exec env ZETA_NAME='zeta' TZ='Asia/Shanghai' ZETA_LANG="$LANG" LANG=C.UTF-8 TERM=dumb bash -lc 'PATH="$PATH:$HOME/.local/bin"; echo '\''a b'\'' "$HOME"'`
	if got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	if got := RemoteCommand("true", "zeta", "UTC", false); got != `exec env ZETA_NAME='zeta' TZ='UTC' ZETA_LANG="$LANG" LANG=C.UTF-8 TERM=dumb bash -c 'true'` {
		t.Errorf("plain %s", got)
	}
}

func TestShellRunsInTheRemoteEnvironment(t *testing.T) {
	b := newBox(t)
	b.shell.TZ = "UTC"
	b.file(t, ".local/bin/only-in-login", "#!/bin/sh\necho found\n", 0o755)
	r, err := b.shell.Run(`echo "$ZETA_NAME $TZ $LANG $ZETA_LANG"; only-in-login; echo 'it''s'`, nil, 10*time.Second, false)
	if err != nil || r.Stdout != testName+" UTC C.UTF-8 C.UTF-8\nfound\nits\n" || r.Code != 0 {
		t.Errorf("login: %v %+v", err, r)
	}
	r, _ = b.shell.Run("only-in-login", nil, 10*time.Second, true)
	if r.Code != 127 {
		t.Errorf("plain shell found ~/.local/bin: %+v", r)
	}
}

func TestPosixZone(t *testing.T) {
	for offset, want := range map[int]string{8 * 3600: "<+08>-8", -5 * 3600: "<-05>5", 5*3600 + 1800: "<+0530>-5:30", 0: "<+00>-0"} {
		if got := posixZone(time.Date(2026, 1, 1, 0, 0, 0, 0, time.FixedZone("", offset))); got != want {
			t.Errorf("offset %d: %s, want %s", offset, got, want)
		}
	}
}

func TestHelperInstallsOnceByContentHash(t *testing.T) {
	b := newBox(t)
	program := testHelper()
	stale := b.file(t, ".cache/"+testName+"/bin/zeta-helper-0123456789abcdef", "old", 0o755)
	other := b.file(t, ".cache/"+testName+"/bin/claude-switch-0123456789abcdef", "other program", 0o755)
	b.session(t, "11111111-2222-3333-4444-555555555555", "你好")
	out, err := b.shell.Helper(program, 30*time.Second, "list")
	if err != nil || !strings.Contains(out, `"id":"11111111-2222-3333-4444-555555555555"`) {
		t.Fatalf("list: %v %q", err, out)
	}
	installed := filepath.Join(b.server.Home, program.RelativePath(testName))
	info, err := os.Stat(installed)
	if err != nil || info.Mode().Perm() != 0o755 {
		t.Fatalf("installed %v %v", info, err)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Error("the old version was kept")
	}
	if _, err := os.Stat(other); err != nil {
		t.Error("another program's version was removed")
	}
	if link, _ := os.Readlink(filepath.Join(b.server.Home, ".cache/"+testName+"/bin/zeta-helper")); link != "zeta-helper-"+program.Version {
		t.Errorf("link %q", link)
	}
	// 装好了就直接跑，不再传程序
	before := info.ModTime()
	time.Sleep(20 * time.Millisecond)
	if _, err := b.shell.Helper(program, 30*time.Second, "list"); err != nil {
		t.Fatal(err)
	}
	if after, _ := os.Stat(installed); !after.ModTime().Equal(before) {
		t.Error("installed again")
	}
}

func TestHelperErrorsAreExplained(t *testing.T) {
	b := newBox(t)
	program := testHelper()
	if _, err := b.shell.Helper(program, 30*time.Second, "no-such-command"); err == nil || !strings.Contains(err.Error(), "| serve") {
		t.Errorf("usage: %v", err)
	}
	// 没有这台的 CPU 能用的版本
	odd := NewProgram("odd-helper", map[string][]byte{"arm64": []byte("x")})
	if _, err := b.shell.Helper(odd, 10*time.Second, "list"); err == nil || !strings.Contains(err.Error(), "没有这台机器") {
		t.Errorf("arch: %v", err)
	}
	b.server.Close()
	if _, err := b.shell.Helper(program, 10*time.Second, "list"); err == nil || !strings.HasPrefix(err.Error(), "连不上「zeta@127.0.0.1:") {
		t.Errorf("offline: %v", err)
	}
}

func TestProgramVersionMatchesKotlin(t *testing.T) {
	// HelperProgram.version：两份程序先 arm64 后 amd64 接起来的 SHA-256，取前 16 位
	p := NewProgram("zeta-helper", map[string][]byte{"arm64": []byte("a"), "amd64": []byte("b")})
	if p.Version != "fb8e20fc2e4c3f24" || p.RelativePath(testName) != ".cache/"+testName+"/bin/zeta-helper-fb8e20fc2e4c3f24" {
		t.Errorf("version %s", p.Version)
	}
}

// events 收 Serve 的回调，给测试按顺序等。
type events struct {
	mu       sync.Mutex
	updates  []SessionsUpdate
	problems []string
	changed  chan struct{}
}

func newEvents() *events { return &events{changed: make(chan struct{}, 100)} }

func (e *events) update(u SessionsUpdate) {
	e.mu.Lock()
	e.updates = append(e.updates, u)
	e.mu.Unlock()
	e.changed <- struct{}{}
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

func TestServeReconnectsAfterTheLinkDrops(t *testing.T) {
	b := newBox(t)
	program := testHelper()
	ev := newEvents()
	s := StartServe(ServeOptions{
		Open:          func() (*sshlink.Process, error) { return OpenServe(b.shell, program) },
		Subscriptions: []string{"sessions.watch"},
		Message: func(kind string, line []byte) {
			if u, err := ParseSessions(line, "r-test"); err == nil && kind == "zeta_sessions" {
				ev.update(u)
			}
		},
		Problem: ev.problem,
		Backoff: func(int) time.Duration { return 10 * time.Millisecond },
	})
	ev.waitFor(t, "the first list", func() bool { return len(ev.updates) == 1 })
	b.server.DropAll()
	ev.waitFor(t, "the second list", func() bool { return len(ev.updates) == 2 })
	if !ev.updates[1].Full || len(ev.problems) < 3 || !strings.HasPrefix(ev.problems[1], "连接断了") || ev.problems[len(ev.problems)-1] != "" {
		t.Errorf("updates %+v, problems %q", ev.updates, ev.problems)
	}
	_ = s.Close()
	count := len(ev.problems)
	b.server.DropAll()
	time.Sleep(100 * time.Millisecond)
	if len(ev.problems) != count {
		t.Errorf("called back after Close: %q", ev.problems)
	}
}

func TestServeStopsWhenItNeverConnected(t *testing.T) {
	b := newBox(t)
	program := testHelper()
	b.server.Close()
	ev := newEvents()
	opens := 0
	StartServe(ServeOptions{
		Open:    func() (*sshlink.Process, error) { opens++; return OpenServe(b.shell, program) },
		Message: func(string, []byte) {},
		Problem: ev.problem,
		Backoff: func(int) time.Duration { return time.Millisecond },
	})
	ev.waitFor(t, "the problem", func() bool { return len(ev.problems) == 1 })
	time.Sleep(50 * time.Millisecond)
	if opens != 1 || !strings.HasPrefix(ev.problems[0], "连不上「") {
		t.Errorf("opens %d, problems %q", opens, ev.problems)
	}
}

func TestServePingsAndRefusesOtherProtocols(t *testing.T) {
	b := newBox(t)
	// 假的 serve：打招呼，把收到的请求记进文件
	ev := newEvents()
	s := StartServe(ServeOptions{
		Open: func() (*sshlink.Process, error) {
			return b.shell.StartPlain(`echo '{"type":"zeta_hello","protocol":1}'; cat > "$HOME/requests"`)
		},
		Subscriptions: []string{"sessions.watch"},
		Message:       func(string, []byte) {},
		Problem:       ev.problem,
		Ping:          20 * time.Millisecond,
	})
	defer s.Close()
	requests := filepath.Join(b.server.Home, "requests")
	deadline := time.Now().Add(10 * time.Second)
	for {
		data, _ := os.ReadFile(requests)
		if strings.Count(string(data), `"method":"ping"`) >= 2 {
			if !strings.HasPrefix(string(data), `{"id":1,"method":"sessions.watch"}`+"\n") {
				t.Errorf("requests %s", data)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("no pings: %s", data)
		}
		time.Sleep(20 * time.Millisecond)
	}

	other := newEvents()
	StartServe(ServeOptions{
		Open: func() (*sshlink.Process, error) {
			return b.shell.StartPlain(`echo '{"type":"zeta_hello","protocol":99}'; cat`)
		},
		Message: func(string, []byte) {},
		Problem: other.problem,
	})
	other.waitFor(t, "the refusal", func() bool { return len(other.problems) == 1 })
	if !strings.Contains(other.problems[0], "协议对不上") {
		t.Errorf("problems %q", other.problems)
	}
}

func TestReconnectDelay(t *testing.T) {
	want := map[int]time.Duration{0: 0, 1: time.Second, 4: 8 * time.Second, 5: 15 * time.Second, 24: 30 * time.Second, 25: time.Minute}
	for attempt, d := range want {
		if got := ReconnectDelay(attempt); got != d {
			t.Errorf("attempt %d: %v", attempt, got)
		}
	}
}

func TestSessionsDecodeOddFields(t *testing.T) {
	u, err := ParseSessions([]byte(`{"type":"zeta_sessions","full":false,"upsert":[{"id":"a","file":"/f","cwd":null,"updated":5},{"id":"b","file":"/g","cwd":7},{"file":"/x"},{"id":"c","file":"/h","cwd":"/w/"}],"remove":["z"]}`), "r-1")
	if err != nil || len(u.Upsert) != 3 || u.Upsert[0].Cwd != "" || u.Upsert[1].Cwd != "7" || u.Upsert[2].Place() != "w" || u.Remove[0] != "z" || u.Upsert[0].Updated != 5 {
		t.Errorf("%+v %v", u, err)
	}
	if (Session{Cwd: ""}).Place() != "/" || (Session{Title: " ", Preview: "p"}).Label() != "p" {
		t.Error("place or label")
	}
}

func TestParseActivity(t *testing.T) {
	got, err := ParseActivity([]byte(`{"type":"conv.activity","sessions":{"s1":{"activity":"answering","tasks":2,"turn_end":5},"s2":{"turn_end":9}}}`))
	if err != nil || got["s1"] != (Activity{State: "answering", Tasks: 2, TurnEnd: 5}) || got["s2"] != (Activity{TurnEnd: 9}) {
		t.Errorf("%v %+v", err, got)
	}
	if got, err := ParseActivity([]byte(`{"type":"conv.activity","sessions":{}}`)); err != nil || got == nil || len(got) != 0 {
		t.Errorf("empty: %v %v", err, got)
	}
}
