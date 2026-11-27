package conv

import (
	"cmp"
	"encoding/json"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/usage"
)

var errClosed = locales.Err("errConvClosed")

// process 是对话背后接着的那个 Claude Code 进程：托管连接，和这条连接上发出、还在等应答的控制请求。
type process struct {
	link Link
	// startKey 是起进程时的托管标识；第一行输出到时 link.Key() 换了，说明托管脚本没起新进程、接上的是本包已在跑的那个，
	// 起进程时定的用量基线就不算数（usage.Tracker.Joined）。接上已在跑的进程（attach）时为空，不用核
	startKey string
	prefix   string // 请求号的开头：每条连接随机起头再递增，重新接上的进程的输出里可能还有上一条连接的请求的应答
	seq      int
	waiting  map[string]func(controlResponse)
}

// controlResponse 是 control_response 里的 response 一项。
type controlResponse struct {
	Subtype   string          `json:"subtype"`
	RequestID string          `json:"request_id"`
	Error     string          `json:"error"`
	Response  json.RawMessage `json:"response"`
	// initialize 的应答：还没答的工具确认（接回进程时以它为准）
	PendingPermissionRequests []json.RawMessage `json:"pending_permission_requests"`
}

func (r controlResponse) ok() bool { return r.Subtype == "success" }

func newProcess() *process {
	return &process{prefix: "zeta-" + newUUID()[:8] + "-", waiting: map[string]func(controlResponse){}}
}

// requestLine 给 body（control_request 的 request 一项）编上号，组成整行；onResponse 不为空时记下等应答。
func (p *process) requestLine(body any, onResponse func(controlResponse)) []byte {
	p.seq++
	id := p.prefix + strconv.Itoa(p.seq)
	if onResponse != nil {
		p.waiting[id] = onResponse
	}
	b, _ := json.Marshal(map[string]any{"type": "control_request", "request_id": id, "request": body})
	return b
}

// request 发一个控制请求。
func (p *process) request(body any, onResponse func(controlResponse)) {
	p.link.Send(p.requestLine(body, onResponse))
}

// answer：这一行是等着的某个请求的应答就交给它的回调（只调一次），返回 true。
func (p *process) answer(r controlResponse) bool {
	f, ok := p.waiting[r.RequestID]
	if ok {
		delete(p.waiting, r.RequestID)
		f(r)
	}
	return ok
}

// control 是控制请求的 request 一项：subtype 加上别的字段。
func control(subtype string, fields ...any) map[string]any {
	m := map[string]any{"subtype": subtype}
	for i := 0; i+1 < len(fields); i += 2 {
		m[fields[i].(string)] = fields[i+1]
	}
	return m
}

// initializeRequest 声明后台任务页有逐个停止的按钮（接 stop_task）：声明了，停止一轮（interrupt）只停这一轮，
// 后台子代理接着跑；不声明时 Claude Code 会把后台子代理一起结束。一个进程只认第一次 initialize 里的声明。
func initializeRequest() map[string]any { return control("initialize", "perTaskStopAffordance", true) }

// codeRewindEnv 打开 Claude Code 的文件快照，「还原代码」要用。
var codeRewindEnv = map[string]string{"CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING": "1"}

// launchOptions 是现在起进程用的客户端设置。
func (c *Conversation) launchOptions() Options {
	if o, ok := c.options[c.starter]; ok {
		return o
	}
	for client := range c.holders {
		return c.options[client]
	}
	return Options{}
}

// openDesk：远程主机上给这个会话在「Zeta」tmux 会话里开窗口。新会话拿到会话号时开，之后每次起进程、接上进程时再开一次（已经有就不动），
// 这样 tmux 服务器没了（主机重启、被停掉）丢掉的窗口，下次用到那个会话就回来。在后台跑，不等结果。
func (c *Conversation) openDesk() {
	if c.session == "" || c.deps.Desk == nil || !c.launchOptions().Desk {
		return
	}
	go c.deps.Desk(c.session, c.cwd)
}

// phoneHolder：有声明处理手机工具的客户端拿着这个对话。
func (c *Conversation) phoneHolder() bool {
	for client := range c.holders {
		if c.options[client].Phone {
			return true
		}
	}
	return false
}

// imagesHint 告诉模型发图的规矩：用户选的图片，原文件传到主机上、写进消息开头的附件行，客户端另把缩小的一份
// 随消息内联（Image.Original 指明是哪个文件的），orderImages 排在别的图后面、和附件行同序。内联的那份长边只有约 1568，
// 看不清时去读原文件。
const imagesHint = "When the user attaches image files ([Attached file: ...] lines), the message also carries a downscaled copy of each one as an inline image, " +
	"after any other inline images and in the same order as those lines. If the inline copy is not enough for the task (small text, fine detail, exact pixels, metadata), " +
	"read the original file with the Read tool or other tools instead of guessing."

// launchArgs 是起 Claude Code 进程的参数。没指定模型时不带 --model：接着已有会话时 Claude Code 照记录用原来的模型；
// 终端里换过模型、之后还没有回答的指定换成的那个。
func (c *Conversation) launchArgs(mode string, o Options) []string {
	args := []string{"-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
		"--include-partial-messages", "--permission-prompt-tool", "stdio"}
	// 接着原会话；分支则从原会话复制出新会话（发出第一条消息时才写出记录）
	args = append(args, c.sessionArgs()...)
	model := c.switchedInTerminal
	if c.model != nil {
		model = *c.model
	}
	if model != "" {
		args = append(args, "--model", model)
	}
	if mode != "" {
		args = append(args, "--permission-mode", mode)
	}
	// 只是允许之后切到完全放行（没有它 set_permission_mode 切不过去），不放行任何东西
	args = append(args, "--allow-dangerously-skip-permissions")
	if c.effort != "" {
		args = append(args, "--effort", c.effort)
	}
	prompt := strings.TrimSpace(imagesHint + " " + o.SystemPrompt + " " + c.launchHint)
	if prompt != "" {
		args = append(args, "--append-system-prompt", prompt)
	}
	// 手机工具：一个不开端口的 MCP 服务器，消息走控制通道，由声明了 phone 的客户端应答
	if o.MCPConfig != "" && c.phoneHolder() {
		args = append(args, "--mcp-config", o.MCPConfig)
	}
	return args
}

// joinRequests：托管脚本没起新进程、接上了本包已在跑这个会话的进程时先发的请求：
// 起进程的参数里这个对话自己定的权限模式、模型和思考强度，在那个进程上照样定下来。
func (c *Conversation) joinRequests(mode string) []map[string]any {
	var out []map[string]any
	if mode != "" {
		out = append(out, control("set_permission_mode", "mode", mode))
	}
	if c.model != nil {
		out = append(out, control("set_model", "model", *c.model))
	}
	if c.effort != "" {
		out = append(out, control("apply_flag_settings", "settings", map[string]any{"effortLevel": c.effort}))
	}
	return out
}

// ensureProcess：进程没起来就起一个，权限模式是 mode；起来了就直接用，不切模式。起不来时在列表里说明原因、返回 nil。
func (c *Conversation) ensureProcess(mode string) *process {
	if c.proc != nil {
		return c.proc
	}
	c.modes.process = mode
	o := c.launchOptions()
	env := map[string]string{}
	for k, v := range o.Env {
		env[k] = v
	}
	for k, v := range codeRewindEnv {
		env[k] = v
	}
	p := newProcess()
	var join [][]byte
	for _, body := range c.joinRequests(mode) {
		join = append(join, p.requestLine(body, nil))
	}
	link, err := c.deps.Start(StartOptions{Key: newUUID(), Cwd: c.cwd, Title: c.title, Args: c.launchArgs(mode, o), Env: env,
		OnJoin: join}, c.events(p))
	if err != nil {
		c.r.AddNoticeMsg(locales.M("noticeStartFailed", "reason", locales.ErrArg(err)), true)
		c.changed()
		return nil
	}
	p.link = link
	p.startKey = link.Key()
	c.proc = p
	c.processStarted(p, c.carriedUsage(), o.CompactPercent)
	return p
}

// carriedUsage 是要起的进程带着的累计用量（usage.Carried）：新对话从零起；续上会话、分支时是 --resume 的那份会话记录里
// 最后一行 cost-state 记的，Claude Code 从它接着累计。找不到、读不了记录时不知道（nil）。
func (c *Conversation) carriedUsage() *usage.Carried {
	resumed := cmp.Or(c.session, c.forkSource) // 同 sessionArgs
	if resumed == "" {
		return &usage.Carried{}
	}
	if c.deps.SessionFile == nil {
		return nil
	}
	file := c.deps.SessionFile(resumed)
	if file == "" {
		return nil
	}
	carried, err := usage.LastCostState(file)
	if err != nil {
		return nil
	}
	return &carried
}

// events 是交给托管连接的回调：都排进对话的 goroutine，只认还是这个对话的进程的。
func (c *Conversation) events(p *process) Events {
	return Events{
		Line: func(line []byte) { c.do(func() { c.receive(p, line) }) },
		Exit: func(e Exit) { c.do(func() { c.onExit(p, e) }) },
	}
}

// processStarted：刚起或刚接上的进程，要模型清单、斜杠命令清单，问用量，顺便问订阅额度。控制请求按顺序处理，之后发的都排在它后面。
// carried 是它带着的累计用量，不知道时为 nil。
func (c *Conversation) processStarted(p *process, carried *usage.Carried, compactPercent int) {
	c.sendNow = false
	c.openDesk()
	p.request(initializeRequest(), func(r controlResponse) { c.initialized(r) })
	c.usage.ProcessStarted(carried, compactPercent)
	c.queryUsage(p)
	c.autoQueryQuota(p)
	c.changed()
}

// autoQueryQuota：起进程时顺便问一次订阅额度，应答交给 Deps.Quota 存起来，客户端不用再问。多久问一次由 Deps.Quota 定；
// 答的是错误（比如这种登录没有额度可查）时不算数。
func (c *Conversation) autoQueryQuota(p *process) {
	q := c.deps.Quota
	if q == nil || !q.ClaimQuery() {
		return
	}
	p.request(usageRequest(), func(r controlResponse) {
		if r.ok() {
			q.Usage(r.Response)
		}
	})
}

// initializeResponse 是 initialize 应答里用到的部分。
type initializeResponse struct {
	Models                []json.RawMessage `json:"models"`
	Commands              json.RawMessage   `json:"commands"`
	CurrentPermissionMode string            `json:"current_permission_mode"`
}

func (c *Conversation) initialized(r controlResponse) {
	var body initializeResponse
	if json.Unmarshal(r.Response, &body) == nil {
		if body.Models != nil {
			c.models = raw(body.Models)
		}
		if len(body.Commands) > 0 {
			c.commands = visibleCommands(body.Commands)
			c.deps.Commands.Remember(c.cwd, c.commands)
		}
		if body.CurrentPermissionMode != "" {
			c.modes.reportedAtStart(body.CurrentPermissionMode)
		}
	}
	// 接回进程时：还在等的工具确认以 Claude Code 报的为准（输出里看不出答没答过）。MCP 表单不在这份清单里，照读到的留着
	if r.PendingPermissionRequests != nil {
		listed := map[string]bool{}
		var waiting []*prompt.Prompt
		for _, line := range r.PendingPermissionRequests {
			if p := prompt.Parse(line); p != nil && p.Kind == prompt.KindPermission {
				listed[p.RequestID] = true
				waiting = append(waiting, p)
			}
		}
		kept := c.prompts[:0]
		for _, p := range c.prompts {
			if p.Kind != prompt.KindPermission || listed[p.RequestID] {
				kept = append(kept, p)
			}
		}
		c.prompts = kept
		for _, p := range waiting {
			c.enqueuePrompt(p)
		}
	}
	c.changed()
}

// queryUsage 问 Claude Code 上下文窗口和实际生效的思考强度。
func (c *Conversation) queryUsage(p *process) {
	p.request(control("get_context_usage"), func(r controlResponse) {
		if c.usage.ContextUsageAnswered(raw(r)) {
			c.changed()
		}
	})
	c.querySettings(p)
}

func (c *Conversation) querySettings(p *process) {
	p.request(control("get_settings"), func(r controlResponse) {
		if c.usage.SettingsAnswered(raw(r)) {
			c.changed()
		}
	})
}

// 这边不再接着背后的进程，进程照常跑，什么时候结束由看门狗按闲置超时定（Link.Leave）；end 时真结束它（等于终端里 /exit）。
func (c *Conversation) closeProcess(end bool) {
	p := c.proc
	if p == nil {
		return
	}
	c.proc = nil
	c.lastProcKey = p.link.Key()
	c.abortRewind(errClosed.Msg)
	if end {
		p.link.Stop()
	} else {
		p.link.Leave()
	}
	c.endTurnState()
}

// endTurnState：进程没了，这一轮也就结束了。排队的消息放回输入框，后台任务跟着没了，等着的请求作废。
func (c *Conversation) endTurnState() {
	for _, m := range c.queued {
		c.recalled = merge(c.recalled, m)
	}
	c.queued = nil
	clear(c.recalling)
	c.steering = false
	c.busy = false
	c.backgroundTasks = 0
	c.tasks.ProcessEnded(c.deps.now())
	c.status = locales.Msg{}
	c.prompts = nil
	clear(c.shellCommands)
	clear(c.contextNotes)
	c.shellInput = ""
	c.interruptRequested = false
	c.r.FinishTurn(false)
	c.subagentsProcessEnded()
	c.taskOutputsProcessEnded()
	c.changed()
}

// onExit：托管进程的连接到此为止。
func (c *Conversation) onExit(p *process, e Exit) {
	if p != c.proc {
		return
	}
	c.proc = nil
	c.lastProcKey = p.link.Key()
	c.abortRewind(locales.M("rewindProcessExited"))
	// 连接意外断开时进程可能还活着：照样留给看门狗按闲置超时收
	p.link.Leave()
	if e.Reason == reasonReleased || e.Reason == reasonHeldElsewhere {
		c.handedAway(e.Reason == reasonReleased)
		return
	}
	wasBusy := c.busy
	c.endTurnState()
	if wasBusy {
		c.turnFinished(outcomeInterrupted)
	}
	if wasBusy || e.Code != 0 {
		c.r.AddNoticeMsg(exitNotice(e, c.session == "", c.cwd), true)
	}
	c.changed()
}

// exitNotice 是进程意外退出时列表里的那句。
func exitNotice(e Exit, fresh bool, cwd string) locales.Msg {
	detail := strings.TrimSpace(e.Stderr)
	if n := len([]rune(detail)); n > 600 {
		detail = string([]rune(detail)[n-600:])
	}
	switch {
	case strings.Contains(detail, cwdProblemMark):
		return locales.M("noticeNoWorkDir", "cwd", cwd)
	case e.Code == 1 && detail == "" && fresh:
		return locales.M("noticeClaudeNotStarted")
	case e.Code == 143 || e.Code == 137:
		return locales.M("noticeClaudeKilled")
	case detail != "":
		return locales.M("noticeClaudeExitedDetail", "code", strconv.Itoa(e.Code), "detail", detail)
	}
	return locales.M("noticeClaudeExited", "code", strconv.Itoa(e.Code))
}

// cwdProblemMark 是 zeta_host.sh 进不去工作目录时报的那句的开头。
const cwdProblemMark = "zeta: 进不去工作目录"

// handedAway：会话到了别处（终端里接回去了，或要起进程时它正被别处占着）。没送到的消息放回输入框，输入栏锁住，
// 等客户端经交接把会话接过来（conv.handoff，见 handOff）。
func (c *Conversation) handedAway(released bool) {
	c.endTurnState()
	for _, m := range c.unsent {
		if m.item != nil {
			c.r.Remove(m.item)
		}
		c.recalled = merge(c.recalled, m)
	}
	c.unsent = nil
	if released {
		c.r.AddNoticeMsg(locales.M("noticeHandedToTerminal"), false)
	}
	c.block(nil)
}
