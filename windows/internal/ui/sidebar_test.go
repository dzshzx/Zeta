package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/egoist/mygo/ui"
)

func TestSidebarSearch(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "看看日志", Cwd: "/srv/logs", Updated: at(10)},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "部署脚本", Cwd: "/srv/deploy", Updated: at(5), Archived: true},
	}})
	f.devices.SearchHits = map[string]map[string]string{f.id: {"s3": "…部署前先跑登录的测试…"}}
	f.app.query = "登录"
	f.tt.Frame()
	f.tt.Frame()
	// 标题里有的、内容里找到的（归档的也算，注明）；别的不列
	f.see(t, "搜索结果", "修登录页", "部署脚本", "…部署前先跑登录的测试…")
	f.notSee(t, "看看日志", "今天")
	if got := f.devices.Searched(); len(got) != 1 || got[0] != f.id+" 登录" {
		t.Errorf("searched %v", got)
	}
	f.app.query = "没有这个词"
	f.tt.Frame()
	f.tt.Frame()
	f.see(t, "没有匹配的对话")
	// 清除键：回到分组
	f.clickButton(t, "清除", 0)
	f.tt.Frame()
	f.see(t, "今天", "看看日志")
	f.notSee(t, "搜索结果")
	// 点开一个结果：搜索框清空
	f.app.query = "日志"
	f.tt.Frame()
	f.tt.Frame()
	f.click(t, "看看日志")
	f.tt.Frame()
	if f.app.query != "" || f.hub.Searching() {
		t.Errorf("query after opening %q", f.app.query)
	}
}

func TestCurrentSessionCard(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	// 新对话还没有会话号时侧栏不列它（同 Compose）；有了会话号、那边的列表还没推来时照样列
	f.notSee(t, "回答中")
	f.push(`"session":"s9","title":"改 README","busy":true`)
	f.tt.Frame()
	if _, ok := f.tt.AccessByRole(ui.RoleButton, "改 README"); !ok {
		t.Fatalf("current session not listed: %q", f.tt.Texts())
	}
	f.see(t, "回答中")
	// 等确认：正看着的照在回答标
	f.push(`"prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`)
	f.see(t, "回答中")
	f.notSee(t, "等你确认")
	// 别的会话由核心报：s1 在别处等确认
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}, "s9": {State: "answering"}})
	f.tt.Frame()
	f.see(t, "等你确认")
}

// sidebarScenes 是侧栏的预览（和 Compose 版同场景对照）：搜索中、正开着的会话在回答。
var sidebarScenes = map[string]func(t *testing.T, f *chatFixture){
	"sidebar-search": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.listLikeCompose()
		f.push(`"session":"s1"`)
		f.devices.SearchHits = map[string]map[string]string{f.id: {"s3": "…日志里 README 的构建命令报错…"}}
		f.app.query = "readme"
		f.tt.Frame()
		f.tt.Frame()
	},
	"sidebar-current": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.listLikeCompose()
		f.push(`"session":"s1","busy":true`)
	},
}

// listLikeCompose 推一份和 Compose 版参照图一样的会话列表：正开着的 s1，今天的 s2，三天前的 s3。
func (f *chatFixture) listLikeCompose() {
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", File: "/home/box/.claude/projects/-w/s1.jsonl", Title: "改 README 的构建命令", Cwd: "/home/box/project", Updated: at(14)},
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "修登录页", Cwd: "/home/box/web", Updated: at(13)},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "看看日志", Cwd: "/srv/logs", Updated: at(14) - 3*86400*1000},
	}})
	f.tt.Frame()
}
