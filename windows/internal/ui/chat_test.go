package ui

import (
	"encoding/json"
	"image/color"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// chatFixture 是一台假设备「box」上的窗口：侧栏列着一个会话，对话连接是 hubtest 的假 serve，conv.open 时推来录好的整份，
// 别的请求照 answers 答（没给的答成功）。
type chatFixture struct {
	*fixture
	id      string
	answers map[string]string
	win     *testWindow
}

func newChatFixture(t *testing.T) *chatFixture {
	t.Helper()
	f := &chatFixture{fixture: newFixture(t), answers: map[string]string{}, win: &testWindow{front: true}}
	f.app.Window = f.win
	f.devices.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			if c.Method == "conv.take_recalled" {
				l.Push(`{"type":"conv.state","conv":"` + c.Conv() + `","recalled":null}`) // 核心取走以后清掉
			}
			if c.Method == "conv.open" {
				for _, line := range hubtest.Recorded(openRecording(c), c.Conv()) {
					l.Push(line)
				}
			}
			if r := f.answers[c.Method]; r != "" {
				return json.RawMessage(r), nil
			}
			return nil, nil
		})
	}
	f.id = f.withHosts(t, "box")[0].ID
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", File: "/home/box/.claude/projects/-w/s1.jsonl", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14)},
	}})
	f.tt.Frame()
	return f
}

// openRecording：接着旧会话打开时推重连那次录的（有历史），新对话推空的。
func openRecording(c hubtest.Call) string {
	if c.Str("session") != "" {
		return "reopened"
	}
	return "open"
}

func (f *chatFixture) link() *hubtest.ChatLink { return f.devices.Chat(f.id) }

// replay 推一段录好的消息，画一帧。
func (f *chatFixture) replay(name string) {
	for _, line := range hubtest.Recorded(name, "w1") {
		f.link().Push(line)
	}
	f.tt.Frame()
}

// push 推一行 conv.state 的变化（fields 是 JSON 对象里的各项，不带大括号）。
func (f *chatFixture) push(fields string) {
	f.link().Push(`{"type":"conv.state","conv":"w1",` + fields + `}`)
	f.tt.Frame()
}

// sent 取走发出的请求，只留 method 是 want 的那些的参数 JSON。
func (f *chatFixture) sent(want string) []string {
	var out []string
	for _, c := range f.link().Took() {
		if c.Method == want {
			out = append(out, c.JSON())
		}
	}
	return out
}

func (f *chatFixture) newChat(t *testing.T) {
	t.Helper()
	f.click(t, "新对话")
	f.link().Took()
}

func TestOpeningASessionShowsItsHistory(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	open := f.sent("conv.open")
	if len(open) != 1 || !strings.Contains(open[0], `"session":"s1"`) || !strings.Contains(open[0], `"file":"/home/box/.claude/projects/-w/s1.jsonl"`) {
		t.Fatalf("打开：%v", open)
	}
	f.see(t, "假 Claude 起的标题", "box · /home/zeta", "你好，介绍一下自己", "收到插话", "已停止")
	// 换到新对话：关掉原来的
	f.click(t, "新对话")
	if got := f.link().Methods(); len(got) != 2 || got[0] != "conv.close" || got[1] != "conv.open" {
		t.Fatalf("换到新对话：%v", got)
	}
	f.see(t, "新对话", "box · /home/zeta")
	f.notSee(t, "你好，介绍一下自己")
}

func TestComposerSendsWithEnter(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.click(t, "消息")
	f.tt.Type("第一行")
	f.tt.Key(ui.Shift, ui.KeyEnter)
	f.tt.Type("第二行")
	if got := f.sent("conv.send"); got != nil {
		t.Fatalf("Shift+Enter 发出去了：%v", got)
	}
	f.tt.Key(0, ui.KeyEnter)
	if got := f.sent("conv.send"); len(got) != 1 || got[0] != `{"conv":"w1","text":"第一行\n第二行"}` {
		t.Fatalf("Enter：%v", got)
	}
	if f.app.pane.draft != "" {
		t.Errorf("发完输入框里还有 %q", f.app.pane.draft)
	}
	// 流式回答一轮：条目跟着推送变；回答中发的照样发（核心让它排队），也能停止
	f.replay("turn")
	f.see(t, "你好，我是按剧本回话的假 Claude", "假 Claude 起的标题")
	f.replay("permission")
	f.replay("permission-answered")
	f.replay("queued")
	f.see(t, "正在回答", "排队中", "先说重点")
	f.click(t, "消息")
	f.tt.Type("再补一句")
	f.click(t, "发送")
	f.click(t, "停止")
	if got := f.link().Methods(); len(got) != 2 || got[0] != "conv.send" || got[1] != "conv.interrupt" {
		t.Fatalf("回答中：%v", got)
	}
}

func TestQueueStrip(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.replay("turn")
	f.replay("permission")
	f.replay("permission-answered")
	f.replay("queued")
	f.click(t, "立即插话")
	f.click(t, "删除")
	f.click(t, "先说重点")
	got := f.link().Took()
	if len(got) != 3 || got[0].Method != "conv.steer" || got[1].JSON() != `{"conv":"w1","recall":false,"uuid":"00000000-0000-4000-8000-000000000004"}` ||
		got[2].JSON() != `{"conv":"w1","recall":true,"uuid":"00000000-0000-4000-8000-000000000004"}` {
		t.Fatalf("排队条：%+v", got)
	}
	// 核心把撤回的放进 recalled：取回来放进输入框
	f.answers["conv.take_recalled"] = `{"uuid":"q","text":"先说重点","images":[],"files":[]}`
	f.push(`"queued":[],"recalled":{"uuid":"q","text":"先说重点","images":[],"files":[]}`)
	if f.app.pane.draft != "先说重点" || len(f.sent("conv.take_recalled")) != 1 {
		t.Fatalf("撤回：输入框 %q", f.app.pane.draft)
	}
	f.push(`"steering":true,"queued":[{"uuid":"x","text":"另一条","images":[],"files":[]}]`)
	f.see(t, "插话中")
	f.notSee(t, "立即插话")
}

func TestToolPermissionCard(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.replay("turn")
	f.replay("permission")
	f.see(t, "允许这次操作？", "列出文件", "以后不再询问", "允许 Bash", "允许", "拒绝")
	f.click(t, "拒绝的理由")
	f.tt.Type("别列了")
	f.click(t, "拒绝并告诉 Claude")
	if got := f.sent("conv.answer"); len(got) != 1 || !strings.Contains(got[0], `"reply":{"kind":"deny","feedback":"别列了"}`) {
		t.Fatalf("拒绝：%v", got)
	}
	// 发出去以后等核心收起，按钮不能再点
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "允许"); !a.Disabled {
		t.Error("回应发出去以后还能再点")
	}
	f.replay("permission-answered")
	f.notSee(t, "允许这次操作？")
}

// 确认卡片在输入栏的位置上（输入栏先收起），Enter 是允许、Esc 是拒绝（同 ChatGPT 客户端）。
func TestPermissionCardKeys(t *testing.T) {
	for _, c := range []struct {
		key  ui.Key
		want string
	}{{ui.KeyEnter, `"reply":{"kind":"allow"}`}, {ui.KeyEscape, `"reply":{"kind":"deny"}`}} {
		f := newChatFixture(t)
		f.newChat(t)
		f.replay("turn")
		f.replay("permission")
		f.see(t, "允许这次操作？")
		f.notSee(t, "发送")
		f.tt.Key(0, c.key)
		f.tt.Frame()
		if got := f.sent("conv.answer"); len(got) != 1 || !strings.Contains(got[0], c.want) {
			t.Fatalf("按 %v：%v", c.key, got)
		}
		f.replay("permission-answered")
		f.see(t, "发送")
	}
}

func TestPlanAndQuestionCards(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"ExitPlanMode","input":{"plan":"1. 改登录页\n2. 跑测试"},"is_plan":true,"notice":"Claude 交来了计划，等你审批"}]`)
	f.see(t, "按这个计划执行？", "改登录页", "跑测试", "批准，自动接受改动")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "继续完善"); !a.Disabled {
		t.Error("没写要改什么也能继续完善")
	}
	f.click(t, "批准，改动逐项确认")
	f.click(t, "批准")
	if got := f.sent("conv.answer"); len(got) != 1 || !strings.Contains(got[0], `"reply":{"kind":"approve_plan","mode":"default"},"request_id":"p1"`) {
		t.Fatalf("批准计划：%v", got)
	}

	f.push(`"prompts":[{"kind":"permission","request_id":"q1","tool_name":"AskUserQuestion","input":{},"asks_input":true,
		"questions":[{"question":"用哪个库？","header":"库","options":[{"label":"标准库","description":"不加依赖"},{"label":"第三方","description":"","preview":"go get x"}],"takes_notes":true},
		{"question":"要测试吗？","header":"测试","options":[{"label":"单元"},{"label":"端到端"}],"multi_select":true}]}]`)
	f.see(t, "需要你的选择", "用哪个库？", "标准库", "不加依赖")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "确定"); !a.Disabled {
		t.Error("没答完也能确定")
	}
	f.click(t, "第三方")
	f.see(t, "go get x")
	f.click(t, "备注")
	f.tt.Type("要最新版")
	f.click(t, "单元")
	f.clickButton(t, "其他", 1)
	f.click(t, "自己写")
	f.tt.Type("冒烟")
	f.click(t, "确定")
	got := f.sent("conv.answer")
	if len(got) != 1 || !strings.Contains(got[0], `"reply":{"kind":"answer","answers":{"用哪个库？":"第三方","要测试吗？":"单元, 冒烟"},"notes":{"用哪个库？":"要最新版"}}`) {
		t.Fatalf("回答：%v", got)
	}
}

func TestElicitationForm(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"prompts":[{"kind":"elicitation","request_id":"e1","server":"部署","message":"填一下端口","notice":"","asks_input":true,
		"fields":[{"key":"port","title":"端口","kind":"integer","required":true},{"key":"tls","title":"加密","kind":"toggle","default":["false"]}]}]`)
	f.see(t, "部署 需要你填写", "填一下端口", "端口 *", "加密")
	f.clickField(t, "端口 *")
	f.tt.Type("八十")
	f.click(t, "加密")
	f.answers["conv.elicit"] = `{"errors":{"port":"要填整数"}}`
	f.click(t, "提交")
	got := f.sent("conv.elicit")
	if len(got) != 1 || !strings.Contains(got[0], `"values":{"port":["八十"],"tls":["true"]}`) || !strings.Contains(got[0], `"action":"accept"`) {
		t.Fatalf("提交：%v", got)
	}
	f.see(t, "要填整数")
	f.click(t, "拒绝")
	if got := f.sent("conv.elicit"); len(got) != 1 || !strings.Contains(got[0], `"action":"decline"`) {
		t.Fatalf("拒绝：%v", got)
	}

	f.push(`"prompts":[{"kind":"elicitation","request_id":"e2","server":"登录","url":"https://example.com/auth","notice":"","asks_input":true}]`)
	f.see(t, "登录 请你去网页上操作", "https://example.com/auth")
	f.click(t, "打开网页")
	if urls := f.tt.OpenedURLs(); len(urls) != 1 || urls[0] != "https://example.com/auth" {
		t.Errorf("打开的网址：%v", urls)
	}
	if got := f.sent("conv.elicit"); len(got) != 1 || !strings.Contains(got[0], `"action":"accept","conv":"w1","request_id":"e2"`) {
		t.Fatalf("网址：%v", got)
	}
}

func TestNewChatInAMissingDirectory(t *testing.T) {
	f := newChatFixture(t)
	f.devices.DirProblems = map[string]string{"/home/box": "「box」上没有 /home/box 这个目录"}
	f.click(t, "新对话")
	f.see(t, "Claude Code 起不来：「box」上没有 /home/box 这个目录。点上面的工作目录换一个，或在「设备」页改默认工作目录。")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "发送"); !a.Disabled {
		t.Error("进不去还能发")
	}
	// 新对话还没发消息：点顶栏的工作目录换一个，在那里重开
	f.click(t, "换工作目录")
	f.clickField(t, "/home/…")
	f.tt.Key(ui.Cmd, ui.KeyA)
	f.tt.Type("/srv/work")
	f.click(t, "确定")
	f.tt.Frame()
	f.notSee(t, "Claude Code 起不来：「box」上没有 /home/box 这个目录。点上面的工作目录换一个，或在「设备」页改默认工作目录。")
	if c := f.hub.Chat(); c == nil || c.Session.Cwd != "/srv/work" || c.CwdProblem != "" {
		t.Fatalf("换目录后：%+v", c)
	}
	if open := f.sent("conv.open"); len(open) == 0 || !strings.Contains(open[len(open)-1], `"cwd":"/srv/work"`) {
		t.Fatalf("新目录里打开：%v", open)
	}
}

func TestReconnectAndNotify(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.win.front = false
	f.replay("turn")
	if len(f.win.notes) != 1 || f.win.notes[0] != "假 Claude 起的标题：已完成" {
		t.Fatalf("通知：%v", f.win.notes)
	}
	f.link().Drop("连接断了：EOF")
	f.tt.Frame()
	f.see(t, "正在重连：连接断了：EOF")
	f.link().Connect()
	f.tt.Frame()
	f.notSee(t, "正在重连：连接断了：EOF")
	if open := f.sent("conv.open"); len(open) != 1 || !strings.Contains(open[0], `"session":"00000000-0000-4000-8000-000000000006"`) {
		t.Fatalf("重连后：%v", open)
	}
}

func TestModeModelEffortAndUsage(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.replay("turn")
	f.see(t, "逐项确认", "默认（claude-fake）", "思考：默认（中）")
	f.click(t, "逐项确认")
	f.click(t, "计划模式")
	f.click(t, "默认（claude-fake）")
	f.click(t, "Opus")
	f.click(t, "思考：默认（中）")
	f.click(t, "超高")
	got := f.link().Took()
	if len(got) != 3 || got[0].JSON() != `{"conv":"w1","value":"plan"}` || got[1].JSON() != `{"conv":"w1","value":"opus"}` ||
		got[2].Method != "conv.effort" || got[2].JSON() != `{"conv":"w1","value":"xhigh"}` {
		t.Fatalf("选择：%+v", got)
	}
	f.click(t, "上下文 0%")
	f.see(t, "本对话累计 · 1 轮", "12.3K", "18")
	f.click(t, "压缩上下文")
	// 打开用量时顺带问了额度（conv.quota、quota.usage，在后台），这里只看压缩
	got = slices.DeleteFunc(f.link().Took(), func(c hubtest.Call) bool { return strings.HasPrefix(c.Method, "quota.") || c.Method == "conv.quota" })
	if len(got) != 1 || got[0].Method != "conv.compact" {
		t.Fatalf("压缩：%+v", got)
	}
}

func TestEarlierHistory(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"earlier":120`)
	f.see(t, "载入更早的 120 条记录")
	f.link().OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		return nil, replyErr{sessionproto.CodeHistoryChanged, stale}
	})
	f.click(t, "载入更早的 120 条记录")
	if got := f.sent("conv.earlier"); len(got) != 1 {
		t.Fatalf("载入更早的：%v", got)
	}
	f.see(t, "没读到更早的记录：会话记录在别处改过，重新打开这个对话再看更早的内容")
	f.notSee(t, "点按重试")
}

// token 数的写法由 helper/usage 的 FormatTokens 管，测试在那边。
func TestReadableModel(t *testing.T) {
	for id, want := range map[string]string{"claude-opus-5-5": "Opus 5.5", "claude-haiku-4-5-20251001": "Haiku 4.5", "claude-opus-5-5[1m]": "Opus 5.5 (1M)", "gpt": "gpt"} {
		if got := readableModel(id); got != want {
			t.Errorf("%s：%s，要 %s", id, got, want)
		}
	}
}

// clickField 点名叫 name 的输入框（旁边写着同样字的说明时，按名字找会先找到说明）。
func (f *chatFixture) clickField(t *testing.T, name string) {
	t.Helper()
	a, ok := f.tt.AccessByRole(ui.RoleTextField, name)
	if !ok {
		t.Fatalf("没有输入框 %q", name)
	}
	f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
}

// chatScenes 是预览图里聊天窗格的几个场景（TestPreviews 画）。
var chatScenes = map[string]func(t *testing.T, f *chatFixture){
	// 「连接」关着：聊天窗格说已断开、给「连接」；设备页的开关关着
	"chat-disconnected": func(t *testing.T, f *chatFixture) {
		f.click(t, "修登录页")
		_ = f.hub.SetConnected(f.id, false)
	},
	"device-disconnected": func(t *testing.T, f *chatFixture) {
		_ = f.hub.SetConnected(f.id, false)
		f.app.device = f.id
		f.app.open(pageDevice)
	},
	"chat-answer":  func(t *testing.T, f *chatFixture) { f.showTurn(t, false) },
	"chat-confirm": func(t *testing.T, f *chatFixture) { f.showTurn(t, true) },
	"chat-mode-menu": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.click(t, "默认权限")
	},
	// 附件：「+」的菜单、带两张图和一个文件的输入栏、输入路径、常用指令
	"attach-menu": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.click(t, "添加附件")
	},
	"attach-composer": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		dir := t.TempDir()
		for name, data := range map[string][]byte{
			"海边.png":   picture(320, 200, color.NRGBA{0x28, 0x78, 0xDC, 255}, color.NRGBA{0xFA, 0xD2, 0x78, 255}),
			"截图.png":   picture(200, 300, color.NRGBA{0x3C, 0xB4, 0x78, 255}, color.NRGBA{0x14, 0x28, 0x50, 255}),
			"需求说明.pdf": []byte("%PDF-1.7"),
		} {
			if err := os.WriteFile(filepath.Join(dir, name), data, 0o644); err != nil {
				t.Fatal(err)
			}
		}
		f.pickReturns(filepath.Join(dir, "需求说明.pdf"), filepath.Join(dir, "海边.png"), filepath.Join(dir, "截图.png"))
		f.attach(t, "文件")
		f.app.pane.draft = "看看这两张图和需求说明"
	},
	"attach-path": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.attach(t, "输入路径")
	},
	"attach-path-folder": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.devices.Paths = map[string]hostlink.PathKind{"/home/box/project/docs": hostlink.PathFolder}
		f.attach(t, "输入路径")
		f.app.path.input = "~/project/docs"
	},
	"attach-prompts": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		var list []settings.SavedPrompt
		list = settings.AddPrompt(list, "p3", "", "审查这次改动，列出风险")
		list = settings.AddPrompt(list, "p2", "周报", "把这周的提交整理成周报，按功能分组")
		list = settings.AddPrompt(list, "p1", "", "先读一遍 README，再列出所有要改的地方")
		if err := f.hub.SetPrompts(list); err != nil {
			t.Fatal(err)
		}
		f.attach(t, "常用指令")
	},
	// 同一段对话上弹出提问、计划审批
	"chat-ask": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"q1","tool_name":"AskUserQuestion","input":{},"asks_input":true,
			"questions":[{"question":"用哪个库？","header":"库","options":[{"label":"标准库","description":"不加依赖"},{"label":"第三方","description":"功能多"}]}]}]`)
	},
	"chat-approve": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"ExitPlanMode","input":{"plan":"# 计划\n1. 改登录页的表单校验\n2. 补单元测试\n3. 跑一遍端到端"},"is_plan":true,"notice":""}]`)
	},
	"chat-history": func(t *testing.T, f *chatFixture) { f.click(t, "修登录页") },
	"chat-streaming": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		for _, name := range []string{"turn", "permission", "permission-answered"} {
			f.replay(name)
		}
		// 回答到一半、排着一条，有一个后台任务
		lines := hubtest.Recorded("queued", "w1")
		for _, line := range lines[:len(lines)-1] {
			f.link().Push(line)
		}
		f.push(`"tasks":[{"id":"t1","type":"local_bash","kind":"command","description":"跑测试","started_at":1,"state":"running","running":true,"readable":true}]`)
		f.push(`"queued":[{"uuid":"q1","text":"先说重点","images":[],"files":[]},{"uuid":"q2","text":"再看看日志","images":[],"files":["/w/log.txt"]}]`)
	},
	"chat-permission": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.replay("turn")
		f.replay("permission")
	},
	"chat-plan": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"ExitPlanMode","input":{"plan":"# 计划\n1. 改登录页的表单校验\n2. 补单元测试\n3. 跑一遍端到端"},"is_plan":true,"notice":""}]`)
	},
	"chat-question": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"q1","tool_name":"AskUserQuestion","input":{},"asks_input":true,
			"questions":[{"question":"用哪个库？","header":"库","options":[{"label":"标准库","description":"不加依赖"},{"label":"第三方","description":"功能多","preview":"go get example.com/x"}],"takes_notes":true}]}]`)
		f.click(t, "第三方")
	},
	"chat-elicit": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,"prompts":[{"kind":"elicitation","request_id":"e1","server":"部署","message":"填一下服务的端口和要不要加密","notice":"","asks_input":true,
			"fields":[{"key":"port","title":"端口","kind":"integer","required":true},{"key":"tls","title":"加密","kind":"toggle","default":["true"]},{"key":"env","title":"环境","kind":"single","choices":[{"value":"prod","label":"正式"},{"value":"dev","label":"测试"}],"default":["dev"]}]}]`)
		f.answers["conv.elicit"] = `{"errors":{"port":"要填整数"}}`
		f.click(t, "提交")
	},
	"chat-cwd": func(t *testing.T, f *chatFixture) {
		f.devices.DirProblems = map[string]string{"/home/box": "「box」上没有 /home/box 这个目录"}
		f.click(t, "新对话")
	},
	"chat-usage": func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.replay("turn")
		f.click(t, "上下文 0%")
	},
}

// showTurn 摆出一轮典型的对话：用户消息、折叠着的步骤卡、带列表和代码块的回答；
// confirm 时再发一条、在跑一步命令，等着确认。侧栏多列两个会话。
func (f *chatFixture) showTurn(t *testing.T, confirm bool) {
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s2", Title: "看看日志", Cwd: "/srv/logs", Updated: at(1) - 3600*1000},
	}})
	f.newChat(t)
	// 用户消息上的 forkable、round_last 照核心的算法写：没有 uuid，改不了；最后一轮能分支，后面还有一轮时下一条也没有 uuid，分支不了
	u1 := `"forkable":true,"round_last":"a1"`
	if confirm {
		u1 = `"round_last":"a1"`
	}
	items := `{"kind":"user","id":"u1",` + u1 + `,"text":"README 里的构建命令还是 ./gradlew，改成 tools/gradle，顺便看看 CLAUDE.md 要不要同步"},` +
		`{"kind":"work","id":"w1","steps":[{"kind":"thinking","id":"t1","text":"先找出两个文件里所有直接调用 gradlew 的地方。","seconds":3},` +
		`{"kind":"tool","id":"s1","name":"Bash","input":{"command":"grep -n gradlew README.md CLAUDE.md","description":"找出直接调用 gradlew 的地方"},"status":"success","result":"README.md:12:./gradlew :app:assembleDebug"},` +
		`{"kind":"tool","id":"s2","name":"Read","input":{"file_path":"/home/box/project/README.md"},"status":"success","result":"（README.md 的内容）"}]},` +
		`{"kind":"answer","id":"a1","text":"改好了：\n\n- **README.md**：构建命令换成 ` + "`tools/gradle`" + `\n- **CLAUDE.md**：没有直接调用，不用改\n\n在本机构建测试版：\n\n` +
		"```bash\\ncd ~/project\\ntools/gradle :app:assembleDebug\\n```" + `\n\n第一次构建要下载依赖，之后几分钟就好。","streaming":false}`
	if confirm {
		items += `,{"kind":"user","id":"u2","forkable":true,"round_last":"w2","text":"构建一下"},{"kind":"work","id":"w2","steps":[{"kind":"tool","id":"s9","name":"Bash",` +
			`"input":{"command":"tools/gradle :app:assembleDebug","description":"构建 debug 包"},"status":"running"}]}`
	}
	f.link().Push(`{"type":"conv.items","conv":"w1","at":0,"remove":0,"items":[` + items + `]}`)
	f.push(`"session":"s0","cwd":"/home/box/project","title":"改 README 的构建命令","reported_model":"claude-opus-5-5"`)
	if confirm {
		f.push(`"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash","input":{"command":"tools/gradle :app:assembleDebug","description":"构建 debug 包"},` +
			`"suggestion_lines":["允许 Bash(tools/gradle:*)"],"notice":""}]`)
	}
}

// testWindow 是测试里的窗口：前台与否由测试定，记下发出的通知、撤掉的通知编号和交代过的系统按钮深浅。
type testWindow struct {
	front     bool
	notes     []string
	opens     []func()
	withdrawn []string
	buttons   []bool
}

func (w *testWindow) Foreground() bool { return w.front }
func (w *testWindow) Notify(n Note) {
	w.notes = append(w.notes, n.Title+"："+n.Body)
	w.opens = append(w.opens, n.Open)
}
func (w *testWindow) Withdraw(id string)      { w.withdrawn = append(w.withdrawn, id) }
func (w *testWindow) ButtonsOverDark(on bool) { w.buttons = append(w.buttons, on) }

// 输入栏上的负载标识：对话有了消息后问那台主机的 system.cpu，忙时变色，隔几秒再问。新对话还没发消息时不问。
func TestHostLoad(t *testing.T) {
	f := newChatFixture(t)
	f.answers["system.cpu"] = `{"percent":93,"cores":8}`
	f.newChat(t)
	if f.tt.HasText("CPU") {
		t.Fatal("新对话还没发消息就问了负载")
	}
	f.replay("turn")
	f.tt.Frame()
	f.tt.Frame()
	f.see(t, "box 的 CPU 使用率 93%，8 个核的平均（整机所有进程，同 Claude Code 状态栏的 cpu%）")
	f.answers["system.cpu"] = `{"percent":12,"cores":8}`
	f.tt.Advance(hostLoadEvery)
	f.tt.Frame()
	f.tt.Frame()
	f.see(t, "box 的 CPU 使用率 12%，8 个核的平均（整机所有进程，同 Claude Code 状态栏的 cpu%）")
}
