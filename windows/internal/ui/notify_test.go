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
