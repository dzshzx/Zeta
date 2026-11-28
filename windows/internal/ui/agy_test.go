package ui

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/uitest"
	"github.com/egoist/mygo/ui"
)

// 侧栏显示 agy 会话角标；未开始的新对话可在输入栏切换 Claude Code / Antigravity、选模型和超高（xhigh）思考强度，对话开始后隐藏工具胶囊。
func TestSidebarBadgeAndComposerHarnessSwitch(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: false, Upsert: []hostlink.Session{
		{ID: "s-agy", File: "/home/box/.gemini/antigravity/s-agy.pb", Title: "重构路由", Cwd: "/home/box/web", Harness: "agy", Updated: at(14)},
	}})
	f.tt.Frame()
	f.see(t, "重构路由", "agy")

	f.newChat(t)
	f.see(t, "Claude Code")
	f.click(t, "Claude Code")
	f.clickMenuItem(t, "Antigravity")
	if got := f.sent("conv.harness"); len(got) != 1 || !strings.Contains(got[0], `"value":"agy"`) {
		t.Fatalf("conv.harness: %v", got)
	}
	f.push(`"harness":"agy"`)
	f.see(t, "Antigravity")

	// 点开模型胶囊可看到并选择 Antigravity 模型
	f.click(t, "默认")
	f.clickMenuItem(t, "Gemini 3.1 Pro (High)")
	if got := f.sent("conv.model"); len(got) != 1 || !strings.Contains(got[0], `"value":"gemini-3.1-pro-high"`) {
		t.Fatalf("conv.model: %v", got)
	}
	f.push(`"model":"gemini-3.1-pro-high"`)
	f.see(t, "Gemini 3.1 Pro (High)")

	// 思考强度菜单支持 xhigh（「超高」）
	f.click(t, "思考：默认")
	f.clickMenuItem(t, "超高")
	if got := f.sent("conv.effort"); len(got) != 1 || !strings.Contains(got[0], `"value":"xhigh"`) {
		t.Fatalf("conv.effort: %v", got)
	}

	// 进程启动或进入已有会话后不再显示工具切换胶囊
	f.push(`"session":"s-agy","process":true`)
	f.notSee(t, "对话工具")
}

func mockAgyDeviceAnswer(c hubtest.Call, agyUpdated *bool) (json.RawMessage, error) {
	switch c.Method {
	case "system.claude":
		return json.RawMessage(`{"version":"2.1.330"}`), nil
	case "system.agy":
		return json.RawMessage(`{"version":"0.2.1"}`), nil
	case "system.agy_update":
		*agyUpdated = true
		return json.RawMessage(`{"ok":true,"before":"0.2.1","version":"0.2.2","output":"updated"}`), nil
	case "system.idle":
		return json.RawMessage(`{"minutes":0}`), nil
	case "config.defaults.read":
		return json.RawMessage(`{"model":"","permissionMode":"default","efforts":[],"effort":"","compactWindow":0}`), nil
	case "config.models.read":
		return json.RawMessage(`{"relay":"","model":"","models":[]}`), nil
	case "accounts.list":
		if c.Str("harness") == "agy" {
			return json.RawMessage(`{"current":{"name":"agy","email":"agy@example.com","subscription":"pro","current":true},` +
				`"accounts":[{"name":"agy","email":"agy@example.com","subscription":"pro","current":true}],"relays":[]}`), nil
		}
		return json.RawMessage(`{"accounts":[],"relays":[]}`), nil
	case "quota.get":
		return json.RawMessage(`{"snapshot":null,"fresh":true}`), nil
	}
	return json.RawMessage(`{}`), nil
}

// 设备页：可设默认对话工具，分段切换 Claude Code / Antigravity 查看对应 CLI 版本与更新、规则与指令及各自账号与额度。
func TestDevicePageSwitchesBetweenClaudeAndAntigravity(t *testing.T) {
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	posts := make(chan func(), 1000)
	d := hubtest.NewDevices()
	var agyUpdated bool
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) { return mockAgyDeviceAnswer(c, &agyUpdated) })
	}
	run := func(fn func()) { fn() }
	post := func(fn func()) { posts <- fn }
	h := hub.New(s, d, run, post)
	app := New(h, run, post)
	app.now = func() time.Time { return now }
	h.Start()
	f := &fixture{devices: d, hub: h, app: app, tt: ui.NewTester(app.View, 1280, 1900)}
	x := f.withHosts(t, "box")[0]

	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.see(t, "默认对话工具", "Claude Code", "Antigravity")

	// 先点分段控件切到 Antigravity 配置与账号，再把默认对话工具也改成 Antigravity
	f.click(t, "Antigravity")
	uitest.Until(t, f.tt, posts, "读到 Antigravity CLI 版本", 10*time.Second, func() bool { return f.shows("0.2.1") })
	f.see(t, "Antigravity CLI", "Antigravity 配置", "规则与指令", "工具权限")

	f.press(t, "默认对话工具")
	f.clickMenuItem(t, "Antigravity")
	if got := f.hub.DefaultHarness(x.ID); got != "agy" {
		t.Fatalf("DefaultHarness = %q", got)
	}

	f.click(t, "更新")
	uitest.Until(t, f.tt, posts, "更新完 Antigravity CLI", 10*time.Second, func() bool { return agyUpdated && f.shows("0.2.2") })

	cfgAgy := f.app.cfg
	f.click(t, "账号与额度")
	uitest.Until(t, f.tt, posts, "读到 Antigravity 账号", 10*time.Second, func() bool { return f.shows("agy@example.com") })
	if f.app.acct == nil || f.app.acct.Harness != "agy" {
		t.Fatalf("acct harness: %+v", f.app.acct)
	}
	f.click(t, "返回")
	if f.app.cfg != cfgAgy {
		t.Fatal("返回设备页时重建了 Antigravity 配置页")
	}
}
