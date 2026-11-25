package ui

import (
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui"
)

// slashCommands 是测试里核心报的几个命令（还没起进程时是它记着的那份）。
const slashCommands = `"commands":[{"name":"compact","description":"压缩上下文","argumentHint":"<说明>"},` +
	`{"name":"clear","description":"清空对话","argumentHint":""},{"name":"review","description":"审查改动","argumentHint":""}]`

func TestSlashCommandsFilterAndInsert(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands + `,"process":true`)
	f.click(t, "消息")
	f.tt.Type("/")
	f.see(t, "/compact", "/clear", "/review", "压缩上下文", "<说明>")
	if got := f.link().Methods(); len(got) != 0 {
		t.Fatalf("已经接着进程，打 / 时不用另外请求：%v", got)
	}
	// 以 c 开头的排前面，名字里含 c 的跟在后面
	f.tt.Type("c")
	f.see(t, "/compact", "/clear")
	f.notSee(t, "/review")
	f.tt.Key(0, ui.KeyDown)
	f.tt.Key(0, ui.KeyEnter)
	f.tt.Frame()
	if f.app.pane.draft != "/clear " {
		t.Fatalf("回车填进去的：%q", f.app.pane.draft)
	}
	if got := f.sent("conv.send"); got != nil {
		t.Fatalf("选命令的回车发出去了：%v", got)
	}
	f.notSee(t, "/compact") // 打了空格就收起
	// 有了空格以后回车照常发
	f.tt.Type("全部")
	f.tt.Key(0, ui.KeyEnter)
	if got := f.sent("conv.send"); len(got) != 1 || !strings.Contains(got[0], `"text":"/clear 全部"`) {
		t.Fatalf("发出：%v", got)
	}
	// 点一条也填进去；上键从第一条绕到最后一条；Esc 收起
	f.tt.Type("/re")
	f.click(t, "/review")
	if f.app.pane.draft != "/review " {
		t.Fatalf("点了填进去的：%q", f.app.pane.draft)
	}
	f.tt.Key(0, ui.KeyBackspace) // 去掉空格又列出来
	f.see(t, "/review")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	f.notSee(t, "/review")
}

// 句中空一格再打 / 也提示（同 Claude Code 终端界面），选了只换掉那个词；紧挨着别的字时不提示；光标挪回那个词又列出来。
func TestSlashCommandsMidText(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(slashCommands)
	f.click(t, "消息")
	f.tt.Type("帮我 /co")
	f.see(t, "/compact")
	f.notSee(t, "/review")
	f.tt.Key(0, ui.KeyEnter)
	f.tt.Frame()
	if f.app.pane.draft != "帮我 /compact " {
		t.Fatalf("回车填进去的：%q", f.app.pane.draft)
	}
	f.tt.Type("你好/c")
	f.notSee(t, "/clear")
	f.tt.Type(" 再 /re")
	f.see(t, "/review")
	// 光标挪到「/」前面收起，挪回「/r」后面按 r 列出来
	// （只按下、先不松开就看：松开键也会建一帧，盖住只挪了光标时没要下一帧的错）
	f.tt.Key(0, ui.KeyLeft)
	f.tt.Key(0, ui.KeyLeft)
	f.tt.KeyDown(ui.KeyLeft)
	f.notSee(t, "/review")
	f.tt.KeyUp(ui.KeyLeft)
	f.tt.Key(0, ui.KeyRight)
	f.tt.KeyDown(ui.KeyRight)
	f.see(t, "/review", "/clear")
	f.tt.KeyUp(ui.KeyRight)
	f.tt.Key(0, ui.KeyTab)
	f.tt.Frame()
	if want := "帮我 /compact 你好/c 再 /review "; f.app.pane.draft != want {
		t.Fatalf("Tab 填进去的：%q，要 %q", f.app.pane.draft, want)
	}
	f.tt.Type("x")
	f.tt.Frame()
	if want := "帮我 /compact 你好/c 再 /review x"; f.app.pane.draft != want {
		t.Fatalf("光标不在命令后面：%q", f.app.pane.draft)
	}
}

// 列表比输入框先建、按键时的字可能比草稿新：光标按上一帧的加上多打、删掉的字数算。
func TestSlashCaretFollowsTyping(t *testing.T) {
	m := slashMenu{caret: 3, caretText: "ab/"}
	if got := m.caretIn("ab/co"); got != 5 {
		t.Fatalf("多打两个字：%d", got)
	}
	if got := m.caretIn("ab"); got != 2 {
		t.Fatalf("删一个字：%d", got)
	}
	if got := (&slashMenu{caret: -1}).caretIn("x"); got != -1 {
		t.Fatalf("选中一段时不提示：%d", got)
	}
}

// 还没接着 Claude Code 进程时开始打 /，让核心先起好进程（conv.prepare）：一个词只问一次，起好后列的是 Claude Code 报的命令，
// 模型菜单换成它报的模型；已经接着进程时不再问。
func TestSlashPreparesTheProcess(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"process":false,"commands":null,"models":null`)
	f.click(t, "默认")
	f.see(t, "Fable", "Opus", "Sonnet", "Haiku") // 还没报清单时的后备
	f.clickMenuItem(t, "Fable")
	if got := f.link().Took(); len(got) != 1 || got[0].Method != "conv.model" || got[0].JSON() != `{"conv":"w1","value":"fable"}` {
		t.Fatalf("新对话选 Fable：%+v", got)
	}
	f.click(t, "消息")
	f.tt.Type("看一下")
	if got := f.link().Methods(); len(got) != 0 {
		t.Fatalf("没打 / 就问了：%v", got)
	}
	f.tt.Type(" /")
	f.tt.Type("re")
	if got := f.link().Methods(); len(got) != 1 || got[0] != "conv.prepare" {
		t.Fatalf("打 / 时：%v", got)
	}
	// 起好了：命令和模型随 conv.state 推来
	f.push(`"process":true,` + slashCommands + `,"models":[{"value":"default","displayName":"默认"},` +
		`{"value":"claude-opus-5-5","displayName":"Opus 5.5"},{"value":"claude-sonnet-5","displayName":"Sonnet 5"}]`)
	f.see(t, "/review", "审查改动")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Type(" /c")
	if got := f.link().Methods(); len(got) != 0 {
		t.Fatalf("已经接着进程又问了：%v", got)
	}
	f.click(t, "默认")
	f.see(t, "Opus 5.5", "Sonnet 5")
	f.notSee(t, "Fable", "Haiku")
	f.tt.CloseMenu()
	// 进程结束了（/exit、主机上闲置超时）：光标停在「/c」后面时不自己问（不然放着的草稿会让进程一直起着），下一个 / 才问
	f.click(t, "消息")
	f.tt.Key(0, ui.KeyEnd)
	f.push(`"process":false`)
	f.tt.Advance(time.Second)
	if got := f.link().Methods(); len(got) != 0 {
		t.Fatalf("没打字就问了：%v", got)
	}
	f.tt.Type(" ")
	f.tt.Type("/")
	if got := f.link().Methods(); len(got) != 1 || got[0] != "conv.prepare" {
		t.Fatalf("进程结束后再打 /：%v", got)
	}
}

func TestSlashSettingsSuggestions(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	p := f.app.pane
	_, got := f.app.suggestions(p, "/", 1)
	if len(got) != 2 || got[0].Name != "config" || got[1].Name != "permissions" {
		t.Fatalf("核心没报命令时的补全：%+v", got)
	}
	f.push(`"commands":[{"name":"config","description":"native config"},{"name":"permissions","description":"native permissions"}]`)
	_, got = f.app.suggestions(p, "/", 1)
	if len(got) != 2 || got[0].Name != "config" || got[0].Description != locales.ClaudecfgConfigTitle() ||
		got[1].Name != "permissions" || got[1].Description != locales.ClaudecfgPermissions() {
		t.Fatalf("同名时应只列本地补全：%+v", got)
	}
}

func TestSlashSettingsOpenForCurrentChat(t *testing.T) {
	for _, tc := range []struct {
		name, input string
		permissions bool
	}{
		{name: "config", input: "/config"},
		{name: "permissions", input: "  /permissions  ", permissions: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newChatFixture(t)
			f.app.device = f.withHosts(t, "other")[0].ID
			f.answers["config.permissions.read"] = `{"servers":[{"name":"test-mcp","scope":"local"}]}`
			f.answers["config.mcp.tools"] = `[]`
			f.click(t, "修登录页")
			f.push(`"cwd":"/home/box/this-chat"`)
			p := f.app.pane
			file := &draftFile{id: 1, name: "note.txt", entry: "/home/box/note.txt"}
			p.att.files = []*draftFile{file}
			f.tt.Frame()
			f.link().Took()
			f.click(t, "消息")
			f.tt.Type(tc.input)
			if tc.permissions {
				f.click(t, locales.ComposerSend())
			} else {
				f.tt.Key(0, ui.KeyEnter)
			}
			if f.app.page != pageDevice || f.app.device != f.id || f.app.cfg == nil || f.app.cfg.Open() != tc.permissions {
				t.Fatalf("应打开当前对话设备的设置，实际为 %q %q", f.app.page, f.app.device)
			}
			var requests []string
			if tc.permissions {
				f.see(t, locales.ClaudecfgPermReload())
				f.click(t, "test-mcp")
				requests = []string{"config.permissions.read", "config.mcp.tools", "config.permissions.read"}
			}
			f.checkSlashSettingsCalls(t, requests)
			f.click(t, locales.SettingsBackToApp())
			if f.app.pane != p || p.draft != "" || len(p.att.files) != 1 || p.att.files[0] != file {
				t.Fatal("只应清除命令文字，保留对话与附件")
			}
		})
	}
}

func (f *chatFixture) checkSlashSettingsCalls(t *testing.T, want []string) {
	t.Helper()
	var got []string
	for _, c := range f.link().Took() {
		switch c.Method {
		case "conv.send":
			t.Fatal("裸命令不应发给 Claude Code")
		case "config.permissions.read", "config.mcp.tools":
			got = append(got, c.Method)
			if cwd := c.Str("cwd"); cwd != "/home/box/this-chat" {
				t.Errorf("%s 用了工作目录 %q", c.Method, cwd)
			}
		}
	}
	if !slices.Equal(got, want) {
		t.Errorf("权限页请求 %v，预期 %v", got, want)
	}
}

func TestSlashSettingsPreserveNormalSendAndEdit(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	for _, text := range []string{"/config theme=dark", "/permissions extra", "看看 /config 怎么用"} {
		if !f.app.send(text) || f.app.page != pageHome {
			t.Fatalf("没有按原路径发送 %q", text)
		}
		if got := f.sent("conv.send"); len(got) != 1 || !strings.Contains(got[0], `"text":"`+text+`"`) {
			t.Fatalf("发送请求：%v", got)
		}
	}
	f.app.pane.acts.editing = &edit{user: items.Item{ID: "u1"}}
	if !f.app.send("/permissions") || f.app.page != pageHome {
		t.Fatal("编辑旧消息时不应打开设置")
	}
	if got := f.sent("conv.rewrite"); len(got) != 1 || !strings.Contains(got[0], `"text":"/permissions"`) {
		t.Fatalf("编辑请求：%v", got)
	}
}
