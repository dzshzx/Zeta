package mobile

import (
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/accounts"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/egoist/mygo/i18n"
)

// 设备页里的「账号与额度」：订阅额度、账号、添加账号（登录）、中转。逻辑都在中枢的 hub.Remote(id).Accounts、.Quota
// （hub/accounts），这里只把它们的状态整理成界面要的样子、把操作转过去；它们做完事经中枢的 post 回到核心 goroutine，
// 界面照常收到「有变化了」再读 accounts。

// Accounts 是方法 accounts 的结果：一台设备的账号页。
type Accounts struct {
	// Loaded 为假是还没读到账号（Accounts、Relays 为空）。
	Loaded   bool            `json:"loaded"`
	Accounts []AccountRow    `json:"accounts"`
	Relays   []coreapi.Relay `json:"relays"`
	// Unsaved：在用的登录还没记过（终端里 /login 的新号），能「记下」。
	Unsaved bool `json:"unsaved"`
	// Blocker 说别的包或终端里的 Claude Code 挡着换号；Working 是正在做的事；Problem 是上一件事为什么没做成。
	Blocker string `json:"blocker"`
	Working string `json:"working"`
	Problem string `json:"problem"`
	// Login 是正在进行的登录，没有时为 null。
	Login *LoginState `json:"login"`
	// Relay 是在用的中转名；Quota 是订阅额度，不显示时（在用中转、还不知道）为 null（只看 accounts.Quota.Shown）。
	Relay string     `json:"relay"`
	Quota *QuotaView `json:"quota"`
	// Draft 是正在添加或编辑的中转，没有时为 null。
	Draft *DraftView `json:"draft"`
}

// AccountRow 是一个账号。
type AccountRow struct {
	Name    string `json:"name"`
	Title   string `json:"title"`
	Email   string `json:"email"`
	Plan    string `json:"plan"`
	Current bool   `json:"current"`
	Expired bool   `json:"expired"`
}

// LoginState 是正在进行的登录：在浏览器里打开 URL 授权，把页面给的授权码交回来（accounts.code）；Notice 是上次交的码为什么被退回。
type LoginState struct {
	URL    string `json:"url"`
	Notice string `json:"notice"`
}

// QuotaView 是订阅额度：各个窗口、额外用量、一句说明。
type QuotaView struct {
	Plan        string       `json:"plan"`
	Unavailable bool         `json:"unavailable"`
	Blocked     bool         `json:"blocked"`
	BlockedText string       `json:"blocked_until"` // 解除时刻怎么说，不知道时为空
	Windows     []WindowView `json:"windows"`
	Extra       *ExtraView   `json:"extra"`
	Footnote    string       `json:"footnote"`
	Refreshing  bool         `json:"refreshing"`
}

// WindowView 是一个额度窗口：名字、已用比例、程度（normal、warning、critical）、什么时候重置。
type WindowView struct {
	Label    string  `json:"label"`
	Percent  int     `json:"percent"`
	Used     float64 `json:"used"`
	Severity string  `json:"severity"`
	Reset    string  `json:"reset"`
}

// ExtraView 是开着的额外用量：Amount 是「已用 / 上限」（没有金额时为空），Used 是已用比例（不知道时为 null）。
type ExtraView struct {
	Amount string   `json:"amount"`
	Used   *float64 `json:"used"`
}

// DraftView 是添加或编辑中的中转。密钥不交回去：界面自己留着用户填的那份。
type DraftView struct {
	Create        bool                 `json:"create"`
	Name          string               `json:"name"`
	BaseURL       string               `json:"base_url"`
	Model         string               `json:"model"`
	HasKey        bool                 `json:"has_key"`
	KeyEditable   bool                 `json:"key_editable"`
	Current       bool                 `json:"current"`
	Ready         bool                 `json:"ready"`
	CanFetch      bool                 `json:"can_fetch"`
	Loading       bool                 `json:"loading"`
	Fetched       bool                 `json:"fetched"`
	Models        []coreapi.RelayModel `json:"models"`
	ModelsProblem string               `json:"models_problem"`
	// Saved：保存成了（界面关掉编辑页）。
	Saved bool `json:"saved"`
}

// relayDraft 是账号页上正在添加或编辑的那个中转（同一时刻只有一个），记着是哪台设备和哪个工具的。
type relayDraft struct {
	device  string
	harness string
	d       *accounts.RelayDraft
	saved   bool
}

type (
	deviceParams struct {
		ID      string `json:"id"`
		Harness string `json:"harness"`
	}
	refreshParams struct {
		deviceParams
		Force bool `json:"force"`
	}
	accountParams struct {
		deviceParams
		Name string `json:"name"`
	}
	codeParams struct {
		deviceParams
		Code string `json:"code"`
	}
	relayDraftParams struct {
		deviceParams
		Name    string `json:"name"`
		BaseURL string `json:"base_url"`
		Key     string `json:"key"`
		Model   string `json:"model"`
	}
)

// loopRemote 是设备 id 的账号和额度（核心 goroutine 上调；别处用 remote）；没有这台、「连接」关着时说已断开。
func (c *Core) loopRemote(id string) (*hub.Remote, error) {
	r := c.hub.Remote(id)
	if r == nil {
		name := c.hub.Name(id)
		return nil, locales.Err(func() string { return locales.HubOffline(name) })
	}
	return r, nil
}

type remoteTarget interface {
	device() string
	tool() string
}

// onRemote 登记一个对设备 id 指定工具的账号做的事（核心 goroutine 上，很快返回；结果在之后的 accounts 里）。
func onRemote[P remoteTarget](name string, f func(c *Core, svc *accounts.Service, q *accounts.Quota, h string, p P) error) {
	onLoop(name, func(c *Core, p P) (any, error) {
		r, err := c.loopRemote(p.device())
		if err != nil {
			return nil, err
		}
		h := sessionproto.NormalizeHarness(p.tool())
		return ok, f(c, r.AccountsFor(h), r.QuotaFor(h), h, p)
	})
}

func (p deviceParams) device() string { return p.ID }
func (p deviceParams) tool() string   { return p.Harness }

func init() {
	reading("accounts", func(c *Core, p deviceParams) (any, error) {
		r, err := c.loopRemote(p.ID)
		if err != nil {
			return nil, err
		}
		h := sessionproto.NormalizeHarness(p.Harness)
		return c.accountsView(p.ID, h, r, time.Now()), nil
	})
	// accounts.open：打开账号页时读一遍账号和额度（额度够新就只读主机上存着的）。
	onRemote("accounts.open", func(_ *Core, svc *accounts.Service, q *accounts.Quota, _ string, _ deviceParams) error {
		if svc.Working() == "" {
			svc.Refresh()
		}
		q.Refresh(false)
		return nil
	})
	onRemote("accounts.refresh", func(_ *Core, svc *accounts.Service, q *accounts.Quota, _ string, p refreshParams) error {
		if svc.Working() == "" {
			svc.Refresh()
		}
		q.Refresh(p.Force)
		return nil
	})
	onRemote("accounts.dismiss", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, _ deviceParams) error {
		svc.DismissProblem()
		return nil
	})
	onRemote("accounts.switch", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, p accountParams) error {
		a, err := account(svc, p.Name)
		if err == nil {
			svc.Switch(a)
		}
		return err
	})
	onRemote("accounts.remove", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, p accountParams) error {
		a, err := account(svc, p.Name)
		if err == nil {
			svc.Remove(a)
		}
		return err
	})
	onRemote("accounts.save", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, _ deviceParams) error {
		svc.Save()
		return nil
	})
	onRemote("accounts.login", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, _ deviceParams) error {
		svc.StartLogin()
		return nil
	})
	onRemote("accounts.code", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, p codeParams) error {
		svc.SubmitCode(p.Code)
		return nil
	})
	onRemote("accounts.cancel", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, _ deviceParams) error {
		svc.CancelLogin()
		return nil
	})
	onRemote("relay.switch", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, p accountParams) error {
		x, err := relay(svc, p.Name)
		if err == nil {
			svc.SwitchRelay(x)
		}
		return err
	})
	onRemote("relay.remove", func(_ *Core, svc *accounts.Service, _ *accounts.Quota, _ string, p accountParams) error {
		svc.RemoveRelay(p.Name, nil)
		return nil
	})
	// relay.edit 开始添加（name 空）或编辑中转 name：草稿留在核心里，之后 relay.draft 改、relay.fetch、relay.save。
	onRemote("relay.edit", func(c *Core, svc *accounts.Service, _ *accounts.Quota, h string, p accountParams) error {
		d := accounts.NewRelayDraft()
		if p.Name != "" {
			x, err := relay(svc, p.Name)
			if err != nil {
				return err
			}
			d = accounts.EditRelayDraft(x)
		}
		c.relay = &relayDraft{device: p.ID, harness: h, d: d}
		return nil
	})
	onRemote("relay.draft", func(c *Core, _ *accounts.Service, _ *accounts.Quota, h string, p relayDraftParams) error {
		d, err := c.draft(p.ID, h)
		if err != nil {
			return err
		}
		if d.d.Create {
			d.d.Name = p.Name // 名称加好以后不能改
		}
		d.d.BaseURL, d.d.Key, d.d.Model = p.BaseURL, p.Key, p.Model
		return nil
	})
	onRemote("relay.fetch", func(c *Core, svc *accounts.Service, _ *accounts.Quota, h string, p deviceParams) error {
		d, err := c.draft(p.ID, h)
		if err == nil {
			svc.FetchRelayModels(d.d)
		}
		return err
	})
	onRemote("relay.save", func(c *Core, svc *accounts.Service, _ *accounts.Quota, h string, p deviceParams) error {
		d, err := c.draft(p.ID, h)
		if err == nil && d.d.Ready() {
			svc.SaveRelay(d.d, func() { d.saved = true })
		}
		return err
	})
	onLoop("relay.close", func(c *Core, _ none) (any, error) {
		c.relay = nil
		return ok, nil
	})
}

// draft 是设备 id 工具 h 上正在编辑的中转。
func (c *Core) draft(id, h string) (*relayDraft, error) {
	if c.relay == nil || c.relay.device != id || c.relay.harness != h {
		return nil, locales.Err(locales.MobileRelayDraftGone)
	}
	return c.relay, nil
}

// account 是读到的账号里叫 name 的那个。
func account(svc *accounts.Service, name string) (coreapi.Account, error) {
	st, _ := svc.State()
	i := slices.IndexFunc(st.Accounts, func(a coreapi.Account) bool { return a.Name == name })
	if name == "" || i < 0 {
		return coreapi.Account{}, locales.Err(locales.MobileAccountGone)
	}
	return st.Accounts[i], nil
}

// relay 是读到的中转里叫 name 的那个。
func relay(svc *accounts.Service, name string) (coreapi.Relay, error) {
	st, _ := svc.State()
	i := slices.IndexFunc(st.Relays, func(x coreapi.Relay) bool { return x.Name == name })
	if i < 0 {
		return coreapi.Relay{}, locales.Err(locales.MobileAccountGone)
	}
	return st.Relays[i], nil
}

func (c *Core) accountsView(id, h string, r *hub.Remote, now time.Time) Accounts {
	svc, q := r.AccountsFor(h), r.QuotaFor(h)
	out := Accounts{Accounts: []AccountRow{}, Relays: []coreapi.Relay{}, Working: svc.Working(),
		Problem: svc.Problem(), Relay: q.Relay()}
	if st, loaded := svc.State(); loaded {
		out.Loaded, out.Blocker = true, st.Blocker
		out.Unsaved = st.Current != nil && st.Current.Name == ""
		for _, a := range st.Accounts {
			out.Accounts = append(out.Accounts, AccountRow{Name: a.Name, Title: a.Title(), Email: a.Email, Plan: a.PlanLabel(),
				Current: st.IsCurrent(a), Expired: a.Expired(now)})
		}
		if st.Relays != nil {
			out.Relays = st.Relays
		}
		if x := st.CurrentRelay(); x != nil && out.Relay == "" {
			out.Relay = x.Name
		}
	}
	if l, ok := svc.Login(); ok {
		out.Login = &LoginState{URL: l.URL, Notice: locales.ErrorText(l.Notice)}
	}
	if q.Shown() && out.Relay == "" {
		out.Quota = quotaView(q, now)
	}
	if d := c.relay; d != nil && d.device == id && d.harness == h {
		out.Draft = draftView(d)
	}
	return out
}

func quotaView(q *accounts.Quota, now time.Time) *QuotaView {
	v := &QuotaView{Windows: []WindowView{}, Footnote: q.Footnote(now), Refreshing: q.Refreshing()}
	s, ok := q.Snapshot()
	if !ok {
		return v
	}
	s = s.At(now)
	locale := i18n.Locale()
	v.Plan, v.Unavailable, v.Blocked = s.PlanLabel(), s.Unavailable, s.Blocked
	if s.Blocked {
		v.BlockedText = accounts.ResetText(s.BlockedUntil, now)
	}
	if s.Unavailable {
		return v
	}
	for _, w := range s.Windows {
		v.Windows = append(v.Windows, WindowView{Label: w.LabelIn(locale), Percent: w.Percent(), Used: w.Used,
			Severity: severityName(w.Severity), Reset: accounts.ResetText(w.ResetsAt, now)})
	}
	if e := s.Extra; e != nil && e.Enabled {
		var amounts []string
		for _, m := range []string{accounts.Money(e.UsedMin, e.Currency), accounts.Money(e.LimitMin, e.Currency)} {
			if m != "" {
				amounts = append(amounts, m)
			}
		}
		v.Extra = &ExtraView{Amount: strings.Join(amounts, " / "), Used: e.Used}
	}
	return v
}

func severityName(s coreapi.Severity) string {
	switch s {
	case coreapi.SeverityWarning:
		return "warning"
	case coreapi.SeverityCritical:
		return "critical"
	}
	return "normal"
}

func draftView(r *relayDraft) *DraftView {
	d := r.d
	models, fetched := d.Models()
	if models == nil {
		models = []coreapi.RelayModel{}
	}
	return &DraftView{Create: d.Create, Name: d.Name, BaseURL: d.BaseURL, Model: d.Model, HasKey: d.HasKey,
		KeyEditable: d.KeyEditable, Current: d.Current, Ready: d.Ready(), CanFetch: d.CanFetch(), Loading: d.Loading(),
		Fetched: fetched, Models: models, ModelsProblem: d.ModelsProblem(), Saved: r.saved}
}
