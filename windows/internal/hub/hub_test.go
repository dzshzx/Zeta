package hub

import (
	"reflect"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/dzshzx/Zeta/windows/internal/settings"
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
	return New(s, devices, func(f func()) { f() }), s, dir
}

func ids(list []host.Session) []string {
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
	d.Push(x.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{{ID: "b", Updated: 2}, {ID: "a", Updated: 1}}})
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
	d.Push(a.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{{ID: "a2", Updated: 20}, {ID: "a1", Updated: 10}}})
	h.ListAll()
	if !h.AllHosts() || !reflect.DeepEqual(d.Watching(), []string{a.ID, b.ID}) {
		t.Fatalf("watching %v", d.Watching())
	}
	d.Push(b.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{{ID: "b1", Updated: 15}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"a2", "b1", "a1"}) {
		t.Errorf("merged %v", got)
	}
	// 增量：a1 变新、a2 删掉，b 的不动
	d.Push(a.ID, host.SessionsUpdate{Upsert: []host.Session{{ID: "a1", Updated: 30}}, Remove: []string{"a2"}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"a1", "b1"}) {
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
	d.PushStale(a.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{{ID: "late"}}})
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
	d.Push(a.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{
		{ID: "new", Updated: midnight + 1000},
		{ID: "pinned-old", Updated: midnight - 1000},
		{ID: "archived", Updated: midnight + 500, Archived: true},
		{ID: "old", Updated: midnight - 2000},
	}})
	h.TogglePin(h.Sessions()[1])
	g := h.Groups(now)
	if !reflect.DeepEqual(ids(g.Pinned), []string{"pinned-old"}) || !reflect.DeepEqual(ids(g.Today), []string{"new"}) || !reflect.DeepEqual(ids(g.Earlier), []string{"old"}) {
		t.Errorf("groups %v %v %v", ids(g.Pinned), ids(g.Today), ids(g.Earlier))
	}
	if !reflect.DeepEqual(s.Pins(), []string{a.ID + "/pinned-old"}) {
		t.Errorf("saved pins %v", s.Pins())
	}
	h.TogglePin(h.Sessions()[1])
	if len(h.Groups(now).Pinned) != 0 || len(s.Pins()) != 0 {
		t.Error("unpin")
	}
}

func TestRemovingTheViewedHost(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.Start()
	d.Push(a.ID, host.SessionsUpdate{Full: true, Upsert: []host.Session{{ID: "a1"}}})
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
