package accounts

import (
	"context"
	"errors"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
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
// 方法和读状态都在界面线程上；后台的事（loop.Run）做完经 loop.Post 回到界面线程改状态。
type Quota struct {
	core   *coreapi.Client
	source UsageSource
	loop   Loop
	now    func() time.Time

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

// Snapshot 是收到的额度，界面按自己的时刻看（Snapshot.At）；还没有时 ok 为 false。
func (q *Quota) Snapshot() (s coreapi.QuotaSnapshot, ok bool) {
	if q.snapshot == nil {
		return coreapi.QuotaSnapshot{}, false
	}
	return *q.snapshot, true
}

// Refreshing 报告是不是正在取。
func (q *Quota) Refreshing() bool { return q.refreshing }

// Refresh 取一次额度。数够新、force 为 false（页面打开、小控件展开）时只读主机上存着的；数旧了或点了刷新（force）时
// 向 Claude Code 要（两次至少隔 15 秒，没有 UsageSource 时也只读存着的）。
func (q *Quota) Refresh(force bool) {
	if q.refreshing {
		return
	}
	now := q.now()
	stale := q.snapshot == nil || now.Sub(q.snapshot.UpdatedAt) >= staleAfter
	ask := q.source != nil && (force || stale) && (q.lastAsked.IsZero() || now.Sub(q.lastAsked) >= askGap)
	if ask {
		q.lastAsked = now
	}
	q.refreshing = true
	q.loop.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), askTimeout)
		defer cancel()
		snap, fresh, err := q.fetch(ctx, ask)
		q.loop.Post(func() { q.received(snap, fresh, err) })
	})
}

// fetch 取一次额度：ask 为真时向 Claude Code 要、交给核心读（quota.usage），否则读核心存着的（quota.get）。
// fresh 为假是 Claude Code 这次没取到服务器的数、核心留着已有的。
func (q *Quota) fetch(ctx context.Context, ask bool) (snap *coreapi.QuotaSnapshot, fresh bool, err error) {
	if !ask {
		snap, err = q.core.Quota(ctx)
		return snap, true, err
	}
	raw, err := q.source.Usage(ctx)
	switch {
	case errors.Is(err, ErrNoProcess):
		snap, err = q.core.Quota(ctx)
		return snap, true, err
	case errors.Is(err, context.DeadlineExceeded):
		return nil, false, errTimedOut
	case err != nil:
		return nil, false, err
	}
	r, err := q.core.SubmitQuotaUsage(ctx, raw)
	return r.Snapshot, r.Fresh, err
}

// received 收下一次取的结果（界面线程上）。
func (q *Quota) received(snap *coreapi.QuotaSnapshot, fresh bool, err error) {
	q.refreshing = false
	if err != nil {
		q.err = err
		return
	}
	if snap != nil {
		q.snapshot = snap
	}
	q.err, q.stale = nil, !fresh && snap != nil
}

// switched：换了号，上一个号的数不再显示，向 Claude Code 重要一次。
func (q *Quota) switched() {
	q.snapshot, q.err, q.stale, q.lastAsked = nil, nil, false, time.Time{}
	q.Refresh(true)
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
	case len(s.Windows) == 0:
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
