package coreapi

import (
	"context"
	"encoding/json"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 对话的请求方法（docs/core.md「对话」）。除了 conv.activity，请求都带 conv：客户端给对话起的名字，在一条连接里不重复，
// conv.open 时起好。Conv 是一个名字的句柄，各方法自动带上它。推送（conv.state、conv.items 等）不经这里。

// Conv 是这条连接上名为 name 的对话。零值不能用，用 Client.Conv。
type Conv struct {
	c    Caller
	name string
}

// Conv 是这条连接上名为 name 的对话的句柄：可以是还没打开的（接着用 Open 打开），也可以是 conv.fork 的 as。
func (cl *Client) Conv(name string) Conv { return Conv{c: cl.c, name: name} }

// Name 是这个对话在这条连接上的名字（conv）。
func (v Conv) Name() string { return v.name }

// p 是带上 conv 的参数。
func (v Conv) p(p params) params {
	if p == nil {
		p = params{}
	}
	p["conv"] = v.name
	return p
}

// Options 是起 Claude Code 进程用的设置（conv.open、conv.options、conv.fork 的 options），都可省，各项见 docs/core.md「对话」。
type Options struct {
	// Env 是只给这个 Claude Code 进程的环境变量（比如自动压缩）。
	Env map[string]string `json:"env,omitempty"`
	// SystemPrompt 是 --append-system-prompt。
	SystemPrompt string `json:"system_prompt,omitempty"`
	// MCPConfig 是 --mcp-config（手机工具）；Phone：这个客户端处理手机工具的调用。
	MCPConfig string `json:"mcp_config,omitempty"`
	Phone     bool   `json:"phone,omitempty"`
	// AutoTitle：一轮以后请 Claude Code 起标题。
	AutoTitle bool `json:"auto_title,omitempty"`
	// CompactPercent 是自动压缩的百分比，用量条用；0 是没设。
	CompactPercent int `json:"compact_percent,omitempty"`
	// Desk：这台是远程主机。新会话拿到会话号时、之后每次起进程时，核心在那边 tmux 会话「zeta」里补开它的窗口。
	Desk bool `json:"desk,omitempty"`
	// ImageRefs：推来的条目里实时收到的图只带 key、不带原文，要看时用 ConvImage 取。
	ImageRefs bool `json:"image_refs,omitempty"`
}

// Outgoing 是要发的一条消息（conv.send；conv.rewrite、conv.restore_code 带的新消息只看 Text、Images、Files）。
type Outgoing struct {
	Text   string
	Images []Image
	// Files 是主机上的路径：本地的文件要先传到主机上。
	Files []string
	// Mode、Rules 是后台运行（定时任务、通知触发）这一条用的权限模式和叠加的规则，用户自己发的不带。
	Mode  string
	Rules json.RawMessage
}

// put 把消息的各项加进 p（文字总是带，别的有才带），返回 p。
func (m Outgoing) put(p params) params {
	p["text"] = m.Text
	if len(m.Images) > 0 {
		p["images"] = m.Images
	}
	if len(m.Files) > 0 {
		p["files"] = m.Files
	}
	if m.Mode != "" {
		p["mode"] = m.Mode
	}
	if len(m.Rules) > 0 {
		p["rules"] = m.Rules
	}
	return p
}

// Message 是排队的、撤回的一条消息：conv.take_recalled 的 result，也是 conv.state 的 queued、recalled 的一项。
type Message struct {
	UUID   string   `json:"uuid"`
	Text   string   `json:"text"`
	Images []Image  `json:"images"`
	Files  []string `json:"files"`
}

// ReplyKind 是对工具确认、计划审批、提问的哪种回应（Reply.Kind）。
type ReplyKind string

// 回应的种类，含义见 docs/core.md「对话」的 reply。
const (
	ReplyAllow       ReplyKind = "allow"
	ReplyAllowAlways ReplyKind = "allow_always"
	ReplyDeny        ReplyKind = "deny"
	ReplyApprovePlan ReplyKind = "approve_plan"
	ReplyAnswer      ReplyKind = "answer"
	ReplyClarify     ReplyKind = "clarify"
)

// Reply 是 conv.answer 的 reply（核心那边是 helper/prompt 的 Reply）。
type Reply struct {
	Kind ReplyKind `json:"kind"`
	// Feedback 是 deny 的理由：不写时和终端里按 Esc 一样停下这一轮。
	Feedback string `json:"feedback,omitempty"`
	// Mode 是 approve_plan 批准后的权限模式。
	Mode string `json:"mode,omitempty"`
	// Answers 是问题 → 回答，Notes 是问题 → 备注（answer、clarify）。
	Answers map[string]string `json:"answers,omitempty"`
	Notes   map[string]string `json:"notes,omitempty"`
}

// ElicitAction 是对 MCP 表单的回应（conv.elicit 的 action）。
type ElicitAction string

// conv.elicit 的 action。
const (
	ElicitAccept  ElicitAction = "accept"
	ElicitDecline ElicitAction = "decline"
	ElicitCancel  ElicitAction = "cancel"
)

// CodeChanges 是把文件改回某条消息之前会动到的：哪些文件、加减几行（conv.code_changes 的 result）。
type CodeChanges struct {
	Files      []string `json:"files"`
	Insertions int      `json:"insertions"`
	Deletions  int      `json:"deletions"`
}

// TaskOutput 是后台命令输出的最后 8 KiB（conv.task_output 的 result，也是同名推送的字段；核心那边是 helper/tasks 的 Output）。
type TaskOutput struct {
	Text       string `json:"text"`
	TotalBytes int64  `json:"total_bytes"`
	Truncated  bool   `json:"truncated"`
	Error      string `json:"error,omitempty"`
}

// OpenSpec 是 conv.open 的参数，空的不发。
type OpenSpec struct {
	// Session 是接着的会话号，新对话为空；File 是它的会话记录（可省，核心按会话号找）。
	Session, File string
	// Key 是 conv.state 的 key：重新打开核心里的那个对话（还没有会话号的分支断线重连时用）。
	Key     string
	Cwd     string
	Title   string
	Harness string
	// Options 总是发。
	Options Options
	// Choices 是用户在这个对话里选过的，没选过的不发（见 Choices）。
	Choices Choices
}

// Choices 是 conv.open 的 choices（核心那边是 helper/conv 的 Choices）：用户在这个对话里选的模型、思考强度和权限模式，
// 客户端记着，每次打开都带上。还没有会话号的对话，核心收掉它、换了核心或选的那次请求没送到时，靠它补回来；有了会话号核心就不看了。
// nil 是没选过，空串是选回默认。
type Choices struct {
	Model   *string `json:"model,omitempty"`
	Effort  *string `json:"effort,omitempty"`
	Mode    *string `json:"mode,omitempty"`
	Harness *string `json:"harness,omitempty"`
}

// Open 打开这个对话（conv.open）；接着推来整份的 conv.state 和 conv.items，可能比应答先到。
// 带 Key 而那个对话已经不在、又没带会话号时出错，code 为 conversation_gone。
func (v Conv) Open(ctx context.Context, s OpenSpec) error {
	p := v.p(params{"options": s.Options})
	if s.Choices != (Choices{}) {
		p["choices"] = s.Choices
	}
	for k, val := range map[string]string{"session": s.Session, "file": s.File, "key": s.Key, "cwd": s.Cwd, "title": s.Title, "harness": s.Harness} {
		if val != "" {
			p[k] = val
		}
	}
	return call(ctx, v.c, "conv.open", p)
}

// Close 这个客户端放开这个对话（conv.close）。
func (v Conv) Close(ctx context.Context) error { return call(ctx, v.c, "conv.close", v.p(nil)) }

// SetOptions 改起进程用的设置，下次起进程时生效（conv.options）。
func (v Conv) SetOptions(ctx context.Context, o Options) error {
	return call(ctx, v.c, "conv.options", v.p(params{"options": o}))
}

// Send 发一条消息（conv.send）；回答进行中时核心让它排队。
func (v Conv) Send(ctx context.Context, m Outgoing) error {
	return call(ctx, v.c, "conv.send", m.put(v.p(nil)))
}

// Interrupt 停止这一轮，排队的消息撤回（conv.interrupt）。
func (v Conv) Interrupt(ctx context.Context) error { return call(ctx, v.c, "conv.interrupt", v.p(nil)) }

// Steer 立即插话，同终端的 send now（conv.steer）。
func (v Conv) Steer(ctx context.Context) error { return call(ctx, v.c, "conv.steer", v.p(nil)) }

// Cancel 撤回一条排队的消息；recall 时放回输入框（conv.cancel）。
func (v Conv) Cancel(ctx context.Context, uuid string, recall bool) error {
	return call(ctx, v.c, "conv.cancel", v.p(params{"uuid": uuid, "recall": recall}))
}

// TakeRecalled 取走撤回放回输入框的那条（conv.take_recalled）；没有时返回 nil。
func (v Conv) TakeRecalled(ctx context.Context) (*Message, error) {
	return maybe[Message](ctx, v.c, "conv.take_recalled", v.p(nil))
}

// Answer 回应工具确认、计划审批或提问（conv.answer）。
func (v Conv) Answer(ctx context.Context, requestID string, r Reply) error {
	return call(ctx, v.c, "conv.answer", v.p(params{"request_id": requestID, "reply": r}))
}

// Elicit 回应 MCP 表单（conv.elicit）。values 是字段键 → 字符串数组（多选几项就几个，其余一个）；values 为 nil 时不发。
// 有字段不合要求时核心不交出去，problems 是键 → 原因；交出去了 problems 为 nil。
func (v Conv) Elicit(ctx context.Context, requestID string, action ElicitAction, values map[string][]string) (problems map[string]string, err error) {
	p := v.p(params{"request_id": requestID, "action": action})
	if values != nil {
		p["values"] = values
	}
	r, err := maybe[struct {
		Errors map[string]string `json:"errors"`
	}](ctx, v.c, "conv.elicit", p)
	if r != nil && len(r.Errors) > 0 {
		return r.Errors, err
	}
	return nil, err
}

// SetModel 只管这个对话的模型（conv.model）；nil 回到默认。
func (v Conv) SetModel(ctx context.Context, value *string) error {
	return call(ctx, v.c, "conv.model", v.p(params{"value": value}))
}

// SetEffort 只管这个对话的思考强度（conv.effort）；nil 回到默认。
func (v Conv) SetEffort(ctx context.Context, value *string) error {
	return call(ctx, v.c, "conv.effort", v.p(params{"value": value}))
}

// SetMode 只管这个对话的权限模式（conv.mode）；nil 回到默认。
func (v Conv) SetMode(ctx context.Context, value *string) error {
	return call(ctx, v.c, "conv.mode", v.p(params{"value": value}))
}

// SetHarness 切换尚未开始的新对话所用的 CLI 工具（conv.harness）："claude" 或 "agy"。已有会话号或消息时出错。
func (v Conv) SetHarness(ctx context.Context, value string) error {
	return call(ctx, v.c, "conv.harness", v.p(params{"value": value}))
}

// Preset 给这个对话预设模型、思考强度，hint 追加进 --append-system-prompt，下次起进程时生效；空的那项照旧（conv.preset）。
func (v Conv) Preset(ctx context.Context, model, effort, hint string) error {
	return call(ctx, v.c, "conv.preset", v.p(params{"model": model, "effort": effort, "hint": hint}))
}

// Compact 压缩上下文（conv.compact）。
func (v Conv) Compact(ctx context.Context) error { return call(ctx, v.c, "conv.compact", v.p(nil)) }

// Prepare 只起好进程、不发消息（conv.prepare）：commands、models 随 conv.state 到来。
func (v Conv) Prepare(ctx context.Context) error { return call(ctx, v.c, "conv.prepare", v.p(nil)) }

// StopTask 停一个后台任务（conv.stop_task）。
func (v Conv) StopTask(ctx context.Context, task string) error {
	return call(ctx, v.c, "conv.stop_task", v.p(params{"task": task}))
}

// TaskOutput 读一次后台命令的最后 8 KiB（不带 watch 的 conv.task_output）；没有进程时返回 nil。
func (v Conv) TaskOutput(ctx context.Context, task string) (*TaskOutput, error) {
	return maybe[TaskOutput](ctx, v.c, "conv.task_output", v.p(params{"task": task}))
}

// WatchTaskOutput 开始（watch 为真）或停止给这个客户端推后台任务 task 的输出（带 watch 的 conv.task_output）。
func (v Conv) WatchTaskOutput(ctx context.Context, task string, watch bool) error {
	return call(ctx, v.c, "conv.task_output", v.p(params{"task": task, "watch": watch}))
}

// End 结束进程，等于终端里 /exit（conv.end）；对话内容留着，下次发消息时接上。
func (v Conv) End(ctx context.Context) error { return call(ctx, v.c, "conv.end", v.p(nil)) }

// Occupant 是 Zeta 之外占着这个会话的进程的样子（conv.occupant）；没人占着时 Status 为 nil。
func (v Conv) Occupant(ctx context.Context) (sessionproto.Occupancy, error) {
	return get[sessionproto.Occupancy](ctx, v.c, "conv.occupant", v.p(nil))
}

// Handoff 把会话从别处接过来（conv.handoff），要等那边退出，最多十几秒。
func (v Conv) Handoff(ctx context.Context) (sessionproto.Handoff, error) {
	return get[sessionproto.Handoff](ctx, v.c, "conv.handoff", v.p(nil))
}

// MCPReply 是手机工具的应答（conv.mcp_reply），response 原样交给 Claude Code。
func (v Conv) MCPReply(ctx context.Context, requestID string, response json.RawMessage) error {
	return call(ctx, v.c, "conv.mcp_reply", v.p(params{"request_id": requestID, "response": response}))
}

// LoadEarlier 载入更早的一页接到最前面，rest 时载完剩下的全部（conv.earlier）；读完才返回。
// 会话记录在别处改过时出错，code 为 history_changed，重试没用。
func (v Conv) LoadEarlier(ctx context.Context, rest bool) error {
	p := v.p(nil)
	if rest {
		p["rest"] = true
	}
	return call(ctx, v.c, "conv.earlier", p)
}

// Rewrite 编辑：剪掉 target（那条用户消息的条目 id）和之后的内容，再发 m（conv.rewrite）。
func (v Conv) Rewrite(ctx context.Context, target string, m Outgoing) error {
	return call(ctx, v.c, "conv.rewrite", m.put(v.p(params{"target": target})))
}

// Regenerate 重新生成：剪掉 target 和之后的内容，再照它原样重发（带 resend 的 conv.rewrite）。
func (v Conv) Regenerate(ctx context.Context, target string) error {
	return call(ctx, v.c, "conv.rewrite", v.p(params{"target": target, "resend": true}))
}

// Drop 删掉 target 开始的这一轮和之后的，只剪不发（conv.drop）。
func (v Conv) Drop(ctx context.Context, target string) error {
	return call(ctx, v.c, "conv.drop", v.p(params{"target": target}))
}

// CodeChanges 是把文件改回 target 之前会动到哪些（conv.code_changes，只看不改）；没有可还原的时返回 nil。
func (v Conv) CodeChanges(ctx context.Context, target string) (*CodeChanges, error) {
	return maybe[CodeChanges](ctx, v.c, "conv.code_changes", v.p(params{"target": target}))
}

// Restore 是 conv.restore_code 的参数：还原成了以后接着做什么。
type Restore struct {
	// Target 是那条用户消息的条目 id。
	Target string
	// Then 是还原成了再发的新消息（同 Rewrite）；为 nil 时看 Resend、Drop。
	Then *Outgoing
	// Resend：照 Target 原样重发（同 Regenerate）。Drop：没带新消息时删掉这一轮。
	Resend, Drop bool
}

// RestoreCode 把文件改回 r.Target 之前，还原成了再照 r 接着做（conv.restore_code）；结果以列表里的提示告知。
func (v Conv) RestoreCode(ctx context.Context, r Restore) error {
	p := v.p(params{"target": r.Target})
	if r.Then != nil {
		r.Then.put(p)
	}
	if r.Resend {
		p["resend"] = true
	}
	if r.Drop {
		p["drop"] = true
	}
	return call(ctx, v.c, "conv.restore_code", p)
}

// SetTitle 改名（conv.title）：写进会话记录，之后不再自动起标题。还没有会话号时出错。
func (v Conv) SetTitle(ctx context.Context, title string) error {
	return call(ctx, v.c, "conv.title", v.p(params{"title": title}))
}

// ForkSpec 是 conv.fork 的参数。
type ForkSpec struct {
	// As 是新对话在这条连接上的名字，Last 是从这一项所在那一轮之后分支。
	As, Last string
	// Options 是新对话的设置；nil 时沿用这个客户端给原对话的。
	Options *Options
}

// Fork 从 s.Last 所在那一轮之后分支（conv.fork）：新对话由这个客户端以 s.As 拿着，核心推来它的整份，可能比应答先到；原对话不动。
func (v Conv) Fork(ctx context.Context, s ForkSpec) error {
	p := v.p(params{"as": s.As, "last": s.Last})
	if s.Options != nil {
		p["options"] = *s.Options
	}
	return call(ctx, v.c, "conv.fork", p)
}

// Quota 借这个对话开着的进程问一次订阅额度（conv.quota），返回 get_usage 应答的 response 原文，交给 quota.usage 读。
// 没有进程时出错。
func (v Conv) Quota(ctx context.Context) (json.RawMessage, error) {
	return raw(ctx, v.c, "conv.quota", v.p(nil))
}

// WatchSubagent 开始（watch 为真）或停止给这个客户端推 Agent 调用 toolUseID 发起的子代理的记录（conv.subagent）。
func (v Conv) WatchSubagent(ctx context.Context, toolUseID string, watch bool) error {
	return call(ctx, v.c, "conv.subagent", v.p(params{"tool_use_id": toolUseID, "watch": watch}))
}

// WatchActivity 订阅（watch 为真）或退订这个核心里各会话的忙闲（conv.activity，不带 conv），见推送 conv.activity。
func (cl *Client) WatchActivity(ctx context.Context, watch bool) error {
	return call(ctx, cl.c, "conv.activity", params{"watch": watch})
}

// ArtifactInfo 是 Antigravity 会话的一份产物摘要。
type ArtifactInfo struct {
	Name       string `json:"name"`
	Summary    string `json:"summary,omitempty"`
	UserFacing bool   `json:"userFacing,omitempty"`
	UpdatedAt  string `json:"updatedAt,omitempty"`
	Size       int64  `json:"size"`
}

// ArtifactContent 是单份产物的正文内容。
type ArtifactContent struct {
	Name      string `json:"name"`
	Summary   string `json:"summary,omitempty"`
	Content   string `json:"content"`
	Truncated bool   `json:"truncated,omitempty"`
	Size      int64  `json:"size"`
}

// SwarmMember 是 Antigravity 协同工作板中的一位成员。
type SwarmMember struct {
	Name           string `json:"name"`
	Model          string `json:"model,omitempty"`
	Role           string `json:"role,omitempty"`
	Status         string `json:"status"`
	ConversationID string `json:"conversationId,omitempty"`
}

// SwarmState 是 Antigravity 协同工作板状态。
type SwarmState struct {
	Title   string        `json:"title,omitempty"`
	Members []SwarmMember `json:"members"`
}

// SetAgent 设新 Antigravity 对话的 `--agent` 启动项（conv.set_agent）。
func (v Conv) SetAgent(ctx context.Context, agent string) error {
	return call(ctx, v.c, "conv.set_agent", v.p(params{"value": agent}))
}

// SetExtraDirs 设新 Antigravity 对话的 `--add-dir` 附加目录清单（conv.set_extra_dirs）。
func (v Conv) SetExtraDirs(ctx context.Context, dirs []string) error {
	return call(ctx, v.c, "conv.set_extra_dirs", v.p(params{"extra_dirs": dirs}))
}

// SetSandbox 设新 Antigravity 对话的 `--sandbox` 沙箱开关（conv.set_sandbox）。
func (v Conv) SetSandbox(ctx context.Context, sandbox bool) error {
	return call(ctx, v.c, "conv.set_sandbox", v.p(params{"sandbox": sandbox}))
}

// Artifacts 列出 Antigravity 会话的产物摘要（conv.artifacts）。
func (v Conv) Artifacts(ctx context.Context) ([]ArtifactInfo, error) {
	return get[[]ArtifactInfo](ctx, v.c, "conv.artifacts", v.p(nil))
}

// ReadArtifact 读取 Antigravity 会话的一份产物正文（conv.artifact_read）。
func (v Conv) ReadArtifact(ctx context.Context, name string) (ArtifactContent, error) {
	return get[ArtifactContent](ctx, v.c, "conv.artifact_read", v.p(params{"name": name}))
}
