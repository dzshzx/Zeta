// Package tasks 把 Claude Code 的事件归约成一个对话的后台任务清单（docs/core.md 的 conv.state 的 tasks），
// 也处理 stop_task、get_task_output 的应答（conv.stop_task、conv.task_output）。
//
// 用法：每行输出事件交给 Board.Observe；发 stop_task 前调 StopRequested、应答到了交给 StopAnswered；
// get_task_output 的应答用 ParseOutput 解成回给客户端的 result；进程没了调 ProcessEnded。
// 这几个返回 true 时清单变了，用 Tasks 取整份推送。Board 不加锁，只在对话自己的那个 goroutine 里用。
package tasks

import (
	"encoding/json"
	"errors"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Kind 是后台任务是什么：后台命令（Bash 的 run_in_background）、监视器（Monitor）、后台子代理，或别的种类。
type Kind string

const (
	KindCommand Kind = "command"
	KindMonitor Kind = "monitor"
	KindAgent   Kind = "agent"
	KindOther   Kind = "other"
)

// State 是任务的状态。StateEnded：从清单里消失了（或进程没了），却没收到结局，不知道是怎么结束的。
type State string

const (
	StateRunning   State = "running"
	StateCompleted State = "completed"
	StateFailed    State = "failed"
	StateStopped   State = "stopped"
	StateEnded     State = "ended"
)

// Task 是 Claude Code 在一个对话里起的一个后台任务。字段取自 stream-json 的几种 system 事件（2.1.328 实测）：
// background_tasks_changed（当前在跑的完整清单）、task_started、task_progress、task_updated、task_notification。
// 时间都是毫秒。没有的文字项是空字符串。
type Task struct {
	ID string `json:"id"`
	// Type 是 Claude Code 的 task_type：local_bash（后台命令和监视器）、local_agent（子代理）等。
	Type        string `json:"type"`
	Kind        Kind   `json:"kind"`
	Description string `json:"description"`
	// Command 是命令（后台命令、监视器）或交给子代理的指令。
	Command string `json:"command,omitempty"`
	// AgentType 是子代理的类型（general-purpose 等）。
	AgentType string `json:"agent_type,omitempty"`
	ToolUseID string `json:"tool_use_id,omitempty"`
	StartedAt int64  `json:"started_at"`
	EndedAt   *int64 `json:"ended_at,omitempty"`
	State     State  `json:"state"`
	// Stopping：用户叫停了，还没等到结果。
	Stopping bool `json:"stopping,omitempty"`
	// Progress 是子代理正在做的那一步（task_progress 的说明）。
	Progress string `json:"progress,omitempty"`
	ToolUses *int   `json:"tool_uses,omitempty"`
	// Summary 是结束时的说明：后台命令是退出码那句，子代理是它交回的报告。和 Description 一样时不留。
	Summary string `json:"summary,omitempty"`
	Error   string `json:"error,omitempty"`
}

// Running：还在跑。
func (t Task) Running() bool { return t.State == StateRunning }

// Readable：能用 get_task_output 读输出的，后台命令和监视器（子代理读不了，看 Progress 和 Summary）。
func (t Task) Readable() bool { return t.Kind == KindCommand || t.Kind == KindMonitor }

// ElapsedMs 是到 now（还在跑时）或结束时跑了多久。
func (t Task) ElapsedMs(now int64) int64 {
	end := now
	if t.EndedAt != nil {
		end = *t.EndedAt
	}
	return max(end-t.StartedAt, 0)
}

// MarshalJSON 在字段之外写出 running、readable，客户端不用自己判断。
func (t Task) MarshalJSON() ([]byte, error) {
	type fields Task
	return json.Marshal(struct {
		fields
		Running  bool `json:"running"`
		Readable bool `json:"readable"`
	}{fields(t), t.Running(), t.Readable()})
}

// same：两份内容一样（指针字段比指向的值）。
func same(a, b Task) bool {
	if !samePtr(a.EndedAt, b.EndedAt) || !samePtr(a.ToolUses, b.ToolUses) {
		return false
	}
	a.EndedAt, b.EndedAt, a.ToolUses, b.ToolUses = nil, nil, nil, nil
	return a == b
}

func samePtr[T comparable](a, b *T) bool {
	return a == b || (a != nil && b != nil && *a == *b)
}

func ptr[T any](v T) *T { return &v }

// Output 是 get_task_output 的应答：输出的最后至多 8 KiB，原样（含终端转义，显示前用 PlainTerminalText）。
// Error 不为空时是没读成的原因。它就是 conv.task_output 回给客户端的 result。
type Output struct {
	Text       string `json:"text"`
	TotalBytes int64  `json:"total_bytes"`
	Truncated  bool   `json:"truncated"`
	Error      string `json:"error,omitempty"`
	// ErrorCode 是 Error 是核心自己写的（Claude Code 没给原因）时它的种类码（docs/i18n.md），客户端按自己的语言出字（ErrorIn）；
	// Error 是 Claude Code 的原文时为空
	ErrorCode string `json:"error_code,omitempty"`
}

// ErrorIn 是 Error 在语言 lang 里怎么说。
func (o Output) ErrorIn(lang string) string {
	if o.ErrorCode == "" {
		return o.Error
	}
	return locales.Msg{Key: o.ErrorCode}.In(lang)
}

// ParseOutput 解 get_task_output 的应答（control_response 里的 response 一项）。
func ParseOutput(answer []byte) Output {
	var a struct {
		Subtype  string  `json:"subtype"`
		Error    *string `json:"error"`
		Response *struct {
			Output     string `json:"output"`
			TotalBytes int64  `json:"total_bytes"`
			Truncated  bool   `json:"truncated"`
		} `json:"response"`
	}
	decode(answer, &a)
	if a.Subtype == "error" {
		if a.Error != nil {
			return Output{Error: *a.Error}
		}
		noOutput := locales.M("taskNoOutput")
		return Output{Error: noOutput.String(), ErrorCode: noOutput.Key}
	}
	if a.Response == nil {
		return Output{}
	}
	return Output{Text: a.Response.Output, TotalBytes: a.Response.TotalBytes, Truncated: a.Response.Truncated}
}

// decode 解出 data 里认得的字段。某个字段的类型对不上时当它没有，别的字段照常解出；data 不是 JSON 时返回 false。
func decode(data []byte, v any) bool {
	err := json.Unmarshal(data, v)
	var typeErr *json.UnmarshalTypeError
	return err == nil || errors.As(err, &typeErr)
}
