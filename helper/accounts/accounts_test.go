package accounts

// 在本机一个临时 HOME 里真跑 claude-switch（helper/go.mod 里 tool 一行的那个，go tool -n 给出编好的程序）。登录状态是
// 假的：邮箱用 example.com，令牌是随手写的字符串，不碰任何人真实的 ~/.claude。冒充 Claude Code 进程的是本测试程序改名
// 的一份（见 TestMain）。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// sleeper 设为 1 时本测试程序只睡两分钟：spawn 拿它冒充 Claude Code 进程（进程名取文件名）。
const sleeper = "ZETA_ACCOUNTS_TEST_SLEEPER"

func TestMain(m *testing.M) {
	if os.Getenv(sleeper) == "1" {
		time.Sleep(2 * time.Minute)
		os.Exit(0)
	}
	os.Exit(m.Run())
}

// switchProgram 是编好的 claude-switch，整个测试只问一次 go。
var switchProgram = sync.OnceValues(func() (string, error) {
	// 编哪一版由 helper 的 go.mod、go.sum 定；子进程读它们，go test 的缓存看不见，这里先读一遍
	for _, f := range []string{"../go.mod", "../go.sum"} {
		if _, err := os.ReadFile(f); err != nil {
			return "", err
		}
	}
	out, err := exec.Command("go", "tool", "-n", "claude-switch").Output()
	if err != nil {
		return "", fmt.Errorf("go tool -n claude-switch：%w", err)
	}
	return strings.TrimSpace(string(out)), nil
})

// testHost 是临时 HOME 上的一台「主机」。
type testHost struct {
	t    *testing.T
	home string
}

func newHost(t *testing.T) *testHost {
	t.Helper()
	if _, err := exec.LookPath("go"); err != nil {
		if os.Getenv("CI") != "" {
			t.Skip("缺 go，CI 里跳过")
		}
		t.Fatal("缺 go：要用它编 claude-switch")
	}
	home, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	h := &testHost{t: t, home: home}
	src, err := switchProgram()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(src)
	if err != nil {
		t.Fatal(err)
	}
	h.writeBytes(".cache/zeta/bin/claude-switch-test", data, 0o755)
	return h
}

func (h *testHost) host() *Host {
	return &Host{Home: h.home, Package: "zeta", Program: filepath.Join(h.home, ".cache/zeta/bin/claude-switch-test"), Wait: time.Second}
}

// login 写一份假的登录：~/.claude.json 里的账号身份，凭据里假的令牌和订阅类型。
func (h *testHost) login(uuid, email, subscription string) {
	h.t.Helper()
	config := map[string]any{"theme": "dark"}
	if data, err := os.ReadFile(filepath.Join(h.home, ".claude.json")); err == nil {
		_ = json.Unmarshal(data, &config)
	}
	config["oauthAccount"] = map[string]any{"accountUuid": uuid, "emailAddress": email, "organizationName": "Org " + uuid}
	h.write(".claude.json", config)
	h.write(".claude/.credentials.json", map[string]any{"claudeAiOauth": map[string]any{
		"accessToken": "fake-access-" + uuid, "refreshToken": "fake-refresh-" + uuid,
		"subscriptionType": subscription, "refreshTokenExpiresAt": 1_900_000_000_000,
	}})
}

// setPlan 把在用的登录的套餐展示名（Claude Code 写进 ~/.claude.json 的 oauthAccount.planDisplayName）设成 plan；空串是删掉。
func (h *testHost) setPlan(plan string) {
	h.t.Helper()
	var config map[string]any
	data, err := os.ReadFile(filepath.Join(h.home, ".claude.json"))
	if err != nil || json.Unmarshal(data, &config) != nil {
		h.t.Fatalf("读不了 .claude.json：%v", err)
	}
	account := config["oauthAccount"].(map[string]any)
	if plan == "" {
		delete(account, "planDisplayName")
	} else {
		account["planDisplayName"] = plan
	}
	h.write(".claude.json", config)
}

// liveUUID 是 ~/.claude.json 里在用的登录编号（假数据）。
func (h *testHost) liveUUID() string {
	h.t.Helper()
	in, err := QuotaStore{Home: h.home}.login()
	if err != nil {
		h.t.Fatal(err)
	}
	return in.UUID
}

func (h *testHost) write(rel string, v any) {
	h.t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		h.t.Fatal(err)
	}
	h.writeBytes(rel, data, 0o600)
}

func (h *testHost) writeBytes(rel string, data []byte, mode os.FileMode) {
	h.t.Helper()
	path := filepath.Join(h.home, rel)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		h.t.Fatal(err)
	}
	if err := os.WriteFile(path, data, mode); err != nil {
		h.t.Fatal(err)
	}
}

// spawn 起一个睡着的进程，名字是 name，带着临时 HOME 和 env；测试结束按记下的 PID 结束它。
func (h *testHost) spawn(name string, env ...string) int {
	h.t.Helper()
	self, err := os.Executable()
	if err != nil {
		h.t.Fatal(err)
	}
	data, err := os.ReadFile(self)
	if err != nil {
		h.t.Fatal(err)
	}
	bin := filepath.Join(h.t.TempDir(), name)
	if err := os.WriteFile(bin, data, 0o755); err != nil {
		h.t.Fatal(err)
	}
	cmd := exec.Command(bin)
	cmd.Env = append([]string{"HOME=" + h.home, "PATH=" + os.Getenv("PATH"), sleeper + "=1"}, env...)
	if err := cmd.Start(); err != nil {
		h.t.Fatal(err)
	}
	h.t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})
	return cmd.Process.Pid
}

// register 在 ~/.claude/sessions 里登记进程 pid，同 Claude Code 自己登记的样子。
func (h *testHost) register(pid int, status string) {
	h.t.Helper()
	start, ok := procStart(pid)
	if !ok {
		h.t.Fatalf("进程 %d 不在了", pid)
	}
	h.write(fmt.Sprintf(".claude/sessions/%d.json", pid), map[string]any{"pid": pid, "procStart": start, "status": status, "kind": "interactive"})
}

func ctx(t *testing.T) context.Context {
	c, cancel := context.WithTimeout(context.Background(), time.Minute)
	t.Cleanup(cancel)
	return c
}

func names(accounts []Account) []string {
	var out []string
	for _, a := range accounts {
		out = append(out, a.Name)
	}
	return out
}

// twoAccounts 摆好两个号：a 是 claude（在用），b 是 claude-b（有存档、记过）。
func twoAccounts(t *testing.T, h *testHost, host *Host) {
	t.Helper()
	h.login("uuid-a", "a@example.com", "max")
	if name, err := host.PrepareLogin(ctx(t)); err != nil || name != "claude-b" {
		t.Fatalf("准备登录：%q %v", name, err)
	}
	st, err := host.List(ctx(t))
	if err != nil || st.Current != nil || !slices.Equal(names(st.Accounts), []string{"claude"}) {
		t.Fatalf("准备好以后应是未登录、claude 有存档：%+v %v", st, err)
	}
	h.login("uuid-b", "b@example.com", "pro")
	// 新登录的号在下一次换号时才记进 claude-switch 的记录
	if err := host.Switch(ctx(t), "claude"); err != nil {
		t.Fatal(err)
	}
}

// 账号的套餐展示名取 claude-switch 报的 plan（Max 20x 现在叫 Ultra，订阅类型仍是 max），没有时为空。
func TestListReportsPlanName(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	st, err := host.List(ctx(t))
	if err != nil || st.Current == nil || st.Current.Plan != "" {
		t.Fatalf("资料里没有套餐名时不报：%+v %v", st.Current, err)
	}
	h.setPlan("Ultra")
	st, err = host.List(ctx(t))
	if err != nil || st.Current == nil || st.Current.Plan != "Ultra" || st.Current.Subscription != "max" {
		t.Fatalf("套餐名 Ultra、订阅类型 max：%+v %v", st.Current, err)
	}
}

func TestListAndSwitch(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	st, err := host.List(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	if st.Current == nil || st.Current.Name != "claude" || st.Current.Title() != "a@example.com" || st.Current.Subscription != "max" {
		t.Fatalf("在用的：%+v", st.Current)
	}
	if st.Current.Organization != "Org uuid-a" || st.Current.RefreshExpiresAt != 1_900_000_000_000 || st.Blocker != "" {
		t.Fatalf("%+v", st)
	}

	twoAccounts(t, h, host)
	if h.liveUUID() != "uuid-a" {
		t.Fatalf("换回以后在用的是 %s", h.liveUUID())
	}
	st, _ = host.List(ctx(t))
	if got := names(st.Accounts); !slices.Equal(got, []string{"claude", "claude-b"}) {
		t.Fatalf("在用的排前面：%q", got)
	}
	if err := host.Switch(ctx(t), "claude-b"); err != nil || h.liveUUID() != "uuid-b" {
		t.Fatalf("换到 claude-b：%v，在用 %s", err, h.liveUUID())
	}
	if err := host.Switch(ctx(t), "nobody"); !errors.Is(err, ErrMissing) {
		t.Fatalf("换到没有的号：%v", err)
	}
}

func TestLoggedOut(t *testing.T) {
	h := newHost(t)
	st, err := h.host().List(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	if st.Current != nil || len(st.Accounts) != 0 {
		t.Fatalf("没登录时：%+v", st)
	}
	if name, err := h.host().PrepareLogin(ctx(t)); err != nil || name != "" {
		t.Fatalf("没登录时准备登录什么都不做：%q %v", name, err)
	}
}

// 在终端里 /login 了一个没记过的号：换号被拒，记下以后就能换。
func TestSaveUnknownLogin(t *testing.T) {
	h := newHost(t)
	host := h.host()
	twoAccounts(t, h, host)
	h.login("uuid-x", "x@example.com", "pro")

	st, err := host.List(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	if st.Current == nil || st.Current.Name != "" || st.Current.Title() != "x@example.com" {
		t.Fatalf("认不出的登录：%+v", st.Current)
	}
	err = host.Switch(ctx(t), "claude-b")
	var busy *BusyError
	if err == nil || errors.As(err, &busy) || !strings.Contains(err.Error(), "不在已记过的账号里") {
		t.Fatalf("认不出时换号：%v", err)
	}

	name, err := host.Save(ctx(t))
	if err != nil || name != "claude-c" {
		t.Fatalf("记下：%q %v", name, err)
	}
	if again, err := host.Save(ctx(t)); err != nil || again != "claude-c" {
		t.Fatalf("记过的不再记：%q %v", again, err)
	}
	if err := host.Switch(ctx(t), "claude-b"); err != nil {
		t.Fatal(err)
	}
	st, _ = host.List(ctx(t))
	if got := names(st.Accounts); !slices.Equal(got, []string{"claude-b", "claude-c"}) {
		t.Fatalf("换到 claude-b 以后：%q", got)
	}
}

func TestRemove(t *testing.T) {
	h := newHost(t)
	host := h.host()
	twoAccounts(t, h, host)
	if err := host.Remove(ctx(t), "claude"); err == nil || !strings.Contains(err.Error(), "在用") {
		t.Fatalf("删在用的号：%v", err)
	}
	if err := host.Remove(ctx(t), "claude-b"); err != nil {
		t.Fatal(err)
	}
	st, _ := host.List(ctx(t))
	if got := names(st.Accounts); !slices.Equal(got, []string{"claude"}) {
		t.Fatalf("删掉以后：%q", got)
	}
	if err := host.Remove(ctx(t), "claude-b"); !errors.Is(err, ErrMissing) {
		t.Fatalf("删没有的号：%v", err)
	}
}

// 同一配置目录上有没登记的 Claude Code 在跑（一个改名叫 claude 的进程）：claude-switch 不换，说它的原话。
func TestSwitchRefusedWhileClaudeRuns(t *testing.T) {
	h := newHost(t)
	host := h.host()
	twoAccounts(t, h, host)
	h.spawn("claude")
	err := host.Switch(ctx(t), "claude-b")
	var busy *BusyError
	if !errors.As(err, &busy) || !strings.Contains(err.Error(), busyMark) {
		t.Fatalf("有进程时换号：%v", err)
	}
	if h.liveUUID() != "uuid-a" {
		t.Fatal("被拒时不该动登录")
	}
}

// 登记了的进程按身上的 ZETA_HOST 分包：别的包和终端里的挡着，说清是谁；本包的不算，换号前先等它退出。
func TestProcessOwnersAndBlocker(t *testing.T) {
	h := newHost(t)
	host := h.host()
	twoAccounts(t, h, host)
	dev := h.spawn("claude", "ZETA_HOST=zeta-dev/host-1")
	mine := h.spawn("claude", "ZETA_HOST=zeta/host-2")
	term := h.spawn("other")
	h.register(dev, "busy")
	h.register(mine, "idle")
	h.register(term, "idle")
	// 启动时刻对不上的登记（pid 被复用了）不算
	h.write(".claude/sessions/stale.json", map[string]any{"pid": term, "procStart": "1", "status": "busy"})

	st, err := host.List(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	owners := map[int]string{}
	for _, p := range st.Procs {
		owners[p.PID] = p.Owner + "/" + p.Status
	}
	want := map[int]string{dev: "zeta-dev/busy", mine: "zeta/idle", term: "/idle"}
	if len(owners) != 3 {
		t.Fatalf("进程 %v", owners)
	}
	for pid, w := range want {
		if owners[pid] != w {
			t.Fatalf("进程 %d 是 %q，要 %q", pid, owners[pid], w)
		}
	}
	const blocker = "另一个 Zeta（测试版）打开了 1 个对话，其中 1 个正在进行，请先在对应应用中结束对话或关闭应用；其他位置还运行着 1 个 Claude Code（如终端中的 claude），请先退出。"
	if st.Blocker != blocker {
		t.Fatalf("挡着的：%q", st.Blocker)
	}
	// 别人的进程不会自己退，不等它们，直接交给 claude-switch 拒绝
	host.Wait = time.Minute
	start := time.Now()
	err = host.Switch(ctx(t), "claude-b")
	if err == nil || err.Error() != blocker {
		t.Fatalf("被挡着时换号：%v", err)
	}
	if d := time.Since(start); d > 30*time.Second {
		t.Fatalf("有别人的进程时不该等：等了 %v", d)
	}
}

// 只剩本包自己的对话（调用方刚叫停）：等它退出再换，换得成。
func TestSwitchWaitsForOwnConversations(t *testing.T) {
	h := newHost(t)
	host := h.host()
	host.Wait = time.Minute
	twoAccounts(t, h, host)
	mine := h.spawn("claude", "ZETA_HOST=zeta/host-1")
	h.register(mine, "idle")
	var killed atomic.Bool
	go func() {
		time.Sleep(300 * time.Millisecond)
		killed.Store(true)
		// 杀了还要收尸：僵尸进程的 /proc/<pid>/stat 还在，会被当成活着
		if p, err := os.FindProcess(mine); err == nil {
			_ = p.Kill()
			_, _ = p.Wait()
		}
	}()
	if err := host.Switch(ctx(t), "claude-b"); err != nil {
		t.Fatalf("本包的对话退出后换号：%v", err)
	}
	if !killed.Load() {
		t.Fatal("本包的对话还开着就去换号了")
	}
	if h.liveUUID() != "uuid-b" {
		t.Fatal("没换成")
	}
}

func TestBlockerIgnoresOwnPackage(t *testing.T) {
	if got := Blocker([]Proc{{PID: 1, Owner: "zeta", Status: "busy"}}, "zeta"); got.Key != "" {
		t.Fatalf("本包的进程不算：%q", got)
	}
	got := Blocker([]Proc{{PID: 1, Owner: "zeta", Status: "idle"}, {PID: 2, Owner: "zeta", Status: "idle"}}, "zeta-dev")
	if got.String() != "另一个 Zeta（正式版）打开了 2 个对话，请先在对应应用中结束对话或关闭应用。" {
		t.Fatalf("%q", got.String())
	}
	// 按客户端的语言说：包的叫法、个数的单复数跟着换
	got = Blocker([]Proc{{PID: 1, Owner: "zeta", Status: "busy"}, {PID: 2, Owner: "zeta-dev", Status: "idle"}, {PID: 3, Status: "idle"}}, "mine")
	if en := got.In("en"); en != "Another Zeta (release, dev) has 2 conversations open, 1 in progress. End them there or close it. "+
		"1 Claude Code is also open elsewhere (such as claude in a terminal). Exit them first." {
		t.Fatalf("英文：%q", en)
	}
	if zh := got.String(); zh != "另一个 Zeta（正式版、测试版）打开了 2 个对话，其中 1 个正在进行，请先在对应应用中结束对话或关闭应用；"+
		"其他位置还运行着 1 个 Claude Code（如终端中的 claude），请先退出。" {
		t.Fatalf("中文：%q", zh)
	}
	if e := (&BusyError{Own: true, Message: "x"}); e.Error() != "当前应用的对话进程尚未退出" {
		t.Fatalf("只剩自己的：%q", e.Error())
	}
}

func TestFreeName(t *testing.T) {
	cases := []struct {
		taken []string
		want  string
	}{
		{nil, "claude"},
		{[]string{"claude-b"}, "claude"},
		{[]string{"claude", "claude-b"}, "claude-c"},
	}
	for _, c := range cases {
		taken := map[string]bool{}
		for _, n := range c.taken {
			taken[n] = true
		}
		if got := FreeName(taken); got != c.want {
			t.Errorf("FreeName(%v) = %q，要 %q", c.taken, got, c.want)
		}
	}
	all := map[string]bool{"claude": true}
	for c := 'b'; c <= 'z'; c++ {
		all["claude-"+string(c)] = true
	}
	if got := FreeName(all); got != "claude-27" {
		t.Errorf("字母用完以后：%q", got)
	}
}

// 非账号目标的名字不能再给新账号用；刚 add、还没登录的号不列。
func TestTargetNamesAreTaken(t *testing.T) {
	st, err := parseStatus([]byte(`{"current":"claude","live":{"name":"claude","current":true,"email":"a@example.com"},
		"accounts":[{"name":"claude","current":true},{"name":"claude-c","current":false,"parked":false}],
		"targets":[{"name":"claude-b","settings":"/x/claude-b.json"}],"pending":false,"running":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	if got := FreeName(st.taken); got != "claude-d" {
		t.Fatalf("新名字 %q", got)
	}
	if got := names(st.Accounts); !slices.Equal(got, []string{"claude"}) {
		t.Fatalf("列出的 %q", got)
	}
	if _, err := parseStatus([]byte("not json")); err == nil {
		t.Fatal("读不懂时要报错")
	}
}

func TestFindProgram(t *testing.T) {
	h := newHost(t)
	got, err := Find(h.home, "zeta", "")
	if err != nil || got != filepath.Join(h.home, ".cache/zeta/bin/claude-switch-test") {
		t.Fatalf("%q %v", got, err)
	}
	if _, err := Find(h.home, "zeta-dev", ""); !errors.Is(err, ErrNoProgram) {
		t.Fatalf("别的包没装：%v", err)
	}
	if _, err := Find(h.home, "zeta", ".cache/zeta/bin/claude-switch-other"); !errors.Is(err, ErrNoProgram) {
		t.Fatalf("指定的版本没装：%v", err)
	}
	if err := (&Host{Home: h.home}).Switch(ctx(t), "claude"); !errors.Is(err, ErrNoProgram) {
		t.Fatalf("没有程序时：%v", err)
	}
}

// 换号、准备登录前（claude-switch 改登录的每一处）都先叫 EndIdle：本包闲着的托管进程平常留给看门狗，开着时 claude-switch 不换。
func TestChangesEndIdleHostedFirst(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	calls := 0
	host.EndIdle = func() { calls++ }
	if _, err := host.PrepareLogin(ctx(t)); err != nil || calls == 0 {
		t.Fatalf("准备登录：%v，叫了 %d 次", err, calls)
	}
	h.login("uuid-b", "b@example.com", "pro")
	before := calls
	if err := host.Switch(ctx(t), "claude"); err != nil || calls != before+1 {
		t.Fatalf("换号：%v，叫了 %d 次", err, calls-before)
	}
}

// 本包闲着的进程被 EndIdle 叫停后，Claude Code 先删 ~/.claude/sessions 里的登记，进程还要再活约半秒（2.1.330 实测：
// end_session 后约 25 毫秒删登记，约 540 毫秒才退出）；claude-switch 按 /proc 认进程，所以要等进程真退出再交给它。
// 换账号和从中转换回账号都一样。
func TestSwitchWaitsForOwnProcessAfterRegistrationGone(t *testing.T) {
	h := newHost(t)
	host := h.host()
	host.Wait = time.Minute
	twoAccounts(t, h, host)
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://relay.example.com", Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	for i, target := range []string{"gw", "claude", "claude-b"} {
		mine := h.spawn("claude", fmt.Sprintf("ZETA_HOST=zeta/host-%d", i))
		h.register(mine, "idle")
		var killed atomic.Bool
		host.EndIdle = func() {
			// 同真 Claude Code 收到 end_session：登记马上没了，进程过一会儿才退出
			if err := os.Remove(filepath.Join(h.home, fmt.Sprintf(".claude/sessions/%d.json", mine))); err != nil {
				t.Error(err)
			}
			go func() {
				time.Sleep(300 * time.Millisecond)
				killed.Store(true)
				if p, err := os.FindProcess(mine); err == nil {
					_ = p.Kill()
					_, _ = p.Wait()
				}
			}()
		}
		if err := host.Switch(ctx(t), target); err != nil {
			t.Fatalf("换到 %s：%v", target, err)
		}
		if !killed.Load() {
			t.Fatalf("换到 %s 时本包的进程还没退出就去换了", target)
		}
	}
}
