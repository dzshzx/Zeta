package conv

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/prompt"
)

var errClosed = errors.New("对话已关闭")

// process 是对话背后接着的那个 Claude Code 进程：托管连接，和这条连接上发出、还在等应答的控制请求。
type process struct {
	link    Link
	prefix  string // 请求号的开头：每条连接随机起头再递增，重新接上的进程的输出里可能还有上一条连接的请求的应答
	seq     int
	waiting map[string]func(controlResponse)
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

// phoneHolder：有声明处理手机工具的客户端拿着这个对话。
func (c *Conversation) phoneHolder() bool {
	for client := range c.holders {
		if c.options[client].Phone {
			return true
		}
	}
	return false
}

// launchArgs 是起 Claude Code 进程的参数。没指定模型时不带 --model：接着已有会话时 Claude Code 照记录用原来的模型；
// 终端里换过模型、之后还没有回答的指定换成的那个。
func (c *Conversation) launchArgs(mode string, o Options) []string {
	args := []string{"-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
		"--include-partial-messages", "--permission-prompt-tool", "stdio"}
	if c.session != "" {
		args = append(args, "--resume", c.session)
	}
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
	prompt := strings.TrimSpace(o.SystemPrompt + " " + c.launchHint)
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
	p := &process{prefix: "zeta-" + newUUID()[:8] + "-", waiting: map[string]func(controlResponse){}}
	var join [][]byte
	for _, body := range c.joinRequests(mode) {
		join = append(join, p.requestLine(body, nil))
	}
	link, err := c.deps.Start(StartOptions{Key: newUUID(), Cwd: c.cwd, Title: c.title, Args: c.launchArgs(mode, o), Env: env,
		OnJoin: join}, c.events(p))
	if err != nil {
		c.r.AddNotice("启动失败："+err.Error(), true)
		c.changed()
		return nil
	}
	p.link = link
	c.proc = p
	c.processStarted(p, c.session == "", o.CompactPercent)
	return p
}

// attach 接上一个还在跑的托管进程（核心重启后打开它的会话时），从输出的第 from 行读起。
func (c *Conversation) attach(key string, from int, mode string) {
	p := &process{prefix: "zeta-" + newUUID()[:8] + "-", waiting: map[string]func(controlResponse){}}
	link, err := c.deps.Attach(key, from, c.events(p))
	if err != nil {
		c.r.AddNotice("重新接上后台进程失败："+err.Error(), true)
		c.changed()
		return
	}
	p.link = link
	c.proc = p
	c.modes.process = mode
	c.processStarted(p, false, c.launchOptions().CompactPercent)
}

// events 是交给托管连接的回调：都排进对话的 goroutine，只认还是这个对话的进程的。
func (c *Conversation) events(p *process) Events {
	return Events{
		Line: func(line []byte) { c.do(func() { c.receive(p, line) }) },
		Exit: func(e Exit) { c.do(func() { c.onExit(p, e) }) },
	}
}

// processStarted：刚起或刚接上的进程，要模型清单、斜杠命令清单，问用量。控制请求按顺序处理，之后发的都排在它后面。
func (c *Conversation) processStarted(p *process, fresh bool, compactPercent int) {
	c.sendNow = false
	p.request(initializeRequest(), func(r controlResponse) { c.initialized(r) })
	c.usage.ProcessStarted(fresh, compactPercent)
	c.queryUsage(p)
	c.changed()
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
			c.commands = body.Commands
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

// detach 这边不再接着背后的进程（别的接着的一方还在时它照常跑）；end 时真结束它（等于终端里 /exit）。
func (c *Conversation) closeProcess(end bool) {
	p := c.proc
	if p == nil {
		return
	}
	c.proc = nil
	if end {
		p.link.Stop()
	} else {
		p.link.Detach()
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
	c.status = ""
	c.prompts = nil
	clear(c.shellCommands)
	c.interruptRequested = false
	c.r.FinishTurn(false)
	c.changed()
}

// onExit：托管进程的连接到此为止。
func (c *Conversation) onExit(p *process, e Exit) {
	if p != c.proc {
		return
	}
	c.proc = nil
	// 连接意外断开时进程可能还活着：没有别的接着的一方就一并结束，免得留下没人管的后台进程
	p.link.Detach()
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
		c.r.AddNotice(exitNotice(e, c.session == "", c.cwd), true)
	}
	c.changed()
}

// exitNotice 是进程意外退出时列表里的那句。
func exitNotice(e Exit, fresh bool, cwd string) string {
	detail := strings.TrimSpace(e.Stderr)
	if n := len([]rune(detail)); n > 600 {
		detail = string([]rune(detail)[n-600:])
	}
	switch {
	case strings.Contains(detail, cwdProblemMark):
		return "Claude Code 没有启动：进不去工作目录 " + cwd + "（没有这个目录，或没有权限）。" +
			"新对话在开始页点工作目录那一行改；默认工作目录在设置里这台设备的页面上改。"
	case e.Code == 1 && detail == "" && fresh:
		return "Claude Code 没能启动"
	case e.Code == 143 || e.Code == 137:
		return "Claude Code 进程被结束了"
	case detail != "":
		return "Claude Code 进程已退出（" + strconv.Itoa(e.Code) + "）\n" + detail
	}
	return "Claude Code 进程已退出（" + strconv.Itoa(e.Code) + "）"
}

// cwdProblemMark 是 zeta_host.sh 进不去工作目录时报的那句的开头。
const cwdProblemMark = "zeta: 进不去工作目录"

// handedAway：会话到了别处（终端里接回去了，或要起进程时它正被别处占着）。没送到的消息放回输入框，输入栏锁住，
// 等客户端经交接把会话接过来（解锁见 Unblock）。
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
		c.r.AddNotice("会话已转到电脑终端继续", false)
	}
	c.blocked = true
	c.changed()
}
