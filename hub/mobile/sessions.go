package mobile

import (
	"slices"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/settings"
	"github.com/egoist/mygo/i18n"
)

// 会话列表的管理（同桌面版 sessionops.go、search.go、sidebarsort.go、alltasks.go、notify.go 调中枢的方式）：
// 置顶、改名、归档、删除、结束、导出、搜索、排序、手动拖动，后台对话和别的会话的提醒。逻辑都在中枢里，这里只转。
// 没成的原因放进下一次 state 的 side.failed（交出去一次就清掉）。

// Side 是 state 里侧栏的其余部分。
type Side struct {
	// Sort 是排序：replied、created、manual（settings.SessionSort）。
	Sort string `json:"sort"`
	// Searching：在搜，sessions 换成了搜索结果；Search 是对话内容搜到哪了：running、done、partial、failed，不在搜时为空。
	Searching bool   `json:"searching"`
	Search    string `json:"search"`
	// Background 是后台任务面板的「后台对话」（hub.BackgroundChats）。
	Background []BackgroundChat `json:"background"`
	// Alerts 是上次 state 以后中枢报来的别的会话的提醒（hub.Alert），交出去就清掉；要先调过 alerts 才有。
	Alerts []Alert `json:"alerts"`
	Failed string  `json:"failed"`
}

// BackgroundChat 是「后台对话」的一行：Activity 是核心报的 waiting、answering、background、idle。
type BackgroundChat struct {
	Backend  string `json:"backend"`
	ID       string `json:"id"`
	Title    string `json:"title"`
	Activity string `json:"activity"`
	Tasks    int    `json:"tasks"`
}

// Alert 是一个提醒：Kind 是 finished（答完一轮）、waiting（开始等你回应）、settled（不再等了，撤掉等回应的通知）。
type Alert struct {
	Backend string `json:"backend"`
	ID      string `json:"id"`
	Title   string `json:"title"`
	Kind    string `json:"kind"`
}

// sidebar 是 Core 上侧栏的状态，只在核心 goroutine 上读写。
type sidebar struct {
	alerts []Alert
	failed string
}

var (
	activityNames = map[hub.Activity]string{hub.Quiet: "", hub.Waiting: "waiting", hub.Answering: "answering",
		hub.Background: "background", hub.Unread: "unread", hub.Idle: "idle"}
	progressNames = map[hub.SearchProgress]string{hub.SearchIdle: "", hub.SearchRunning: "running", hub.SearchDone: "done",
		hub.SearchPartial: "partial", hub.SearchFailed: "failed"}
	alertNames = map[hub.AlertKind]string{hub.AlertFinished: "finished", hub.AlertWaiting: "waiting", hub.AlertSettled: "settled"}
)

// rows 是会话列表要列的：在搜时是搜索结果，否则照所选的排序（归档的也在，界面自己分）。
func (c *Core) rows(current hostlink.Session) []hostlink.Session {
	if c.hub.Searching() {
		return c.hub.Results(current, time.Now())
	}
	return c.hub.Ordered(time.Now(), current)
}

// decorate 给一行补上行尾的状态和搜索片段。开着的那个按聊天页的对话算；它在等你时标成在回答（确认卡就在眼前，同桌面版）。
func (c *Core) decorate(row Session, s, current hostlink.Session) Session {
	var local hub.Local
	isCurrent := current.ID != "" && s.Backend == current.Backend && s.ID == current.ID
	if ch := c.hub.Chat(); isCurrent && ch != nil && ch.Conv != nil {
		st := ch.Conv.State()
		local = hub.Local{Waiting: len(st.Prompts) > 0, Busy: st.Busy, Tasks: st.RunningTasks()}
	}
	status := c.hub.Status(s, local)
	if isCurrent && status.Activity == hub.Waiting {
		status.Activity = hub.Answering
	}
	row.Activity, row.Tasks, row.Snippet = activityNames[status.Activity], status.Tasks, c.hub.Snippet(s)
	row.Draft, _ = c.comp().rowDraft(s.ID)
	return row
}

func (c *Core) side() Side {
	h := c.hub
	out := Side{Sort: string(h.Sort()), Searching: h.Searching(), Search: progressNames[h.SearchProgress()],
		Background: []BackgroundChat{}, Alerts: c.sidebar.alerts, Failed: c.sidebar.failed}
	if out.Alerts == nil {
		out.Alerts = []Alert{}
	}
	c.sidebar.alerts, c.sidebar.failed = nil, ""
	for _, b := range h.BackgroundChats() {
		s := b.Session
		out.Background = append(out.Background, BackgroundChat{Backend: s.Backend, ID: s.ID, Title: hub.SessionLabel(s, i18n.Locale()),
			Activity: b.Activity.State, Tasks: b.Activity.Tasks})
	}
	return out
}

// failed 是交给中枢的没成时的回调：原因放进 side.failed；what 是说明做的什么（可以为空），原因接在后面。
func (c *Core) failed(what string) func(error) {
	return func(err error) {
		c.sidebar.failed = locales.ErrorText(err)
		if what != "" {
			c.sidebar.failed = what + "\n" + c.sidebar.failed
		}
	}
}

type (
	sessionRef struct {
		Backend string `json:"backend"`
		ID      string `json:"id"`
	}
	sessionsParams struct {
		Sessions []sessionRef `json:"sessions"`
		Archived bool         `json:"archived"`
	}
	renameParams struct {
		sessionRef
		Title string `json:"title"`
	}
	queryParams struct {
		Query string `json:"query"`
	}
	sortParams struct {
		Sort string `json:"sort"`
	}
	moveParams struct {
		sessionRef
		Target sessionRef `json:"target"`
		Below  bool       `json:"below"`
	}
)

// Exported 是 export 答的：存成的文件名和 Markdown。
type Exported struct {
	Name    string `json:"name"`
	Content string `json:"content"`
}

// exportTimeout 是 export 最多等多久（中枢自己的时限之外再留一点）。
const exportTimeout = 90 * time.Second

func init() {
	onLoop("alerts", func(c *Core, _ none) (any, error) {
		c.hub.OnAlert(func(a hub.Alert) {
			c.sidebar.alerts = append(c.sidebar.alerts, Alert{Backend: a.Session.Backend, ID: a.Session.ID,
				Title: hub.SessionLabel(a.Session, i18n.Locale()), Kind: alertNames[a.Kind]})
		})
		return ok, nil
	})
	onLoop("pin", func(c *Core, p sessionRef) (any, error) {
		s, err := c.find(p)
		if err == nil {
			c.hub.TogglePin(s, c.failed(""))
		}
		return ok, err
	})
	onLoop("rename", func(c *Core, p renameParams) (any, error) {
		s, err := c.find(p.sessionRef)
		if err == nil {
			c.hub.Rename(s, p.Title, c.failed(""))
		}
		return ok, err
	})
	onLoop("archive", func(c *Core, p sessionsParams) (any, error) {
		list, err := c.findAll(p.Sessions)
		if err == nil {
			c.hub.SetArchived(list, p.Archived, c.failed(""))
		}
		return ok, err
	})
	onLoop("delete", func(c *Core, p sessionsParams) (any, error) {
		list, err := c.findAll(p.Sessions)
		if err == nil {
			c.hub.Delete(list, c.failed(""))
		}
		return ok, err
	})
	onLoop("end", func(c *Core, p sessionsParams) (any, error) {
		list, err := c.findAll(p.Sessions)
		if err == nil {
			c.hub.End(list, c.failed(""))
		}
		return ok, err
	})
	blocking("export", func(c *Core, p sessionsParams) (any, error) {
		type result struct {
			e   Exported
			err error
		}
		done := make(chan result, 1)
		var err error
		c.Do(func() {
			var list []hostlink.Session
			if list, err = c.findAll(p.Sessions); err != nil {
				return
			}
			c.hub.Export(list, time.Now(), func(e hub.Exported) { done <- result{e: Exported{Name: e.Name, Content: e.Content}} },
				func(err error) { done <- result{err: err} })
		})
		if err != nil {
			return nil, err
		}
		select {
		case r := <-done:
			return r.e, r.err
		case <-time.After(exportTimeout):
			return nil, locales.Err(locales.HubSessionNothingExported)
		}
	})
	onLoop("search", func(c *Core, p queryParams) (any, error) {
		c.hub.SetQuery(p.Query)
		return ok, nil
	})
	onLoop("sort", func(c *Core, p sortParams) (any, error) {
		c.hub.SetSort(settings.SessionSort(p.Sort))
		return ok, nil
	})
	reading("canmove", func(c *Core, p moveParams) (any, error) {
		s, t, err := c.pair(p)
		return map[string]bool{"ok": err == nil && c.hub.CanMove(s, t, p.Below)}, nil
	})
	onLoop("move", func(c *Core, p moveParams) (any, error) {
		s, t, err := c.pair(p)
		if err != nil {
			return nil, err
		}
		return map[string]bool{"ok": c.hub.Move(s, t, p.Below, c.failed(""))}, nil
	})
}

// find 找列着的会话（搜索结果、归档的也算）。
func (c *Core) find(r sessionRef) (hostlink.Session, error) {
	s, found := c.session(r.Backend, r.ID)
	if !found {
		return s, locales.Err(locales.MobileSessionGone)
	}
	return s, nil
}

func (c *Core) findAll(refs []sessionRef) ([]hostlink.Session, error) {
	list := make([]hostlink.Session, 0, len(refs))
	for _, r := range refs {
		s, err := c.find(r)
		if err != nil {
			return nil, err
		}
		if !slices.ContainsFunc(list, func(x hostlink.Session) bool { return x.Backend == s.Backend && x.ID == s.ID }) {
			list = append(list, s)
		}
	}
	return list, nil
}

func (c *Core) pair(p moveParams) (s, t hostlink.Session, err error) {
	if s, err = c.find(p.sessionRef); err != nil {
		return
	}
	t, err = c.find(p.Target)
	return
}
