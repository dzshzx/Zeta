package prompt

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// ReplyKind 是用户对 can_use_tool 的哪种回应。
type ReplyKind string

const (
	// ReplyAllow 允许这一次。
	ReplyAllow ReplyKind = "allow"
	// ReplyAllowAlways 允许，并照 Claude Code 的建议以后不再问（TUI 的 “Yes, and don't ask again …”）。
	ReplyAllowAlways ReplyKind = "allow_always"
	// ReplyDeny 拒绝。没写理由时和 TUI 按 Esc 一样：这一轮停下等用户；写了理由（TUI 的 “No, and tell Claude
	// what to do differently”）就把它交给 Claude，它接着做。计划审批的「不批准、继续完善」也走这里，理由必填。
	ReplyDeny ReplyKind = "deny"
	// ReplyApprovePlan 批准计划，之后按 Mode 跑（acceptEdits 是自动接受改动，default 是逐项确认）。
	ReplyApprovePlan ReplyKind = "approve_plan"
	// ReplyAnswer 回答 AskUserQuestion。
	ReplyAnswer ReplyKind = "answer"
	// ReplyClarify 先不回答，想和 Claude 聊聊这些问题（TUI 的 “Chat about this”），带上已经选了、写了的。
	ReplyClarify ReplyKind = "clarify"
)

// Reply 是客户端对 can_use_tool 的回应，即 conv.answer 的 reply。
type Reply struct {
	Kind     ReplyKind `json:"kind"`
	Feedback string    `json:"feedback,omitempty"` // deny：拒绝的理由
	Mode     string    `json:"mode,omitempty"`     // approve_plan：批准后的权限模式
	// Answers 是问题 → 回答：多选用 ", " 连起来，自己写的照原样（answer、clarify）。
	Answers map[string]string `json:"answers,omitempty"`
	// Notes 是问题 → 备注（answer、clarify）。
	Notes map[string]string `json:"notes,omitempty"`
}

// StopsTurn：这次回应等于叫停这一轮（不写理由的拒绝，和终端里按 Esc 一样），这一轮的结局记为已停止，不算出错。
func (r Reply) StopsTurn() bool {
	return r.Kind == ReplyDeny && !notBlank(r.Feedback)
}

// Response 是 can_use_tool 应答里的 response（behavior 那一层）。
type Response struct {
	Behavior           string            `json:"behavior"` // allow 或 deny
	UpdatedInput       json.RawMessage   `json:"updatedInput,omitempty"`
	UpdatedPermissions []json.RawMessage `json:"updatedPermissions,omitempty"`
	Message            string            `json:"message,omitempty"`
	Interrupt          bool              `json:"interrupt,omitempty"`
}

// RejectWithFeedback 是 TUI 拒绝并写了理由时交给模型的话，后面接用户写的。
const RejectWithFeedback = "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n"

// Respond 把用户的回应拼成和 TUI 一样的 response。p 不是 can_use_tool、回应的种类认不出时返回错误。
func (p *Prompt) Respond(r Reply) (Response, error) {
	if p.Kind != KindPermission {
		return Response{}, errors.New("这个请求不是工具确认，不能这样回应")
	}
	switch r.Kind {
	case ReplyAllow:
		return allow(p.Input), nil
	case ReplyAllowAlways:
		resp := allow(p.Input)
		resp.UpdatedPermissions = p.Suggestions
		return resp, nil
	case ReplyApprovePlan:
		return approvePlan(p.Input, r.Mode)
	case ReplyAnswer:
		input, err := p.answered(r.Answers, r.Notes)
		return allow(input), err
	case ReplyDeny:
		feedback := strings.TrimSpace(r.Feedback)
		if feedback == "" {
			// 带 interrupt 时 Claude Code 用它自己的那句（STOP … wait for the user），message 不交给模型
			return Response{Behavior: "deny", Message: "User rejected tool use", Interrupt: true}, nil
		}
		return Response{Behavior: "deny", Message: RejectWithFeedback + feedback}, nil
	case ReplyClarify:
		return Response{Behavior: "deny", Message: RejectWithFeedback + clarifyText(p.Questions, r.Answers, r.Notes)}, nil
	default:
		return Response{}, fmt.Errorf("认不出的回应 %q", r.Kind)
	}
}

func allow(input json.RawMessage) Response {
	return Response{Behavior: "allow", UpdatedInput: input}
}

// approvePlan 批准计划，并把这个对话的权限模式改成 mode。
func approvePlan(input json.RawMessage, mode string) (Response, error) {
	if mode == "" {
		return Response{}, errors.New("批准计划要带 mode")
	}
	setMode, err := json.Marshal(struct {
		Type        string `json:"type"`
		Mode        string `json:"mode"`
		Destination string `json:"destination"`
	}{"setMode", mode, "session"})
	resp := allow(input)
	resp.UpdatedPermissions = []json.RawMessage{setMode}
	return resp, err
}

// annotation 是 AskUserQuestion 应答里一个问题的附注：选中那项的预览、用户写的备注。
type annotation struct {
	Preview *string `json:"preview,omitempty"`
	Notes   string  `json:"notes,omitempty"`
}

// answered 是原来的参数加上 answers 和 annotations，和 TUI 交回去的一样。
func (p *Prompt) answered(answers, notes map[string]string) (json.RawMessage, error) {
	var annotations object
	for _, q := range p.Questions {
		a := annotation{Preview: q.preview(answers), Notes: q.note(notes)}
		if a.Preview == nil && a.Notes == "" {
			continue
		}
		raw, err := json.Marshal(a)
		if err != nil {
			return nil, err
		}
		annotations.set(q.Question, raw)
	}
	if answers == nil {
		answers = map[string]string{}
	}
	answersRaw, err := json.Marshal(answers)
	if err != nil {
		return nil, err
	}
	var input object
	decode(p.Input, &input)
	input.set("answers", answersRaw)
	if len(annotations) > 0 {
		raw, err := json.Marshal(annotations)
		if err != nil {
			return nil, err
		}
		input.set("annotations", raw)
	}
	return json.Marshal(input)
}

// preview 是单选题选中那项的预览，多选题、没选、选的那项没有预览时为 nil。
func (q *Question) preview(answers map[string]string) *string {
	answer, ok := answers[q.Question]
	if q.MultiSelect || !ok {
		return nil
	}
	for _, o := range q.Options {
		if o.Label == answer {
			return o.Preview
		}
	}
	return nil
}

// note 是用户给这个问题写的备注，去掉首尾空白；这个问题不收备注时为空。
func (q *Question) note(notes map[string]string) string {
	if !q.TakesNotes {
		return ""
	}
	return strings.TrimSpace(notes[q.Question])
}

// clarifyText 是 TUI 的 “Chat about this” 交给模型的说明，连同已经选了的回答和备注。
func clarifyText(questions []Question, answers, notes map[string]string) string {
	var b strings.Builder
	b.WriteString("The user wants to clarify these questions.\n")
	b.WriteString("    This means they may have additional information, context or questions for you.\n")
	b.WriteString("    Take their response into account and then reformulate the questions if appropriate.\n")
	b.WriteString("    Start by asking them what they would like to clarify.\n")
	b.WriteString("    Questions asked:\n")
	for i, q := range questions {
		if i > 0 {
			b.WriteByte('\n')
		}
		b.WriteString(`- "` + q.Question + `"`)
		if answer := answers[q.Question]; notBlank(answer) {
			b.WriteString("\n  Answer: " + answer)
		} else {
			b.WriteString("\n  (No answer provided)")
		}
		if note := q.note(notes); note != "" {
			b.WriteString("\n  User notes: " + note)
		}
	}
	return b.String()
}
