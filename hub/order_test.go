package hub

import (
	"errors"
	"fmt"
	"math"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/settings"
)

// 三种排序的分组和先后：最新回答、发起时间照所选的那个时间分今天和更早、排先后（不看 Updated）；手动排序是置顶和一整组，按 Rank，
// 相同时按 Key。选哪种存在这台电脑的设置里。
func TestSortModesGroupAndOrder(t *testing.T) {
	d := hubtest.NewDevices()
	h, s, dir := newHubIn(t, d)
	x, _ := h.AddHost("box", "", d.Found("box"))
	now := time.Date(2026, 11, 22, 15, 0, 0, 0, time.Local)
	midnight := time.Date(2026, 11, 22, 0, 0, 0, 0, time.Local).UnixMilli()
	hour := int64(time.Hour / time.Millisecond)
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		// 昨天发起、今天答过；Updated 最新（工具结果刚写进去）但它不算
		{ID: "old-busy", Created: midnight - 5*hour, Replied: midnight + 2*hour, Rank: 5, Updated: midnight + 9*hour},
		// 今天发起、今天答过
		{ID: "new", Created: midnight + 3*hour, Replied: midnight + 4*hour, Rank: 9, Updated: midnight + 4*hour},
		// 昨天发起、昨天答完
		{ID: "old", Created: midnight - 9*hour, Replied: midnight - hour, Rank: 5, Updated: midnight - hour},
		{ID: "pinned", Created: midnight - 20*hour, Replied: midnight + hour, Rank: 1, Pinned: true},
		{ID: "pinned2", Created: midnight + hour, Replied: midnight - 2*hour, Rank: 2, Pinned: true},
		{ID: "gone", Created: midnight + 5*hour, Replied: midnight + 5*hour, Rank: 100, Archived: true},
	}})
	check := func(what string, pinned, today, earlier, chats []string) {
		t.Helper()
		g := h.Groups(now, hostlink.Session{})
		got := [][]string{ids(g.Pinned), ids(g.Today), ids(g.Earlier), ids(g.Chats)}
		if want := [][]string{pinned, today, earlier, chats}; !reflect.DeepEqual(got, want) {
			t.Errorf("%s：%v，要 %v", what, got, want)
		}
	}
	if h.Sort() != settings.SortReplied {
		t.Fatalf("默认的排序 %q", h.Sort())
	}
	check("最新回答", []string{"pinned", "pinned2"}, []string{"new", "old-busy"}, []string{"old"}, nil)
	h.SetSort(settings.SortCreated)
	check("发起时间", []string{"pinned2", "pinned"}, []string{"new"}, []string{"old-busy", "old"}, nil)
	h.SetSort(settings.SortManual)
	// Rank 相同（old-busy、old 都是 5）时按 Key
	check("手动排序", []string{"pinned2", "pinned"}, nil, nil, []string{"new", "old", "old-busy"})
	if got := ids(h.Archived(now, hostlink.Session{})); !reflect.DeepEqual(got, []string{"gone"}) {
		t.Errorf("归档 %v", got)
	}
	if again, _ := settings.Load(dir); again.SessionSort() != settings.SortManual || s.SessionSort() != settings.SortManual {
		t.Errorf("存下的排序 %q", again.SessionSort())
	}
	// 开着的会话列表里还没有：三个值都算作 now，在最上面
	current := hostlink.Session{Backend: x.ID, ID: "fresh"}
	for _, mode := range []settings.SessionSort{settings.SortReplied, settings.SortCreated, settings.SortManual} {
		h.SetSort(mode)
		g := h.Groups(now, current)
		first := slices.Concat(g.Today, g.Chats)
		if len(first) == 0 || first[0].ID != "fresh" {
			t.Errorf("%s：开着的新会话不在最上面 %v %v", mode, ids(g.Today), ids(g.Chats))
		}
	}
}

// manualHub 是手动排序下列着一台设备的中枢，会话按 ranks 给的位置（会话号 s0、s1……），pinned 里的置顶。
func manualHub(t *testing.T, ranks []float64, pinned ...string) (*Hub, *hubtest.Devices, string) {
	t.Helper()
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	x, _ := h.AddHost("box", "", d.Found("box"))
	h.SetSort(settings.SortManual)
	var list []hostlink.Session
	for i, r := range ranks {
		id := fmt.Sprintf("s%d", i)
		list = append(list, hostlink.Session{ID: id, Created: 1, Rank: r, Pinned: slices.Contains(pinned, id)})
	}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: list})
	h.Groups(time.Now(), hostlink.Session{})
	return h, d, x.ID
}

// session 是设备 id 上的会话 sid。
func session(id, sid string) hostlink.Session { return hostlink.Session{Backend: id, ID: sid} }

// orders 是发出的 sessions.order：会话号 → 位置。
func orders(t *testing.T, d *hubtest.Devices) map[string]float64 {
	t.Helper()
	out := map[string]float64{}
	for _, call := range d.SessionCalls() {
		var sid string
		var rank float64
		_, rest, _ := strings.Cut(call, " sessions.order ")
		if _, err := fmt.Sscanf(rest, `{"rank":%g,"session":%q}`, &rank, &sid); err != nil {
			t.Fatalf("不是 sessions.order：%q（%v）", call, err)
		}
		out[sid] = rank
	}
	return out
}

// 拖到两行中间取两者的中点，拖到最上面、最下面比挨着的大、小 1000；界面马上照新位置排。
func TestMovePicksMidpointTopAndBottom(t *testing.T) {
	cases := []struct {
		what       string
		drag, onto string
		below      bool
		want       map[string]float64
		order      []string
		ranks      []float64
	}{
		{what: "中间", drag: "s3", onto: "s1", below: true, ranks: []float64{400, 300, 200, 100},
			want: map[string]float64{"s3": 250}, order: []string{"s0", "s1", "s3", "s2"}},
		{what: "最上面", drag: "s2", onto: "s0", ranks: []float64{400, 300, 200, 100},
			want: map[string]float64{"s2": 1400}, order: []string{"s2", "s0", "s1", "s3"}},
		{what: "最下面", drag: "s0", onto: "s3", below: true, ranks: []float64{400, 300, 200, 100},
			want: map[string]float64{"s0": -900}, order: []string{"s1", "s2", "s3", "s0"}},
		{what: "下面那行的上半", drag: "s0", onto: "s2", ranks: []float64{400, 300, 200, 100},
			want: map[string]float64{"s0": 250}, order: []string{"s1", "s0", "s2", "s3"}},
	}
	for _, c := range cases {
		h, d, id := manualHub(t, c.ranks)
		if !h.CanMove(session(id, c.drag), session(id, c.onto), c.below) {
			t.Errorf("%s：说不能挪", c.what)
		}
		if !h.Move(session(id, c.drag), session(id, c.onto), c.below, func(err error) { t.Error(err) }) {
			t.Errorf("%s：没挪", c.what)
			continue
		}
		if got := orders(t, d); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s：发出 %v，要 %v", c.what, got, c.want)
		}
		if got := ids(h.Groups(time.Now(), hostlink.Session{}).Chats); !reflect.DeepEqual(got, c.order) {
			t.Errorf("%s：先后 %v，要 %v", c.what, got, c.order)
		}
	}
}

// 中点分不开（等于邻居）时，从落点往下挨着的几行在上面那行和下一个分得开的行之间均匀重排，逐个发 sessions.order。
func TestMoveSpreadsWhenThereIsNoRoom(t *testing.T) {
	// s1、s2 的位置相同：拖 s4 到两者之间，中点等于两者
	h, d, id := manualHub(t, []float64{400, 300, 300, 100, 50})
	if !h.Move(session(id, "s4"), session(id, "s1"), true, func(err error) { t.Error(err) }) {
		t.Fatal("没挪")
	}
	// 新的先后 s0 s1 s4 s2 s3：s4、s2 在 s1（300）和 s3（100）之间均匀排
	step := (300 - 100) / 3.0
	want := map[string]float64{"s4": 300 - step, "s2": 300 - 2*step}
	if got := orders(t, d); !reflect.DeepEqual(got, want) {
		t.Errorf("发出 %v，要 %v", got, want)
	}
	if got := ids(h.Groups(time.Now(), hostlink.Session{}).Chats); !reflect.DeepEqual(got, []string{"s0", "s1", "s4", "s2", "s3"}) {
		t.Errorf("先后 %v", got)
	}
	// 浮点数挨着的两行：中点等于其中一个，同样重排
	a := 1e12
	b := math.Nextafter(a, math.Inf(1)) // 比 a 大一个最小的间隔
	h, d, id = manualHub(t, []float64{b, a, a - 10, 5})
	if !h.Move(session(id, "s3"), session(id, "s0"), true, func(err error) { t.Error(err) }) {
		t.Fatal("没挪")
	}
	got := orders(t, d)
	if len(got) != 2 || !(b > got["s3"] && got["s3"] > got["s1"] && got["s1"] > a-10) {
		t.Errorf("挨着的两行：发出 %v", got)
	}
	if got := ids(h.Groups(time.Now(), hostlink.Session{}).Chats); !reflect.DeepEqual(got, []string{"s0", "s3", "s1", "s2"}) {
		t.Errorf("先后 %v", got)
	}
}

// 只能在同一组里挪：置顶和其余之间拖动无效，落回原处、拖到自己身上、不在手动排序时也不挪。置顶组里照样能挪。
func TestMoveStaysInItsGroup(t *testing.T) {
	h, d, id := manualHub(t, []float64{400, 300, 200, 100}, "s0", "s1")
	nope := func(what string, drag, onto string, below bool) {
		t.Helper()
		if h.CanMove(session(id, drag), session(id, onto), below) || h.Move(session(id, drag), session(id, onto), below, func(err error) { t.Error(err) }) {
			t.Errorf("%s：挪了", what)
		}
	}
	nope("置顶拖到其余", "s0", "s2", false)
	nope("其余拖到置顶", "s3", "s1", true)
	nope("落回原处（下面那行的上半）", "s2", "s3", false)
	nope("落回原处（上面那行的下半）", "s3", "s2", true)
	nope("拖到自己身上", "s2", "s2", true)
	if got := d.SessionCalls(); len(got) != 0 {
		t.Errorf("发出了 %v", got)
	}
	if !h.Move(session(id, "s1"), session(id, "s0"), false, func(err error) { t.Error(err) }) {
		t.Fatal("置顶组里没挪")
	}
	if got := orders(t, d); !reflect.DeepEqual(got, map[string]float64{"s1": 1400}) {
		t.Errorf("置顶组里：发出 %v", got)
	}
	h.SetSort(settings.SortReplied)
	h.Groups(time.Now(), hostlink.Session{})
	nope("不在手动排序", "s3", "s2", false)
}

// 核心没记下时改回原来的位置，原因交给 failed。
func TestMoveRevertsWhenTheCoreRefuses(t *testing.T) {
	h, d, id := manualHub(t, []float64{400, 300, 200})
	d.SessionsErr = map[string]error{"sessions.order": errors.New("写不进去")}
	var failed []error
	if !h.Move(session(id, "s2"), session(id, "s0"), false, func(err error) { failed = append(failed, err) }) {
		t.Fatal("没挪")
	}
	if len(failed) != 1 || failed[0].Error() != "写不进去" {
		t.Errorf("failed %v", failed)
	}
	if got := ids(h.Groups(time.Now(), hostlink.Session{}).Chats); !reflect.DeepEqual(got, []string{"s0", "s1", "s2"}) {
		t.Errorf("没改回去：%v", got)
	}
	if r := find(h, "s2").Rank; r != 200 {
		t.Errorf("s2 的位置 %v", r)
	}
}
