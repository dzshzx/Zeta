package usage

import "math"

// Turn 是一轮（用户发一次、到 result 为止）的用量。
type Turn struct {
	Tokens Tokens `json:"tokens"`
	// CostUSD 是按 API 标价折算的费用，算不出来时没有。
	CostUSD    *float64 `json:"cost_usd,omitempty"`
	DurationMs *int64   `json:"duration_ms,omitempty"`
	// CostEstimated：费用不是 Claude Code 报的，是按 token 数和价目表估的。从会话记录恢复的含子代理（各按自己的模型估）；
	// 基线未知的实时一轮（接上不是这个对话起的、还在跑的进程后的第一轮，见 Tracker.Joined）只有主对话的，不含子代理。
	CostEstimated bool `json:"cost_estimated,omitempty"`
}

// Level 是上下文快满时的提醒档位。
type Level string

const (
	LevelNormal   Level = "normal"
	LevelWarning  Level = "warning"
	LevelCritical Level = "critical"
)

// Usage 是一个对话的用量，整份推给客户端（conv.state 的 usage），客户端照着显示，不再计算。
type Usage struct {
	// ContextTokens 是主对话最近一次请求实测的上下文占用；还没有实测或刚压缩完时是 Claude Code 的估算（ContextEstimated）。
	ContextTokens    *int64 `json:"context_tokens,omitempty"`
	ContextEstimated bool   `json:"context_estimated,omitempty"`
	// ContextWindow 是上下文窗口：优先用 Claude Code 自己算的（会按 autoCompactWindow 设置缩小），
	// 其次是以前在这台主机上学到的，最后是模型本身的窗口。
	ContextWindow *int64 `json:"context_window,omitempty"`
	// Model 是主对话实际用的模型（如 claude-opus-5-5）。
	Model       string `json:"model,omitempty"`
	Turns       []Turn `json:"turns,omitempty"`
	Compactions int    `json:"compactions,omitempty"`
	// AppliedEffort 是 Claude Code 报告的、当前实际生效的思考强度。
	AppliedEffort string `json:"applied_effort,omitempty"`
	// AutoCompact：这个进程开没开自动压缩（get_context_usage 的 isAutoCompactEnabled）；还没问到时没有。
	AutoCompact *bool `json:"auto_compact,omitempty"`
	// CompactAt 是自动压缩会在上下文到多少 token 时触发（已算上给进程的百分比，见 compactThreshold）；关着或还不知道时没有。
	CompactAt *int64 `json:"compact_at,omitempty"`

	// 以下由上面几项算出。

	// Total 是各轮 token 之和。
	Total Tokens `json:"total"`
	// TotalCost 是已知费用之和；一轮都算不出来时没有。
	TotalCost *float64 `json:"total_cost,omitempty"`
	// CostPartial：有些轮次连估都估不出费用（模型不在价目表里）。
	CostPartial bool `json:"cost_partial,omitempty"`
	// CostEstimated：有些轮次（从会话记录恢复的、接上后台进程后的第一轮）的费用是估的。
	CostEstimated bool `json:"cost_estimated,omitempty"`
	// Fraction 是上下文占窗口的比例（0..1）；占用或窗口不知道时没有。
	Fraction *float32 `json:"fraction,omitempty"`
	// CompactFraction 是压缩点在窗口里的位置（0..1）；不知道压缩点时没有。
	CompactFraction *float32 `json:"compact_fraction,omitempty"`
	Level           Level    `json:"level"`
}

// fill 算出 Usage 里由别的字段得出的几项。
func (u *Usage) fill() {
	u.Total = Tokens{}
	u.TotalCost = nil
	for _, t := range u.Turns {
		u.Total = u.Total.Add(t.Tokens)
		if t.CostUSD == nil {
			u.CostPartial = true
		} else {
			u.TotalCost = ptr(orZeroF(u.TotalCost) + *t.CostUSD)
		}
		if t.CostEstimated {
			u.CostEstimated = true
		}
	}
	u.Fraction = ratio(u.ContextTokens, u.ContextWindow)
	if u.CompactAt != nil && *u.CompactAt > 0 {
		u.CompactFraction = ratio(u.CompactAt, u.ContextWindow)
	}
	u.Level = u.level()
}

func orZeroF(v *float64) float64 {
	if v == nil {
		return 0
	}
	return *v
}

// ratio 是 part / whole，限在 0..1；part 没有、whole 没有或不大于 0 时没有。
func ratio(part, whole *int64) *float32 {
	if part == nil || whole == nil || *whole <= 0 {
		return nil
	}
	f := float32(float64(*part) / float64(*whole))
	return ptr(min(max(f, 0), 1))
}

// level：快满时提醒。知道压缩点时按离压缩点多近算（到 95% 就是马上要压缩了）；不知道时按窗口算：
// Claude Code 默认在窗口减去约 3.3 万处压缩，92% 左右就到了；自动压缩关着时窗口满了就发不出去。
func (u *Usage) level() Level {
	if u.ContextTokens == nil {
		return LevelNormal
	}
	critical, warning := float32(0.92), float32(0.75)
	var f float32
	switch {
	case u.CompactAt != nil && *u.CompactAt > 0:
		f = float32(float64(*u.ContextTokens) / float64(*u.CompactAt))
		critical, warning = 0.95, 0.80
	case u.Fraction != nil:
		f = *u.Fraction
	default:
		return LevelNormal
	}
	switch {
	case f >= critical:
		return LevelCritical
	case f >= warning:
		return LevelWarning
	}
	return LevelNormal
}

// summaryBuffer 是 Claude Code 在有效窗口里留给压缩摘要的余量。
const summaryBuffer = 13_000

// compactThreshold 是实际的压缩点。reported 是 get_context_usage 的 autoCompactThreshold（不含百分比），
// percent 是起进程时给的 CLAUDE_AUTOCOMPACT_PCT_OVERRIDE（1..100 以外当作没给）。
//
// Claude Code 的做法（2.1.328 实测）：有效窗口 E = 压缩窗口 − min(模型最大输出, 20000)，默认在 E − 13000 处压缩，
// 也就是它报的 autoCompactThreshold；给了百分比 p 时压到 min(⌊E × p/100⌋, E − 13000)，只会更早、不会更晚。
// 照它的算法用双精度算（JS 的 Math.floor(e * (p / 100))），整数算法在个别数上会差 1（比如 10 万的 29%）。
func compactThreshold(reported *int64, percent int) *int64 {
	if reported == nil {
		return nil
	}
	if percent < 1 || percent > 100 {
		return ptr(*reported)
	}
	effective := *reported + summaryBuffer
	return ptr(min(int64(math.Floor(float64(effective)*(float64(percent)/100))), *reported))
}
