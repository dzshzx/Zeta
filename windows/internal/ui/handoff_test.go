package ui

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/egoist/mygo/ui"
)

func TestHandoffText(t *testing.T) {
	for _, c := range []struct {
		o       *sessionproto.Occupancy
		handing bool
		problem string
		summary string
		button  string
		enabled bool
	}{
		{nil, false, "", "正在查询它的状态", "接管会话", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true}, false, "", "当前空闲", "接管会话", true},
		{&sessionproto.Occupancy{Status: ptr("waiting"), WaitingFor: ptr("Bash"), HandsOver: true}, false, "", "正在等待确认（Bash）", "接管会话", true},
		{&sessionproto.Occupancy{Status: ptr("busy")}, false, "", "本轮结束后可以接管", "等待对方空闲", false},
		{&sessionproto.Occupancy{Status: ptr("shell")}, false, "", "! 命令", "等待对方空闲", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true}, true, "", "当前空闲", "正在接管…", false},
		{&sessionproto.Occupancy{Status: ptr("idle"), HandsOver: true}, false, "接管失败：连不上", "接管失败：连不上", "接管会话", true},
	} {
		summary, button, enabled := handoffText(c.o, c.handing, c.problem)
		if !strings.Contains(summary, c.summary) || button != c.button || enabled != c.enabled {
			t.Errorf("%+v：%q %q %v", c.o, summary, button, enabled)
		}
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
	f.see(t, "会话正在别处运行", "正在查询它的状态")
	f.push(`"occupant":{"status":"busy","waiting_for":null,"hands_over":false}`)
	f.see(t, "本轮结束后可以接管", "等待对方空闲")
	f.push(`"occupant":{"status":"idle","waiting_for":null,"hands_over":true}`)
	f.see(t, "当前空闲", "接管会话")
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
