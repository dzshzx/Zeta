// Package prompt 处理 Claude Code 等用户回应的请求，和终端版（TUI）里弹出的那些对话框一一对应。它们经
// --permission-prompt-tool stdio 变成 control_request 发过来：
//   - can_use_tool：批准一次工具调用（权限确认）、计划审批（ExitPlanMode）、回答问题（AskUserQuestion）。
//   - elicitation：MCP 服务器要用户填一张表（表单）或去网页上做一件事（网址）。
//
// Parse 把请求读成 Prompt，它的 JSON 就是核心推给客户端的格式（conv.state 的 prompts，见 docs/core.md）。
// 客户端的回应由 Prompt.Respond（can_use_tool）和 Prompt.Elicit（elicitation）拼成和 TUI 一样的应答。
//
// 行为和 Kotlin 版（core/Prompts.kt）一致，用例照它的 PromptsTest 按值对照。
package prompt

import (
	"encoding/json"
	"strings"
)

// Kind 是请求的种类。
type Kind string

const (
	KindPermission  Kind = "permission"  // can_use_tool：工具确认、计划审批、提问
	KindElicitation Kind = "elicitation" // MCP 服务器的表单或网址
)

// 几个有专门界面的工具。
const (
	toolExitPlanMode    = "ExitPlanMode"
	toolAskUserQuestion = "AskUserQuestion"
)

// Prompt 是一个在等用户回应的请求。两种请求只用到各自那几个字段，没写出的字段是零值。
type Prompt struct {
	Kind      Kind   `json:"kind"`
	RequestID string `json:"request_id"`

	// 下面是 can_use_tool 的。

	ToolName string `json:"tool_name,omitempty"`
	// Input 是工具调用的参数，原样保留，批准时照样交回去；不是对象时为 {}。
	Input json.RawMessage `json:"input,omitempty"`
	// Questions 是 AskUserQuestion 要问的，别的工具没有。
	Questions []Question `json:"questions,omitempty"`
	// Suggestions 是 Claude Code 建议的「以后不再问」要做的改动（permission_suggestions），每项原样保留，
	// 选了就原样交回去；空的时候界面不给这个选项。
	Suggestions []json.RawMessage `json:"suggestions,omitempty"`
	// SuggestionLines 是 Suggestions 逐条写成的大白话，界面直接列出来。
	SuggestionLines []string `json:"suggestion_lines,omitempty"`
	// Reason 是 Claude Code 说明为什么要问（decision_reason）。
	Reason string `json:"reason,omitempty"`
	// AgentID 是发起它的后台子代理，主对话发起的为空。
	AgentID string `json:"agent_id,omitempty"`
	// ToolUseID 是要批准的那次工具调用。别的客户端答了以后，看到它的 tool_result 就收起。
	ToolUseID string `json:"tool_use_id,omitempty"`
	// IsPlan：这是计划审批（ExitPlanMode）。
	IsPlan bool `json:"is_plan,omitempty"`

	// 下面是 elicitation 的。

	Server  string `json:"server,omitempty"`
	Message string `json:"message,omitempty"`
	// URL 不为空时是网址模式（去网页上做），否则是表单，Fields 是要填的各项。
	URL    string  `json:"url,omitempty"`
	Fields []Field `json:"fields,omitempty"`

	// 两种都有的。

	// Notice 是通知里的一句：在等什么。
	Notice string `json:"notice"`
	// AsksInput：要用户回答（提问、MCP 表单），不只是点允许或拒绝。界面据此说「需要你回答」还是「需要你确认」。
	AsksInput bool `json:"asks_input,omitempty"`
}

// Question 是 AskUserQuestion 的一个问题。
type Question struct {
	Question    string   `json:"question"`
	Header      string   `json:"header"`
	Options     []Option `json:"options"`
	MultiSelect bool     `json:"multi_select,omitempty"`
	// TakesNotes：能给选中的那项写备注。和 TUI 一样，只有单选题、有选项带预览时才能写。
	TakesNotes bool `json:"takes_notes,omitempty"`
}

// Option 是问题的一个选项。Preview 为 nil 是没有预览，和空字符串的预览不同。
type Option struct {
	Label       string  `json:"label"`
	Description string  `json:"description"`
	Preview     *string `json:"preview,omitempty"`
}

// FieldKind 是表单里一项的种类，决定用什么输入框、交回去的值是什么类型。
type FieldKind string

const (
	FieldText    FieldKind = "text"    // 文字
	FieldInteger FieldKind = "integer" // 整数
	FieldNumber  FieldKind = "number"  // 数字
	FieldToggle  FieldKind = "toggle"  // 开关
	FieldSingle  FieldKind = "single"  // 单选
	FieldMulti   FieldKind = "multi"   // 多选
)

// Field 是表单里的一项，按 MCP 的 requestedSchema（只有一层、只有基本类型）读出来。
type Field struct {
	Key         string    `json:"key"`
	Title       string    `json:"title"`
	Description string    `json:"description,omitempty"`
	Required    bool      `json:"required,omitempty"`
	Kind        FieldKind `json:"kind"`
	// Choices 是单选、多选的可选值。
	Choices []Choice `json:"choices,omitempty"`
	// Default 是 schema 给的默认值，已经换成输入框里的写法（多选是选中的值，开关是 "true"/"false"）。
	Default []string `json:"default,omitempty"`
	// Min、Max 是数字的范围，多选时是能选几项。
	Min       *float64 `json:"min,omitempty"`
	Max       *float64 `json:"max,omitempty"`
	MinLength *int     `json:"min_length,omitempty"`
	MaxLength *int     `json:"max_length,omitempty"`
	// Format 是 email、uri、date、date-time 之类，只用来换键盘和提示。
	Format string `json:"format,omitempty"`
}

// Choice 是单选、多选的一个可选值：Value 交回去，Label 给人看。
type Choice struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// Parse 读一行 control_request。不是要用户回应的（比如手机工具的 mcp_message）、读不出来的返回 nil。
func Parse(line []byte) *Prompt {
	var event controlRequest
	if !decode(line, &event) || event.Type != "control_request" || event.RequestID == nil || event.Request == nil {
		return nil
	}
	var p *Prompt
	switch r := event.Request; r.Subtype {
	case "can_use_tool":
		p = permission(r)
	case "elicitation":
		p = elicitation(r)
	default:
		return nil
	}
	p.RequestID = *event.RequestID
	p.Notice = p.notice()
	return p
}

func permission(r *requestBody) *Prompt {
	p := &Prompt{
		Kind:      KindPermission,
		ToolName:  r.ToolName,
		Input:     json.RawMessage("{}"),
		Reason:    string(r.DecisionReason),
		AgentID:   r.AgentID,
		ToolUseID: r.ToolUseID,
		IsPlan:    r.ToolName == toolExitPlanMode,
		AsksInput: r.ToolName == toolAskUserQuestion,
	}
	if isObject(r.Input) {
		p.Input = r.Input
	}
	if p.AsksInput {
		p.Questions = parseQuestions(p.Input)
	}
	// 这类请求 TUI 不给「不再问」那一行（规则会比这次要做的宽）
	if !r.SuppressAlwaysAllowRule {
		p.Suggestions = r.PermissionSuggestions
		p.SuggestionLines = describeSuggestions(p.Suggestions)
	}
	return p
}

func parseQuestions(input json.RawMessage) []Question {
	var in struct {
		Questions objects[wireQuestion] `json:"questions"`
	}
	decode(input, &in)
	questions := make([]Question, 0, len(in.Questions))
	for _, w := range in.Questions {
		q := Question{Question: w.Question, Header: w.Header, MultiSelect: w.MultiSelect, Options: []Option{}}
		for _, o := range w.Options {
			q.Options = append(q.Options, Option(o))
			if o.Preview != nil {
				q.TakesNotes = !q.MultiSelect
			}
		}
		questions = append(questions, q)
	}
	return questions
}

func elicitation(r *requestBody) *Prompt {
	p := &Prompt{Kind: KindElicitation, Message: r.Message, AsksInput: true}
	if r.DisplayName != nil {
		p.Server = *r.DisplayName
	} else {
		p.Server = r.MCPServerName
	}
	if r.Mode == "url" {
		p.URL = r.URL
	}
	if r.RequestedSchema != nil {
		p.Fields = r.RequestedSchema.fields(texts(r.PropertyOrder))
	}
	return p
}

func (p *Prompt) notice() string {
	switch {
	case p.Kind == KindElicitation && p.URL != "":
		return "MCP 服务器 " + p.Server + " 需要你去网页上操作"
	case p.Kind == KindElicitation:
		return "MCP 服务器 " + p.Server + " 需要你填写信息"
	case p.IsPlan:
		return "Claude 交来了计划，等你审批"
	case p.ToolName == toolAskUserQuestion:
		return "Claude 有问题要问你"
	default:
		return "Claude 想用 " + p.ToolName
	}
}

// notBlank：去掉首尾空白后还有字。
func notBlank(s string) bool { return strings.TrimSpace(s) != "" }
