package conv

import (
	"errors"
	"slices"

	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/transcript"
)

// 等用户回应的请求（工具确认、计划审批、提问、MCP 表单）按到达顺序排在 c.prompts 里，客户端一次弹第一个。
// 主对话和后台子代理可能同时各有一个在等。

// enqueuePrompt：同一个号已经在等（接回进程时重放过来的）就不再排一次。
func (c *Conversation) enqueuePrompt(p *prompt.Prompt) {
	if slices.ContainsFunc(c.prompts, func(q *prompt.Prompt) bool { return q.RequestID == p.RequestID }) {
		return
	}
	c.prompts = append(c.prompts, p)
	c.changed()
}

func (c *Conversation) dropPrompt(requestID string) {
	n := len(c.prompts)
	c.prompts = slices.DeleteFunc(c.prompts, func(p *prompt.Prompt) bool { return p.RequestID == requestID })
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
	c.prompts = slices.DeleteFunc(c.prompts, func(p *prompt.Prompt) bool {
		return p.Kind == prompt.KindPermission && done[p.ToolUseID]
	})
	if len(c.prompts) != n {
		c.changed()
	}
}

var errNoPrompt = errors.New("没有在等这个请求")

func (c *Conversation) findPrompt(requestID string, kind prompt.Kind) *prompt.Prompt {
	i := slices.IndexFunc(c.prompts, func(p *prompt.Prompt) bool { return p.RequestID == requestID && p.Kind == kind })
	if i < 0 {
		return nil
	}
	return c.prompts[i]
}

// answer 回应一次 can_use_tool（工具确认、计划审批、提问）。
func (c *Conversation) answer(requestID string, reply prompt.Reply) error {
	p := c.findPrompt(requestID, prompt.KindPermission)
	if p == nil {
		return errNoPrompt
	}
	response, err := p.Respond(reply)
	if err != nil {
		return err
	}
	// 不写理由的拒绝和终端里按 Esc 一样叫停这一轮：结局算已停止，不算出错
	if reply.StopsTurn() {
		c.interruptRequested = true
	}
	c.respond(requestID, response)
	return nil
}

// elicit 回应一次 MCP 表单：接受表单时 values 是各项填的（键 → 输入框里的字，多选是选中的值）。
// 填得不对时返回各项的错误（键 → 说明），不交给 Claude Code。
func (c *Conversation) elicit(requestID string, action prompt.ElicitAction, values map[string][]string) (map[string]string, error) {
	p := c.findPrompt(requestID, prompt.KindElicitation)
	if p == nil {
		return nil, errNoPrompt
	}
	response, problems, err := p.Elicit(action, values)
	if err != nil || len(problems) > 0 {
		return problems, err
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
		if p.Kind == prompt.KindPermission {
			_ = c.answer(p.RequestID, prompt.Reply{Kind: prompt.ReplyDeny})
		} else {
			_, _ = c.elicit(p.RequestID, prompt.ElicitCancel, nil)
		}
	}
}
