package ui

import (
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/drafts"
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

// sidebarScenes 是侧栏的预览：搜索中、正开着的会话在回答、别的会话有草稿。
var sidebarScenes = map[string]func(t *testing.T, f *chatFixture){
	"sidebar-search": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.listSample()
		f.push(`"session":"s1"`)
		f.devices.SearchHits = map[string]map[string]string{f.id: {"s3": "…日志里 README 的构建命令报错…"}}
		f.app.query = "readme"
		f.tt.Frame()
		f.tt.Frame()
	},
	"sidebar-current": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.listSample()
		f.push(`"session":"s1","busy":true`)
	},
	"sidebar-draft": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.listSample()
		f.app.draftBook().records["s2"] = drafts.Draft{Key: "s2", Text: "登录按钮再大一点，颜色换成"}
		f.tt.Frame()
	},
}

// listSample 推一份会话列表：正开着的 s1，今天的 s2，三天前的 s3。
func (f *chatFixture) listSample() {
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", File: "/home/box/.claude/projects/-w/s1.jsonl", Title: "改 README 的构建命令", Cwd: "/home/box/project", Updated: at(14)},
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "修登录页", Cwd: "/home/box/web", Updated: at(13)},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "看看日志", Cwd: "/srv/logs", Updated: at(14) - 3*86400*1000},
	}})
	f.tt.Frame()
}

// 已结束（核心报 live 为假）的会话标题和小字灰着，读屏说「已结束」；还有进程的照常是次要色（侧栏标题照 ChatGPT 客户端用次要色）。
func TestEndedSessionsAreDimmed(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "跑完的对话", Cwd: "/srv/done", Updated: at(12), Ended: true},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "还开着的对话", Cwd: "/srv/live", Updated: at(11)},
	}})
	f.tt.Frame()
	for _, c := range []struct {
		title string
		ended bool
	}{{"跑完的对话", true}, {"还开着的对话", false}, {"修登录页", false}} {
		a, ok := f.tt.AccessByRole(ui.RoleButton, c.title)
		if !ok {
			t.Fatalf("no row %q in %q", c.title, f.tt.Texts())
		}
		if got := strings.HasSuffix(a.Description, "已结束"); got != c.ended {
			t.Errorf("%s: description %q", c.title, a.Description)
		}
	}
	// 画面上：已结束那行的标题字最深处也比还开着的浅得多（次要色 #5D5D5D 约 93，三级色 #8F8F8F 约 143）
	ended, live := f.darkestInk(t, "跑完的对话"), f.darkestInk(t, "还开着的对话")
	if live > 100 || ended < 140 {
		t.Errorf("darkest ink: ended %d, live %d", ended, live)
	}
	// 进程又起来了（live 为真推来）：恢复次要色
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "跑完的对话", Cwd: "/srv/done", Updated: at(12)},
	}})
	f.tt.Frame()
	if got := f.darkestInk(t, "跑完的对话"); got > 100 {
		t.Errorf("revived row still dim: %d", got)
	}
}

// darkestInk 是文字 text 所在的框里最深的像素的亮度（0 最黑，255 最白）。
func (f *fixture) darkestInk(t *testing.T, text string) int {
	t.Helper()
	r, ok := f.tt.Find(text)
	if !ok {
		t.Fatalf("no %q in %q", text, f.tt.Texts())
	}
	img := f.tt.Image()
	// Find 给的是窗口的 DIP；测试器的像素比是 1，和画面的像素一一对应
	darkest := 255
	for y := int(r.Y); y < int(r.Y+r.H); y++ {
		for x := int(r.X); x < int(r.X+r.W); x++ {
			c := img.RGBAAt(x, y)
			if l := (int(c.R)*299 + int(c.G)*587 + int(c.B)*114) / 1000; l < darkest {
				darkest = l
			}
		}
	}
	return darkest
}

// 鼠标停在会话行上一会儿，右边出一张卡片：标题、目录、设备、别处在运行；行尾只有图标（读屏名是那几个字），目录平时不出现。
func TestRowHoverCard(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s4", File: "/home/box/.claude/projects/-w/s4.jsonl", Title: "终端里的对话", Cwd: "/srv/term", Updated: at(13), External: true},
	}})
	f.tt.Frame()
	if !f.shows("别处运行中") {
		t.Fatal("行尾没有别处运行中的图标")
	}
	if f.tt.HasText("term") {
		t.Fatalf("没悬停就出了目录：%q", f.tt.Texts())
	}
	r, ok := f.tt.Find("终端里的对话")
	if !ok {
		t.Fatalf("没有这一行：%q", f.tt.Texts())
	}
	f.tt.Move(r.X+r.W/2, r.Y+r.H/2)
	f.tt.Advance(700 * time.Millisecond)
	f.tt.Frame()
	for _, want := range []string{"term", "别处运行中"} {
		if !f.tt.HasText(want) {
			t.Errorf("卡片里没有 %q：%q", want, f.tt.Texts())
		}
	}
}

func TestAgo(t *testing.T) {
	now := time.Date(2026, 11, 23, 12, 0, 0, 0, time.Local)
	for _, c := range []struct {
		d    time.Duration
		want string
	}{
		{20 * time.Second, "刚刚"},
		{5 * time.Minute, "5 分钟前"},
		{3 * time.Hour, "3 小时前"},
		{2 * 24 * time.Hour, "2 天前"},
		{15 * 24 * time.Hour, "2 周前"},
		{60 * 24 * time.Hour, "9 月 24 日"},
		{400 * 24 * time.Hour, "2025 年 10 月 19 日"},
	} {
		if got := ago(now.Add(-c.d), now); got != c.want {
			t.Errorf("%v 前：%q，要 %q", c.d, got, c.want)
		}
	}
}
