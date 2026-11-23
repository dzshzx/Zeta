package ui

import (
	"reflect"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/prompt"
)

// 窗口不在前台时：开着的对话来了确认提醒一次（同一个请求再推不再提醒）；别的会话由核心报的忙闲提醒，第一次见的不算；
// 窗口在前台时都不发。点别的会话的通知打开那个会话。
func TestNotifications(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.listSample()
	f.push(`"session":"s9","title":"开着的对话","busy":true`)
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}, "s9": {State: "answering"}})
	f.tt.Frame()

	// 在前台：来了确认也不发
	f.push(`"prompts":[{"kind":"permission","request_id":"p0","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`)
	f.push(`"prompts":[]`)
	f.win.front = false
	f.push(`"prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`)
	f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`)
	if len(f.win.notes) != 1 || !strings.HasPrefix(f.win.notes[0], "开着的对话：需要你确认：") {
		t.Fatalf("开着的对话的确认：%q", f.win.notes)
	}

	// 别的会话：开始等回应、答完了一轮；开着的 s9 的忙闲不经这里提醒
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}, "s9": {State: "waiting"}})
	f.tt.Frame()
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 5}, "s9": {State: "idle", TurnEnd: 5}})
	f.tt.Frame()
	// 新冒出来的会话第一次见，不提醒
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 5}, "s2": {State: "waiting"}})
	f.tt.Frame()
	want := []string{"改 README 的构建命令：需要你回应", "改 README 的构建命令：这一轮结束了"}
	if got := f.win.notes[1:]; !reflect.DeepEqual(got, want) {
		t.Fatalf("别的会话：%q", got)
	}

	// 点通知打开 s1
	f.win.opens[len(f.win.opens)-1]()
	f.tt.Frame()
	if c := f.hub.Chat(); c == nil || c.Session.ID != "s1" || c.Session.File == "" {
		t.Fatalf("点通知后：%+v", c)
	}

	// 回到前台：别的会话答完也不发
	f.win.front = true
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 5}, "s2": {State: "idle", TurnEnd: 9}})
	f.tt.Frame()
	if len(f.win.notes) != 3 {
		t.Fatalf("前台：%q", f.win.notes)
	}
}

// 等回应的通知在请求处理掉以后撤掉：开着的对话里请求不在等着的里面了（窗口回到前台也撤），别的会话核心报不再等、或核心放手了；
// 答完了一轮的通知换掉等回应的那条，不另撤，窗口在前台不发时撤掉。换了对话以后，原来那个的通知由核心报的忙闲来撤。
func TestWaitingNotificationsWithdrawn(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.listSample()
	f.push(`"session":"s9","title":"开着的对话","busy":true`)
	idle := hostlink.Activity{State: "idle"}
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": idle, "s2": idle, "s9": {State: "answering"}})
	f.tt.Frame()
	activity := func(a map[string]hostlink.Activity) {
		t.Helper()
		f.devices.PushActivity(f.id, a)
		f.tt.Frame()
	}
	withdrawn := func(step string, want ...string) {
		t.Helper()
		if !reflect.DeepEqual(f.win.withdrawn, want) {
			t.Fatalf("%s：撤了 %q，要 %q", step, f.win.withdrawn, want)
		}
		f.win.withdrawn = nil
	}
	s1, s2, s9 := noteID(f.id, "s1"), noteID(f.id, "s2"), noteID(f.id, "s9")
	permission := func(id string) string {
		return `"prompts":[{"kind":"permission","request_id":"` + id + `","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`
	}

	// 开着的对话：别处答了，窗口回到了前台也撤
	f.win.front = false
	f.push(permission("p1"))
	f.win.front = true
	f.push(`"prompts":[]`)
	withdrawn("开着的对话答了", s9)
	// 在前台来的请求没发通知，答了也没什么可撤
	f.push(permission("p2"))
	f.push(`"prompts":[]`)
	withdrawn("前台来的请求")
	// 一个答了接着来下一个：撤掉旧的、发新的
	f.win.front = false
	f.push(permission("p3"))
	f.push(permission("p4"))
	withdrawn("换了请求", s9)
	if got := len(f.win.notes); got != 3 { // p1、p3、p4
		t.Fatalf("开着的对话的通知：%q", f.win.notes)
	}

	// 别的会话：开始等、不等了（别处答了）
	activity(map[string]hostlink.Activity{"s1": {State: "waiting"}, "s2": idle, "s9": {State: "waiting"}})
	activity(map[string]hostlink.Activity{"s1": {State: "answering"}, "s2": idle, "s9": {State: "waiting"}})
	withdrawn("别的会话答了", s1)
	// 等着时核心放手了（对话结束）
	activity(map[string]hostlink.Activity{"s1": {State: "answering"}, "s2": {State: "waiting"}, "s9": {State: "waiting"}})
	activity(map[string]hostlink.Activity{"s1": {State: "answering"}, "s9": {State: "waiting"}})
	withdrawn("别的会话结束", s2)
	// 答完了一轮：通知换掉等回应的那条，不另撤；之后再报不等了也不撤（那条已经不是等回应的了）
	activity(map[string]hostlink.Activity{"s1": {State: "waiting"}, "s9": {State: "waiting"}})
	activity(map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 5}, "s9": {State: "waiting"}})
	withdrawn("答完了一轮")
	if last := f.win.notes[len(f.win.notes)-1]; last != "改 README 的构建命令：这一轮结束了" {
		t.Fatalf("答完了一轮的通知：%q", last)
	}
	// 窗口在前台时答完了一轮：不发通知，等回应的那条撤掉
	activity(map[string]hostlink.Activity{"s1": {State: "waiting", TurnEnd: 5}, "s9": {State: "waiting"}})
	f.win.front = true
	activity(map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 9}, "s9": {State: "waiting"}})
	withdrawn("前台答完了一轮", s1)

	// 换到别的对话以后，原来开着的那个（还等着 p4）由核心报的忙闲来撤
	f.click(t, "修登录页")
	if c := f.hub.Chat(); c == nil || c.Session.ID != "s2" {
		t.Fatalf("换对话：%+v", c)
	}
	withdrawn("换对话")
	activity(map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 9}, "s9": {State: "answering"}})
	withdrawn("原来的对话答了", s9)
}

func TestWaitingText(t *testing.T) {
	for _, c := range []struct {
		pr   prompt.Prompt
		want string
	}{
		{prompt.Prompt{Kind: prompt.KindElicitation, Server: "github", URL: "https://example.com"}, "github 请你去网页上操作"},
		{prompt.Prompt{Kind: prompt.KindElicitation, Server: "github"}, "github 需要你填写"},
		{prompt.Prompt{Kind: prompt.KindPermission, Questions: []prompt.Question{{Question: " 用哪种数据库？ "}}}, "需要你回答：用哪种数据库？"},
		{prompt.Prompt{Kind: prompt.KindPermission, Questions: []prompt.Question{{Question: "一"}, {Question: "二"}}}, "需要你回答"},
		{prompt.Prompt{Kind: prompt.KindPermission, IsPlan: true}, "需要你确认计划"},
	} {
		if got := waitingText(c.pr); got != c.want {
			t.Errorf("%+v：%q，要 %q", c.pr, got, c.want)
		}
	}
	if got := waitingText(prompt.Prompt{Kind: prompt.KindPermission, ToolName: "Bash", Input: []byte(`{"command":"ls"}`)}); !strings.HasPrefix(got, "需要你确认：") || got == "需要你确认：" {
		t.Errorf("工具：%q", got)
	}
}
