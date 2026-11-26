package usage

import (
	"encoding/json"
	"errors"
)

// Tokens 是一次或几次模型请求的 token 数。
type Tokens struct {
	Input      int64
	Output     int64
	CacheRead  int64
	CacheWrite int64
}

// Total 是四项之和。
func (t Tokens) Total() int64 { return t.Input + t.Output + t.CacheRead + t.CacheWrite }

// Add 是两份逐项相加。
func (t Tokens) Add(o Tokens) Tokens {
	return Tokens{t.Input + o.Input, t.Output + o.Output, t.CacheRead + o.CacheRead, t.CacheWrite + o.CacheWrite}
}

// Covers：每一项都不小于 o。累计数只会涨，变小说明计数从头开始了。
func (t Tokens) Covers(o Tokens) bool {
	return t.Input >= o.Input && t.Output >= o.Output && t.CacheRead >= o.CacheRead && t.CacheWrite >= o.CacheWrite
}

// Sub 是逐项相减，小于 0 的算 0。
func (t Tokens) Sub(o Tokens) Tokens {
	return Tokens{max(t.Input-o.Input, 0), max(t.Output-o.Output, 0), max(t.CacheRead-o.CacheRead, 0),
		max(t.CacheWrite-o.CacheWrite, 0)}
}

// MarshalJSON 写出四项和它们的和 total。
func (t Tokens) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Input      int64 `json:"input"`
		Output     int64 `json:"output"`
		CacheRead  int64 `json:"cache_read"`
		CacheWrite int64 `json:"cache_write"`
		Total      int64 `json:"total"`
	}{t.Input, t.Output, t.CacheRead, t.CacheWrite, t.Total()})
}

// UnmarshalJSON 读 MarshalJSON 写出的（客户端读 conv.state 的 usage 用）；total 是算出来的，不读。
func (t *Tokens) UnmarshalJSON(data []byte) error {
	var v struct {
		Input      int64 `json:"input"`
		Output     int64 `json:"output"`
		CacheRead  int64 `json:"cache_read"`
		CacheWrite int64 `json:"cache_write"`
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return err
	}
	*t = Tokens{v.Input, v.Output, v.CacheRead, v.CacheWrite}
	return nil
}

// APITokens 是 usage 里的四项，按 API 的键名读写（zeta-helper history 的 subagent_usage 行就是这个形状）；字段同 Tokens，可以直接转换。
type APITokens struct {
	Input      int64 `json:"input_tokens"`
	Output     int64 `json:"output_tokens"`
	CacheRead  int64 `json:"cache_read_input_tokens"`
	CacheWrite int64 `json:"cache_creation_input_tokens"`
}

// apiUsage 是 API 的 usage 形状：assistant 消息、stream_event、result 行都是它。
type apiUsage struct {
	InputTokens              *int64 `json:"input_tokens"`
	OutputTokens             *int64 `json:"output_tokens"`
	CacheReadInputTokens     *int64 `json:"cache_read_input_tokens"`
	CacheCreationInputTokens *int64 `json:"cache_creation_input_tokens"`
	CacheCreation            *struct {
		Ephemeral5m *int64 `json:"ephemeral_5m_input_tokens"`
	} `json:"cache_creation"`
	Speed string `json:"speed"`
}

// tokens 是 usage 里的四项；usage 没有、或四项都没有时 ok 为 false。
func (u *apiUsage) tokens() (t Tokens, ok bool) {
	if u == nil {
		return Tokens{}, false
	}
	if u.InputTokens == nil && u.OutputTokens == nil && u.CacheReadInputTokens == nil && u.CacheCreationInputTokens == nil {
		return Tokens{}, false
	}
	return Tokens{orZero(u.InputTokens), orZero(u.OutputTokens), orZero(u.CacheReadInputTokens),
		orZero(u.CacheCreationInputTokens)}, true
}

// modelUsage 是 result 行 modelUsage 里一个模型的累计。
type modelUsage struct {
	InputTokens              int64  `json:"inputTokens"`
	OutputTokens             int64  `json:"outputTokens"`
	CacheReadInputTokens     int64  `json:"cacheReadInputTokens"`
	CacheCreationInputTokens int64  `json:"cacheCreationInputTokens"`
	ContextWindow            *int64 `json:"contextWindow"`
}

// sumModels 是 modelUsage 各模型加在一起（这个进程启动以来的累计）；一个模型都没有时 ok 为 false。
func sumModels(models map[string]modelUsage) (t Tokens, ok bool) {
	if len(models) == 0 {
		return Tokens{}, false
	}
	for _, m := range models {
		t = t.Add(Tokens{m.InputTokens, m.OutputTokens, m.CacheReadInputTokens, m.CacheCreationInputTokens})
	}
	return t, true
}

func orZero(v *int64) int64 {
	if v == nil {
		return 0
	}
	return *v
}

func ptr[T any](v T) *T { return &v }

// decode 解出 data 里认得的字段。某个字段的类型对不上时当它没有，别的字段照常解出；data 不是 JSON 时返回 false。
func decode(data []byte, v any) bool {
	err := json.Unmarshal(data, v)
	var typeErr *json.UnmarshalTypeError
	return err == nil || errors.As(err, &typeErr)
}
