package conv

import (
	"encoding/json"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"

	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/transcript"
	"github.com/dzshzx/Zeta/helper/usage"
)

// event 是 Claude Code 输出（stream-json）的一行里对话用到的部分；各类型只用到自己那几项。
type event struct {
	Type    string `json:"type"`
	Subtype string `json:"subtype"`

	// system init、status 等
	SessionID      string            `json:"session_id"`
	Model          string            `json:"model"`
	PermissionMode string            `json:"permissionMode"`
	Capabilities   []string          `json:"capabilities"`
	Status         *string           `json:"status"`
	Title          string            `json:"title"`
	Attempt        int               `json:"attempt"`
	Tasks          []json.RawMessage `json:"tasks"`

	// stream_event、assistant、user
	Event           *transcript.StreamEvent   `json:"event"`
	Message         *transcript.Message       `json:"message"`
	ParentToolUseID string                    `json:"parent_tool_use_id"`
	ToolUseResult   *transcript.ToolUseResult `json:"tool_use_result"`
	IsReplay        bool                      `json:"isReplay"`

	// command_lifecycle
	CommandUUID string `json:"command_uuid"`
	State       string `json:"state"`

	// result
	UserMessageUUIDs []string          `json:"user_message_uuids"`
	IsError          bool              `json:"is_error"`
	Result           json.RawMessage   `json:"result"`
	Errors           []json.RawMessage `json:"errors"`

	// control_request、control_cancel_request、control_response
	RequestID string           `json:"request_id"`
	Request   json.RawMessage  `json:"request"`
	Response  *controlResponse `json:"response"`

	// conversation_reset（/clear）
	NewConversationID string `json:"new_conversation_id"`

	// rate_limit_event：订阅额度，原样交给 Deps.Quota
	RateLimitInfo json.RawMessage `json:"rate_limit_info"`
}

// receive 处理托管进程输出的一行。
func (c *Conversation) receive(p *process, line []byte) {
	if p != c.proc {
		return
	}
	// 进程有了输出：交给它的消息已经送到了
	c.unsent = nil
	now := c.deps.now()
	if c.usage.Observe(line) {
		c.changed()
	}
	if c.tasks.Observe(line, now) {
		c.changed()
	}
	var e event
	if json.Unmarshal(line, &e) != nil {
		return
	}
	c.observeSubagents(e, line)
	if c.handle(p, e, line) {
		c.status = locales.Msg{}
		c.lastActivity = now
		c.changed()
	}
}

// handle 处理一个事件，返回聊天列表有没有变。
func (c *Conversation) handle(p *process, e event, line []byte) bool {
	// 子代理内部的消息不进主列表，只显示发起它的那一步
	fromSubagent := e.ParentToolUseID != ""
	switch e.Type {
	case "system":
		return c.handleSystem(p, e, line)
	case "stream_event":
		if e.Event == nil {
			return false
		}
		if e.Event.Type == "message_start" && e.Event.Message != nil {
			c.replyStarted(e.Event.Message.ID)
		}
		if fromSubagent {
			return false
		}
		c.r.ApplyStream(*e.Event, c.deps.now())
		return true
	case "assistant":
		if e.Message == nil {
			return false
		}
		c.replyStarted(e.Message.ID)
		if fromSubagent {
			return false
		}
		c.r.ApplyAssistant(*e.Message, false)
		return true
	case "user":
		return c.handleUser(e, fromSubagent)
	case "command_lifecycle":
		return c.handleLifecycle(e)
	case "result":
		return c.handleResult(e)
	case "control_request":
		c.handleControlRequest(e, line)
	case "control_cancel_request":
		// Claude Code 自己撤回的（这一轮被打断、子代理被停）
		c.dropPrompt(e.RequestID)
	case "conversation_reset":
		// /clear：Claude Code 换了一个新会话，之前的内容它已经看不到了
		c.subagents.sessionCleared(c.session) // 之前的子代理记录在原来的会话目录里
		c.setSession(e.NewConversationID)
		c.title = ""
		c.r.AddNoticeMsg(locales.M("noticeContextCleared"), false)
		return true
	case "control_response":
		if e.Response != nil {
			p.answer(*e.Response)
		}
	case "rate_limit_event":
		// 模型请求的响应头里带着 5 小时和每周窗口的数，变了 Claude Code 就发一条（子代理的请求也算）
		if c.deps.Quota != nil && len(e.RateLimitInfo) > 0 {
			c.deps.Quota.RateLimit(e.RateLimitInfo)
		}
	}
	return false
}

func (c *Conversation) handleSystem(p *process, e event, line []byte) bool {
	switch e.Subtype {
	case "init":
		c.handleInit(e)
	case "background_tasks_changed":
		// 每次都是完整的清单（不含前台运行的命令），数一下就是还在跑的
		if n := len(e.Tasks); n != c.backgroundTasks {
			c.backgroundTasks = n
			c.changed()
		}
	case "status":
		if e.PermissionMode != "" {
			c.modes.reported(e.PermissionMode)
		}
		if e.Status != nil && *e.Status == "compacting" {
			c.status = locales.M("statusCompacting")
			c.lastActivity = c.deps.now()
		}
		c.changed()
	case "compact_boundary":
		c.r.AddNoticeMsg(usage.CompactNotice(line), false)
		// 压缩后的 post_tokens 不含系统提示和工具，问 Claude Code 要含这些的估算
		c.queryUsage(p)
		return true
	case "session_title_changed":
		if strings.TrimSpace(e.Title) != "" {
			c.title = e.Title
			c.changed()
		}
	case "api_retry":
		c.status = locales.M("statusRetrying", "attempt", max(e.Attempt, 0))
		c.lastActivity = c.deps.now()
		c.changed()
	}
	return false
}

func (c *Conversation) handleInit(e event) {
	c.setSession(e.SessionID)
	// 实际在用的模型：没指定时（接着旧会话）是 Claude Code 照会话记录恢复的那个
	if e.Model != "" {
		c.reportedModel = e.Model
	}
	// 起进程时没指定模式才要 init 报的；之后的变化看 status
	if e.PermissionMode != "" {
		c.modes.reportedAtStart(e.PermissionMode)
	}
	c.sendNow = slices.Contains(e.Capabilities, "interrupt_send_now_v1")
	// 每一轮开头都有 init。闲着时来的是 Claude Code 自己起的一轮：后台任务跑完（或后台子代理交回结果）后
	// 它会接着处理，这一轮没有 Zeta 发的消息，也没有 command_lifecycle，同样以 result 结束。
	// 交回去只进上下文的 ! 命令和输出也各有一个 init（带它们的 uuid），它们不算一轮
	if !c.onlyContextNotes(e.UserMessageUUIDs) {
		c.setBusy()
	}
	c.changed()
}

func (c *Conversation) handleUser(e event, fromSubagent bool) bool {
	if e.Message == nil {
		return false
	}
	c.dropAnsweredPrompts(e.Message)
	if fromSubagent {
		return false
	}
	// Zeta 不让 Claude Code 回放用户消息，回放出来的只有 ! 命令和它的输出
	if e.IsReplay {
		if e.Message.Content.Blocks != nil || !c.r.ApplyShell(e.Message.Content.Text) {
			return false
		}
		c.shellIntoContext(e.Message.Content.Text)
		return true
	}
	c.r.ApplyUser(*e.Message, false, transcript.UserOptions{Result: e.ToolUseResult})
	return true
}

// handleLifecycle：排队的消息开始处理（并进当前一轮或另起一轮）或被撤回；返回聊天列表有没有变。
func (c *Conversation) handleLifecycle(e event) bool {
	id := e.CommandUUID
	if id == "" || c.contextNotes[id] {
		return false
	}
	if (e.State == "completed" || e.State == "cancelled") && c.shellCommands[id] {
		delete(c.shellCommands, id)
		c.finishShell()
		return true
	}
	i := slices.IndexFunc(c.queued, func(m *outgoing) bool { return m.uuid == id })
	switch e.State {
	case "started":
		c.setBusy()
		if i < 0 {
			return false
		}
		m := c.queued[i]
		c.queued = slices.Delete(c.queued, i, i+1)
		delete(c.recalling, id)
		if len(c.queued) == 0 {
			c.steering = false
		}
		m.item = c.r.AddUser(m.text, m.inlineImages(), m.files, m.uuid)
		return true
	case "cancelled":
		if i < 0 {
			return false
		}
		m := c.queued[i]
		c.queued = slices.Delete(c.queued, i, i+1)
		if c.recalling[id] {
			delete(c.recalling, id)
			c.recalled = merge(c.recalled, m)
		}
		if len(c.queued) == 0 {
			c.steering = false
		}
		c.changed()
	}
	return false
}

// shellIntoContext 把 ! 命令和它的输出照终端版并进上下文：终端里两行都是会话记录里的用户消息，下一轮模型看得到；
// stream-json 的 bash_command 只回放给客户端看，不进上下文、不写会话记录（2.1.330，ClaudeInteractionContractTest 钉住，
// 那边改成自己并进上下文时那个用例失败，这段连同 contextNotes 一起删掉）。等到输出时把回放的两行原样交回去，
// shouldQuery 为假：只并进上下文和会话记录，不请求模型；各出一个 num_turns 为 0 的 result，不算一轮。
func (c *Conversation) shellIntoContext(text string) {
	if strings.HasPrefix(strings.TrimSpace(text), "<bash-input>") {
		c.shellInput = text
		return
	}
	input := c.shellInput
	c.shellInput = ""
	if input == "" {
		return
	}
	for _, line := range []string{input, text} {
		id := newUUID()
		c.contextNotes[id] = true
		m := userMessage(id, line)
		m["shouldQuery"] = false
		b, _ := json.Marshal(m)
		c.proc.link.Send(b)
	}
}

// onlyContextNotes：这个 init 或 result 是不是只为交回去进上下文的 ! 命令或输出出的。和别的消息并成一轮的不算。
func (c *Conversation) onlyContextNotes(ids []string) bool {
	return len(ids) > 0 && !slices.ContainsFunc(ids, func(id string) bool { return !c.contextNotes[id] })
}

// finishShell：! 命令做完了。它没有 result，做完就算这一条结束。
func (c *Conversation) finishShell() {
	if len(c.queued) > 0 {
		return
	}
	c.busy = false
	c.r.FinishTurn(false)
	c.changed()
}

// handleResult：一轮结束。收尾这一轮的列表，说明停止或出错；还有排队的消息时 Claude Code 会接着另起一轮。
func (c *Conversation) handleResult(e event) bool {
	notes := c.onlyContextNotes(e.UserMessageUUIDs)
	for _, id := range e.UserMessageUUIDs {
		delete(c.contextNotes, id)
	}
	if notes {
		return false
	}
	c.r.FinishTurn(false)
	failed := e.IsError || e.Subtype != "success"
	switch {
	case c.interruptRequested && c.steering:
		c.r.AddNoticeMsg(locales.M("noticeSteered"), false)
	case c.interruptRequested:
		c.r.AddNoticeMsg(locales.M("noticeStopped"), false)
	case failed:
		c.addFailure(e)
	}
	stopped := c.interruptRequested
	c.interruptRequested = false
	// 插话会并进同一轮、只出一个 result，所以不按发出的条数算
	if len(c.queued) == 0 {
		switch {
		case stopped:
			c.endTurn(outcomeStopped)
		case failed:
			c.endTurn(outcomeFailed)
		default:
			c.endTurn(outcomeDone)
		}
	}
	return true
}

// addFailure 加一轮出错结束时的提示：result 的文字，没有就把 errors 连起来（都是 Claude Code 的原文），都没有就报 subtype。
func (c *Conversation) addFailure(e event) {
	if text := failureText(e); text != "" {
		c.r.AddNotice(text, true)
		return
	}
	c.r.AddNoticeMsg(locales.M("noticeTurnAbnormal", "subtype", e.Subtype), true)
}

// failureText 是一轮出错结束时 Claude Code 给的原文：result 的文字，没有就把 errors 连起来；都没有时为空。
func failureText(e event) string {
	var text string
	if json.Unmarshal(e.Result, &text) == nil && strings.TrimSpace(text) != "" {
		return text
	}
	var parts []string
	for _, raw := range e.Errors {
		var s string
		if json.Unmarshal(raw, &s) == nil {
			parts = append(parts, s)
		}
	}
	if joined := strings.Join(parts, "\n"); strings.TrimSpace(joined) != "" {
		return joined
	}
	return ""
}

// 一轮的结局（conv.turn 的 outcome）和结束的那一句。
const (
	outcomeDone        = "done"
	outcomeStopped     = "stopped"
	outcomeFailed      = "failed"
	outcomeInterrupted = "interrupted" // 进程意外没了
)

// outcomeSaid 是各结局的那一句。
var outcomeSaid = map[string]locales.Msg{outcomeDone: locales.M("turnDone"), outcomeStopped: locales.M("turnStopped"),
	outcomeFailed: locales.M("turnFailed"), outcomeInterrupted: locales.M("turnInterrupted")}

// turnMsg 是 conv.turn。
type turnMsg struct {
	Type    string `json:"type"`
	Conv    string `json:"conv"`
	Phase   string `json:"phase"`
	Outcome string `json:"outcome,omitempty"`
	// Text 是结局那一句的源语言，给不认 Code 的旧客户端；Code 是它的种类码，客户端按自己的语言出字
	Text string `json:"text,omitempty"`
	Code string `json:"code,omitempty"`
}

// setBusy：这一轮开始了（没在进行时才算开始）。
func (c *Conversation) setBusy() {
	if c.busy {
		return
	}
	c.busy = true
	c.lastActivity = c.deps.now()
	c.flush()
	c.broadcast(func(name string) any { return &turnMsg{Type: "conv.turn", Conv: name, Phase: "started"} }, false)
}

func (c *Conversation) endTurn(outcome string) {
	c.busy = false
	if outcome == outcomeDone {
		c.requestTitle()
	}
	c.turnFinished(outcome)
}

// turnFinished 先把状态推出去（客户端据此判断结束的是哪个对话），再说这一轮结束了。
func (c *Conversation) turnFinished(outcome string) {
	c.turnEnd = c.deps.now()
	c.flush()
	c.broadcast(func(name string) any {
		return &turnMsg{Type: "conv.turn", Conv: name, Phase: "finished", Outcome: outcome,
			Text: outcomeSaid[outcome].String(), Code: outcomeSaid[outcome].Key}
	}, false)
}

// replyStarted：新的一条回复开始（子代理的也算）。手机工具的控件编号从这里起换成上一批结果里的那份，告诉处理手机工具的客户端。
func (c *Conversation) replyStarted(id string) {
	if id == "" || id == c.lastReply {
		return
	}
	c.lastReply = id
	c.broadcast(func(name string) any {
		return map[string]string{"type": "conv.reply", "conv": name, "message_id": id}
	}, true)
}

// setSession：Claude Code 报了会话号（新对话第一次、/clear 换了新会话）。核心按会话号找对话的地方跟着换。
func (c *Conversation) setSession(id string) {
	if id == "" || id == c.session {
		return
	}
	before := c.session
	c.session = id
	if before == "" {
		c.openDesk()
	}
	if c.deps.SessionChanged != nil {
		c.deps.SessionChanged(c, before, id)
	}
	c.changed()
}

// handleControlRequest：要用户回应的请求进队；手机工具的调用转给处理手机工具的客户端。
func (c *Conversation) handleControlRequest(e event, line []byte) {
	if p := prompt.Parse(line); p != nil {
		c.enqueuePrompt(p)
		return
	}
	var req struct {
		Subtype string          `json:"subtype"`
		Message json.RawMessage `json:"message"`
	}
	if json.Unmarshal(e.Request, &req) != nil || req.Subtype != "mcp_message" || e.RequestID == "" {
		return
	}
	if !c.phoneHolder() {
		c.mcpReply(e.RequestID, mcpUnavailable(req.Message))
		return
	}
	c.broadcast(func(name string) any {
		return map[string]any{"type": "conv.mcp", "conv": name, "request_id": e.RequestID, "message": req.Message}
	}, true)
}

// mcpUnavailable 是没有客户端能处理手机工具时给 Claude Code 的 JSON-RPC 出错应答（通知不用应答，给空的）。
func mcpUnavailable(message json.RawMessage) json.RawMessage {
	var m struct {
		ID json.RawMessage `json:"id"`
	}
	if json.Unmarshal(message, &m) != nil || len(m.ID) == 0 {
		return json.RawMessage(`{}`)
	}
	return raw(map[string]any{"jsonrpc": "2.0", "id": m.ID,
		"error": map[string]any{"code": -32000, "message": "手机现在没连着，用不了手机工具"}})
}

// mcpReply 把手机工具的应答交回 Claude Code。
func (c *Conversation) mcpReply(requestID string, response json.RawMessage) {
	if c.proc == nil {
		return
	}
	c.proc.link.Send(controlSuccess(requestID, map[string]any{"mcp_response": response}))
}

// controlSuccess 是答 Claude Code 发来的控制请求的那一行。
func controlSuccess(requestID string, response any) []byte {
	b, _ := json.Marshal(map[string]any{"type": "control_response", "response": map[string]any{
		"subtype": "success", "request_id": requestID, "response": response}})
	return b
}
