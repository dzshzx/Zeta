package accounts

import (
	"context"
	"encoding/json"
	"errors"
	"time"
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
//   - quota.get、quota.event：主机上存着的上一次的数，和对话里 rate_limit_event 并进去的变化，不用起 Claude Code；
//   - UsageSource（conv.quota）：向 Claude Code 要一次 get_usage，应答交给 quota.usage 读成额度、存起来。
//
// 方法和读状态都在界面线程上；后台的事做完经 post 回到界面线程改状态。
type Quota struct {
	caller Caller
	source UsageSource
	post   func(func())
	now    func() time.Time

	snapshot   *Snapshot
	refreshing bool
	err        string
	lastAsked  time.Time
}

// NewQuota 返回一台主机的额度。source 为 nil 时只读主机上存着的数；now 为 nil 时用 time.Now。
func NewQuota(caller Caller, source UsageSource, post func(func()), now func() time.Time) *Quota {
	if now == nil {
		now = time.Now
	}
	return &Quota{caller: caller, source: source, post: post, now: now}
}

// Snapshot 是收到的额度，界面按自己的时刻看（Snapshot.At）；还没有时 ok 为 false。
func (q *Quota) Snapshot() (s Snapshot, ok bool) {
	if q.snapshot == nil {
		return Snapshot{}, false
	}
	return *q.snapshot, true
}

// Refreshing 报告是不是正在取。
func (q *Quota) Refreshing() bool { return q.refreshing }

// Err 是上一次没取到最新的数的原因；取到了就清掉。
func (q *Quota) Err() string { return q.err }

// quotaResult 是 quota.* 的结果。
type quotaResult struct {
	Snapshot *Snapshot `json:"snapshot"`
	Fresh    *bool     `json:"fresh"`
	Changed  bool      `json:"changed"`
}

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
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), askTimeout)
		defer cancel()
		r, err := q.fetch(ctx, ask)
		q.post(func() { q.received(r, err) })
	}()
}

func (q *Quota) fetch(ctx context.Context, ask bool) (quotaResult, error) {
	var r quotaResult
	if !ask {
		return r, q.call(ctx, "quota.get", nil, &r)
	}
	raw, err := q.source.Usage(ctx)
	switch {
	case errors.Is(err, ErrNoProcess):
		return r, q.call(ctx, "quota.get", nil, &r)
	case errors.Is(err, context.DeadlineExceeded):
		return r, errors.New("等了太久没有结果")
	case err != nil:
		return r, err
	}
	return r, q.call(ctx, "quota.usage", map[string]any{"response": raw}, &r)
}

func (q *Quota) call(ctx context.Context, method string, params map[string]any, out *quotaResult) error {
	raw, err := q.caller.Call(ctx, method, params)
	if err != nil {
		return err
	}
	if json.Unmarshal(raw, out) != nil {
		return errors.New("读不懂主机的回答")
	}
	return nil
}

// received 收下一次取的结果（界面线程上）。
func (q *Quota) received(r quotaResult, err error) {
	q.refreshing = false
	if err != nil {
		q.err = "没取到最新的额度：" + err.Error()
		return
	}
	if r.Snapshot != nil {
		q.snapshot = r.Snapshot
	}
	q.err = ""
	if r.Fresh != nil && !*r.Fresh && r.Snapshot != nil {
		q.err = "没取到最新的额度，显示的是" + AgeText(r.Snapshot.UpdatedAt, q.now()) + "的数"
	}
}

// Observe 收下对话里的一条 stream-json 事件：rate_limit_event 交给主机并进存着的额度（quota.event），别的不管。
func (q *Quota) Observe(event []byte) {
	var ev struct {
		Type string          `json:"type"`
		Info json.RawMessage `json:"rate_limit_info"`
	}
	if json.Unmarshal(event, &ev) != nil || ev.Type != "rate_limit_event" || ev.Info == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), askTimeout)
		defer cancel()
		var r quotaResult
		if q.call(ctx, "quota.event", map[string]any{"info": ev.Info}, &r) != nil || !r.Changed || r.Snapshot == nil {
			return
		}
		q.post(func() { q.snapshot = r.Snapshot })
	}()
}

// switched：换了号，上一个号的数不再显示，向 Claude Code 重要一次。
func (q *Quota) switched() {
	q.snapshot, q.err, q.lastAsked = nil, "", time.Time{}
	q.Refresh(true)
}

// Footnote 是额度的一句说明：多久以前的数、有没有在刷新、上次为什么没取到。
func (q *Quota) Footnote(now time.Time) string {
	s, ok := q.Snapshot()
	var text string
	switch {
	case !ok && q.refreshing:
		text = "正在取额度"
	case !ok:
		text = "还没有额度数据"
	case s.Unavailable:
		text = "这种登录方式没有订阅额度"
	case len(s.Windows) == 0:
		text = "Claude Code 还没拿到这个账号的额度"
	case q.refreshing:
		text = AgeText(s.UpdatedAt, now) + "更新，正在刷新"
	default:
		text = AgeText(s.UpdatedAt, now) + "更新"
	}
	if q.err != "" {
		text += "。" + q.err
	}
	return text
}
