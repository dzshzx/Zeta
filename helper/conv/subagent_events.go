package conv

import (
	"bytes"
	"encoding/json"
	"slices"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// subagentSystem 是输出流里子代理开始（task_started）、结局（task_notification）用到的几项。
type subagentSystem struct {
	TaskType  string `json:"task_type"`
	ToolUseID string `json:"tool_use_id"`
	TaskID    string `json:"task_id"`
	Status    string `json:"status"`
}

// agentResult 是主对话里 Agent 调用的结果上带着的子代理 id（前台的做完时、后台的一起来就有）。
type agentResult struct {
	ToolUseResult struct {
		AgentID string `json:"agentId"`
	} `json:"tool_use_result"`
}

// observeSubagents 跟着输出流里和子代理有关的事件：记下 Agent 调用和子代理 id 的对应、子代理的结局，
// 子代理内部的消息交给有人看着的那个子代理。e 是 line 解出来的。
func (c *Conversation) observeSubagents(e event, line []byte) {
	switch {
	case e.Type == "system" && (e.Subtype == "task_started" || e.Subtype == "task_notification"):
		var s subagentSystem
		if json.Unmarshal(line, &s) == nil && s.TaskID != "" {
			c.observeTask(e.Subtype, s)
		}
	case (e.Type == "assistant" || e.Type == "user") && e.Message != nil && e.ParentToolUseID != "":
		l := c.subagents.logs[e.ParentToolUseID]
		if l != nil && len(l.watchers) > 0 &&
			l.t.observeLive(subagentEvent{Type: e.Type, Message: *e.Message, ToolUseResult: e.ToolUseResult}) {
			c.publishSubagent(l)
			c.followSubagent(l)
		}
	case e.Type == "user" && e.Message != nil && bytes.Contains(line, []byte(`"agentId"`)):
		var r agentResult
		if json.Unmarshal(line, &r) != nil || r.ToolUseResult.AgentID == "" {
			return
		}
		for _, b := range e.Message.Content.Blocks {
			if b.ToolUseID != "" {
				c.subagents.ids.put(b.ToolUseID, r.ToolUseResult.AgentID)
				return
			}
		}
	}
}

// observeTask：子代理开始时记下 Agent 调用对应的子代理 id；报了结局时记下，有人看着的那个跟着更新。
func (c *Conversation) observeTask(subtype string, s subagentSystem) {
	if subtype == "task_started" {
		if s.TaskType == "local_agent" && s.ToolUseID != "" {
			c.subagents.ids.put(s.ToolUseID, s.TaskID)
		}
		return
	}
	if s.Status != "completed" && s.Status != "failed" && s.Status != "stopped" {
		return
	}
	c.subagents.ended.put(s.TaskID, s.Status)
	for _, l := range c.subagents.logs {
		if len(l.watchers) > 0 && c.subagents.agentID(l) == s.TaskID {
			c.publishSubagent(l)
			c.followSubagent(l)
		}
	}
}

// subagentsProcessEnded：进程没了，有人看着的子代理都不再算在跑。
func (c *Conversation) subagentsProcessEnded() {
	for _, l := range c.subagents.logs {
		if len(l.watchers) > 0 {
			c.publishSubagent(l)
		}
	}
}

// agentCall 是发起子代理的 Agent 调用的参数里查看页用得着的几项。
type agentCall struct {
	Description string `json:"description"`
	AgentType   string `json:"subagent_type"`
	Prompt      string `json:"prompt"`
}

// fillSubagentStatus 填上 status 的 meta：记录旁的 meta 读到了就用它；还没读到时先用发起它的 Agent 调用的参数填上说明、类型，
// 交给它的任务也一样，查看页一打开就有概要。
func (c *Conversation) fillSubagentStatus(l *subagentLog, status *subagentStatus) {
	if l.t.meta != nil {
		meta := *l.t.meta
		status.Meta = &meta
		return
	}
	call := c.agentCallOf(l)
	if call == nil {
		return
	}
	status.Meta = &subagentMeta{AgentType: call.AgentType, Description: call.Description}
	if status.Prompt == "" {
		status.Prompt = call.Prompt
	}
}

// agentCallOf 是列表里发起子代理 l 的那次调用的参数（参数收齐以后就不变，找到一次就记下）；不在已载入的列表里时为 nil。
func (c *Conversation) agentCallOf(l *subagentLog) *agentCall {
	if l.call != nil {
		return l.call
	}
	for _, it := range slices.Backward(c.r.Items) {
		for _, s := range it.Steps {
			if s.ID != l.toolUseID || s.Kind != transcript.StepTool || s.Input == nil {
				continue
			}
			var call agentCall
			if json.Unmarshal(s.Input, &call) != nil {
				return nil
			}
			l.call = &call
			return l.call
		}
	}
	return nil
}
