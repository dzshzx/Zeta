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
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/settings"
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

	// 改名：发用户输入的原样，换成核心答的（空白压过的）
	var shown string
	f.devices.OnSessions = func(_ string, c hubtest.Call) {
		if c.Method == "sessions.rename" {
			shown = find(f.hub, "s1").Title
		}
	}
	f.hub.Rename(list[0], "  新的   名字 ", failed)
	f.devices.OnSessions = nil
	if got := find(f.hub, "s1").Title; shown != "  新的   名字 " || got != "新的 名字" {
		t.Errorf("等核心时 %q，答了以后 %q", shown, got)
	}
	f.hub.SetArchived([]hostlink.Session{list[0], list[1]}, true, failed) // 已经归档的不再发
	if !find(f.hub, "s1").Archived || len(f.hub.Archived(time.Now(), hostlink.Session{})) != 2 {
		t.Errorf("归档后 %+v", f.hub.Sessions())
	}
	want := []string{
		f.id + ` sessions.rename {"session":"s1","title":"  新的   名字 "}`,
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

	// 删除：列表马上去掉；没删成时重读那台的列表
	f.devices.SessionCalls()
	f.devices.SessionsErr = nil
	f.hub.Delete([]hostlink.Session{find(f.hub, "s3")}, failed)
	if find(f.hub, "s3").ID != "" {
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

	// 开着的也走 sessions.rename（核心跟着改开着的对话的标题）；聊天窗格的标题跟着换
	f.hub.Rename(list[0], "新名字", fail)
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], "sessions.rename") {
		t.Errorf("改名发出 %q", got)
	}
	if f.hub.Chat().Session.Title != "新名字" {
		t.Errorf("聊天窗格的标题 %q", f.hub.Chat().Session.Title)
	}

	// 删开着的：核心删成了，聊天窗格关掉它（放开），进程由核心结束
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

// 能不能删以核心的应答为准：这边不筛，核心答 active 的是 ErrActive，照原样放回列表原处、不重订，开着的不关；其余照删。
func TestDeletingAnActiveSessionIsRefusedByTheCore(t *testing.T) {
	f := newChatFixture(t)
	f.listed()
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}})
	f.hub.OpenSession(find(f.hub, "s1"))
	f.devices.ErrBySession = map[string]error{"s1": hubtest.Refuse("对话正在进行，结束后再删", sessionproto.CodeActive)}
	opened := f.devices.Opened(f.id)
	var why error
	f.hub.Delete([]hostlink.Session{find(f.hub, "s1"), find(f.hub, "s3")}, func(err error) { why = err })
	if !errors.Is(why, ErrActive) || why.Error() != "1 个没成功：对话正在进行，结束后再删" {
		t.Errorf("原因 %v", why)
	}
	if got := f.devices.SessionCalls(); len(got) != 2 {
		t.Errorf("发出 %q", got)
	}
	if f.hub.Chat() == nil || f.devices.Opened(f.id) != opened {
		t.Errorf("开着的关了，或重订了列表（订阅 %d 次）", f.devices.Opened(f.id)-opened)
	}
	if got := ids(f.hub.Sessions()); !reflect.DeepEqual(got, []string{"s1", "s2"}) || find(f.hub, "s1").Title != "修登录页" {
		t.Errorf("放回去的列表 %v", f.hub.Sessions())
	}
	// 核心说已经没有的算删成了
	f.devices.ErrBySession = map[string]error{"s1": hubtest.Refuse("没有这个会话", sessionproto.CodeNoSession)}
	why = nil
	f.hub.Delete([]hostlink.Session{{Backend: f.id, ID: "s1"}}, func(err error) { why = err })
	if why != nil || f.hub.Chat() != nil {
		t.Errorf("已经没有的：%v，开着 %v", why, f.hub.Chat() != nil)
	}
}

// 别的原因没删成的不知道删到哪一步：收尾时那台重订一次列表拿整份对上，几个没成也只订一次。
func TestFailedDeletesRewatchEachDeviceOnce(t *testing.T) {
	f := newChatFixture(t)
	f.listed()
	f.devices.SessionsErr = map[string]error{"sessions.delete": errors.New("没有权限")}
	opened := f.devices.Opened(f.id)
	var why error
	f.hub.Delete([]hostlink.Session{find(f.hub, "s1"), find(f.hub, "s2"), find(f.hub, "s3")}, func(err error) { why = err })
	if why == nil || why.Error() != "3 个没成功：没有权限" || f.devices.Opened(f.id) != opened+1 {
		t.Errorf("原因 %v，重订 %d 次", why, f.devices.Opened(f.id)-opened)
	}
}

// 每台发一次带 sessions 和这边 utc_offset 的请求，各会话的导出照选中的先后合成，文件名的时刻是这边的。
func TestExport(t *testing.T) {
	f := newChatFixture(t)
	list := f.listed()
	now := time.Date(2026, 11, 22, 9, 5, 0, 0, time.FixedZone("", 5*3600+45*60))
	var got []Exported
	done := func(e Exported) { got = append(got, e) }
	fail := func(err error) { t.Error(err) }
	f.hub.Export(list[:1], now, done, fail)
	f.hub.Export([]hostlink.Session{list[2], list[0]}, now, done, fail)
	want := []string{
		f.id + ` sessions.export {"sessions":["s1"],"utc_offset":345}`,
		f.id + ` sessions.export {"sessions":["s3","s1"],"utc_offset":345}`,
	}
	if calls := f.devices.SessionCalls(); !reflect.DeepEqual(calls, want) {
		t.Errorf("发出 %q", calls)
	}
	if len(got) != 2 || got[0].Name != "Zeta-对话 s1-20261122-0905.md" || got[0].Content != "# 对话 s1\n\n正文\n" {
		t.Fatalf("一个：%+v", got)
	}
	if got[1].Name != "Zeta-2个对话-20261122-0905.md" || got[1].Content != "# 对话 s3\n\n正文\n\n---\n\n# 对话 s1\n\n正文\n" {
		t.Errorf("几个：%+v", got[1])
	}
	f.devices.SessionsErr = map[string]error{"sessions.export": errors.New("读不了")}
	var why error
	f.hub.Export(list[:1], now, done, func(err error) { why = err })
	if why == nil || why.Error() != "读不了" {
		t.Errorf("导不出来：%v", why)
	}
}

// 置顶看列表项的 pinned，点置顶发 sessions.pin；没成时改回去。
func TestPinsGoThroughTheCore(t *testing.T) {
	f := newChatFixture(t)
	f.listed()
	var failures []string
	failed := func(err error) { failures = append(failures, err.Error()) }
	f.hub.TogglePin(find(f.hub, "s3"), failed)
	if !find(f.hub, "s3").Pinned || !reflect.DeepEqual(ids(f.hub.Groups(time.Now(), hostlink.Session{}).Pinned), []string{"s3"}) {
		t.Errorf("置顶后 %+v", f.hub.Sessions())
	}
	f.devices.SessionsErr = map[string]error{"sessions.pin": errors.New("连不上")}
	f.hub.TogglePin(find(f.hub, "s3"), failed)
	f.hub.TogglePin(find(f.hub, "s1"), failed)
	if !find(f.hub, "s3").Pinned || find(f.hub, "s1").Pinned || len(failures) != 2 {
		t.Errorf("没成：%+v，原因 %q", f.hub.Sessions(), failures)
	}
	want := []string{
		f.id + ` sessions.pin {"pinned":true,"session":"s3"}`,
		f.id + ` sessions.pin {"pinned":false,"session":"s3"}`,
		f.id + ` sessions.pin {"pinned":true,"session":"s1"}`,
	}
	if got := f.devices.SessionCalls(); !reflect.DeepEqual(got, want) {
		t.Errorf("发出 %q", got)
	}
	// 核心推来的列表里置顶的（不在最新 100 个里的也在）就在置顶组
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "far", Updated: 1, Pinned: true}}})
	if got := ids(f.hub.Groups(time.Now(), hostlink.Session{}).Pinned); !reflect.DeepEqual(got, []string{"s3", "far"}) {
		t.Errorf("置顶组 %v", got)
	}
}

// 本机设置里从前记的置顶：会话列表的订阅连上那台（收到招呼）时逐个发 sessions.pin，答成功或 no_session 的从本机删掉，
// 别的出错留着；同一个进程里每台只迁一次，没迁成的等下次启动。对话连接的招呼不迁。迁完之前置顶取 pinned 和本机记的并集，
// 取消置顶也删本机记的。别的设备的不动。
func TestLocalPinsMoveToTheCore(t *testing.T) {
	d := hubtest.NewDevices()
	h, s := newHub(t, d)
	x, err := h.AddHost("box", "", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	other := "r-lab/keep" // 别的设备的
	keys := []string{x.ID + "/ok", x.ID + "/gone", x.ID + "/later", x.ID + "/mine", other}
	if err := s.SetPins(keys); err != nil {
		t.Fatal(err)
	}
	h.pins = loadPins(s) // 像带着这些设置启动
	d.Push(x.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "ok", Updated: 3}, {ID: "later", Updated: 2}, {ID: "mine", Updated: 1}}})
	if got := ids(h.Groups(time.Now(), hostlink.Session{}).Pinned); !reflect.DeepEqual(got, []string{"ok", "later", "mine"}) {
		t.Errorf("迁之前的置顶 %v", got)
	}
	// 取消置顶：本机记的也删
	h.TogglePin(find(h, "mine"), func(err error) { t.Error(err) })
	if slices.Contains(s.Pins(), keys[3]) || h.Pinned(find(h, "mine")) {
		t.Errorf("取消后本机还记着 %v", s.Pins())
	}
	d.SessionCalls()
	// 对话连接的招呼只补主机名
	h.chatConn(x.ID)
	d.Chat(x.ID).Greet("")
	if got := d.SessionCalls(); len(got) != 0 {
		t.Errorf("对话连接的招呼也迁了：%q", got)
	}
	d.ErrBySession = map[string]error{
		"gone":  hubtest.Refuse("没有这个会话", sessionproto.CodeNoSession),
		"later": hubtest.Refuse("写不进去", ""), // 别的出错：留着下次启动再迁
	}
	d.Greet(x.ID, "")
	want := []string{
		x.ID + ` sessions.pin {"pinned":true,"session":"gone"}`,
		x.ID + ` sessions.pin {"pinned":true,"session":"later"}`,
		x.ID + ` sessions.pin {"pinned":true,"session":"ok"}`,
	}
	got := d.SessionCalls()
	slices.Sort(got) // 几个一起发，先后不定
	if !reflect.DeepEqual(got, want) {
		t.Errorf("发出 %q", got)
	}
	if !reflect.DeepEqual(s.Pins(), []string{keys[2], other}) || !find(h, "ok").Pinned || !h.Pinned(find(h, "later")) {
		t.Errorf("迁过以后本机记的 %v，列表 %+v", s.Pins(), h.Sessions())
	}
	// 同一个进程里再连上也不再试
	d.ErrBySession = nil
	d.Greet(x.ID, "")
	if got := d.SessionCalls(); len(got) != 0 {
		t.Errorf("没迁成的又试了：%q", got)
	}
	// 下次启动再迁剩下的；迁完了不再发
	h.pins = loadPins(s)
	d.Greet(x.ID, "")
	d.Greet(x.ID, "")
	if got := d.SessionCalls(); !reflect.DeepEqual(got, []string{x.ID + ` sessions.pin {"pinned":true,"session":"later"}`}) {
		t.Errorf("再启动发出 %q", got)
	}
	if !reflect.DeepEqual(s.Pins(), []string{other}) {
		t.Errorf("迁完本机记的 %v", s.Pins())
	}
}

// 招呼里的主机名补上设备的主机名（没起名字的设备就叫它）；空的不动。
func TestHelloNamesTheDevice(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Greet(f.id, "")
	if x, _ := f.hub.Host(f.id); x.Hostname != "box" {
		t.Errorf("空的也记了：%q", x.Hostname)
	}
	f.devices.Greet(f.id, "lab")
	if x, _ := f.hub.Host(f.id); x.Name() != "lab" || f.hub.Settings().Hosts()[0].Hostname != "lab" {
		t.Errorf("名字 %q，存下的 %+v", x.Name(), f.hub.Settings().Hosts())
	}
	f.hub.OpenSession(hostlink.Session{Backend: f.id, ID: "s1"})
	f.devices.Chat(f.id).Greet("lab-2")
	if x, _ := f.hub.Host(f.id); x.Hostname != "lab-2" {
		t.Errorf("对话连接的招呼：%q", x.Hostname)
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
	f.devices.Handoffs = map[string]string{"s1": `{"handed":false,"status":"busy"}`}
	ho := &f.hub.Chat().Handoff
	f.hub.HandOver()
	if ho.Problem != nil || ho.Handing {
		t.Errorf("正忙：%+v", ho)
	}
	f.devices.Handoffs["s1"] = `{"handed":false,"status":"stuck"}`
	f.hub.HandOver()
	if !strings.Contains(locales.ErrorText(ho.Problem), "没有退出") {
		t.Errorf("没退：%q", locales.ErrorText(ho.Problem))
	}
	f.devices.Handoffs = nil
	f.hub.HandOver()
	want := []string{f.id + ` sessions.handoff {"session":"s1"}`, f.id + ` sessions.handoff {"session":"s1"}`, f.id + ` sessions.handoff {"session":"s1"}`}
	if got := f.devices.SessionCalls(); !reflect.DeepEqual(got, want) || ho.Problem != nil {
		t.Errorf("发出 %q，%+v", got, ho)
	}
}

// status 是占着的进程报的状态；没有时是空。
func status(o *sessionproto.Occupancy) string {
	if o == nil || o.Status == nil {
		return ""
	}
	return *o.Status
}

// 同一条连接再发 sessions.search 时核心停掉上一个，上一个照样答一份 complete 为假的汇总；它停下前推来的命中、
// 那份汇总都晚于新的一次到。命中按搜索的名字分发，侧栏只算最新那次的。
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
	// 上一次的命中两处推来：新的一次正在搜时（这条连接上最近的名字已经是新的），和新的一次答完以后
	oldStarted, release, stalePushed := make(chan struct{}), make(chan struct{}), make(chan struct{})
	afterNew, oldDone := make(chan struct{}), make(chan struct{})
	d.OnSearch = func(l *hubtest.ChatLink, name any, query string) (json.RawMessage, error) {
		if query == "old" {
			close(oldStarted)
			<-release // 核心收到新的一次才停掉这个
			l.PushMatch(name, "stale", "old 的命中")
			close(stalePushed)
			<-afterNew
			l.PushMatch(name, "stale", "old 的命中")
			defer close(oldDone)
			return json.RawMessage(`{"scanned":3,"total":300,"matched":1,"complete":false}`), nil
		}
		close(release)
		<-stalePushed
		l.PushMatch(name, "fresh", "new 的命中")
		return json.RawMessage(`{"scanned":300,"total":300,"matched":1,"complete":true}`), nil
	}
	h.SetQuery("old")
	<-oldStarted
	h.SetQuery("new")
	for h.SearchProgress() != SearchDone {
		(<-posts)()
	}
	close(afterNew)
	<-oldDone
	drain()
	r := h.Results(hostlink.Session{}, time.Now())
	if len(r) != 1 || r[0].ID != "fresh" || h.Snippet(r[0]) != "new 的命中" || h.SearchProgress() != SearchDone {
		t.Errorf("结果 %v，片段 %q，进度 %v", ids(r), h.Snippet(hostlink.Session{Backend: x.ID, ID: "stale"}), h.SearchProgress())
	}
}
