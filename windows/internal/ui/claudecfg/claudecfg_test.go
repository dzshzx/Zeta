package claudecfg

import (
	"archive/zip"
	"bytes"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/claudecfg/cfgtest"
	"github.com/egoist/mygo/ui"
)

// fixture 是嵌在一个假设备页里的这部分界面，后面接一个跑在临时 HOME 里的真 zeta-helper serve。后台的活同步跑完。
type fixture struct {
	t     *testing.T
	serve *cfgtest.Serve
	prefs *fakePrefs
	page  *Page
	tt    *ui.Tester
}

func newFixture(t *testing.T, opts Options) *fixture {
	serve := cfgtest.Start(t)
	prefs := &fakePrefs{strings: map[string][]string{}}
	opts.Core = coreapi.New(serve)
	opts.Device = cfg.DeviceSettings(prefs, "dev1")
	opts.Run = func(f func()) { f() }
	opts.Post = func(f func()) { f() }
	page := New(opts)
	page.Load()
	view := func(c *ui.Context) {
		if page.Open() {
			ui.Column(c).Grow(1).Children(func() { page.SubPage(c) })
			return
		}
		ui.Scroll(c).Grow(1).Children(func() {
			ui.Column(c).Padding(24).Gap(20).Children(func() { page.Sections(c) })
		})
	}
	f := &fixture{t: t, serve: serve, prefs: prefs, page: page, tt: ui.NewTester(view, 1280, 1600)}
	f.tt.Frame()
	return f
}

// press 点无障碍名字是 name 的那个控件（不算同名的一段字）。
func (f *fixture) press(name string) {
	f.t.Helper()
	f.tt.Frame()
	for _, a := range f.tt.AccessAll() {
		if a.Name == name && a.Role != ui.RoleText {
			f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
			f.tt.Frame()
			return
		}
	}
	f.t.Fatalf("没有 %q；%q", name, f.tt.Texts())
}

// click 点写着 text 的东西。
func (f *fixture) click(text string) {
	f.t.Helper()
	f.tt.Frame()
	if err := f.tt.Click(text); err != nil {
		f.t.Fatalf("点 %q：%v；%q", text, err, f.tt.Texts())
	}
	f.tt.Frame()
}

// pick 在下拉 name 里选 option。
func (f *fixture) pick(name, option string) {
	f.t.Helper()
	f.press(name)
	f.click(option)
}

// typeInto 把 text 打进无障碍名字是 name 的输入框。
func (f *fixture) typeInto(name, text string) {
	f.t.Helper()
	f.press(name)
	f.tt.Type(text)
	f.tt.Frame()
}

func (f *fixture) see(texts ...string) {
	f.t.Helper()
	f.tt.Frame()
	for _, text := range texts {
		if !f.tt.HasText(text) {
			f.t.Errorf("没有 %q；%q", text, f.tt.Texts())
		}
	}
}

type fakePrefs struct {
	strings map[string][]string
}

func (p *fakePrefs) Bool(string) (bool, bool) { return false, false }
func (p *fakePrefs) Int(string) (int64, bool) { return 0, false }
func (p *fakePrefs) Strings(key string) ([]string, bool) {
	v, ok := p.strings[key]
	return v, ok
}

func (p *fakePrefs) SetStrings(key string, v []string) error {
	p.strings[key] = v
	return nil
}

func TestDefaultsAndDeviceSettings(t *testing.T) {
	f := newFixture(t, Options{})
	f.serve.Write(t, ".claude/settings.json", "{\n  \"model\": \"sonnet\"\n}\n")
	f.page.Load()
	f.see("新对话默认", "默认权限模式", "Claude Code 配置", "自动压缩上下文")

	f.pick("默认权限模式", "计划模式")
	if got := f.serve.Read(".claude/settings.json"); !strings.Contains(got, `"defaultMode": "plan"`) || !strings.Contains(got, `"model": "sonnet"`) {
		t.Errorf("settings.json：%s", got)
	}
	f.pick("上下文窗口", "400K")
	if got := f.serve.Read(".claude/settings.json"); !strings.Contains(got, "400000") {
		t.Errorf("settings.json：%s", got)
	}

	f.press("自动压缩上下文")
	f.press("自动生成对话标题")
	got := strings.Join(f.prefs.strings["remoteClaude"], ",")
	if got != "dev1/autoCompact=false,dev1/autoTitle=false" {
		t.Errorf("remoteClaude %q", got)
	}

	// 闲置超时存在那台主机上：没设时是 10 分钟，选了写进那边的文件，别的客户端设的不在几档里的值也列出来
	f.see("10 分钟")
	f.pick("闲置超时", "不自动结束")
	if got := f.serve.Read(".config/zeta/idle-minutes"); got != "0\n" {
		t.Errorf("idle-minutes %q", got)
	}
	f.serve.Write(t, ".config/zeta/idle-minutes", "45\n")
	f.page.Load()
	f.see("45 分钟")
}

func TestMemoryEditorAsksBeforeOverwriting(t *testing.T) {
	f := newFixture(t, Options{})
	f.press("打开记忆")
	f.see("用户指令", "还没有创建，打开就能写")
	f.press("打开CLAUDE.md")
	f.typeInto("CLAUDE.md", "回答用中文")
	f.click("保存")
	if got := f.serve.Read(".claude/CLAUDE.md"); got != "回答用中文" {
		t.Fatalf("CLAUDE.md %q", got)
	}

	// 编辑期间文件被改过：先问，确认后覆盖
	f.typeInto("CLAUDE.md", "。")
	f.serve.Write(t, ".claude/CLAUDE.md", "别处改的")
	f.click("保存")
	f.see("文件已经变了")
	f.click("仍然保存")
	if got := f.serve.Read(".claude/CLAUDE.md"); got != "回答用中文。" {
		t.Errorf("CLAUDE.md %q", got)
	}
}

func TestSkillImportAndReplace(t *testing.T) {
	pkg := skillZip(t, "---\nname: tidy\ndescription: 整理代码\n---\n正文\n")
	f := newFixture(t, Options{OpenZip: func() (string, []byte, error) { return "tidy.zip", pkg, nil }})
	f.press("打开Skills")
	f.see("还没有 Skill")
	f.click("选择文件")
	f.see("已安装")
	f.click("知道了")
	f.see("整理代码")
	if !strings.Contains(f.serve.Read(".claude/skills/tidy/SKILL.md"), "整理代码") {
		t.Fatal("没装进 ~/.claude/skills/tidy")
	}

	f.click("选择文件")
	f.see("替换 Skill？")
	f.click("替换")
	f.see("已替换")
}

func skillZip(t *testing.T, skill string) []byte {
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	fw, err := w.Create("tidy/SKILL.md")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fw.Write([]byte(skill)); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestNewSubagent(t *testing.T) {
	f := newFixture(t, Options{})
	f.press("打开Subagent")
	f.press("新建 Subagent")
	f.typeInto("名称", "code-reviewer")
	f.typeInto("什么时候用", "改完代码后审查")
	f.press("全部工具")
	f.press("读文件")
	f.typeInto("系统提示词", "你是审查员")
	f.click("保存")
	got := f.serve.Read(".claude/agents/code-reviewer.md")
	for _, want := range []string{"name: code-reviewer", "description: 改完代码后审查", "tools: Read", "你是审查员"} {
		if !strings.Contains(got, want) {
			t.Errorf("没有 %q：%s", want, got)
		}
	}
	f.see("code-reviewer")
}

func TestToolPermissions(t *testing.T) {
	f := newFixture(t, Options{})
	f.serve.Write(t, ".claude/settings.json", `{"permissions":{"allow":["Bash(npm test)"]}}`)
	f.press("打开工具权限")
	f.see("另有 1 条带参数的规则", "Bash(npm test)")

	f.pick("Bash", "拒绝")
	if got := f.serve.Read(".claude/settings.json"); !strings.Contains(got, `"deny": [`) || !strings.Contains(got, `"Bash"`) {
		t.Errorf("settings.json：%s", got)
	}

	f.click("添加规则")
	f.typeInto("规则", "Read(./.env)")
	f.click("保存")
	if got := f.serve.Read(".claude/settings.json"); !strings.Contains(got, `"Read(./.env)"`) {
		t.Errorf("settings.json：%s", got)
	}

	f.press("删除：Bash(npm test)")
	f.click("删除")
	if got := f.serve.Read(".claude/settings.json"); strings.Contains(got, "npm test") {
		t.Errorf("没删掉：%s", got)
	}
}

func TestMcpServers(t *testing.T) {
	f := newFixture(t, Options{})
	f.press("打开MCP 服务器")
	f.see("还没有 MCP 服务器")
	f.click("添加服务器")
	f.typeInto("名称", "bad name")
	f.typeInto("服务器地址", "https://mcp.invalid/mcp")
	f.click("添加")
	f.see("名称只能用字母、数字、下划线和连字符")

	f.press("名称")
	f.tt.Key(ui.Cmd, ui.KeyA)
	f.tt.Type("docs")
	f.typeInto("Bearer Token", "secret-token")
	f.click("添加")
	f.see("docs", "https://mcp.invalid/mcp")
	if strings.Contains(strings.Join(f.tt.Texts(), "\n"), "secret-token") {
		t.Error("界面上露出了 Token")
	}

	f.press("打开docs")
	f.see("Bearer Token", "所有项目")
	f.click("删除")
	f.see("删除服务器")
	f.press("删除")
	f.see("还没有 MCP 服务器")
}
