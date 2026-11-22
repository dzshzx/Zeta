package ui

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// 整条链路：设置页经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，账号一行读一次账号列表
// （serve 跑按内容哈希装上去的 claude-switch），进设备页读一次 Claude Code 的新对话默认（那边 ~/.claude/settings.json）。
func TestSettingsThroughServe(t *testing.T) {
	helper, err := host.LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		t.Fatal(err)
	}
	switcher, err := host.LoadProgram(hostlink.ClaudeSwitch)
	if err != nil {
		t.Fatal(err)
	}
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	if err := os.MkdirAll(filepath.Join(server.Home, ".claude"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(server.Home, ".claude", "settings.json"), []byte(`{"model":"sonnet"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	x := sshlink.Host{ID: "r-e2e", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetHosts([]sshlink.Host{x}); err != nil {
		t.Fatal(err)
	}
	devices := noSSHConfig{&host.Devices{Links: sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute), Helper: helper, Switch: switcher}}
	posts := make(chan func(), 1000)
	run := func(f func()) { go f() }
	post := func(f func()) { posts <- f }
	h := hub.New(s, devices, run, post)
	app := New(h, run, post)
	f := &fixture{hub: h, app: app, tt: ui.NewTester(app.View, 1280, 1800)}
	t.Cleanup(func() { stopCore(server.Home, helper) })
	until := func(what string, done func() bool) {
		t.Helper()
		deadline := time.After(90 * time.Second)
		for {
			f.tt.Frame()
			if done() {
				return
			}
			select {
			case fn := <-posts:
				fn()
			case <-time.After(20 * time.Millisecond):
			case <-deadline:
				t.Fatalf("90 秒内没等到%s；界面上：%q", what, f.tt.Texts())
			}
		}
	}

	f.click(t, "设置")
	r := h.Remote(x.ID)
	until("读到账号", func() bool { _, ok := r.Accounts.State(); return ok || r.Accounts.Problem() != "" })
	if p := r.Accounts.Problem(); p != "" {
		t.Fatalf("账号没读成：%s", p)
	}
	f.see(t, "账号与额度", "未登录") // 临时家目录里没登录过
	if _, err := os.Stat(filepath.Join(server.Home, switcher.RelativePath(host.LinuxName))); err != nil {
		t.Fatalf("claude-switch 没装到主机上：%v", err)
	}

	f.clickButton(t, x.Name(), 0)
	until("读到新对话默认", func() bool {
		return f.shows("Sonnet") || strings.Contains(strings.Join(f.tt.Texts(), "\n"), "读取失败")
	})
	f.see(t, "Sonnet", "新对话默认", "Claude Code 配置")
	f.notSee(t, "还没读到 Claude Code 的设置")
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
		_ = syscall.Kill(pid, syscall.SIGTERM)
	}
}
