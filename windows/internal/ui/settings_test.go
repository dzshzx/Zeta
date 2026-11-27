package ui

import (
	"context"
	"encoding/json"
	"errors"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/hub/claudecfg/cfgtest"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/settings"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	"github.com/dzshzx/Zeta/windows/internal/ui/uitest"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// settingsFixture 是设置各页的窗口：假设备的对话连接上，config.* 交给跑在临时 HOME 里的真 zeta-helper serve（cfgtest），
// accounts.*、quota.* 照 docs/core.md 的格式答。后台的活当场跑，交回主线程的排进队列，until 在测试的 goroutine 上执行。
// 邮箱都是 example.com 的假数据。
type settingsFixture struct {
	*fixture
	serve   *cfgtest.Serve
	posts   chan func()
	updates *fakeUpdates
	// relay 不空时这台在用中转 relay（不是订阅账号）：账号里没有在用的，quota.get 带 relay，conv.quota 被拒。
	relay string
}

func newSettingsFixture(t *testing.T) *settingsFixture {
	t.Helper()
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	f := &settingsFixture{serve: cfgtest.Start(t), posts: make(chan func(), 1000), updates: &fakeUpdates{}}
	d := hubtest.NewDevices()
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			if c.Method == "conv.open" {
				for _, line := range hubtest.Recorded("open", c.Conv()) {
					l.Push(line)
				}
				return nil, nil
			}
			return f.answer(c)
		})
	}
	run := func(fn func()) { fn() }
	post := func(fn func()) { f.posts <- fn }
	h := hub.New(s, d, run, post)
	app := New(h, run, post)
	app.now = func() time.Time { return now }
	app.About = &about.Page{Version: appversion.Version{Code: 80, Name: "0.8.0"}, Updates: f.updates, Prefs: s}
	h.Start()
	f.fixture = &fixture{devices: d, hub: h, app: app, tt: ui.NewTester(app.View, 1280, 860)}
	return f
}

// answer 是假 serve 的应答：config.*、diag.* 交给真的，账号和额度照格式编。
func (f *settingsFixture) answer(c hubtest.Call) (json.RawMessage, error) {
	switch {
	case strings.HasPrefix(c.Method, "config."), strings.HasPrefix(c.Method, "diag."):
		return f.serve.Call(context.Background(), c.Method, c.Params)
	case c.Method == "accounts.list" && f.relay != "":
		return json.RawMessage(`{"current":null,"accounts":[{"name":"claude","email":"a@example.com","subscription":"max"}],` +
			`"procs":[],"relays":[{"name":"` + f.relay + `","baseUrl":"https://relay.example.com",` +
			`"hasKey":true,"keyEditable":true,"current":true}]}`), nil
	case c.Method == "conv.quota" && f.relay != "":
		return nil, codedErr{"errNoSubscription", "这台设备正在用中转，没有订阅额度"}
	case c.Method == "quota.get" && f.relay != "":
		return json.RawMessage(`{"snapshot":null,"relay":"` + f.relay + `"}`), nil
	case c.Method == "accounts.list":
		return json.RawMessage(`{"current":{"name":"claude","email":"a@example.com","subscription":"max","current":true},` +
			`"accounts":[{"name":"claude","email":"a@example.com","subscription":"max","current":true},` +
			`{"name":"claude-b","email":"b@example.com","subscription":"pro"}],"procs":[],"blocker":""}`), nil
	case c.Method == "conv.quota":
		return json.RawMessage(`{"rate_limits_available":true}`), nil
	case c.Method == "quota.get", c.Method == "quota.usage":
		at := time.Now() // 额度按 Tester 的时钟（起时的真实时刻）看
		reset := at.Add(2 * time.Hour).UTC().Format(time.RFC3339)
		return json.RawMessage(`{"fresh":true,"snapshot":{"windows":[` +
			`{"kind":"weekly_all","label":"每周 · 全部模型","used":0.86,"resetsAt":"` + reset + `","severity":"warning","active":true},` +
			`{"kind":"five_hour","label":"5 小时","used":0.3,"resetsAt":"` + reset + `","severity":"normal"}],` +
			`"subscription":"max","updatedAt":"` + at.UTC().Format(time.RFC3339) + `"}}`), nil
	}
	return nil, errors.New("不认识的方法 " + c.Method)
}

// until 执行交回主线程的事、画帧，直到 done 为真；限时 30 秒。
func (f *settingsFixture) until(t *testing.T, what string, done func() bool) {
	t.Helper()
	uitest.Until(t, f.tt, f.posts, what, 30*time.Second, done)
}

// fakeUpdates 是关于页用的更新：available 时有新版本 0.9.0。
type fakeUpdates struct {
	available bool
	updated   int
}

func (u *fakeUpdates) Enabled() bool { return true }
func (u *fakeUpdates) Status() update.Status {
	if !u.available {
		return update.Status{Phase: update.UpToDate}
	}
	return update.Status{Phase: update.Available, Manifest: &update.Manifest{VersionCode: 90, VersionName: "0.9.0",
		Notes: "- 设置页接上了", PublishedAt: "2026-11-22T08:00:00Z", MSI: &update.Package{File: "Zeta-v0.9.0.msi", Size: 48 << 20}}}
}
func (u *fakeUpdates) Check()  {}
func (u *fakeUpdates) Update() { u.updated++ }
func (u *fakeUpdates) Cancel() {}

func TestSettingsPage(t *testing.T) {
	f := newSettingsFixture(t)
	f.withHosts(t, "box")
	f.click(t, "设置")
	// 侧栏换成设置的分类，右边是常规；各台的额度标在分类上
	f.until(t, "读到额度", func() bool { return f.shows("已用 86%") })
	f.see(t, "返回应用", "常规", "设备", "box", "添加设备", "关于", "外观", "主题模式", "跟随系统", "快捷键", "Ctrl+N")
	f.notSee(t, "修登录页", "有新版本")

	// 外观：选深色，存进设置，界面跟着换
	f.press(t, "主题模式")
	f.click(t, "深色")
	if dark, set := f.hub.Settings().Dark(); !set || !dark {
		t.Fatalf("外观没存上：%v %v", dark, set)
	}
	f.see(t, "深色")

	// 语言：默认跟随系统，菜单里是文字表里的几种、各用自己的写法；选了交给 mygo（这里是内存里的），按钮上换成它
	f.press(t, "语言")
	f.clickMenuItem(t, "English")
	if got := f.app.Language.Chosen(); got != "en" {
		t.Fatalf("选了 English，记下的是 %q", got)
	}
	f.see(t, "English")
	f.press(t, "语言")
	f.see(t, "简体中文")
	f.clickMenuItem(t, "跟随系统")
	if got := f.app.Language.Chosen(); got != "" {
		t.Fatalf("选回跟随系统，记下的是 %q", got)
	}

	// 有新版本时「关于」标出来，点进关于页能更新
	f.updates.available = true
	f.tt.Frame()
	f.see(t, "有新版本 0.9.0")
	f.click(t, "关于")
	if f.app.page != pageAbout {
		t.Fatalf("没进关于页：%q", f.app.page)
	}
	f.see(t, "新版本", "Zeta 0.9.0", "设置页接上了", "0.8.0", locales.SettingsUnofficial())
	f.click(t, "更新")
	if f.updates.updated != 1 {
		t.Fatal("点了更新没开始")
	}

	// 添加设备是另一类
	f.click(t, "添加设备")
	f.see(t, "从 SSH 配置导入", "手动添加")
	// Esc 回到对话
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.page != pageHome {
		t.Fatalf("Esc 以后在 %q", f.app.page)
	}
	f.click(t, "设置")
	f.click(t, "返回应用")
	if f.app.page != pageHome {
		t.Fatalf("返回应用以后在 %q", f.app.page)
	}
}

func TestDevicePageOpensItsAccounts(t *testing.T) {
	f := newSettingsFixture(t)
	hosts := f.withHosts(t, "box", "lab")
	f.click(t, "设置")
	// 账号与额度不单列一组：侧栏只有设备，额度写在设备行上；设备页里一行进它
	f.until(t, "读到额度", func() bool { return f.shows("已用 86%") })
	f.notSee(t, "账号与额度")
	f.clickButton(t, "lab", 0)
	f.see(t, "账号与额度", "检查连接")
	f.click(t, "账号与额度")
	if f.app.page != pageDevice || f.app.acct == nil || f.app.device != hosts[1].ID {
		t.Fatalf("page %q, device %q", f.app.page, f.app.device)
	}
	f.until(t, "账号页读完", func() bool { return f.shows("b@example.com") && f.shows("每周 · 全部模型") })
	f.see(t, "lab · 账号与额度", "订阅额度", "每周 · 全部模型", "当前", "切换", "刷新")
	f.notSee(t, "Esc") // 这里 Esc 回设备页，「返回应用」旁边不标

	// 返回键和 Esc 都回到那台的设备页（设备页的状态留着，不重新读），设备页上再按 Esc 才回到对话
	cfg := f.app.cfg
	f.click(t, "返回")
	if f.app.page != pageDevice || f.app.acct != nil || f.app.device != hosts[1].ID {
		t.Fatalf("返回到了 page %q, device %q", f.app.page, f.app.device)
	}
	if f.app.cfg != cfg {
		t.Fatal("返回设备页时重建了设备页")
	}
	f.see(t, "检查连接")
	f.see(t, "Esc")
	f.click(t, "账号与额度")
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.page != pageDevice || f.app.acct != nil {
		t.Fatalf("Esc 以后在 %q，账号页开着：%v", f.app.page, f.app.acct != nil)
	}
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.page != pageHome {
		t.Fatalf("设备页上 Esc 以后在 %q", f.app.page)
	}
}

func TestDevicePageShowsClaudeConfig(t *testing.T) {
	f := newSettingsFixture(t)
	f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet"}`)
	x := f.withHosts(t, "box")[0]
	f.tt.SetSize(1280, 1800)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	if f.app.page != pageDevice || f.app.device != x.ID {
		t.Fatalf("page %q, device %q", f.app.page, f.app.device)
	}
	f.until(t, "读到新对话默认", func() bool { return f.shows("Sonnet") })
	f.see(t, "连接", "检查连接", "新对话默认", "默认模型", "Claude", "自动压缩上下文", "压缩时机", "Claude Code 配置", "记忆", "工具权限", "删除设备")

	// 关掉自动压缩：存在这台的设置里，离开设备页时交给开着的对话（这里没有对话，只看存上了）
	f.press(t, "自动压缩上下文")
	entries, _ := f.hub.Settings().Store().Strings("remoteClaude")
	if !strings.Contains(strings.Join(entries, ","), x.ID+"/autoCompact=false") {
		t.Fatalf("自动压缩没存上：%v", entries)
	}

	// 点进记忆是设备页里的子页，返回回到设备页
	f.click(t, "记忆")
	f.until(t, "记忆页", func() bool { return f.shows("自动记忆") })
	f.click(t, "返回")
	f.see(t, "检查连接")
	if f.app.page != pageDevice {
		t.Fatalf("返回到了 %q", f.app.page)
	}
}

// TestSettingsPreviews 在设了 ZETA_PREVIEW_DIR 时把设置页、设备页（含默认模型的下拉和「更多模型」）、账号与额度、诊断（清单、点开的日志、运行日志）、关于（有新版本）、界面缩放 125% 的设置页画成 PNG（浅色、深色）。
func TestSettingsPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	scenes := []struct {
		name   string
		height int
		open   func(t *testing.T, f *settingsFixture)
	}{
		{"settings", 1000, func(t *testing.T, f *settingsFixture) {
			f.click(t, "设置")
			f.until(t, "读到额度", func() bool { return f.shows("已用 86%") })
		}},
		{"settings-device", 1900, func(t *testing.T, f *settingsFixture) {
			f.click(t, "设置")
			f.clickButton(t, "box", 0)
			f.until(t, "读到新对话默认", func() bool { return f.shows("Sonnet") })
		}},
		{"settings-device-models", 900, func(t *testing.T, f *settingsFixture) {
			withPrepare(f)
			f.click(t, "设置")
			f.clickButton(t, "box", 0)
			f.until(t, "要到清单", func() bool { return f.shows("Sonnet 5.5") })
			f.press(t, "默认模型")
			f.click(t, "更多模型")
		}},
		{"settings-accounts", 900, func(t *testing.T, f *settingsFixture) {
			f.click(t, "设置")
			f.clickButton(t, "box", 0)
			f.click(t, "账号与额度")
			f.until(t, "账号页读完", func() bool { return f.shows("b@example.com") && f.shows("每周 · 全部模型") })
		}},
		{"settings-about", 1000, func(t *testing.T, f *settingsFixture) {
			f.click(t, "设置")
			f.click(t, "关于")
		}},
		{"settings-diag", 1300, func(t *testing.T, f *settingsFixture) {
			withRunLog(t, f)
			showDiag(t, f)
		}},
		{"settings-diag-runlog", 800, func(t *testing.T, f *settingsFixture) {
			withRunLog(t, f)
			showDiag(t, f)
			f.click(t, "查看运行日志")
		}},
		{"settings-diag-file", 700, func(t *testing.T, f *settingsFixture) {
			showDiag(t, f)
			f.click(t, "修登录")
			f.until(t, "读到内容", func() bool { return f.shows("MCP 连不上：Authorization: Bearer fake-token-1\nboom\n") })
		}},
		{"settings-zoom-125", 1000, func(t *testing.T, f *settingsFixture) {
			f.app.setZoom(125) // 界面缩放 125%（zoom.go）：整窗按 1.25 倍画
			f.click(t, "设置")
		}},
		{"update-notice", 860, func(_ *testing.T, f *settingsFixture) {
			f.app.ShowUpdate("0.9.0")
		}},
		{"chat-quota", 900, func(t *testing.T, f *settingsFixture) {
			f.click(t, "新对话")
			f.until(t, "打开新对话", func() bool { c := f.hub.Chat(); return c != nil && c.Conv != nil })
			for _, line := range hubtest.Recorded("turn", f.hub.Chat().Conv.Name()) {
				f.devices.Chat(f.hub.Chat().Backend).Push(line)
			}
			f.until(t, "答完一轮", func() bool { return f.shows("上下文 0%") })
			f.click(t, "上下文 0%")
			f.until(t, "额度", func() bool { return f.shows("刚刚更新") })
		}},
	}
	for _, s := range scenes {
		for _, look := range previewLooks {
			i18n.SetLocaleFunc(nil) // 场景按源语言的文字点控件，画之前再换语言
			dark := look.dark
			f := newSettingsFixture(t)
			f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet","permissions":{"defaultMode":"acceptEdits"}}`)
			f.updates.available = true
			f.withHosts(t, "box")
			f.tt.SetSize(1280, s.height)
			f.tt.SetDark(dark)
			s.open(t, f)
			f.tt.Move(1275, 5) // 指针挪开，不留悬停的底色
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			look.speak(t, f.tt)
			f.tt.Frame()
			f.tt.Frame()
			out, err := os.Create(filepath.Join(dir, s.name+"-"+look.suffix()+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, f.tt.Image()); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}

// press 点无障碍名字是 name 的那个控件（开关、下拉这类，不算同名的一段字）。
func (f *fixture) press(t *testing.T, name string) {
	t.Helper()
	for _, a := range f.tt.AccessAll() {
		if a.Name == name && a.Role != ui.RoleText {
			f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
			f.tt.Frame()
			return
		}
	}
	t.Fatalf("没有 %q；%q", name, f.tt.Texts())
}

// 聊天页的用量弹出框最上面是这台的订阅额度（同手机版），打开时先读存着的（核心据此说是不是订阅），再借开着的进程问一次。
func TestUsagePopoverShowsQuota(t *testing.T) {
	f := newSettingsFixture(t)
	x := f.withHosts(t, "box")[0]
	f.click(t, "新对话")
	f.until(t, "打开新对话", func() bool { c := f.hub.Chat(); return c != nil && c.Conv != nil })
	for _, line := range hubtest.Recorded("turn", f.hub.Chat().Conv.Name()) {
		f.devices.Chat(x.ID).Push(line)
	}
	f.until(t, "答完一轮", func() bool { return f.shows("上下文 0%") })
	f.devices.Chat(x.ID).Took()
	f.click(t, "上下文 0%")
	f.until(t, "额度", func() bool { return f.shows("每周 · 全部模型") && f.shows("刚刚更新") })
	f.see(t, "订阅额度", "Max", "86%", "上下文", "本对话累计 · 1 轮")
	var methods []string
	for _, c := range f.devices.Chat(x.ID).Took() {
		methods = append(methods, c.Method)
	}
	if strings.Join(methods, " ") != "quota.get conv.quota quota.usage" {
		t.Fatalf("问额度：%v", methods)
	}
}

// 自动检查查到新版本时窗口底部提示一句，点「查看」进关于页；关掉就不再显示。
func TestUpdateNotice(t *testing.T) {
	f := newSettingsFixture(t)
	f.updates.available = true
	f.app.ShowUpdate("0.9.0")
	f.tt.Frame()
	f.see(t, "Zeta 0.9.0 已发布，可在设置中更新", "查看")
	f.click(t, "查看")
	if f.app.page != pageAbout {
		t.Fatalf("没进关于页：%q", f.app.page)
	}
	f.notSee(t, "Zeta 0.9.0 已发布，可在设置中更新")
	f.app.ShowUpdate("0.9.0")
	f.tt.Frame()
	f.clickButton(t, "关闭提示", 0)
	f.tt.Frame()
	f.notSee(t, "Zeta 0.9.0 已发布，可在设置中更新")
}

// codedErr 是核心答的带 code 的出错（同 hostlink.ReplyError）。
type codedErr struct{ code, msg string }

func (e codedErr) Error() string { return e.msg }
func (e codedErr) Code() string  { return e.code }

// 这台在用中转（不是订阅账号）：用量弹出框没有订阅额度一节，也不发 conv.quota、quota.usage；设置侧栏写在用的中转，
// 账号页没有订阅额度一组，在用的中转那一行写「当前」。
func TestRelayHidesQuota(t *testing.T) {
	f := newSettingsFixture(t)
	f.relay = "cpa"
	x := f.withHosts(t, "box")[0]
	f.click(t, "新对话")
	f.until(t, "打开新对话", func() bool { c := f.hub.Chat(); return c != nil && c.Conv != nil })
	for _, line := range hubtest.Recorded("turn", f.hub.Chat().Conv.Name()) {
		f.devices.Chat(x.ID).Push(line)
	}
	f.until(t, "答完一轮", func() bool { return f.shows("上下文 0%") })
	f.devices.Chat(x.ID).Took()
	f.click(t, "上下文 0%")
	f.until(t, "读完", func() bool { r := f.hub.Remote(x.ID); return !r.Quota.Refreshing() && r.Quota.Relay() == "cpa" })
	f.see(t, "上下文", "本对话累计 · 1 轮")
	f.notSee(t, "订阅额度", "86%", "Max")
	for _, c := range f.devices.Chat(x.ID).Took() {
		if c.Method == "conv.quota" || c.Method == "quota.usage" {
			t.Fatalf("在用中转时问了额度：%s", c.Method)
		}
	}

	f.click(t, "上下文 0%")
	f.click(t, "设置")
	f.until(t, "读到中转", func() bool { return f.shows("中转 cpa") })
	f.notSee(t, "已用 86%")
	f.click(t, "中转 cpa") // 侧栏那台：进设备页，「账号与额度」一行也写着中转
	f.until(t, "设备页写着中转", func() bool { return f.app.page == pageDevice && f.shows("检查连接") })
	f.click(t, "账号与额度")
	f.until(t, "账号页", func() bool { return f.shows("添加中转") })
	f.see(t, "cpa", "当前", "https://relay.example.com", "a@example.com")
	f.notSee(t, "每周 · 全部模型", "还没有登录")
	if f.shows("订阅额度") || f.shows("刷新") { // 中转一组的说明里也有「订阅额度」几个字，按整段比
		t.Fatalf("在用中转还显示额度：%q", f.tt.Texts())
	}
}
