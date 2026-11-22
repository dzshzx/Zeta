package hub

import (
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

func TestCurrentSessionStaysListed(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.ListOnly(a.ID)
	now := time.Date(2026, 11, 22, 15, 0, 0, 0, time.Local)
	d.Push(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "old", Updated: now.Add(-48 * time.Hour).UnixMilli()}}})
	// 新对话有了会话号、那边的列表还没推来：放在今天最前
	fresh := hostlink.Session{ID: "fresh", Backend: a.ID, Title: "新对话"}
	g := h.Groups(now, fresh)
	if !reflect.DeepEqual(ids(g.Today), []string{"fresh"}) || !reflect.DeepEqual(ids(g.Earlier), []string{"old"}) {
		t.Errorf("groups %v %v", ids(g.Today), ids(g.Earlier))
	}
	// 推来以后用那边的，不重复
	d.Push(a.ID, hostlink.SessionsUpdate{Upsert: []hostlink.Session{{ID: "fresh", Title: "起好的标题", Updated: now.UnixMilli()}}})
	g = h.Groups(now, fresh)
	if len(g.Today) != 1 || g.Today[0].Title != "起好的标题" {
		t.Errorf("today %+v", g.Today)
	}
	// 还没有会话号的、不在列着的设备上的不加
	if g := h.Groups(now, hostlink.Session{Backend: a.ID}); len(g.Today) != 1 {
		t.Errorf("no id: %v", ids(g.Today))
	}
	if g := h.Groups(now, hostlink.Session{ID: "x", Backend: b.ID}); len(g.Today) != 1 {
		t.Errorf("other host: %v", ids(g.Today))
	}
}

func TestStatusFollowsTheCore(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	s1, s2 := hostlink.Session{ID: "s1", Backend: a.ID}, hostlink.Session{ID: "s2", Backend: a.ID}
	if got := h.Status(s1, Local{}); got != (Status{}) {
		t.Errorf("history only: %+v", got)
	}
	d.PushActivity(a.ID, map[string]hostlink.Activity{"s1": {State: "answering", Tasks: 1}, "s2": {State: "idle", TurnEnd: 5}})
	if got := h.Status(s1, Local{}); got != (Status{Answering, 1}) {
		t.Errorf("answering: %+v", got)
	}
	if got := h.Status(s2, Local{}); got != (Status{Activity: Idle}) {
		t.Errorf("idle: %+v", got)
	}
	// 这边开着的对话等确认，比核心报的要紧
	if got := h.Status(s1, Local{Waiting: true}); got.Activity != Waiting {
		t.Errorf("waiting: %+v", got)
	}
	// s2 又答完一轮：不在眼前，记成有新回答；点开后去掉
	d.PushActivity(a.ID, map[string]hostlink.Activity{"s2": {State: "idle", TurnEnd: 9}})
	if got := h.Status(s2, Local{}); got.Activity != Unread {
		t.Errorf("unread: %+v", got)
	}
	if got := h.Status(s1, Local{}); got != (Status{}) {
		t.Errorf("s1 gone from the core: %+v", got)
	}
	h.OpenSession(s2)
	if got := h.Status(s2, Local{}); got.Activity != Idle {
		t.Errorf("after opening: %+v", got)
	}
	// 正开着的那个答完一轮不算新回答
	d.PushActivity(a.ID, map[string]hostlink.Activity{"s2": {State: "idle", TurnEnd: 12}})
	if got := h.Status(s2, Local{}); got.Activity != Idle {
		t.Errorf("current one ended a turn: %+v", got)
	}
	// 断开订阅后不留过时的
	d.Fail(a.ID, "断了")
	h.Refresh()
	if got := h.Status(s2, Local{}); got != (Status{}) {
		t.Errorf("after reconnecting: %+v", got)
	}
}

func TestSearchMatchesTitlesAndContent(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.ListAll()
	now := time.Date(2026, 11, 22, 15, 0, 0, 0, time.Local)
	d.Push(a.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "a1", Title: "修 login 页", Updated: 30}, {ID: "a2", Preview: "看看 Login 日志", Updated: 20}, {ID: "a3", Title: "别的", Updated: 10, Archived: true},
	}})
	d.Push(b.ID, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "b1", Title: "部署", Updated: 25}}})
	d.SearchHits = map[string]map[string]string{a.ID: {"a3": "…加上 login 的重试…"}, b.ID: {"b1": "…LOGIN 失败…"}}
	d.Partial = map[string]bool{b.ID: true}
	h.SetQuery("  login ")
	if !h.Searching() || h.SearchProgress() != SearchPartial {
		t.Fatalf("progress %v", h.SearchProgress())
	}
	// 照列表的先后：标题、首句里有的，加上内容里找到的（归档的也算）
	got := h.Results(hostlink.Session{}, now)
	if !reflect.DeepEqual(ids(got), []string{"a1", "b1", "a2", "a3"}) {
		t.Errorf("results %v", ids(got))
	}
	if h.Snippet(got[1]) != "…LOGIN 失败…" || h.Snippet(got[0]) != "" {
		t.Errorf("snippets %q %q", h.Snippet(got[1]), h.Snippet(got[0]))
	}
	// 两头空白不同、关键词一样时不重搜
	h.SetQuery("login")
	if n := len(d.Searched()); n != 2 {
		t.Errorf("searched %v", d.Searched())
	}
	// 都没搜成：只比对标题
	d.SearchErr = map[string]error{a.ID: errors.New("连不上"), b.ID: errors.New("连不上")}
	h.SetQuery("部署")
	if h.SearchProgress() != SearchFailed || !reflect.DeepEqual(ids(h.Results(hostlink.Session{}, now)), []string{"b1"}) {
		t.Errorf("failed: %v %v", h.SearchProgress(), ids(h.Results(hostlink.Session{}, now)))
	}
	h.SetQuery(" ")
	if h.Searching() || h.SearchProgress() != SearchIdle || h.Results(hostlink.Session{}, now) != nil {
		t.Error("cleared")
	}
}

func TestCombinedSearchOutcome(t *testing.T) {
	for _, c := range []struct {
		in   []searchOutcome
		want SearchProgress
	}{
		{[]searchOutcome{{true, true}, {true, true}}, SearchDone},
		{[]searchOutcome{{true, true}, {false, true}}, SearchPartial},
		{[]searchOutcome{{true, true}, {false, false}}, SearchPartial},
		{[]searchOutcome{{false, false}}, SearchFailed},
		{nil, SearchFailed},
	} {
		if got := combined(c.in); got != c.want {
			t.Errorf("%v: %v, want %v", c.in, got, c.want)
		}
	}
}
