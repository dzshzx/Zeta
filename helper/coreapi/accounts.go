package coreapi

import (
	"context"
	"encoding/json"
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 账号与额度：accounts.* 和 quota.*（docs/core.md「账号与额度」，核心里的实现在 helper/accounts.go 与 helper/accounts）。
// 本文件分三段：账号（列、换、记下、删）、登录、额度。每个 accounts.* 请求都带 program（claude-switch 相对家目录的路径，
// 客户端带的那个版本；空时不带，核心自己在 ~/.cache/<包>/bin 下找）。核心回 code CodeClaudeSwitchMissing 时由客户端装上再试，
// 这一层不重试。

// CodeClaudeSwitchMissing 是 accounts.* 出错时的 code：主机上还没装 claude-switch。别的 code 见 docs/core.md。
const CodeClaudeSwitchMissing = "claude_switch_missing"

// accountsParams 是一个 accounts.* 请求的参数：p 加上 program。
func accountsParams(program string, p params) params {
	if p == nil {
		p = params{}
	}
	if program != "" {
		p["program"] = program
	}
	return p
}

// ---- 账号 ----

// Account 是 claude-switch 记下的一个账号：身份和套餐，没有令牌（同 helper/accounts 的 Account）。
type Account struct {
	// Name 是 claude-switch 的账号名（claude、claude-b…）；在用的登录还没记过、认不出是哪个号时为空。
	Name         string `json:"name"`
	Email        string `json:"email,omitempty"`
	Organization string `json:"organization,omitempty"`
	DisplayName  string `json:"displayName,omitempty"`
	// Subscription 是订阅类型（pro、max、team…）。
	Subscription string `json:"subscription,omitempty"`
	// RefreshExpiresAt 是续期凭证的到期时刻（Unix 毫秒），过了只能重新登录；不知道时为 0。
	RefreshExpiresAt int64 `json:"refreshExpiresAt,omitempty"`
	// Current、Parked 照 claude-switch --json：在用的、存着档的。
	Current bool `json:"current"`
	Parked  bool `json:"parked"`
}

// Title 是给人看的名字：邮箱，没有时是显示名，再没有是账号名。
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

// ClaudeProcess 是 ~/.claude/sessions 登记的一个还开着的 Claude Code 进程。Owner 是托管它的 Zeta 包（zeta、zeta-dev…），别处起的为空。
type ClaudeProcess struct {
	PID    int    `json:"pid"`
	Owner  string `json:"owner"`
	Status string `json:"status"`
	Kind   string `json:"kind"`
}

// AccountList 是一台主机上的账号，accounts.list 的结果。
type AccountList struct {
	// Current 是在用的登录，未登录时为 nil；认不出是哪个号时 Name 为空。
	Current *Account `json:"current"`
	// Accounts 是要列的账号：在用的在前，再是存着的其他账号。
	Accounts []Account `json:"accounts"`
	// Procs 是开着的 Claude Code 进程。
	Procs []ClaudeProcess `json:"procs"`
	// Blocker 说明别的包或终端里的 Claude Code 挡着换号，没有时为空。
	Blocker string `json:"blocker,omitempty"`
}

// IsCurrent 报告 a 是不是在用的那个。
func (l AccountList) IsCurrent(a Account) bool { return l.Current != nil && a.Name == l.Current.Name }

// ListAccounts 列这台主机上的账号、开着的 Claude Code 和挡着换号的原因（accounts.list）。
func (cl *Client) ListAccounts(ctx context.Context, program string) (AccountList, error) {
	return get[AccountList](ctx, cl.c, "accounts.list", accountsParams(program, nil))
}

// SwitchAccount 换成存着的 name（accounts.switch）。
func (cl *Client) SwitchAccount(ctx context.Context, program, name string) error {
	return call(ctx, cl.c, "accounts.switch", accountsParams(program, params{"name": name}))
}

// SaveAccount 给还没记过的在用登录起个空闲的名字记下，返回这个名字（accounts.save）。
func (cl *Client) SaveAccount(ctx context.Context, program string) (string, error) {
	r, err := get[accountName](ctx, cl.c, "accounts.save", accountsParams(program, nil))
	return r.Name, err
}

// RemoveAccount 删掉不在用的 name 在这台主机上的存档与记录（accounts.remove）；服务端仍是登录状态。
func (cl *Client) RemoveAccount(ctx context.Context, program, name string) error {
	return call(ctx, cl.c, "accounts.remove", accountsParams(program, params{"name": name}))
}

// accountName 是 accounts.save、accounts.prepare_login 的结果。
type accountName struct {
	Name string `json:"name"`
}

// ---- 登录 ----
//
// 登录另一个号：客户端用 LoginStart 拿到授权网址、让用户打开，再用 LoginCode 交回授权码；登录命令由核心跑。
// 自己跑登录命令的调用方（契约测试）先调 PrepareLogin。

// 交授权码出错时的 code（docs/core.md「账号与额度」）。
const (
	CodeLoginIncomplete = "login_incomplete" // 授权码不完整，登录还在等：改了再交
	CodeNoLogin         = "no_login"         // 没有在等的登录：重新开始
)

// LoginStart 是开始登录的结果：授权网址，和登录后新号会记成的名字（没登录过时为空）。
type LoginStart struct {
	URL  string `json:"url"`
	Name string `json:"name"`
}

// LoginStart 把在用的号存档并退出登录，在主机上起 claude auth login，返回授权网址（accounts.login_start）。
func (cl *Client) LoginStart(ctx context.Context, program string) (LoginStart, error) {
	return get[LoginStart](ctx, cl.c, "accounts.login_start", accountsParams(program, nil))
}

// LoginCode 把授权码交给在等的登录命令，成功时返回记下的新号的名字（accounts.login_code）。
// 出错的 code 是 CodeLoginIncomplete 时登录还在等；其余情况登录已结束，要重新开始。
func (cl *Client) LoginCode(ctx context.Context, program, code string) (string, error) {
	r, err := get[accountName](ctx, cl.c, "accounts.login_code", accountsParams(program, params{"code": code}))
	return r.Name, err
}

// LoginCancel 结束正在进行的登录（accounts.login_cancel）。
func (cl *Client) LoginCancel(ctx context.Context) error {
	return call(ctx, cl.c, "accounts.login_cancel", nil)
}

// PrepareLogin 为登录另一个号做准备：起好新号的名字、把在用的号存档并退出登录，返回新号的名字（accounts.prepare_login）。
func (cl *Client) PrepareLogin(ctx context.Context, program string) (string, error) {
	r, err := get[accountName](ctx, cl.c, "accounts.prepare_login", accountsParams(program, nil))
	return r.Name, err
}

// ---- 额度 ----

// Severity 是一个额度窗口有多紧张。
type Severity int

const (
	SeverityNormal Severity = iota
	SeverityWarning
	SeverityCritical
)

var severityNames = []string{"normal", "warning", "critical"}

// UnmarshalText 认 normal、warning、critical；不认识的当 normal。
func (s *Severity) UnmarshalText(b []byte) error {
	*s = Severity(max(slices.Index(severityNames, string(b)), 0))
	return nil
}

// QuotaWindow 是订阅额度的一个窗口：5 小时、每周、每周里按模型分的……种类和次序都照服务器给的。
type QuotaWindow struct {
	Kind  string `json:"kind"`
	Group string `json:"group"`
	// Scope 是按模型或入口分的窗口的显示名，不分的为空。
	Scope string `json:"scope,omitempty"`
	// Used 是已用比例，通常 0～1。
	Used float64 `json:"used"`
	// ResetsAt 是重置时刻，不知道时为零值。
	ResetsAt time.Time `json:"resetsAt,omitzero"`
	Severity Severity  `json:"severity"`
	// Active：服务器挑的「只能显示一个时显示这个」。
	Active bool `json:"active,omitempty"`
	// Label 是界面上的名字（源语言）；LabelCode 是它的种类码（核心文字表的键，参数 scope 就是 Scope），按界面语言出字用 LabelIn。
	Label     string `json:"label"`
	LabelCode string `json:"labelCode,omitempty"`
}

// LabelIn 是窗口的名字用界面语言 locale 说出来的文字；核心没给种类码时（服务器给的原文、旧核心）是 Label。
func (w QuotaWindow) LabelIn(locale string) string {
	if w.LabelCode == "" {
		return w.Label
	}
	return locales.Msg{Key: w.LabelCode, Args: map[string]any{"scope": w.Scope}}.In(locale)
}

// Percent 是已用的百分数，取整。
func (w QuotaWindow) Percent() int { return max(int(w.Used*100), 0) }

// QuotaExtra 是额外用量（超出套餐后按量付费），金额是 Currency 的最小单位。
type QuotaExtra struct {
	Enabled  bool     `json:"enabled"`
	UsedMin  *float64 `json:"usedMinor,omitempty"`
	LimitMin *float64 `json:"limitMinor,omitempty"`
	Currency string   `json:"currency,omitempty"`
	Used     *float64 `json:"used,omitempty"`
}

// QuotaSnapshot 是一个账号的订阅额度（quota.* 结果里的 snapshot，同 helper/accounts 的 Snapshot）。
type QuotaSnapshot struct {
	Windows []QuotaWindow `json:"windows"`
	Extra   *QuotaExtra   `json:"extra,omitempty"`
	// Subscription 是订阅类型。
	Subscription string `json:"subscription,omitempty"`
	// Unavailable：这种登录没有订阅额度。
	Unavailable  bool      `json:"unavailable,omitempty"`
	Blocked      bool      `json:"blocked,omitempty"`
	BlockedUntil time.Time `json:"blockedUntil,omitzero"`
	// UpdatedAt 是核心收到这份数的时刻。
	UpdatedAt time.Time `json:"updatedAt"`
}

// At 是按 now 看的额度：过了重置时刻的窗口从零算起，过了解除时刻的拒绝不再算数。核心给的是收到时的数，客户端按自己的时钟看。
func (s QuotaSnapshot) At(now time.Time) QuotaSnapshot {
	out := s
	out.Windows = make([]QuotaWindow, len(s.Windows))
	for i, w := range s.Windows {
		if !w.ResetsAt.IsZero() && !w.ResetsAt.After(now) {
			w.Used, w.ResetsAt, w.Severity = 0, time.Time{}, SeverityNormal
		}
		out.Windows[i] = w
	}
	if s.Blocked && !s.BlockedUntil.IsZero() && !s.BlockedUntil.After(now) {
		out.Blocked, out.BlockedUntil = false, time.Time{}
	}
	return out
}

// Headline 是只能显示一个时显示的窗口：被拒时是最满的，否则是服务器挑的，再否则最满的。没有窗口时 ok 为 false。
func (s QuotaSnapshot) Headline() (w QuotaWindow, ok bool) {
	if len(s.Windows) == 0 {
		return QuotaWindow{}, false
	}
	if !s.Blocked {
		if i := slices.IndexFunc(s.Windows, func(w QuotaWindow) bool { return w.Active }); i >= 0 {
			return s.Windows[i], true
		}
	}
	return slices.MaxFunc(s.Windows, func(a, b QuotaWindow) int {
		switch {
		case a.Used < b.Used:
			return -1
		case a.Used > b.Used:
			return 1
		}
		return 0
	}), true
}

// Quota 读在用账号存着的额度（quota.get）；还没有时为 nil。
func (cl *Client) Quota(ctx context.Context) (*QuotaSnapshot, error) {
	r, err := get[struct {
		Snapshot *QuotaSnapshot `json:"snapshot"`
	}](ctx, cl.c, "quota.get", nil)
	return r.Snapshot, err
}

// WatchQuota 订阅（on 为真）或退订额度的推送 quota.snapshot（quota.watch）；推送由客户端自己解。
func (cl *Client) WatchQuota(ctx context.Context, on bool) error {
	return call(ctx, cl.c, "quota.watch", params{"watch": on})
}

// QuotaUsage 是 quota.usage 的结果。
type QuotaUsage struct {
	Snapshot *QuotaSnapshot `json:"snapshot"`
	// Fresh 为假：Claude Code 这次没取到服务器的数，留着的是已有的窗口。
	Fresh bool `json:"fresh"`
}

// SubmitQuotaUsage 把 get_usage 的应答（control_response 的 response.response）交给核心读成额度并存起来（quota.usage）。
func (cl *Client) SubmitQuotaUsage(ctx context.Context, response json.RawMessage) (QuotaUsage, error) {
	return get[QuotaUsage](ctx, cl.c, "quota.usage", params{"response": response})
}

// QuotaEvent 是 quota.event 的结果。
type QuotaEvent struct {
	Snapshot *QuotaSnapshot `json:"snapshot"`
	// Changed：存着的额度因这次事件变了。
	Changed bool `json:"changed"`
}

// SubmitQuotaEvent 把对话里 rate_limit_event 的 rate_limit_info 并进存着的额度（quota.event）。核心接着的对话里的事件核心已经存了，不用再交。
func (cl *Client) SubmitQuotaEvent(ctx context.Context, info json.RawMessage) (QuotaEvent, error) {
	return get[QuotaEvent](ctx, cl.c, "quota.event", params{"info": info})
}
