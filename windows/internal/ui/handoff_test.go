package ui

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/egoist/mygo/ui"
)

func TestHandoffText(t *testing.T) {
	for _, c := range []struct {
		o       *sessionproto.Occupancy
		handing bool
		problem string
		title   string
		summary string
		button  string
		enabled bool
	}{
		{nil, false, "", "会话正在别处运行", "正在查询那边的状态", "接管会话", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true, App: "terminal"}, false, "",
			"会话正在本机终端里运行", "当前空闲。接管后那边会退出，会话转到 Zeta 继续。", "接管会话", true},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true, App: "zeta-dev", Device: "Xiaomi 17"}, false, "",
			"会话正在 Xiaomi 17 的 Zeta 测试版里运行", "接管后那边的对话会结束", "接管会话", true},
		{&sessionproto.Occupancy{Status: ptr("waiting"), WaitingFor: ptr("Bash"), HandsOver: true}, false, "",
			"会话正在别处运行", "正在等待确认：Bash。", "接管会话", true},
		{&sessionproto.Occupancy{Status: ptr("busy"), App: "terminal", Device: "work-pc"}, false, "",
			"会话正在 work-pc 的终端里运行", "正在回答，本轮结束后可以接管。", "等待对方空闲", false},
		{&sessionproto.Occupancy{Status: ptr("shell")}, false, "", "会话正在别处运行", "! 命令", "等待对方空闲", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true}, true, "", "会话正在别处运行", "当前空闲", "正在接管…", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true}, false, "接管失败：连不上", "会话正在别处运行", "接管失败：连不上", "接管会话", true},
	} {
		title, summary, button, enabled := handoffText(c.o, c.handing, c.problem)
		if title != c.title || !strings.Contains(summary, c.summary) || button != c.button || enabled != c.enabled {
			t.Errorf("%+v：%q %q %q %v", c.o, title, summary, button, enabled)
		}
	}
}

// 用例同手机版 ExternalSessionNoticeTest 的 occupantPlace。
func TestOccupantPlace(t *testing.T) {
	for _, c := range []struct{ app, device, want string }{
		{"terminal", "", "本机终端里"},
		{"terminal", "work-pc", "work-pc 的终端里"},
		{"terminal", "100.64.0.9", "100.64.0.9 的终端里"},
		{"zeta", "", "Zeta 里"},
		{"zeta", "DESKTOP-7", "DESKTOP-7 的 Zeta 里"},
		{"zeta-dev", "Xiaomi 17", "Xiaomi 17 的 Zeta 测试版里"},
		{"claude-vscode", "", "VS Code 里"},
		{"claude-vscode", "work-pc", "work-pc 的 VS Code 里"},
		{"claude-desktop", "", "Claude 桌面版里"},
		{"claude-vscode", "小米 17", "小米 17 的 VS Code 里"},
		{"sdk-ts", "work-pc", ""},
		{"", "", ""},
	} {
		if got := occupantPlace(c.app, c.device); got != c.want {
			t.Errorf("%q %q：%q，要 %q", c.app, c.device, got, c.want)
		}
	}
}

func TestEndConfirmSummarySaysWhere(t *testing.T) {
	ext := []hostlink.Session{{External: true}}
	if got := endConfirmSummary(ext, &sessionproto.Occupancy{App: "terminal", Device: "work-pc"}); !strings.HasPrefix(got, "它正在 work-pc 的终端里运行，结束会停掉那边的进程") {
		t.Errorf("%q", got)
	}
	if got := endConfirmSummary(ext, nil); !strings.HasPrefix(got, "它正在别处运行，") {
		t.Errorf("%q", got)
	}
	if got := endConfirmSummary(ext, &sessionproto.Occupancy{App: "sdk-py"}); !strings.HasPrefix(got, "它正在别处运行，") {
		t.Errorf("%q", got)
	}
}

func ptr(s string) *string { return &s }

// 卡片照 conv.state 的 occupant 说话，不自己去查；点「接管」才发 sessions.handoff。
func TestHandoffCardReplacesTheComposer(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.tt.Frame()
	session := f.hub.Chat().Conv.Session() // 录的对话报的会话号
	f.push(`"blocked":true,"occupant":null`)
	f.tt.Frame()
	f.see(t, "会话正在别处运行", "正在查询那边的状态。")
	f.push(`"occupant":{"status":"busy","waiting_for":null,"hands_over":false,"app":"terminal","device":"work-pc"}`)
	f.see(t, "会话正在 work-pc 的终端里运行", "正在回答，本轮结束后可以接管。", "等待对方空闲")
	f.push(`"occupant":{"status":"idle","waiting_for":null,"hands_over":true,"app":"zeta-dev","device":"Xiaomi 17"}`)
	f.see(t, "会话正在 Xiaomi 17 的 Zeta 测试版里运行", "当前空闲。接管后那边的对话会结束，会话转到这里继续。", "接管会话")
	if _, ok := f.tt.AccessByRole(ui.RoleTextField, "消息"); ok {
		t.Error("输入栏还在")
	}
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("卡片显示着时自己发了请求：%q", got)
	}
	f.click(t, "接管会话")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.handoff {"session":"`+session+`"}`) {
		t.Errorf("发出 %q", got)
	}
	// 核心解开 blocked：卡片收起
	f.push(`"blocked":false,"occupant":null`)
	f.tt.Frame()
	f.notSee(t, "会话正在别处运行")
	if _, ok := f.tt.AccessByRole(ui.RoleTextField, "消息"); !ok {
		t.Error("输入栏没回来")
	}
}

// 那边发了中断还不退：卡片上说原因。
func TestHandoffStuck(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.tt.Frame()
	session := f.hub.Chat().Conv.Session()
	f.devices.Handoffs = map[string]string{session: `{"handed":false,"status":"stuck"}`}
	f.push(`"blocked":true,"occupant":{"status":"idle","waiting_for":null,"hands_over":true}`)
	f.click(t, "接管会话")
	f.tt.Frame()
	f.see(t, "没有退出", "接管会话")
}
