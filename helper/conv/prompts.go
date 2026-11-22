package conv

import (
	"encoding/json"
	"errors"
	"slices"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// Prompt 是一个等用户回应的请求（工具确认、计划审批、提问、MCP 表单），按到达顺序排队，客户端一次弹第一个。
// 主对话和后台子代理可能同时各有一个在等。
type Prompt struct {
	requestID  string
	toolUseID  string
	permission bool            // can_use_tool；不是的是 elicitation
	view       json.RawMessage // 推给客户端的样子
	parsed     parsedPrompt
}

// enqueuePrompt：同一个号已经在等（接回进程时重放过来的）就不再排一次。
func (c *Conversation) enqueuePrompt(p *Prompt) {
	if slices.ContainsFunc(c.prompts, func(q *Prompt) bool { return q.requestID == p.requestID }) {
		return
	}
	c.prompts = append(c.prompts, p)
	c.changed()
}

func (c *Conversation) dropPrompt(requestID string) {
	n := len(c.prompts)
	c.prompts = slices.DeleteFunc(c.prompts, func(p *Prompt) bool { return p.requestID == requestID })
	if len(c.prompts) != n {
		c.changed()
	}
}

// dropAnsweredPrompts：别的客户端（或别的版本的核心）答了的确认。Claude Code 不通知没答的这边，这边看到那次工具调用有了结果
// （同意后跑完、拒绝后的说明）就把确认收起。子代理发起的确认，结果在子代理那一行里，所以子代理的行也要看。
func (c *Conversation) dropAnsweredPrompts(m *transcript.Message) {
	done := map[string]bool{}
	for _, b := range m.Content.Blocks {
		if b.Type == "tool_result" && b.ToolUseID != "" {
			done[b.ToolUseID] = true
		}
	}
	if len(done) == 0 {
		return
	}
	n := len(c.prompts)
	c.prompts = slices.DeleteFunc(c.prompts, func(p *Prompt) bool { return p.permission && done[p.toolUseID] })
	if len(c.prompts) != n {
		c.changed()
	}
}

var errNoPrompt = errors.New("没有在等这个请求")

func (c *Conversation) findPrompt(requestID string) *Prompt {
	i := slices.IndexFunc(c.prompts, func(p *Prompt) bool { return p.requestID == requestID })
	if i < 0 {
		return nil
	}
	return c.prompts[i]
}

// answer 回应一次 can_use_tool（工具确认、计划审批、提问）。
func (c *Conversation) answer(requestID string, reply json.RawMessage) error {
	p := c.findPrompt(requestID)
	if p == nil || !p.permission {
		return errNoPrompt
	}
	response, stops, err := permissionResponse(p, reply)
	if err != nil {
		return err
	}
	// 不写理由的拒绝和终端里按 Esc 一样叫停这一轮：结局算已停止，不算出错
	if stops {
		c.interruptRequested = true
	}
	c.respond(requestID, response)
	return nil
}

// elicit 回应一次 MCP 表单：action 是 accept、decline 或 cancel；接受表单时 values 是各项填的（键 → 输入框里的字，多选是选中的值）。
// 填得不对时返回各项的错误（键 → 说明），不交给 Claude Code。
func (c *Conversation) elicit(requestID, action string, values map[string][]string) (map[string]string, error) {
	p := c.findPrompt(requestID)
	if p == nil || p.permission {
		return nil, errNoPrompt
	}
	response, problems := elicitationResponse(p, action, values)
	if len(problems) > 0 {
		return problems, nil
	}
	c.respond(requestID, response)
	return nil, nil
}

// respond 把应答交回 Claude Code，收起这个请求。
func (c *Conversation) respond(requestID string, response any) {
	c.dropPrompt(requestID)
	if c.proc != nil {
		c.proc.link.Send(controlSuccess(requestID, response))
	}
}

// dismissPrompts：停止时把在等的都答掉。工具确认按 Esc 那样拒绝（这一轮停下），MCP 表单取消。
func (c *Conversation) dismissPrompts() {
	for _, p := range slices.Clone(c.prompts) {
		if p.permission {
			_ = c.answer(p.requestID, json.RawMessage(`{"kind":"deny"}`))
		} else {
			_, _ = c.elicit(p.requestID, "cancel", nil)
		}
	}
}
