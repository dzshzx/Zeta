package hub

import (
	"encoding/json"
	"errors"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// listed 推一份 box 上的整份列表：s1 今天、s2 归档的、s3 更早。
func (f *chatFixture) listed() []hostlink.Session {
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", Title: "修登录页", Updated: 30},
		{ID: "s2", Title: "旧的", Updated: 20, Archived: true},
		{ID: "s3", Title: "部署", Updated: 10},
	}})
	return f.hub.Sessions()
}

func find(h *Hub, id string) hostlink.Session {
	i := slices.IndexFunc(h.Sessions(), func(s hostlink.Session) bool { return s.ID == id })
	if i < 0 {
		return hostlink.Session{}
	}
	return h.Sessions()[i]
}

func TestRenameArchiveAndDeleteGoThroughTheCore(t *testing.T) {
	f := newChatFixture(t)
	list := f.listed()
	var failures []string
	failed := func(err error) { failures = append(failures, err.Error()) }

	f.hub.Rename(list[0], "  新的   名字 ", failed)
	if got := find(f.hub, "s1").Title; got != "新的 名字" {
		t.Errorf("改名后 %q", got)
	}
	f.hub.SetArchived([]hostlink.Session{list[0], list[1]}, true, failed) // 已经归档的不再发
	if !find(f.hub, "s1").Archived || len(f.hub.Archived(time.Now(), hostlink.Session{})) != 2 {
		t.Errorf("归档后 %+v", f.hub.Sessions())
	}
	want := []string{
		f.id + ` sessions.rename {"session":"s1","title":"新的 名字"}`,
		f.id + ` sessions.archive {"archived":true,"session":"s1"}`,
	}
	if got := f.devices.SessionCalls(); !reflect.DeepEqual(got, want) {
		t.Errorf("发出 %q", got)
	}

	// 没成：改回去，说原因
	f.devices.SessionsErr = map[string]error{"sessions.rename": errors.New("写不进去"), "sessions.archive": errors.New("连不上")}
	f.hub.Rename(find(f.hub, "s3"), "别的", failed)
	f.hub.SetArchived([]hostlink.Session{find(f.hub, "s1")}, false, failed)
	if find(f.hub, "s3").Title != "部署" || !find(f.hub, "s1").Archived {
		t.Errorf("没成却没改回去：%+v", f.hub.Sessions())
	}
	if !reflect.DeepEqual(failures, []string{"写不进去", "连不上"}) {
		t.Errorf("原因 %q", failures)
	}

	// 删除：列表、置顶马上去掉；没删成时重读那台的列表
	f.devices.SessionCalls()
	f.hub.TogglePin(find(f.hub, "s3"))
	f.devices.SessionsErr = nil
	f.hub.Delete([]hostlink.Session{find(f.hub, "s3")}, failed)
	if find(f.hub, "s3").ID != "" || f.hub.Pinned(hostlink.Session{Backend: f.id, ID: "s3"}) {
		t.Errorf("删了还在：%+v", f.hub.Sessions())
	}
	if got := f.devices.SessionCalls(); !reflect.DeepEqual(got, []string{f.id + ` sessions.delete {"session":"s3"}`}) {
		t.Errorf("发出 %q", got)
	}
	opened := f.devices.Opened(f.id)
	f.devices.SessionsErr = map[string]error{"sessions.delete": errors.New("没有权限")}
	f.hub.Delete([]hostlink.Session{find(f.hub, "s1")}, failed)
	if f.devices.Opened(f.id) != opened+1 || failures[len(failures)-1] != "没有权限" {
		t.Errorf("没删成：订阅 %d 次，原因 %q", f.devices.Opened(f.id), failures)
	}
}

func TestOperationsOnTheOpenChat(t *testing.T) {
	f := newChatFixture(t)
	list := f.listed()
	f.hub.OpenSession(list[0])
	f.link().Took()
	fail := func(err error) { t.Error(err) }

	// 开着的也走 sessions.rename（核心跟着改开着的对话的标题）；聊天窗格的标题先换上
	f.hub.Rename(list[0], "新名字", fail)
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], "sessions.rename") {
		t.Errorf("改名发出 %q", got)
	}
	if f.hub.Chat().Session.Title != "新名字" {
		t.Errorf("聊天窗格的标题 %q", f.hub.Chat().Session.Title)
	}

	// 删开着的：聊天窗格关掉它（放开），进程由核心结束
	f.hub.Delete([]hostlink.Session{find(f.hub, "s1")}, fail)
	if f.hub.Chat() != nil {
		t.Error("删了还开着")
	}
	if got := f.link().Methods(); !reflect.DeepEqual(got, []string{"conv.close"}) {
		t.Errorf("删除时发出 %v", got)
	}
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], "sessions.delete") {
		t.Errorf("发出 %q", got)
	}

	// 归档开着的：聊天窗格关掉它
	f.hub.OpenSession(find(f.hub, "s3"))
	f.hub.SetArchived([]hostlink.Session{find(f.hub, "s3")}, true, fail)
	if f.hub.Chat() != nil {
		t.Error("归档了还开着")
	}
}

func TestActiveSessionsCannotBeDeleted(t *testing.T) {
	f := newChatFixture(t)
	f.listed()
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}, "s3": {State: "idle"}})
	if !f.hub.Active(find(f.hub, "s1"), Local{}) || f.hub.Active(find(f.hub, "s3"), Local{}) {
		t.Error("核心报的忙闲")
	}
	if !f.hub.Active(hostlink.Session{Backend: f.id, ID: "x", External: true}, Local{}) {
		t.Error("别处开着的能删")
	}
	if !f.hub.Active(find(f.hub, "s3"), Local{Busy: true}) {
		t.Error("这边在回答的能删")
	}
}

func TestExport(t *testing.T) {
	f := newChatFixture(t)
	list := f.listed()
	now := time.Date(2026, 11, 22, 9, 5, 0, 0, time.Local)
	var got []Exported
	done := func(e Exported) { got = append(got, e) }
	fail := func(err error) { t.Error(err) }
	f.hub.Export(list[:1], now, done, fail)
	f.hub.Export(list, now, done, fail)
	if len(got) != 2 || got[0].Name != "Zeta-对话 s1-20261122-0905.md" || got[0].Content != "# 对话 s1\n\n正文\n" {
		t.Fatalf("一个：%+v", got)
	}
	if got[1].Name != "Zeta-3个对话-20261122-0905.md" || strings.Count(got[1].Content, "\n---\n\n") != 2 {
		t.Errorf("几个：%+v", got[1])
	}
	f.devices.SessionsErr = map[string]error{"sessions.export": errors.New("读不了")}
	var why error
	f.hub.Export(list[:1], now, done, func(err error) { why = err })
	if why == nil || why.Error() != "读不了" {
		t.Errorf("导不出来：%v", why)
	}
}

func TestExportFileName(t *testing.T) {
	now := time.Date(2026, 1, 2, 3, 4, 0, 0, time.Local)
	for title, want := range map[string]string{
		"修 a/b:c*?":                   "Zeta-修 abc-20260102-0304.md",
		"  ":                          "Zeta-对话-20260102-0304.md",
		strings.Repeat("长", 50) + "尾": "Zeta-" + strings.Repeat("长", 40) + "-20260102-0304.md",
	} {
		if got := ExportFileName(title, now); got != want {
			t.Errorf("%q：%q", title, got)
		}
	}
	if CleanTitle(" a \n b ") != "a b" || len([]rune(CleanTitle(strings.Repeat("字", 300)))) != 200 {
		t.Error("CleanTitle")
	}
}

func TestFarPinnedSessionsAreLookedUp(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	x, err := h.AddHost("box", "", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "old"})
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "gone"})
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "s1"})
	d.Far = map[string][]hostlink.Session{x.ID: {{ID: "old", Title: "三个月前的", Updated: 1}}}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Title: "今天的", Updated: 50}}})
	if got := d.SessionCalls(); !reflect.DeepEqual(got, []string{x.ID + ` sessions.lookup {"sessions":["gone","old"]}`}) {
		t.Errorf("发出 %q", got)
	}
	g := h.Groups(time.Now(), hostlink.Session{})
	if got := ids(g.Pinned); !reflect.DeepEqual(got, []string{"s1", "old"}) || g.Pinned[1].Backend != x.ID {
		t.Errorf("置顶 %v", got)
	}
	// 那边已经没有的置顶去掉；再推整份不再问
	if h.Pinned(hostlink.Session{Backend: x.ID, ID: "gone"}) {
		t.Error("没有了的还置顶着")
	}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Updated: 60}}})
	if got := d.SessionCalls(); len(got) != 0 {
		t.Errorf("又问了 %q", got)
	}
	// 没问成的下次再问
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "later"})
	d.SessionsErr = map[string]error{"sessions.lookup": errors.New("连不上")}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Updated: 60}}})
	d.SessionsErr = nil
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Updated: 60}}})
	if got := d.SessionCalls(); len(got) != 2 || !h.Pinned(hostlink.Session{Backend: x.ID, ID: "old"}) {
		t.Errorf("发出 %q", got)
	}
}

func TestImagesComeFromTheCore(t *testing.T) {
	f := newChatFixture(t)
	f.devices.ImageData = []byte("png!")
	data, err := f.hub.ImageLoader(f.id)("/home/box/.claude/projects/-w/s1.jsonl", "0.1")
	if err != nil || string(data) != "png!" {
		t.Fatalf("取到 %q、%v", data, err)
	}
	f.devices.ImageData = nil
	if _, err := f.hub.ImageLoader(f.id)("/f", "0.0"); err == nil {
		t.Error("没有的图也取到了")
	}
}

func TestHandOver(t *testing.T) {
	f := newChatFixture(t)
	f.hub.OpenSession(hostlink.Session{Backend: f.id, ID: "s1"})
	f.devices.Occupants = map[string]string{"s1": `{"status":"busy","waiting_for":null,"hands_over":false}`}
	f.devices.Handoffs = map[string]string{"s1": `{"handed":false,"status":"busy"}`}
	now := time.Now()
	if wait := f.hub.PollOccupant(now); wait != OccupantPoll {
		t.Errorf("下次 %v", wait)
	}
	ho := &f.hub.Chat().Handoff
	if ho.Occupant == nil || ho.Occupant.Status != "busy" || ho.Occupant.HandsOver {
		t.Fatalf("占着的 %+v", ho.Occupant)
	}
	if wait := f.hub.PollOccupant(now.Add(time.Second)); wait != 2*time.Second {
		t.Errorf("没到时候：%v", wait)
	}
	f.hub.HandOver()
	if ho.Problem != "" || ho.Occupant.Status != "busy" {
		t.Errorf("正忙：%+v %q", ho.Occupant, ho.Problem)
	}
	f.devices.Handoffs["s1"] = `{"handed":false,"status":"stuck"}`
	f.hub.HandOver()
	if !strings.Contains(ho.Problem, "没有退出") {
		t.Errorf("没退：%q", ho.Problem)
	}
	// 那边自己退出了：接过来，好让核心解开 blocked
	f.devices.Occupants, f.devices.Handoffs = nil, nil
	f.devices.SessionCalls()
	f.hub.PollOccupant(now.Add(4 * time.Second))
	want := []string{f.id + ` sessions.occupant {"session":"s1"}`, f.id + ` sessions.handoff {"session":"s1"}`}
	if got := f.devices.SessionCalls(); !reflect.DeepEqual(got, want) || ho.Occupant != nil || ho.Problem != "" || !ho.Handed {
		t.Errorf("发出 %q，%+v %q", got, ho.Occupant, ho.Problem)
	}
	// 交出来了：等核心推 conv.state 解开 blocked，这期间不再查、不再接
	for _, later := range []time.Duration{7 * time.Second, 10 * time.Second, 18 * time.Second} {
		f.hub.PollOccupant(now.Add(later))
		f.hub.HandOver()
	}
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Errorf("交出来以后又发 %q", got)
	}
	// 等满 handoffSettle 还没解开（推送丢了）：再查一次
	f.hub.PollOccupant(now.Add(4*time.Second + handoffSettle))
	if got := f.devices.SessionCalls(); len(got) != 2 {
		t.Errorf("到点以后发出 %q", got)
	}
}

// 同一条连接再发 sessions.search 时核心停掉上一个，上一个照样答一份 complete 为假的汇总；它停下前推来的命中、
// 那份汇总都晚于新的一次到。侧栏只算最新那次的。
func TestOnlyTheLatestSearchCounts(t *testing.T) {
	d := hubtest.NewDevices()
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	posts := make(chan func(), 100)
	h := New(s, d, func(f func()) { go f() }, func(f func()) { posts <- f })
	h.SearchDelay = 0
	x, err := h.AddHost("box", "", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "stale", Updated: 2}, {ID: "fresh", Updated: 1}}})
	drain := func() {
		for {
			select {
			case f := <-posts:
				f()
			case <-time.After(50 * time.Millisecond):
				return
			}
		}
	}
	drain()
	oldStarted, release, oldDone := make(chan struct{}), make(chan struct{}), make(chan struct{})
	d.OnSearch = func(l *hubtest.ChatLink, id int64, query string) (json.RawMessage, error) {
		if query == "old" {
			close(oldStarted)
			<-release // 核心收到新的一次才停掉这个
			l.PushMatch(id, "stale", "old 的命中")
			defer close(oldDone)
			return json.RawMessage(`{"scanned":3,"total":300,"matched":1,"complete":false}`), nil
		}
		l.PushMatch(id, "fresh", "new 的命中")
		return json.RawMessage(`{"scanned":300,"total":300,"matched":1,"complete":true}`), nil
	}
	h.SetQuery("old")
	<-oldStarted
	h.SetQuery("new")
	for h.SearchProgress() != SearchDone {
		(<-posts)()
	}
	close(release)
	<-oldDone
	drain()
	r := h.Results(hostlink.Session{}, time.Now())
	if len(r) != 1 || r[0].ID != "fresh" || h.Snippet(r[0]) != "new 的命中" || h.SearchProgress() != SearchDone {
		t.Errorf("结果 %v，片段 %q，进度 %v", ids(r), h.Snippet(hostlink.Session{Backend: x.ID, ID: "stale"}), h.SearchProgress())
	}
}

// 单独取来的置顶会话并在列表里：那台推来的列表不把它拿掉，列表里有它时换成列表里的样子；不再列那台时连同问过的记号一起清掉。
func TestFarSessionsStayInTheList(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	x, _ := h.AddHost("box", "", d.Found("box"))
	y, _ := h.AddHost("lab", "", d.Found("lab"))
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: "old"})
	d.Far = map[string][]hostlink.Session{x.ID: {{ID: "old", Title: "三个月前的", Updated: 1}}}
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Updated: 50}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"s1", "old"}) {
		t.Fatalf("取回后 %v", got)
	}
	// 增量里的 Remove、再推整份都不拿掉它
	d.Push(x.ID, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "s2", Updated: 60}}, Remove: []string{"old", "s1"}})
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s2", Updated: 60}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"s2", "old"}) {
		t.Errorf("推了列表后 %v", got)
	}
	// 列表里有了它：用列表里的样子，只算一次
	d.Push(x.ID, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "old", Title: "又聊了", Updated: 70}}})
	if got := ids(h.Sessions()); !reflect.DeepEqual(got, []string{"old", "s2"}) || h.Sessions()[0].Title != "又聊了" {
		t.Errorf("列表里有了 %+v", h.Sessions())
	}
	// 改名：列表里的那份跟着改
	h.Rename(hostlink.Session{Backend: x.ID, ID: "old"}, "改过的", func(err error) { t.Error(err) })
	if find(h, "old").Title != "改过的" {
		t.Errorf("改名后 %+v", h.Sessions())
	}
	// 只列另一台：这台的单独取来的和问过的都清掉；再列回来重新问
	d.SessionCalls()
	h.ListOnly(y.ID)
	if len(h.far) != 0 || len(h.farAsked) != 0 {
		t.Errorf("没清掉：far %v，asked %v", h.far, h.farAsked)
	}
	h.ListOnly(x.ID)
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s2", Updated: 60}}})
	if got := d.SessionCalls(); !reflect.DeepEqual(got, []string{x.ID + ` sessions.lookup {"sessions":["old"]}`}) {
		t.Errorf("列回来后发出 %q", got)
	}
}
