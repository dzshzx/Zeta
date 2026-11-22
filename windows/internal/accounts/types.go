package accounts

import (
	"slices"
	"strings"
	"time"
)

// 下面几种照 serve 的 JSON 解码（helper/accounts 里同名的类型）。

// Account 是 claude-switch 记下的一个账号：身份和套餐，没有令牌。
type Account struct {
	// Name 是 claude-switch 的账号名（claude、claude-b…）；在用的登录还没记过、认不出是哪个号时为空。
	Name         string `json:"name"`
	Email        string `json:"email"`
	Organization string `json:"organization"`
	DisplayName  string `json:"displayName"`
	// Subscription 是订阅类型（pro、max、team…）。
	Subscription string `json:"subscription"`
	// RefreshExpiresAt 是续期凭证的到期时刻（Unix 毫秒），过了只能重新登录；不知道时为 0。
	RefreshExpiresAt int64 `json:"refreshExpiresAt"`
}

// Title 是界面上的名字：邮箱，没有时是显示名，再没有是账号名。
func (a Account) Title() string {
	for _, s := range []string{a.Email, a.DisplayName} {
		if strings.TrimSpace(s) != "" {
			return s
		}
	}
	return a.Name
}

// Plan 是套餐的显示名。
func (a Account) Plan() string { return PlanName(a.Subscription) }

// Expired 报告续期凭证在 now 时是不是已经过期。
func (a Account) Expired(now time.Time) bool {
	return a.RefreshExpiresAt > 0 && a.RefreshExpiresAt <= now.UnixMilli()
}

// PlanName 把订阅类型（pro、max…）写成显示名。
func PlanName(subscription string) string {
	if subscription == "" {
		return ""
	}
	return strings.ToUpper(subscription[:1]) + subscription[1:]
}

// Proc 是一个还开着的 Claude Code 进程，Owner 是托管它的 Zeta 包，别处起的为空。
type Proc struct {
	PID    int    `json:"pid"`
	Owner  string `json:"owner"`
	Status string `json:"status"`
	Kind   string `json:"kind"`
}

// State 是一台主机上的账号（accounts.list 的结果）。
type State struct {
	// Current 是在用的登录，未登录时为 nil；认不出是哪个号时 Name 为空。
	Current *Account `json:"current"`
	// Accounts 是要列的账号：在用的在前，再是存着的其他账号。
	Accounts []Account `json:"accounts"`
	Procs    []Proc    `json:"procs"`
	// Blocker 说明别的 Claude Code 挡着换号，没有时为空。
	Blocker string `json:"blocker"`
}

// IsCurrent 报告 a 是不是在用的那个。
func (s State) IsCurrent(a Account) bool { return s.Current != nil && a.Name == s.Current.Name }

// Severity 是一个额度窗口有多紧张。
type Severity int

const (
	Normal Severity = iota
	Warning
	Critical
)

func (s *Severity) UnmarshalText(b []byte) error {
	switch string(b) {
	case "critical":
		*s = Critical
	case "warning":
		*s = Warning
	default:
		*s = Normal
	}
	return nil
}

// Window 是订阅额度的一个窗口：5 小时、每周、每周里按模型分的……
type Window struct {
	Kind  string `json:"kind"`
	Label string `json:"label"`
	// Used 是已用比例，通常 0～1。
	Used float64 `json:"used"`
	// ResetsAt 是重置时刻，不知道时为零值。
	ResetsAt time.Time `json:"resetsAt"`
	Severity Severity  `json:"severity"`
	// Active：服务器挑的「只能显示一个时显示这个」。
	Active bool `json:"active"`
}

// Percent 是已用的百分数，取整。
func (w Window) Percent() int { return max(int(w.Used*100), 0) }

// Extra 是额外用量（超出套餐后按量付费），金额是 Currency 的最小单位。
type Extra struct {
	Enabled  bool     `json:"enabled"`
	UsedMin  *float64 `json:"usedMinor"`
	LimitMin *float64 `json:"limitMinor"`
	Currency string   `json:"currency"`
	Used     *float64 `json:"used"`
}

// Snapshot 是一个账号的订阅额度（quota.* 结果里的 snapshot）。
type Snapshot struct {
	Windows      []Window  `json:"windows"`
	Extra        *Extra    `json:"extra"`
	Subscription string    `json:"subscription"`
	Unavailable  bool      `json:"unavailable"`
	Blocked      bool      `json:"blocked"`
	BlockedUntil time.Time `json:"blockedUntil"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

// At 是按 now 看的额度：过了重置时刻的窗口从零算起，过了解除时刻的拒绝不再算数。主机给的是收到时的数，这里按界面的时钟看。
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

// Headline 是只能显示一个时显示的窗口：被拒时是最满的，否则是服务器挑的，再否则最满的。没有窗口时 ok 为 false。
func (s Snapshot) Headline() (w Window, ok bool) {
	if len(s.Windows) == 0 {
		return Window{}, false
	}
	if !s.Blocked {
		if i := slices.IndexFunc(s.Windows, func(w Window) bool { return w.Active }); i >= 0 {
			return s.Windows[i], true
		}
	}
	return slices.MaxFunc(s.Windows, func(a, b Window) int {
		switch {
		case a.Used < b.Used:
			return -1
		case a.Used > b.Used:
			return 1
		}
		return 0
	}), true
}
