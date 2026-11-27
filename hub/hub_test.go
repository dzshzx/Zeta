package hub

import (
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/settings"
)

func newHub(t *testing.T, devices *hubtest.Devices) (*Hub, *settings.Settings) {
	t.Helper()
	h, s, _ := newHubIn(t, devices)
	return h, s
}

func newHubIn(t *testing.T, devices *hubtest.Devices) (*Hub, *settings.Settings, string) {
	t.Helper()
	dir := t.TempDir()
	s, err := settings.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	sync := func(f func()) { f() }
	h := New(s, devices, sync, sync)
	h.SearchDelay = 0
	return h, s, dir
}

func ids(list []hostlink.Session) []string {
	var out []string
	for _, s := range list {
		out = append(out, s.ID)
	}
	return out
}

func TestAddingTheFirstHostShowsItsSessions(t *testing.T) {
	d := hubtest.NewDevices()
	h, s, dir := newHubIn(t, d)
	h.Start()
	if h.Backend() != "" || len(h.Listed()) != 0 || d.Watching() != nil {
		t.Fatal("watching without hosts")
	}
	x, err := h.AddHost("box", "~/.ssh/k", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	if h.Backend() != x.ID || s.Backend() != x.ID || x.Hostname != "box" || x.Key != "~/.ssh/k" || x.DefaultCwd != x.Home {
		t.Errorf("host %+v, backend %q", x, h.Backend())
	}
	if !reflect.DeepEqual(d.Watching(), []string{x.ID}) {
		t.Fatalf("watching %v", d.Watching())
	}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "b", Updated: 2}, {ID: "a", Updated: 1}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"b", "a"}) || h.Sessions()[0].Backend != x.ID {
		t.Errorf("sessions %v", got)
	}
	// 再加一台：侧栏不换
	y, _ := h.AddHost("lab", "", d.Found("lab"))
	if h.Backend() != x.ID || !reflect.DeepEqual(d.Watching(), []string{x.ID}) {
		t.Errorf("backend %q after adding %s", h.Backend(), y.ID)
	}
	again, _ := settings.Load(dir)
	if len(again.Hosts()) != 2 {
		t.Errorf("saved hosts %v", again.Hosts())
	}
}

func TestUpdatesMergeAndAllHostsListTogether(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.Start()
	d.Push(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "a2", Updated: 20}, {ID: "a1", Updated: 10}}})
	h.ListAll()
	if !h.AllHosts() || !reflect.DeepEqual(d.Watching(), []string{a.ID, b.ID}) {
		t.Fatalf("watching %v", d.Watching())
	}
	d.Push(b.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "b1", Updated: 15}}})
	if got := ids(h.Ordered(time.Now(), hostlink.Session{})); !reflect.DeepEqual(got, []string{"a2", "b1", "a1"}) {
		t.Errorf("merged %v", got)
	}
	// 增量：a1 有了新回答、a2 删掉，b 的不动
	d.Push(a.ID, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "a1", Updated: 30}}, Remove: []string{"a2"}})
	if got := ids(h.Ordered(time.Now(), hostlink.Session{})); !reflect.DeepEqual(got, []string{"a1", "b1"}) {
		t.Errorf("after update %v", got)
	}
	// 一台连不上：列着几台时注明是哪台；连上了就清掉
	d.Fail(b.ID, "对方拒绝连接")
	if got := h.Problem(); got != "b：没读到会话列表：对方拒绝连接" {
		t.Errorf("problem %q", got)
	}
	// 连不上的那台，刷新时重连
	h.Refresh()
	if d.Opened(b.ID) != 2 || d.Opened(a.ID) != 1 {
		t.Errorf("opened a %d, b %d", d.Opened(a.ID), d.Opened(b.ID))
	}
	d.Fail(b.ID, "")
	if h.Problem() != "" {
		t.Errorf("problem %q", h.Problem())
	}
	// 只列 b：a 的订阅断开、会话拿掉
	h.ListOnly(b.ID)
	if h.AllHosts() || h.Backend() != b.ID || !reflect.DeepEqual(d.Watching(), []string{b.ID}) || !reflect.DeepEqual(ids(h.Sessions()), []string{"b1"}) {
		t.Errorf("watching %v, sessions %v", d.Watching(), ids(h.Sessions()))
	}
	// 断开以后那台再推来的不算
	d.PushStale(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "late"}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"b1"}) {
		t.Errorf("stale push landed: %v", got)
	}
}

func TestGroupsAndPins(t *testing.T) {
	d := hubtest.NewDevices()
	h, s := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	now := time.Date(2026, 11, 22, 15, 0, 0, 0, time.Local)
	midnight := time.Date(2026, 11, 22, 0, 0, 0, 0, time.Local).UnixMilli()
	d.Push(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "new", Updated: midnight + 1000},
		{ID: "pinned-old", Updated: midnight - 1000},
		{ID: "archived", Updated: midnight + 500, Archived: true},
		{ID: "old", Updated: midnight - 2000},
	}})
	failed := func(err error) { t.Error(err) }
	h.TogglePin(h.Sessions()[1], failed)
	g := h.Groups(now, hostlink.Session{})
	if !reflect.DeepEqual(ids(g.Pinned), []string{"pinned-old"}) || !reflect.DeepEqual(ids(g.Today), []string{"new"}) || !reflect.DeepEqual(ids(g.Earlier), []string{"old"}) {
		t.Errorf("groups %v %v %v", ids(g.Pinned), ids(g.Today), ids(g.Earlier))
	}
	// 置顶记在核心里，本机设置不记
	if got := d.SessionCalls(); !reflect.DeepEqual(got, []string{a.ID + ` sessions.pin {"pinned":true,"session":"pinned-old"}`}) || s.Pins() != nil {
		t.Errorf("发出 %q，本机记的 %v", got, s.Pins())
	}
	h.TogglePin(h.Sessions()[1], failed)
	if got := d.SessionCalls(); len(h.Groups(now, hostlink.Session{}).Pinned) != 0 || len(got) != 1 || !strings.Contains(got[0], `"pinned":false`) {
		t.Errorf("取消置顶：发出 %q", got)
	}
}

func TestRemovingTheViewedHost(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.Start()
	d.Push(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "a1"}}})
	if err := h.RemoveHost(a.ID); err != nil {
		t.Fatal(err)
	}
	if h.Backend() != b.ID || !reflect.DeepEqual(d.Watching(), []string{b.ID}) || len(h.Sessions()) != 0 || !reflect.DeepEqual(d.Retained(), []string{b.ID}) {
		t.Errorf("backend %q, watching %v, sessions %v, retained %v", h.Backend(), d.Watching(), h.Sessions(), d.Retained())
	}
	if h.Name(a.ID) != "已删除的设备" {
		t.Error("removed host still named")
	}
}

func TestChangingTheKeyReconnects(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	h.Start()
	renamed := a
	renamed.CustomName = "工作站"
	_ = h.UpdateHost(renamed)
	if d.Opened(a.ID) != 1 || h.Name(a.ID) != "工作站" {
		t.Errorf("rename reconnected: %d", d.Opened(a.ID))
	}
	rekeyed := renamed
	rekeyed.Key = "~/.ssh/other"
	_ = h.UpdateHost(rekeyed)
	if d.Opened(a.ID) != 2 {
		t.Errorf("opened %d", d.Opened(a.ID))
	}
	if got := h.OtherNames("x"); !reflect.DeepEqual(got, []string{"工作站"}) {
		t.Errorf("names %v", got)
	}
}

func TestConfigHostsSkipKnownAndLocalhost(t *testing.T) {
	d := hubtest.NewDevices()
	d.Config = []string{"box", "localhost", "lab"}
	h, _ := newHub(t, d)
	got, err := h.ConfigHosts([]string{"box"})
	if err != nil || !reflect.DeepEqual(got, []string{"lab"}) {
		t.Errorf("config hosts %v %v", got, err)
	}
}

// 分组算好存着：列表、置顶、开着的会话变了或过了零点才重算，侧栏每帧取到的是同一份。
func TestGroupsFollowChanges(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	x, _ := h.AddHost("box", "", d.Found("box"))
	now := time.Date(2026, 11, 22, 12, 0, 0, 0, time.Local)
	today := now.Add(-time.Hour).UnixMilli()
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "a", Updated: today}, {ID: "b", Updated: today - 1}, {ID: "c", Updated: 1, Archived: true},
	}})
	g := h.Groups(now, hostlink.Session{})
	if again := h.Groups(now, hostlink.Session{}); &again.Today[0] != &g.Today[0] {
		t.Error("没变却重算了")
	}
	check := func(what string, now time.Time, current hostlink.Session, pinned, todays, earlier, archived []string) {
		t.Helper()
		g := h.Groups(now, current)
		got := [][]string{ids(g.Pinned), ids(g.Today), ids(g.Earlier), ids(h.Archived(now, current))}
		if want := [][]string{pinned, todays, earlier, archived}; !reflect.DeepEqual(got, want) {
			t.Errorf("%s：%v，要 %v", what, got, want)
		}
	}
	check("起头", now, hostlink.Session{}, nil, []string{"a", "b"}, nil, []string{"c"})
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "b"}, func(err error) { t.Error(err) })
	check("置顶", now, hostlink.Session{}, []string{"b"}, []string{"a"}, nil, []string{"c"})
	h.SetArchived([]hostlink.Session{{Backend: x.ID, ID: "a"}}, true, func(err error) { t.Error(err) })
	check("归档", now, hostlink.Session{}, []string{"b"}, nil, nil, []string{"a", "c"})
	d.Push(x.ID, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "n", Updated: today + 1}}})
	check("推来新的", now, hostlink.Session{}, []string{"b"}, []string{"n"}, nil, []string{"a", "c"})
	check("开着的不在列表里", now, hostlink.Session{Backend: x.ID, ID: "new"}, []string{"b"}, []string{"new", "n"}, nil, []string{"a", "c"})
	check("过了零点", now.Add(24*time.Hour), hostlink.Session{}, []string{"b"}, nil, []string{"n"}, []string{"a", "c"})
	h.ListOnly(x.ID) // 列哪几台没变，照样重算
	check("刷新", now.Add(24*time.Hour), hostlink.Session{}, []string{"b"}, nil, []string{"n"}, []string{"a", "c"})
}
