package ui

import (
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/egoist/mygo/ui"
)

func TestHandoffText(t *testing.T) {
	for _, c := range []struct {
		o       *hub.Occupant
		handing bool
		problem string
		summary string
		button  string
		enabled bool
	}{
		{nil, false, "", "正在查询它的状态", "接管会话", false},
		{&hub.Occupant{Status: "idle", HandsOver: true}, false, "", "当前空闲", "接管会话", true},
		{&hub.Occupant{Status: "waiting", WaitingFor: "Bash", HandsOver: true}, false, "", "正在等待确认（Bash）", "接管会话", true},
		{&hub.Occupant{Status: "busy"}, false, "", "本轮结束后可以接管", "等待对方空闲", false},
		{&hub.Occupant{Status: "shell"}, false, "", "! 命令", "等待对方空闲", false},
		{&hub.Occupant{Status: "idle", HandsOver: true}, true, "", "当前空闲", "正在接管…", false},
		{&hub.Occupant{Status: "idle", HandsOver: true}, false, "接管失败：连不上", "接管失败：连不上", "接管会话", true},
	} {
		summary, button, enabled := handoffText(c.o, c.handing, c.problem)
		if !strings.Contains(summary, c.summary) || button != c.button || enabled != c.enabled {
			t.Errorf("%+v：%q %q %v", c.o, summary, button, enabled)
		}
	}
}

func TestHandoffCardReplacesTheComposer(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.tt.Frame()
	session := f.hub.Chat().Conv.Session() // 录的对话报的会话号
	f.devices.Occupants = map[string]string{session: `{"status":"idle","waiting_for":null,"hands_over":true}`}
	f.push(`"blocked":true`)
	f.tt.Frame()
	f.see(t, "会话正在别处运行", "当前空闲", "接管会话")
	if _, ok := f.tt.AccessByRole(ui.RoleTextField, "消息"); ok {
		t.Error("输入栏还在")
	}
	f.click(t, "接管会话")
	if got := f.devices.SessionCalls(); len(got) != 2 || !strings.Contains(got[1], `sessions.handoff {"session":"`+session+`"}`) {
		t.Errorf("发出 %q", got)
	}
	// 交出来了：等核心推来解开 blocked，按钮停在「正在接管…」
	f.tt.Frame()
	f.see(t, "正在接管…")
	// 核心解开 blocked：卡片收起
	f.push(`"blocked":false`)
	f.tt.Frame()
	f.notSee(t, "会话正在别处运行")
	if _, ok := f.tt.AccessByRole(ui.RoleTextField, "消息"); !ok {
		t.Error("输入栏没回来")
	}
}
