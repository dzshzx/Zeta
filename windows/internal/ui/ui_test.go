package ui

import (
	"errors"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// fixture 是一个不连设备的窗口：假设备（hubtest），后台的事当场跑完，时钟停在 now。
type fixture struct {
	devices *hubtest.Devices
	hub     *hub.Hub
	app     *App
	tt      *ui.Tester
}

var now = time.Date(2026, 11, 22, 15, 0, 0, 0, time.Local)

func newFixture(t *testing.T) *fixture {
	t.Helper()
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	d := hubtest.NewDevices()
	sync := func(f func()) { f() }
	h := hub.New(s, d, sync, sync)
	h.SearchDelay = 0
	app := New(h, sync, sync)
	app.now = func() time.Time { return now }
	h.Start()
	return &fixture{devices: d, hub: h, app: app, tt: ui.NewTester(app.View, 1280, 860)}
}

// withHosts 先加好名叫 names 的几台。
func (f *fixture) withHosts(t *testing.T, names ...string) []sshlink.Host {
	t.Helper()
	var hosts []sshlink.Host
	for _, name := range names {
		x, err := f.hub.AddHost(name, "", f.devices.Found(name))
		if err != nil {
			t.Fatal(err)
		}
		hosts = append(hosts, x)
	}
	f.tt.Frame()
	return hosts
}

func (f *fixture) click(t *testing.T, text string) {
	t.Helper()
	if err := f.tt.Click(text); err != nil {
		t.Fatalf("click %q: %v; texts %q", text, err, f.tt.Texts())
	}
}

func (f *fixture) see(t *testing.T, texts ...string) {
	t.Helper()
	for _, text := range texts {
		if !f.tt.HasText(text) {
			t.Errorf("no %q in %q", text, f.tt.Texts())
		}
	}
}

func (f *fixture) notSee(t *testing.T, texts ...string) {
	t.Helper()
	for _, text := range texts {
		if f.tt.HasText(text) {
			t.Errorf("%q still shown in %q", text, f.tt.Texts())
		}
	}
}

// shows：有一处文字正好是 text。
func (f *fixture) shows(text string) bool {
	_, ok := f.tt.Find(text)
	return ok
}
func at(hours int) int64 {
	return time.Date(2026, 11, 22, hours, 0, 0, 0, time.Local).UnixMilli()
}

func TestFirstRunAddsADeviceFromTheSSHConfig(t *testing.T) {
	f := newFixture(t)
	f.devices.Config = []string{"box", "localhost", "lab"}
	f.devices.KeyList = []string{"~/.ssh/id_ed25519", "~/.ssh/work"}
	f.see(t, "还没有设备", "添加设备")
	f.click(t, "添加设备")
	f.see(t, "从 SSH 配置导入", "box", "lab", "自动选择", desktopFooter)
	f.notSee(t, "localhost")
	if f.shows("已添加") {
		t.Error("added hosts listed before any was added")
	}
	// 选一把私钥再加
	f.click(t, "自动选择")
	f.click(t, "work")
	if f.app.add.key != "~/.ssh/work" {
		t.Fatalf("key %q", f.app.add.key)
	}
	if err := f.tt.Click("添加"); err != nil {
		t.Fatal(err)
	}
	f.see(t, "已添加「box」", "Claude Code 2.1.330 · Linux x86_64 · SSH 密钥 work", "主机指纹 SHA256:")
	f.click(t, "好")
	x := f.hub.Hosts()[0]
	if x.Target != "box" || x.Key != "~/.ssh/work" || f.hub.Backend() != x.ID {
		t.Errorf("host %+v", x)
	}
	// 加过的不再列；侧栏换到它
	f.see(t, "已添加", "lab")
	if f.tt.HasText("还没有设备") {
		t.Error("sidebar still empty")
	}
	f.devices.Push(x.ID, hostlink.SessionsUpdate{Full: true})
	f.click(t, "返回应用")
	f.see(t, "还没有对话")
}

func TestManualAddExplainsWhatWentWrong(t *testing.T) {
	f := newFixture(t)
	f.devices.Probes["zeta@lab:2222"] = hubtest.Probe{Err: sshlink.Problem("对方拒绝连接（端口上没有 SSH 服务）")}
	f.app.open(pageDevices)
	f.tt.Frame()
	f.click(t, "地址")
	f.tt.Type("bad address")
	if f.app.add.draft != "bad address" {
		t.Fatalf("typed %q", f.app.add.draft)
	}
	if b, ok := f.tt.AccessByRole(ui.RoleButton, "添加"); !ok || !b.Disabled {
		t.Errorf("add enabled for a bad address: %+v", b)
	}
	f.app.add.draft = " ssh://zeta@lab:2222/ "
	f.tt.Frame()
	f.click(t, "添加")
	f.see(t, "没有添加「zeta@lab:2222」", "对方拒绝连接（端口上没有 SSH 服务）")
	if len(f.hub.Hosts()) != 0 || f.devices.Probed()[0] != "zeta@lab:2222" {
		t.Errorf("hosts %v, probed %v", f.hub.Hosts(), f.devices.Probed())
	}
}

func TestSidebarGroupsAndPins(t *testing.T) {
	f := newFixture(t)
	x := f.withHosts(t, "box")[0]
	f.devices.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14)},
		{ID: "s2", Preview: "看看日志", Cwd: "/srv/logs/", Updated: at(14) - 25*3600*1000, External: true},
		{ID: "s3", Title: "归档的", Updated: at(13), Archived: true},
	}})
	f.tt.Frame()
	f.see(t, "今天", "修登录页", "更早", "看看日志", "别处运行中") // 目录在悬停提示里，别处运行的标在标题后面
	f.notSee(t, "置顶", "归档的")
	if err := f.tt.RightClick("看看日志"); err != nil {
		t.Fatal(err)
	}
	if err := f.tt.ChooseMenuItem("置顶"); err != nil {
		t.Fatalf("menu %v: %v", f.tt.Menu(), err)
	}
	f.see(t, "置顶")
	f.notSee(t, "更早")
	if err := f.tt.RightClick("看看日志"); err != nil {
		t.Fatal(err)
	}
	if err := f.tt.ChooseMenuItem("取消置顶"); err != nil {
		t.Fatalf("menu %v: %v", f.tt.Menu(), err)
	}
	f.see(t, "更早")
}

func TestHostSwitcherListsAllDevices(t *testing.T) {
	f := newFixture(t)
	hosts := f.withHosts(t, "box", "lab")
	f.devices.Push(hosts[0].ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "a", Title: "盒子上的", Cwd: "/w/proj", Updated: at(10)}}})
	f.tt.Frame()
	f.see(t, "盒子上的")
	f.click(t, "box")
	f.click(t, "全部设备")
	if !f.hub.AllHosts() {
		t.Fatal("not listing all hosts")
	}
	f.devices.Push(hosts[1].ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "b", Title: "实验室的", Cwd: "/x/exp", Updated: at(11)}}})
	f.tt.Frame()
	f.see(t, "盒子上的", "实验室的", "box", "lab", "全部设备") // 列着全部设备时标题后面是设备名
	f.click(t, "全部设备")
	f.clickMenuItem(t, "lab") // 菜单里那一项（会话行后面也有「lab」两个字）
	f.see(t, "实验室的")
	f.notSee(t, "盒子上的")
	f.devices.Fail(hosts[1].ID, "连不上「lab」：网络不通")
	f.tt.Frame()
	f.see(t, "没读到会话列表：连不上「lab」：网络不通")
}

func TestEditingADevice(t *testing.T) {
	f := newFixture(t)
	f.devices.KeyList = []string{"~/.ssh/k"}
	hosts := f.withHosts(t, "box", "lab")
	f.click(t, "设置")
	// 侧栏的设备菜单上也写着 box：点设置页里那一行（按钮）
	f.clickButton(t, "box", 0)
	if f.app.page != pageDevice || f.app.device != hosts[0].ID {
		t.Fatalf("page %q, device %q", f.app.page, f.app.device)
	}
	f.see(t, "/home/box", "SHA256:")

	// 改名：点那一行弹出输入框，和别的设备重名时不让存
	f.click(t, "自定义设备名")
	f.app.edit.name = "lab"
	f.tt.Frame()
	f.see(t, "已经有叫「lab」的设备了")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "确定"); !a.Disabled {
		t.Error("重名也能确定")
	}
	f.app.edit.name = "工作站"
	f.tt.Frame()
	f.click(t, "确定")
	if f.hub.Name(hosts[0].ID) != "工作站" {
		t.Errorf("name %q", f.hub.Name(hosts[0].ID))
	}

	// 默认工作目录：~ 开头的换成家目录下
	f.click(t, "默认工作目录")
	f.app.edit.cwd = "~/src/"
	f.tt.Frame()
	f.click(t, "确定")
	x, _ := f.hub.Host(hosts[0].ID)
	if x.DefaultCwd != "/home/box/src" {
		t.Errorf("cwd %q", x.DefaultCwd)
	}

	// 改私钥：连上了才存
	f.click(t, "自动选择")
	f.click(t, "k")
	f.see(t, "SSH 密钥已改为 k")
	f.click(t, "好")
	if x, _ := f.hub.Host(hosts[0].ID); x.Key != "~/.ssh/k" {
		t.Errorf("key %q", x.Key)
	}
}

func TestCheckingFindsAChangedHostKey(t *testing.T) {
	f := newFixture(t)
	x := f.withHosts(t, "box")[0]
	fresh := hubtest.NewDevices().HostKey()
	f.devices.Probes["box"] = hubtest.Probe{Err: &sshlink.HostKeyChangedError{Seen: fresh}}
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.tt.Frame()
	f.click(t, "检查连接")
	f.see(t, "主机指纹变了", "现在的："+sshlink.Fingerprint(fresh))
	f.click(t, "改认新的")
	f.see(t, "已改认新的主机指纹，再检查一次连接")
	if got, _ := f.hub.Host(x.ID); got.HostKey != fresh {
		t.Error("new host key not saved")
	}
	// 连上了：家目录变了就跟着改
	moved := f.devices.Found("box")
	moved.Home = "/srv/box"
	f.devices.Probes["box"] = hubtest.Probe{Found: moved}
	f.click(t, "检查连接")
	f.see(t, "连上了：Claude Code 2.1.330")
	if got, _ := f.hub.Host(x.ID); got.Home != "/srv/box" || got.DefaultCwd != "/srv/box" {
		t.Errorf("host %+v", got)
	}
	f.devices.Probes["box"] = hubtest.Probe{Err: errors.New("dial tcp: i/o timeout")}
	f.click(t, "检查连接")
	f.see(t, "没连上：连接超时")
}

func TestRemovingADevice(t *testing.T) {
	f := newFixture(t)
	f.tt.SetSize(1280, 1800) // 删除设备在设备页最下面
	hosts := f.withHosts(t, "box", "lab")
	f.app.device = hosts[0].ID
	f.app.open(pageDevice)
	f.tt.Frame()
	f.click(t, "删除设备")
	f.see(t, "删除「box」？")
	f.click(t, "删除")
	if len(f.hub.Hosts()) != 1 || f.app.page != pageSettings || f.hub.Backend() != hosts[1].ID {
		t.Errorf("hosts %v, page %q", f.hub.Hosts(), f.app.page)
	}
	f.see(t, "lab")
}

func TestNormalizeCwd(t *testing.T) {
	for in, want := range map[string]string{"~": "/h", "~/a/": "/h/a", " /x//y ": "/x/y", "rel": "", "": ""} {
		got, ok := normalizeCwd(in, "/h")
		if got != want || ok != (want != "") {
			t.Errorf("%q: %q %v", in, got, ok)
		}
	}
}

// TestPreviews 在设了 ZETA_PREVIEW_DIR 时把几个场景画成 PNG（浅色、深色），给人看样子；不设时不画。
// Context.TitleBar 照真窗口给，右上角画出系统按钮的示意（withCaptionButtons），看得出整窗的样子。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	scenes := map[string]func(t *testing.T, f *fixture){
		"empty":   func(*testing.T, *fixture) {},
		"devices": func(_ *testing.T, f *fixture) { f.devices.Config = []string{"box", "lab"}; f.app.open(pageDevices) },
		"sidebar": func(t *testing.T, f *fixture) {
			x := f.withHosts(t, "box")[0]
			f.devices.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
				{ID: "s1", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14)},
				{ID: "s2", Preview: "看看日志", Cwd: "/srv/logs", Updated: at(1) - 3600*1000},
			}})
		},
		"device": func(t *testing.T, f *fixture) {
			f.app.device = f.withHosts(t, "box")[0].ID
			f.app.open(pageDevice)
		},
		"device-remove": func(t *testing.T, f *fixture) {
			f.app.device = f.withHosts(t, "box")[0].ID
			f.app.open(pageDevice)
			f.tt.SetSize(1280, 1800) // 删除设备在设备页最下面
			f.tt.Frame()
			f.click(t, "删除设备")
		},
	}
	all := map[string]func(t *testing.T) *fixture{}
	for name, setup := range scenes {
		all[name] = func(t *testing.T) *fixture { f := newFixture(t); setup(t, f); return f }
	}
	for _, scenes := range []map[string]func(t *testing.T, f *chatFixture){chatScenes, sidebarScenes} {
		for name, setup := range scenes {
			all[name] = func(t *testing.T) *fixture { f := newChatFixture(t); setup(t, f); return f.fixture }
		}
	}
	for name, setup := range all {
		for _, dark := range []bool{false, true} {
			f := setup(t)
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			f.tt.Advance(2 * time.Second) // 列表跟到底部的动画走完
			f.tt.Frame()
			suffix := "light"
			if dark {
				suffix = "dark"
			}
			out, err := os.Create(filepath.Join(dir, name+"-"+suffix+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, withCaptionButtons(f.tt.Image(), dark)); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}

// clickButton 点第 n 个（从 0 数）名叫 name 的按钮：同一页上有几个同名的按钮时用。
func (f *fixture) clickButton(t *testing.T, name string, n int) {
	t.Helper()
	for _, a := range f.tt.AccessAll() {
		if a.Role != ui.RoleButton || a.Name != name {
			continue
		}
		if n == 0 {
			f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
			return
		}
		n--
	}
	t.Fatalf("no button %q", name)
}

// clickMenuItem 点开着的菜单里名叫 name 的那一项（界面上别处也有同样的字时用）。
func (f *fixture) clickMenuItem(t *testing.T, name string) {
	t.Helper()
	for _, a := range f.tt.AccessAll() {
		if a.Name == name && (a.Role == ui.RoleMenuItem || a.Role == ui.RoleMenuItemRadio || a.Role == ui.RoleMenuItemCheckBox) {
			f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
			f.tt.Frame()
			return
		}
	}
	t.Fatalf("菜单里没有 %q", name)
}
