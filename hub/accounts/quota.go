package accounts

import (
	"context"
	"errors"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/locales"
)

const (
	// staleAfter：数比这旧时才去向 Claude Code 要（页面打开、小控件展开时）。
	staleAfter = 2 * time.Minute
	// askGap：两次向 Claude Code 要之间至少隔这么久，它取额度的接口会限流。
	askGap = 15 * time.Second
	// askTimeout：要一次最多等这么久。
	askTimeout = time.Minute
)

// Quota 是一台主机上登录着的账号的订阅额度。数有两个来源，都经主机上的 serve 存成同一份（按账号分开）：
//   - quota.get：主机上存着的上一次的数（核心接着的对话里的 rate_limit_event 核心自己并进去），不用起 Claude Code；
//   - UsageSource（conv.quota）：向 Claude Code 要一次 get_usage，应答交给 quota.usage 读成额度、存起来。
//
// 这台在用中转（不是订阅账号）时没有订阅额度：桌面版各处显不显示额度只看这里的 Shown。依据只取核心给的状态——
// quota.get、quota.usage 结果里的 relay，accounts.list 读到的在用中转（Service 经 follow 告诉这里）。在用中转时只读
// quota.get（看换没换回账号），不发 conv.quota、quota.usage；还不知道时先读一次 quota.get，是订阅再决定要不要问 Claude Code。
//
// 方法和读状态都在界面线程上；后台的事（loop.Run）做完经 loop.Post 回到界面线程改状态。
type Quota struct {
	core   *coreapi.Client
	source UsageSource
	loop   Loop
	now    func() time.Time

	// known：核心说过在用的是不是中转；relay 是在用的中转名，在用订阅账号时为空。
	known bool
	relay string

	snapshot   *coreapi.QuotaSnapshot
	refreshing bool
	// err 是上一次没取到最新的数的原因；stale：取到了，但 Claude Code 这次没拿到服务器的数，显示的还是旧的。
	err       error
	stale     bool
	lastAsked time.Time
}

// NewQuota 返回一台主机的额度。source 为 nil 时只读主机上存着的数；now 为 nil 时用 time.Now。
func NewQuota(core *coreapi.Client, source UsageSource, loop Loop, now func() time.Time) *Quota {
	if now == nil {
		now = time.Now
	}
	return &Quota{core: core, source: source, loop: loop, now: now}
}

// Snapshot 是收到的额度，界面按自己的时刻看（Snapshot.At）；还没有、不是订阅账号时 ok 为 false。
func (q *Quota) Snapshot() (s coreapi.QuotaSnapshot, ok bool) {
	if q.snapshot == nil || q.relay != "" {
		return coreapi.QuotaSnapshot{}, false
	}
	return *q.snapshot, true
}

// Refreshing 报告是不是正在取。
func (q *Quota) Refreshing() bool { return q.refreshing }

// Subscribed 报告核心说过这台在用订阅账号（或未登录），不是中转；还不知道时为假。
func (q *Quota) Subscribed() bool { return q.known && q.relay == "" }

// Shown 报告界面显不显示订阅额度（账号页的额度一组、用量弹出框的额度一节、设置侧栏的已用百分比）：核心说了在用订阅
// 账号时显示；还不知道时不显示，免得在用中转的设备先闪一下额度，只有读失败时显示（让原因和刷新按钮看得见）。
func (q *Quota) Shown() bool { return q.Subscribed() || (!q.known && q.err != nil) }

// Relay 是在用的中转名；在用订阅账号、还不知道时为空。
func (q *Quota) Relay() string { return q.relay }

// Refresh 取一次额度。数够新、force 为 false（页面打开、小控件展开）时只读主机上存着的；数旧了或点了刷新（force）时
// 向 Claude Code 要（两次至少隔 15 秒，没有 UsageSource 时也只读存着的）。不是订阅账号、还不知道时先只读 quota.get。
func (q *Quota) Refresh(force bool) {
	if q.refreshing {
		return
	}
	now := q.now()
	stale := q.snapshot == nil || now.Sub(q.snapshot.UpdatedAt) >= staleAfter
	ask := q.source != nil && q.relay == "" && (force || stale) && (q.lastAsked.IsZero() || now.Sub(q.lastAsked) >= askGap)
	if ask {
		q.lastAsked = now
	}
	known := q.known
	q.refreshing = true
	q.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), askTimeout)
		defer cancel()
		r := q.fetch(ctx, ask, known)
		q.loop.Post(func() { q.received(r) })
	})
}

// fetched 是取一次的结果：Relay 不空时在用中转（没有额度）；Fresh 为假是 Claude Code 这次没取到服务器的数、核心留着已有的。
type fetched struct {
	coreapi.QuotaUsage
	err error
}

// fetch 取一次额度：known 为假（还不知道是不是订阅）时先读核心存着的（quota.get），在用中转就到此为止；ask 为真时再向
// Claude Code 要、交给核心读（quota.usage），否则就是读到的这份。
func (q *Quota) fetch(ctx context.Context, ask, known bool) fetched {
	var got *coreapi.QuotaState
	get := func() fetched {
		if got == nil {
			st, err := q.core.Quota(ctx)
			if err != nil {
				return fetched{err: err}
			}
			got = &st
		}
		return fetched{QuotaUsage: coreapi.QuotaUsage{Snapshot: got.Snapshot, Fresh: true, Relay: got.Relay}}
	}
	if !known || !ask {
		if r := get(); r.err != nil || !ask || r.Relay != "" {
			return r
		}
	}
	raw, err := q.source.Usage(ctx)
	switch code, _ := hostlink.ReplyCode(err); {
	case errors.Is(err, ErrNoProcess):
		return get()
	case code == coreapi.CodeNoSubscription: // 读过以后换成了中转：重读一次，拿到中转名
		got = nil
		return get()
	case errors.Is(err, context.DeadlineExceeded):
		return fetched{err: errTimedOut}
	case err != nil:
		return fetched{err: err}
	}
	r, err := q.core.SubmitQuotaUsage(ctx, raw)
	return fetched{QuotaUsage: r, err: err}
}

// received 收下一次取的结果（界面线程上）。
func (q *Quota) received(r fetched) {
	q.refreshing = false
	if r.err != nil {
		q.err = r.err
		return
	}
	q.follow(r.Relay)
	if r.Relay != "" {
		q.lastAsked = time.Time{} // 在用中转时没问 Claude Code，换回账号后不用等间隔
		return
	}
	if r.Snapshot != nil {
		q.snapshot = r.Snapshot
	}
	q.err, q.stale = nil, !r.Fresh && r.Snapshot != nil
}

// follow 收下核心说的在用中转（空是在用订阅账号或未登录）：换成中转时丢掉额度；换回账号时数旧了，下次取会问。
func (q *Quota) follow(relay string) {
	q.known = true
	if relay != "" && relay != q.relay {
		q.snapshot, q.err, q.stale = nil, nil, false
	}
	q.relay = relay
}

// switched：换了号，上一个号的数不再显示，向 Claude Code 重要一次。
func (q *Quota) switched() {
	q.snapshot, q.err, q.stale, q.lastAsked = nil, nil, false, time.Time{}
	q.Refresh(true)
}

// ApplyState 收下核心推送的 quota.snapshot（界面线程上）。
func (q *Quota) ApplyState(st coreapi.QuotaState) {
	q.follow(st.Relay)
	if st.Relay != "" {
		return
	}
	if st.Snapshot != nil {
		q.snapshot = st.Snapshot
	}
	q.err, q.stale = nil, false
}

// errTimedOut 是向 Claude Code 要额度等太久了。
var errTimedOut = locales.Err(locales.AccountsQuotaTimedOut)

// Footnote 是额度的一句说明：多久以前的数、有没有在刷新、上次为什么没取到。按界面现在的语言出字。
func (q *Quota) Footnote(now time.Time) string {
	s, ok := q.Snapshot()
	var text string
	switch {
	case !ok && q.refreshing:
		text = locales.AccountsQuotaFetching()
	case !ok:
		text = locales.AccountsQuotaNoData()
	case s.Unavailable:
		text = locales.AccountsQuotaUnavailable()
	case len(s.Windows) == 0 && (s.Extra == nil || !s.Extra.Enabled):
		text = locales.AccountsQuotaNotYet()
	case q.refreshing:
		text = locales.AccountsQuotaUpdatedRefreshing(AgeText(s.UpdatedAt, now))
	default:
		text = locales.AccountsQuotaUpdated(AgeText(s.UpdatedAt, now))
	}
	switch {
	case q.err != nil:
		text = locales.AccountsQuotaNoteProblem(text, locales.AccountsQuotaFailed(locales.ErrorText(q.err)))
	case q.stale && ok:
		text = locales.AccountsQuotaNoteProblem(text, locales.AccountsQuotaStale(AgeText(s.UpdatedAt, now)))
	}
	return text
}
