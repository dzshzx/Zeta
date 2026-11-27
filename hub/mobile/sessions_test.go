package mobile

import (
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
)

// addDevice 加一台假设备，推上 list 这些会话，答设备编号。
func addDevice(t *testing.T, c *Core, d *hubtest.Devices, list ...hostlink.Session) string {
	t.Helper()
	if d.OnChat == nil {
		d.OnChat = func(l *hubtest.ChatLink) { l.Connect() }
	}
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	id := call[State](t, c, "state", nil).Devices[0].ID
	d.Push(id, hostlink.SessionsUpdate{Full: true, Upsert: list})
	c.Do(func() {})
	return id
}

// 置顶、改名、归档、删除、结束、手动挪动都经中枢交给设备核心的 sessions.*；不在列表里的会话说已经没了。
func TestSessionOps(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	id := addDevice(t, c, d, hostlink.Session{ID: "s1", Title: "一", Updated: 2, Rank: 2},
		hostlink.Session{ID: "s2", Title: "二", Updated: 1, Rank: 1})
	one := map[string]string{"backend": id, "id": "s1"}
	both := map[string]any{"sessions": []map[string]string{one, {"backend": id, "id": "s2"}}}
	call[map[string]bool](t, c, "sort", map[string]string{"sort": "manual"})
	if st := call[State](t, c, "state", nil); st.Side.Sort != "manual" {
		t.Fatalf("排序 %q", st.Side.Sort)
	}
	move := map[string]any{"backend": id, "id": "s2", "target": one, "below": false}
	if !call[map[string]bool](t, c, "canmove", move)["ok"] {
		t.Fatal("s2 拖到 s1 上面应当能挪")
	}
	call[map[string]bool](t, c, "move", move)
	call[map[string]bool](t, c, "pin", one)
	call[map[string]bool](t, c, "rename", map[string]string{"backend": id, "id": "s1", "title": "新名字"})
	call[map[string]bool](t, c, "archive", map[string]any{"sessions": both["sessions"], "archived": true})
	call[map[string]bool](t, c, "delete", map[string]any{"sessions": []map[string]string{one}})
	if why := failure(c, "pin", `{"backend":"`+id+`","id":"nope"}`); why == "" {
		t.Error("不在列表里的会话也置顶了")
	}
	seen := ""
	want := []string{"sessions.pin", "sessions.rename", "sessions.archive", "sessions.delete", "sessions.order"}
	until(t, c, changed, func() bool {
		seen += strings.Join(d.SessionCalls(), "\n") + "\n"
		got := seen
		return !slices.ContainsFunc(want, func(m string) bool { return !strings.Contains(got, m) })
	})
}

// 结束：Zeta 的经 sessions.end，别处运行的经 sessions.end_elsewhere；没成的原因放进 side.failed，交出去一次就清掉。
func TestEndAndFailure(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	id := addDevice(t, c, d, hostlink.Session{ID: "s1", Updated: 2}, hostlink.Session{ID: "s2", External: true, Updated: 1})
	seen := ""
	call[map[string]bool](t, c, "end", map[string]any{"sessions": []map[string]string{{"backend": id, "id": "s1"}, {"backend": id, "id": "s2"}}})
	until(t, c, changed, func() bool {
		seen += strings.Join(d.SessionCalls(), "\n") + "\n"
		got := seen
		return strings.Contains(got, "sessions.end ") && strings.Contains(got, "sessions.end_elsewhere")
	})
	d.SessionsErr = map[string]error{"sessions.pin": hubtest.Refuse("不让置顶", "")}
	call[map[string]bool](t, c, "pin", map[string]string{"backend": id, "id": "s1"})
	var failed string
	until(t, c, changed, func() bool { failed = c.sidebar.failed; return failed != "" })
	if st := call[State](t, c, "state", nil); !strings.Contains(st.Side.Failed, "不让置顶") {
		t.Fatalf("side.failed %q", st.Side.Failed)
	}
	if st := call[State](t, c, "state", nil); st.Side.Failed != "" {
		t.Fatalf("交出去以后还在：%q", st.Side.Failed)
	}
}

// 导出：等设备答来，交回文件名和 Markdown。
func TestExport(t *testing.T) {
	c, d := newCore(t)
	id := addDevice(t, c, d, hostlink.Session{ID: "s1", Updated: 1})
	e := call[Exported](t, c, "export", map[string]any{"sessions": []map[string]string{{"backend": id, "id": "s1"}}})
	if e.Name == "" || !strings.Contains(e.Content, "# 对话 s1") {
		t.Fatalf("导出 %+v", e)
	}
}

// 搜索：sessions 换成搜索结果，命中内容的带片段；清空后回到整个列表。
func TestSearch(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	id := addDevice(t, c, d, hostlink.Session{ID: "s1", Title: "苹果", Updated: 2}, hostlink.Session{ID: "s2", Title: "香蕉", Updated: 1})
	d.SearchHits = map[string]map[string]string{id: {"s2": "里面有苹果"}}
	call[map[string]bool](t, c, "search", map[string]string{"query": "苹果"})
	var st State
	until(t, c, changed, func() bool { st = c.state(); return st.Side.Search == "done" })
	if !st.Side.Searching || len(st.Sessions) != 2 || st.Sessions[1].Snippet != "里面有苹果" {
		t.Fatalf("搜索结果 %+v %+v", st.Side, st.Sessions)
	}
	call[map[string]bool](t, c, "search", map[string]string{"query": ""})
	if st = call[State](t, c, "state", nil); st.Side.Searching || len(st.Sessions) != 2 || st.Sessions[1].Snippet != "" {
		t.Fatalf("清空后 %+v %+v", st.Side, st.Sessions)
	}
}

// 核心报的忙闲：行尾标在跑、等你，后台对话列出来；调过 alerts 后开始等你、答完一轮都交成提醒，交出去一次就清掉。
func TestActivityAndAlerts(t *testing.T) {
	c, d := newCore(t)
	id := addDevice(t, c, d, hostlink.Session{ID: "s1", Title: "一", Updated: 2}, hostlink.Session{ID: "s2", Updated: 1})
	call[map[string]bool](t, c, "alerts", nil)
	d.PushActivity(id, map[string]hostlink.Activity{"s1": {State: "idle"}, "s2": {State: "idle"}})
	c.Do(func() {})
	d.PushActivity(id, map[string]hostlink.Activity{"s1": {State: "waiting"}, "s2": {State: "answering", Tasks: 2}})
	c.Do(func() {})
	st := call[State](t, c, "state", nil)
	if st.Sessions[0].Activity != "waiting" || st.Sessions[1].Activity != "answering" || st.Sessions[1].Tasks != 2 {
		t.Fatalf("行尾 %+v", st.Sessions)
	}
	if len(st.Side.Background) != 2 || st.Side.Background[0].ID != "s1" || st.Side.Background[0].Title != "一" {
		t.Fatalf("后台对话 %+v", st.Side.Background)
	}
	if len(st.Side.Alerts) != 1 || st.Side.Alerts[0].Kind != "waiting" || st.Side.Alerts[0].ID != "s1" {
		t.Fatalf("提醒 %+v", st.Side.Alerts)
	}
	d.PushActivity(id, map[string]hostlink.Activity{"s1": {State: "idle", TurnEnd: 5}, "s2": {State: "answering", Tasks: 2}})
	c.Do(func() {})
	if st = call[State](t, c, "state", nil); len(st.Side.Alerts) != 1 || st.Side.Alerts[0].Kind != "finished" {
		t.Fatalf("答完一轮的提醒 %+v", st.Side.Alerts)
	}
	if st = call[State](t, c, "state", nil); len(st.Side.Alerts) != 0 {
		t.Fatalf("交出去以后还在 %+v", st.Side.Alerts)
	}
}
