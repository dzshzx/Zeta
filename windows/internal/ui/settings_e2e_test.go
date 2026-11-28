package ui

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/host"
	"github.com/dzshzx/Zeta/hub/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/uitest"
	"github.com/egoist/mygo/ui"
)

// 整条链路：设置页经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，账号一行读一次账号列表
// （serve 跑按内容哈希装上去的 claude-switch），进设备页读一次 Claude Code 的新对话默认（那边 ~/.claude/settings.json）。
func TestSettingsThroughServe(t *testing.T) {
	f := newCoreFixture(t, 1800)
	home := f.server.Home
	if err := os.MkdirAll(filepath.Join(home, ".claude"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".claude", "settings.json"), []byte(`{"model":"sonnet"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	f.click(t, "设置")
	r := f.hub.Remote(f.host.ID)
	f.until(t, "读到账号", func() bool { _, ok := r.Accounts.State(); return ok || r.Accounts.Problem() != "" })
	if p := r.Accounts.Problem(); p != "" {
		t.Fatalf("账号没读成：%s", p)
	}
	f.app.device = f.host.ID
	f.app.open(pageDevice)
	f.app.openAccounts()
	f.until(t, "账号页", func() bool { return f.shows("尚未登录") }) // 临时家目录里没登录过
	if _, err := os.Stat(filepath.Join(home, loadProgram(t, hostlink.ClaudeSwitch).RelativePath(host.LinuxName))); err != nil {
		t.Fatalf("claude-switch 没装到主机上：%v", err)
	}

	// 连上时招呼里的主机名记下了：设备叫它
	x, _ := f.hub.Host(f.host.ID)
	if hostname, _ := os.Hostname(); x.Name() != hostname {
		t.Errorf("设备的名字 %q，主机名 %q", x.Name(), hostname)
	}
	f.clickButton(t, x.Name(), 0)
	f.until(t, "读到新对话默认", func() bool {
		return f.shows("Sonnet") || strings.Contains(strings.Join(f.tt.Texts(), "\n"), "读取失败")
	})
	f.see(t, "Sonnet", "新对话默认", "Claude Code 配置")
	f.notSee(t, "尚未读取到 Claude Code 的设置")
}

// coreFixture 是整条链路的测试窗口：一台经 SSH 连的设备（sshtest，命令在临时家目录里真跑 bash），上面按内容哈希装
// zeta-helper 和 claude-switch。中枢和界面交回主线程的函数排进 posts，until 在测试的 goroutine 上一个个跑。
type coreFixture struct {
	*fixture
	server *sshtest.Server
	host   sshlink.Host
	posts  chan func()
}

// newCoreFixture 建 1280×height 的窗口和它连的设备；还不连，测试点了什么才连。
func newCoreFixture(t *testing.T, height int) *coreFixture {
	t.Helper()
	helper := loadProgram(t, hostlink.ZetaHelper)
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	x := sshlink.Host{ID: "r-e2e", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetHosts([]sshlink.Host{x}); err != nil {
		t.Fatal(err)
	}
	devices := noSSHConfig{&host.Devices{Links: sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute),
		Helper: helper, Switch: loadProgram(t, hostlink.ClaudeSwitch)}}
	posts := make(chan func(), 1000)
	run := func(f func()) { go f() }
	post := func(f func()) { posts <- f }
	h := hub.New(s, devices, run, post)
	app := New(h, run, post)
	t.Cleanup(func() { stopCore(server.Home, helper) })
	return &coreFixture{fixture: &fixture{hub: h, app: app, tt: ui.NewTester(app.View, 1280, height)},
		server: server, host: x, posts: posts}
}

func loadProgram(t *testing.T, name string) *hostlink.Program {
	t.Helper()
	program, err := host.LoadProgram(name)
	if err != nil {
		t.Fatal(err)
	}
	return program
}

// until 跑交回主线程的函数、画帧，直到 done；90 秒没等到就失败。
func (f *coreFixture) until(t *testing.T, what string, done func() bool) {
	t.Helper()
	uitest.Until(t, f.tt, f.posts, what, 90*time.Second, done)
}

// fakeClaude 把假 Claude（tools/fake-claude）装成设备上的 claude，按 scenario 一行一轮回话。
func (f *coreFixture) fakeClaude(t *testing.T, scenario string) {
	t.Helper()
	for _, tool := range []string{"bash", "jq"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("假 Claude 要 %s：%v", tool, err)
		}
	}
	fake, err := os.ReadFile("../../../tools/fake-claude/fake-claude.sh")
	if err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(f.server.Home, ".local/bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "claude"), fake, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "scenario.jsonl"), []byte(scenario), 0o644); err != nil {
		t.Fatal(err)
	}
}

// noSSHConfig 是测试用的真设备：这台电脑的 SSH 配置和私钥不去读（设备页打开时会列私钥），当作没有。
type noSSHConfig struct{ *host.Devices }

func (noSSHConfig) Keys() []string                 { return nil }
func (noSSHConfig) ConfigHosts() ([]string, error) { return nil, nil }

// stopCore 停掉测试里拉起的核心：它自己记在锁文件里的进程号（同 hub 的端到端测试）。
func stopCore(home string, program *hostlink.Program) {
	data, err := os.ReadFile(filepath.Join(home, ".cache", host.LinuxName, "core", program.Version+".lock"))
	if err != nil {
		return
	}
	if pid, _ := strconv.Atoi(strings.TrimSpace(string(data))); pid > 0 {
		if p, err := os.FindProcess(pid); err == nil {
			_ = p.Signal(syscall.SIGTERM)
		}
	}
}
