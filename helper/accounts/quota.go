package accounts

import (
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Severity 是一个额度窗口有多紧张。
type Severity int

const (
	Normal Severity = iota
	Warning
	Critical
)

var severityNames = []string{"normal", "warning", "critical"}

func (s Severity) MarshalText() ([]byte, error) {
	if s < Normal || s > Critical {
		return nil, fmt.Errorf("不认识的紧张程度 %d", int(s))
	}
	return []byte(severityNames[s]), nil
}

func (s *Severity) UnmarshalText(b []byte) error {
	i := slices.Index(severityNames, string(b))
	if i < 0 {
		*s = Normal
		return nil
	}
	*s = Severity(i)
	return nil
}

// 服务器没给紧张程度时，自己按比例折算的门槛。
const (
	WarnAt     = 0.8
	CriticalAt = 0.95
)

// SeverityOf 按已用比例折算紧张程度。
func SeverityOf(used float64) Severity {
	switch {
	case used >= CriticalAt:
		return Critical
	case used >= WarnAt:
		return Warning
	}
	return Normal
}

// Window 是订阅额度的一个窗口：5 小时、每周、每周里按模型分的……账号的套餐有哪些就有哪些，种类和次序都照服务器给的。
type Window struct {
	// Kind 是服务器的分类（session、weekly_all、weekly_scoped，以后可能有新的），Group 是它的大类。
	Kind  string `json:"kind"`
	Group string `json:"group"`
	// Scope 是按模型或入口分的窗口的显示名，不分的为空。
	Scope string `json:"scope,omitempty"`
	// Used 是已用比例，通常 0～1，超过上限后偶尔大于 1。
	Used float64 `json:"used"`
	// ResetsAt 是重置时刻，不知道时为零值。
	ResetsAt time.Time `json:"resetsAt,omitzero"`
	Severity Severity  `json:"severity"`
	// Active：服务器挑的「只能显示一个时显示这个」。
	Active bool `json:"active,omitempty"`
	// Label 是窗口的名字（labelOf，源语言），给不认 LabelCode 的旧客户端。LabelCode 是它的种类码（docs/i18n.md），
	// 参数只有 scope（就是 Scope），客户端按自己的语言出字（LabelIn）；名字是服务器给的原文（按模型分的窗口的显示名）时为空。
	// 不另带 args：Window 要能用 == 比较（Event 据此判断变没变）。
	Label     string `json:"label"`
	LabelCode string `json:"labelCode,omitempty"`
}

// LabelIn 是窗口的名字在语言 lang 里怎么说。
func (w Window) LabelIn(lang string) string {
	if w.LabelCode == "" {
		return w.Label
	}
	return locales.Msg{Key: w.LabelCode, Args: map[string]any{"scope": w.Scope}}.In(lang)
}

// labelOf 是窗口的名字：5 小时、每周 · 全部模型、每周 · Opus……；名字就是服务器给的原文时 Key 为空，raw 是它。
func labelOf(w Window) (said locales.Msg, raw string) {
	switch {
	case w.Kind == "session" || (w.Group == "session" && w.Scope == ""):
		return locales.M("quotaSession"), ""
	case w.Kind == "weekly_all":
		return locales.M("quotaWeeklyAll"), ""
	case (w.Group == "weekly" || strings.HasPrefix(w.Kind, "weekly")) && w.Scope != "":
		return locales.M("quotaWeeklyScoped", "scope", w.Scope), ""
	case w.Group == "weekly" || strings.HasPrefix(w.Kind, "weekly"):
		return locales.M("quotaWeekly"), ""
	case w.Scope != "":
		return locales.Msg{}, w.Scope
	}
	return locales.Msg{}, w.Kind
}

// setLabel 填上窗口的名字（Label、LabelCode、LabelArgs）。
func (w *Window) setLabel() {
	said, raw := labelOf(*w)
	w.Label, w.LabelCode = raw, said.Key
	if said.Key != "" {
		w.Label = said.String()
	}
}

// Percent 是已用的百分数，取整。
func (w Window) Percent() int { return max(int(w.Used*100), 0) }

// Extra 是额外用量（超出套餐后按量付费）。金额是 Currency 的最小单位（美元是分），不知道时为 nil。
type Extra struct {
	Enabled  bool     `json:"enabled"`
	UsedMin  *float64 `json:"usedMinor,omitempty"`
	LimitMin *float64 `json:"limitMinor,omitempty"`
	Currency string   `json:"currency,omitempty"`
	Used     *float64 `json:"used,omitempty"`
}

// Snapshot 是一个账号的订阅额度。
type Snapshot struct {
	Windows []Window `json:"windows"`
	Extra   *Extra   `json:"extra,omitempty"`
	// Subscription 是 Claude Code 报的订阅类型（pro、max…）；Max 与 Ultra 都是 max，分不开。
	Subscription string `json:"subscription,omitempty"`
	// Plan 是套餐的展示名（Pro、Max、Ultra…），由 QuotaStore 取在用登录的资料（见 QuotaStore.login）；没有时省略，界面回落到
	// Subscription。
	Plan string `json:"plan,omitempty"`
	// Unavailable：这种登录方式没有订阅额度（API key、Bedrock、Vertex，或登录缺 profile 权限）。
	Unavailable bool `json:"unavailable,omitempty"`
	// Blocked：请求已经被拒，到了上限，BlockedUntil 解除（不知道时为零值）。
	Blocked      bool      `json:"blocked,omitempty"`
	BlockedUntil time.Time `json:"blockedUntil,omitzero"`
	// UpdatedAt 是收到这份数的时刻。
	UpdatedAt time.Time `json:"updatedAt"`
}

// withPlan 在 plan 不为空时把套餐展示名换成它：额度都是在用登录的，登录资料里的名字比存着的新。
func (s Snapshot) withPlan(plan string) Snapshot {
	if plan != "" {
		s.Plan = plan
	}
	return s
}

// At 是按 now 看的额度：过了重置时刻的窗口从零算起，过了解除时刻的拒绝不再算数。
func (s Snapshot) At(now time.Time) Snapshot {
	out := s
	out.Windows = make([]Window, len(s.Windows))
	for i, w := range s.Windows {
		if !w.ResetsAt.IsZero() && !w.ResetsAt.After(now) {
			w.Used, w.ResetsAt, w.Severity = 0, time.Time{}, Normal
		}
		out.Windows[i] = w
	}
	if s.Blocked && !s.BlockedUntil.IsZero() && !s.BlockedUntil.After(now) {
		out.Blocked, out.BlockedUntil = false, time.Time{}
	}
	return out
}

// ---------- get_usage ----------

// usage 是控制请求 get_usage 的应答（control_response 的 response.response，Claude Code 2.1.328 实测）。
type usage struct {
	Available    *bool  `json:"rate_limits_available"`
	Subscription string `json:"subscription_type"`
	Limits       *struct {
		// Rows 是服务器的额度行；没有时（Claude Code 只拿到了响应头里的数）退回固定的几项。
		Rows           *[]usageRow   `json:"limits"`
		FiveHour       *usageWindow  `json:"five_hour"`
		SevenDay       *usageWindow  `json:"seven_day"`
		SevenDayOpus   *usageWindow  `json:"seven_day_opus"`
		SevenDaySonnet *usageWindow  `json:"seven_day_sonnet"`
		ModelScoped    []usageWindow `json:"model_scoped"`
		Extra          *struct {
			Enabled     bool     `json:"is_enabled"`
			UsedCredits *float64 `json:"used_credits"`
			Monthly     *float64 `json:"monthly_limit"`
			Currency    string   `json:"currency"`
			Utilization *float64 `json:"utilization"`
		} `json:"extra_usage"`
	} `json:"rate_limits"`
}

type usageRow struct {
	Kind     string   `json:"kind"`
	Group    string   `json:"group"`
	Percent  *float64 `json:"percent"`
	Severity string   `json:"severity"`
	ResetsAt string   `json:"resets_at"`
	Active   bool     `json:"is_active"`
	Scope    *struct {
		Model *struct {
			DisplayName string `json:"display_name"`
		} `json:"model"`
		Surface *struct {
			DisplayName string `json:"display_name"`
		} `json:"surface"`
	} `json:"scope"`
}

type usageWindow struct {
	Utilization *float64 `json:"utilization"`
	ResetsAt    string   `json:"resets_at"`
	DisplayName string   `json:"display_name"`
}

// ErrNotUsage 是交来的 JSON 不是 get_usage 的应答。
var ErrNotUsage = errors.New("不是额度的应答")

// ParseUsage 读 get_usage 的应答（control_response 里 response.response 那个对象）。有服务器的额度行时照它显示：
// 有哪些窗口、叫什么、多紧张都是服务器定的；没有时退回 Claude Code 的 /usage 文字版那几项。
func ParseUsage(raw []byte, now time.Time) (Snapshot, error) {
	var u usage
	if err := json.Unmarshal(raw, &u); err != nil || u.Available == nil {
		return Snapshot{}, ErrNotUsage
	}
	s := Snapshot{Subscription: u.Subscription, Unavailable: !*u.Available, UpdatedAt: now}
	l := u.Limits
	if l == nil {
		return s, nil
	}
	if l.Rows != nil {
		s.Windows = serverRows(*l.Rows)
	} else {
		s.Windows = fixedWindows(l.FiveHour, l.SevenDay, l.SevenDayOpus, l.SevenDaySonnet, l.ModelScoped)
	}
	label(s.Windows)
	if e := l.Extra; e != nil {
		s.Extra = &Extra{Enabled: e.Enabled, UsedMin: e.UsedCredits, LimitMin: e.Monthly, Currency: e.Currency}
		if e.Utilization != nil {
			v := *e.Utilization / 100
			s.Extra.Used = &v
		}
	}
	return s, nil
}

func serverRows(rows []usageRow) []Window {
	var out []Window
	for _, r := range rows {
		if r.Percent == nil {
			continue
		}
		w := Window{Kind: r.Kind, Group: r.Group, Used: *r.Percent / 100, ResetsAt: parseTime(r.ResetsAt), Active: r.Active}
		if r.Scope != nil {
			switch {
			case r.Scope.Model != nil:
				w.Scope = r.Scope.Model.DisplayName
			case r.Scope.Surface != nil:
				w.Scope = r.Scope.Surface.DisplayName
			}
		}
		switch r.Severity {
		case "critical":
			w.Severity = Critical
		case "warning":
			w.Severity = Warning
		case "normal":
			w.Severity = Normal
		default:
			w.Severity = SeverityOf(w.Used)
		}
		out = append(out, w)
	}
	return out
}

func fixedWindows(five, seven, opus, sonnet *usageWindow, scoped []usageWindow) []Window {
	var out []Window
	add := func(u *usageWindow, kind, group, scope string) {
		if u == nil || u.Utilization == nil {
			return
		}
		used := *u.Utilization / 100
		out = append(out, Window{Kind: kind, Group: group, Scope: scope, Used: used, ResetsAt: parseTime(u.ResetsAt), Severity: SeverityOf(used)})
	}
	add(five, "session", "session", "")
	add(seven, "weekly_all", "weekly", "")
	add(opus, "weekly_scoped", "weekly", "Opus")
	add(sonnet, "weekly_scoped", "weekly", "Sonnet")
	for _, m := range scoped {
		if m.DisplayName == "" || slices.ContainsFunc(out, func(w Window) bool { return w.Scope == m.DisplayName }) {
			continue
		}
		add(&m, "weekly_scoped", "weekly", m.DisplayName)
	}
	return out
}

func parseTime(s string) time.Time {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// ---------- rate_limit_event ----------

// rateLimitInfo 是 stream-json 里 rate_limit_event 的 rate_limit_info（2.1.328 实测）：unifiedWindows 里 five_hour、
// seven_day 是 0～1 的比例和秒级的重置时刻；顶层 status 为 rejected 表示请求被拒了。
type rateLimitInfo struct {
	Status         string   `json:"status"`
	ResetsAt       *float64 `json:"resetsAt"`
	UsingOverage   bool     `json:"isUsingOverage"`
	UnifiedWindows *struct {
		FiveHour *eventWindow `json:"five_hour"`
		SevenDay *eventWindow `json:"seven_day"`
	} `json:"unifiedWindows"`
}

type eventWindow struct {
	Utilization *float64 `json:"utilization"`
	ResetsAt    *float64 `json:"resetsAt"`
	Status      string   `json:"status"`
}

// ApplyEvent 把一条 rate_limit_event 的 rate_limit_info 并进 prev（nil 是还没有数）：只更新事件里带的窗口，别的
// （按模型分的每周窗口、额外用量）照旧。没有变化时 changed 为 false。
func ApplyEvent(prev *Snapshot, info []byte, now time.Time) (next Snapshot, changed bool, err error) {
	var ev rateLimitInfo
	if err := json.Unmarshal(info, &ev); err != nil {
		return Snapshot{}, false, ErrNotUsage
	}
	base := Snapshot{UpdatedAt: now}
	if prev != nil {
		base = *prev
	}
	windows := base.Windows
	if u := ev.UnifiedWindows; u != nil {
		for _, it := range []struct {
			w           *eventWindow
			kind, group string
		}{{u.FiveHour, "session", "session"}, {u.SevenDay, "weekly_all", "weekly"}} {
			if w, ok := eventToWindow(it.w, it.kind, it.group); ok {
				windows = mergeWindow(windows, w)
			}
		}
	}
	if slices.Equal(windows, base.Windows) && ev.Status == "" {
		return base, false, nil
	}
	base.Windows = windows
	base.Blocked = ev.Status == "rejected" && !ev.UsingOverage
	base.BlockedUntil = time.Time{}
	if base.Blocked {
		base.BlockedUntil = seconds(ev.ResetsAt)
	}
	base.UpdatedAt = now
	return base, true, nil
}

func eventToWindow(w *eventWindow, kind, group string) (Window, bool) {
	if w == nil || w.Utilization == nil {
		return Window{}, false
	}
	used := *w.Utilization
	sev := SeverityOf(used)
	switch w.Status {
	case "rejected":
		sev = Critical
	case "allowed_warning":
		sev = max(sev, Warning)
	}
	nw := Window{Kind: kind, Group: group, Used: used, ResetsAt: seconds(w.ResetsAt), Severity: sev}
	nw.setLabel()
	return nw, true
}

// mergeWindow 用 w 换掉 windows 里同一种（不分模型的）窗口，保留它的 Active；还没有就加上，5 小时的放在最前。
func mergeWindow(windows []Window, w Window) []Window {
	out := slices.Clone(windows)
	if i := slices.IndexFunc(out, func(o Window) bool { return o.Kind == w.Kind && o.Scope == "" }); i >= 0 {
		w.Active = out[i].Active
		out[i] = w
		return out
	}
	if w.Kind == "session" {
		return append([]Window{w}, out...)
	}
	return append(out, w)
}

func seconds(v *float64) time.Time {
	if v == nil {
		return time.Time{}
	}
	return time.UnixMilli(int64(*v * 1000))
}

// label 给每个窗口填上名字。
func label(windows []Window) {
	for i := range windows {
		windows[i].setLabel()
	}
}
