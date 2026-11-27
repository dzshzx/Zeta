package mobile

import (
	"cmp"
	"slices"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/egoist/mygo/i18n"
)

// State 是方法 state 的结果：界面要画的全部，字都已按界面语言出好。
type State struct {
	Devices []Device `json:"devices"`
	// Listed 是会话列表列的哪台设备，空是全部设备；Backend 是新对话开在哪台。
	Listed  string `json:"listed"`
	Backend string `json:"backend"`
	// Sessions 照所选的排序（置顶的不提前，界面自己分组）；Problem 是没读成会话列表的原因。
	Sessions []Session `json:"sessions"`
	Problem  string    `json:"problem"`
	// Chat 是聊天页里的对话，没有时为 null。
	Chat *Chat `json:"chat"`
}

// Device 是一台设备。
type Device struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Target      string `json:"target"`
	Home        string `json:"home"`
	Off         bool   `json:"off"`
	Fingerprint string `json:"fingerprint"`
}

// Session 是会话列表的一行。
type Session struct {
	Backend  string `json:"backend"`
	ID       string `json:"id"`
	Title    string `json:"title"`
	Place    string `json:"place"`
	Updated  int64  `json:"updated"`
	Pinned   bool   `json:"pinned"`
	External bool   `json:"external"`
	Ended    bool   `json:"ended"`
	Archived bool   `json:"archived"`
}

// Chat 是聊天页的样子。Items 是上次 state 以后条目的变化：Full 时第一项是整份，界面依次套上（同桌面版的 Take）。
type Chat struct {
	Backend     string             `json:"backend"`
	Session     string             `json:"session"`
	Title       string             `json:"title"`
	Device      string             `json:"device"`
	Cwd         string             `json:"cwd"`
	Opening     bool               `json:"opening"`
	Problem     string             `json:"problem"`
	LinkProblem string             `json:"link_problem"`
	Connected   bool               `json:"connected"`
	Busy        bool               `json:"busy"`
	Blocked     bool               `json:"blocked"`
	Status      string             `json:"status"`
	Model       string             `json:"model"`
	Earlier     int                `json:"earlier"`
	Prompts     []prompt.Prompt    `json:"prompts"`
	Items       []chat.ItemsChange `json:"items"`
	// Failed 是最近一次操作（发消息、停止、回应确认）没成的原因，交出去一次就清掉。
	Failed string `json:"failed"`
	// Composer 是输入栏（composer.go），对话还没打开成时为 null。
	Composer *Composer `json:"composer"`
}

func (c *Core) state() State {
	h := c.hub
	locale := i18n.Locale()
	st := State{Backend: h.Backend(), Problem: h.Problem(), Devices: []Device{}, Sessions: []Session{}}
	if !h.AllHosts() {
		st.Listed = h.Backend()
	}
	for _, x := range h.Hosts() {
		st.Devices = append(st.Devices, Device{ID: x.ID, Name: x.Name(), Target: x.Target, Home: x.Home, Off: x.Off,
			Fingerprint: x.Fingerprint()})
	}
	var current hostlink.Session
	if ch := h.Chat(); ch != nil {
		current = ch.Session
	}
	for _, s := range h.Ordered(time.Now(), current) {
		st.Sessions = append(st.Sessions, Session{Backend: s.Backend, ID: s.ID, Title: hub.SessionLabel(s, locale), Place: s.Place(),
			Updated: s.Updated, Pinned: h.Pinned(s), External: s.External, Ended: s.Ended, Archived: s.Archived})
	}
	st.Chat = c.chatState(locale)
	c.fillComposer(st.Chat, locale)
	return st
}

func (c *Core) chatState(locale string) *Chat {
	h := c.hub
	ch := h.Chat()
	if ch == nil {
		c.shown = nil
		return nil
	}
	out := &Chat{Backend: ch.Backend, Session: ch.Session.ID, Title: hub.SessionLabel(ch.Session, locale), Device: h.Name(ch.Backend),
		Cwd: ch.Session.Cwd, Opening: ch.Opening, Problem: cmp.Or(locales.ErrorText(ch.Problem), ch.CwdProblem),
		LinkProblem: h.ChatLinkProblem(), Prompts: []prompt.Prompt{}, Items: []chat.ItemsChange{}, Failed: c.problem}
	c.problem = ""
	if ch.Conv == nil {
		c.shown = nil
		return out
	}
	u := ch.Conv.Take()
	if c.shown != ch.Conv {
		c.shown = ch.Conv
		u.Items = []chat.ItemsChange{{Full: true, Items: ch.Conv.Items()}}
	}
	s := u.State
	out.Session = cmp.Or(s.Session, out.Session)
	out.Title = cmp.Or(s.Title, out.Title)
	if out.Title == "" {
		out.Title = locales.ChatNewTitle()
	}
	out.Cwd = cmp.Or(s.Cwd, out.Cwd)
	out.Connected, out.Busy, out.Blocked, out.Earlier = u.Connected, s.Busy, s.Blocked, s.Earlier
	out.Status = s.StatusIn(locale)
	out.Model = s.ReportedModel
	if s.Prompts != nil {
		out.Prompts = s.Prompts
	}
	for _, change := range u.Items {
		change.Items = said(change.Items, locale)
		out.Items = append(out.Items, change)
	}
	return out
}

// said 把条目里核心按种类码写的字（提示、工具结果）换成界面语言说的，Swift 一侧只管显示 text、result。改的是副本。
func said(list []items.Item, locale string) []items.Item {
	out := slices.Clone(list)
	for i := range out {
		it := &out[i]
		if it.Code != "" {
			it.Text = it.Said(locale)
		}
		if len(it.Steps) > 0 {
			it.Steps = slices.Clone(it.Steps)
			for j := range it.Steps {
				if it.Steps[j].ResultCode != "" {
					it.Steps[j].Result = it.Steps[j].ResultSaid(locale)
				}
			}
		}
	}
	return out
}
