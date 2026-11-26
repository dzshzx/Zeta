package ui

import (
	"cmp"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// sortSample 推一份带三种排序用的值的列表：s1 最新回答最新、发起最早，s3 发起最新、回答最早（昨天）；手动位置 s1 > s2 > s3。
func (f *chatFixture) sortSample() {
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14), Created: at(1), Replied: at(14), Rank: 400},
		{ID: "s2", Title: "看看日志", Cwd: "/srv/logs", Updated: at(13), Created: at(5), Replied: at(12), Rank: 300},
		{ID: "s3", Title: "部署脚本", Cwd: "/srv/deploy", Updated: at(12), Created: at(9), Replied: at(-14), Rank: 200},
	}})
	f.tt.Frame()
}

// rowBox 是标题为 title 的会话行在窗口里的框。
func (f *fixture) rowBox(t *testing.T, title string) ui.Rect {
	t.Helper()
	a, ok := f.tt.AccessByRole(ui.RoleButton, title)
	if !ok {
		t.Fatalf("没有「%s」这一行：%q", title, f.tt.Texts())
	}
	return a.Bounds
}

// rowOrder 是侧栏里 titles 这几行从上到下的先后。
func (f *fixture) rowOrder(t *testing.T, titles ...string) string {
	t.Helper()
	y := map[string]float32{}
	for _, title := range titles {
		y[title] = f.rowBox(t, title).Y
	}
	sorted := slices.Clone(titles)
	slices.SortFunc(sorted, func(a, b string) int { return cmp.Compare(y[a], y[b]) })
	return strings.Join(sorted, " ")
}

// drag 按住标题为 from 的那一行，拖到 to 那一行的上半（below 为假）或下半松开。
func (f *fixture) drag(t *testing.T, from, to string, below bool) {
	t.Helper()
	a, b := f.rowBox(t, from), f.rowBox(t, to)
	x, y := a.X+a.W/2, a.Y+a.H/2
	ty := b.Y + b.H/4
	if below {
		ty = b.Y + b.H*3/4
	}
	f.tt.Press(x, y)
	f.tt.Move(x, y+(ty-y)/3)
	f.tt.Move(x, ty)
	f.tt.Release(x, ty)
	f.tt.Frame()
}

// 排序按钮在第一组的标题右边，鼠标在侧栏上才有；菜单列三种、当前那项打勾，选了就换排序、存进设置。
func TestSortMenuSwitchesTheOrder(t *testing.T) {
	f := newChatFixture(t)
	f.sortSample()
	f.tt.Move(900, 400) // 聊天窗格里
	f.tt.Frame()
	if _, ok := f.tt.AccessByRole(ui.RoleMenuButton, "排序"); ok {
		t.Error("鼠标不在侧栏上时也有排序按钮")
	}
	f.tt.Move(120, 500)
	f.tt.Frame()
	f.see(t, "今天", "更早")
	if got := f.rowOrder(t, "修登录页", "看看日志", "部署脚本"); got != "修登录页 看看日志 部署脚本" {
		t.Errorf("最新回答：%s", got)
	}
	open := func() {
		t.Helper()
		b, ok := f.tt.AccessByRole(ui.RoleMenuButton, "排序")
		if !ok {
			t.Fatalf("没有排序按钮：%q", f.tt.Texts())
		}
		f.tt.ClickAt(b.Bounds.X+b.Bounds.W/2, b.Bounds.Y+b.Bounds.H/2)
		f.tt.Frame()
	}
	open()
	var items []string
	for _, a := range f.tt.AccessAll() {
		if a.Role == ui.RoleMenuItemRadio {
			items = append(items, a.Name+map[bool]string{true: "✓"}[a.Checked])
		}
	}
	if got := strings.Join(items, " "); got != "最新回答✓ 发起时间 手动排序" {
		t.Errorf("菜单 %s", got)
	}
	f.clickMenuItem(t, "发起时间")
	if f.hub.Sort() != settings.SortCreated {
		t.Errorf("排序 %q", f.hub.Sort())
	}
	if got := f.rowOrder(t, "修登录页", "看看日志", "部署脚本"); got != "部署脚本 看看日志 修登录页" {
		t.Errorf("发起时间：%s", got)
	}
	f.tt.Move(120, 500)
	open()
	f.clickMenuItem(t, "手动排序")
	f.see(t, "对话")
	f.notSee(t, "今天", "更早")
	if got := f.rowOrder(t, "修登录页", "看看日志", "部署脚本"); got != "修登录页 看看日志 部署脚本" {
		t.Errorf("手动排序：%s", got)
	}
}

// 手动排序时按住一行拖到别处：落点在两行中间取中点，经那台的 sessions.order 记下，列表马上照新的排；
// 只按下松开照样是打开，拖到置顶那一组无效，不在手动排序、多选时拖不动。
func TestManualSortDragsARow(t *testing.T) {
	f := newChatFixture(t)
	f.sortSample()
	f.hub.SetSort(settings.SortManual)
	f.tt.Frame()
	f.drag(t, "部署脚本", "修登录页", true)
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.HasSuffix(got[0], ` sessions.order {"rank":350,"session":"s3"}`) {
		t.Fatalf("发出 %q", got)
	}
	if got := f.rowOrder(t, "修登录页", "看看日志", "部署脚本"); got != "修登录页 部署脚本 看看日志" {
		t.Errorf("拖完：%s", got)
	}
	// 拖到最上面：比第一行大 1000
	f.drag(t, "看看日志", "修登录页", false)
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.HasSuffix(got[0], ` sessions.order {"rank":1400,"session":"s2"}`) {
		t.Errorf("拖到最上面：发出 %q", got)
	}
	// 按下不动就松开：打开它，不发 sessions.order
	b := f.rowBox(t, "部署脚本")
	f.tt.Press(b.X+b.W/2, b.Y+b.H/2)
	f.tt.Release(b.X+b.W/2, b.Y+b.H/2)
	f.tt.Frame()
	if c := f.hub.Chat(); c == nil || c.Session.ID != "s3" {
		t.Errorf("单击没打开：%+v", c)
	}
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("单击发出 %q", got)
	}
	// 置顶的一组和其余之间拖不过去
	f.hub.TogglePin(hostlink.Session{Backend: f.id, ID: "s1"}, func(err error) { t.Error(err) })
	f.tt.Frame()
	f.devices.SessionCalls()
	f.drag(t, "看看日志", "修登录页", true)
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("拖进置顶：发出 %q", got)
	}
	// 多选时拖不动
	f.tt.ClickWith(ui.Cmd, "看看日志")
	f.tt.Frame()
	f.drag(t, "部署脚本", "看看日志", false)
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("多选时：发出 %q", got)
	}
	f.app.clearSelection()
	// 不在手动排序时拖不动
	f.hub.SetSort(settings.SortReplied)
	f.tt.Frame()
	f.drag(t, "部署脚本", "看看日志", false)
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("最新回答时：发出 %q", got)
	}
}
