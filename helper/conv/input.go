package conv

import (
	"encoding/json"
	"errors"
	"path"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
)

var (
	errBlocked = errors.New("这个会话正被别处的 Claude Code 占着，先把它接过来")
	errLoading = errors.New("历史还在载入")
)

// Message 是 conv.send 的参数。Mode、Rules 是后台运行（定时任务、通知触发）这一条用的权限模式和叠加的规则，用户自己发的不带。
type Message struct {
	Text   string          `json:"text"`
	Images []Image         `json:"images"`
	Files  []string        `json:"files"`
	Mode   string          `json:"mode"`
	Rules  json.RawMessage `json:"rules"`
}

// empty：没有字（只有空白也算没有）、没有图、没有文件。
func (m Message) empty() bool {
	return strings.TrimSpace(m.Text) == "" && len(m.Images) == 0 && len(m.Files) == 0
}

// isExitCommand：这条消息只是 /exit 或 /quit（去掉首尾空白后），不带图片和文件。
func isExitCommand(text string, m Message) bool {
	return (text == "/exit" || text == "/quit") && len(m.Images) == 0 && len(m.Files) == 0
}

// sendMessage 发一条消息；回答进行中时排队，Claude Code 在下一个工具边界把它并进这一轮，没有可等的就在这一轮结束后另起一轮。
func (c *Conversation) sendMessage(client int, m Message) error {
	text := strings.TrimSpace(m.Text)
	switch {
	case text == "" && len(m.Images) == 0 && len(m.Files) == 0:
		return nil
	case c.blocked:
		return errBlocked
	case c.rewind != nil:
		return errRewinding
	}
	// /exit（别名 /quit，同 Claude Code 2.1.330 的 exit 命令）：照终端里那样结束进程，不发给 Claude Code
	// （它在 stream-json 下答「isn't available in this environment」），也不进会话记录。下次发消息时 --resume 接上
	if isExitCommand(text, m) {
		c.end()
		return nil
	}
	c.startedBy(client)
	// ! 开头的一行：和终端版一样由 Claude Code 在会话的目录里直接执行，命令和输出记进会话，不让模型回答
	if m.Mode == "" && len(m.Images) == 0 && len(m.Files) == 0 && len(text) > 1 && text[0] == '!' {
		c.runShell(strings.TrimSpace(text[1:]), m.Text)
		return nil
	}
	if strings.TrimSpace(c.title) == "" {
		c.title = placeholderTitle(text, m)
		c.autoName.arm(c.title)
	}
	p := c.authorize(m.Mode, m.Rules)
	if p == nil {
		return nil
	}
	c.deliver(p, c.stage(text, m.Images, m.Files))
	return nil
}

// placeholderTitle 是新对话第一句话截出来的临时标题，等 Claude Code 起好标题再换。
func placeholderTitle(text string, m Message) string {
	first, _, _ := strings.Cut(text, "\n")
	if r := []rune(first); len(r) > 40 {
		first = string(r[:40])
	}
	switch {
	case strings.TrimSpace(first) != "":
		return first
	case len(m.Files) > 0:
		return path.Base(strings.TrimSuffix(strings.TrimPrefix(m.Files[0], "@"), "/"))
	}
	return "图片"
}

// authorize：这一条按发的人的权限跑。后台运行的切到它的权限模式、加上它的规则；用户自己的切到用户的、撤掉规则。
// 每条都做，不记上一条是谁发的；模式没变就不发。进程还没起来时按这个模式起。返回进程，起不来时为 nil。
func (c *Conversation) authorize(runMode string, rules json.RawMessage) *process {
	mode := runMode
	if mode == "" {
		mode = c.modes.forUser()
	}
	p := c.proc
	if p == nil {
		if p = c.ensureProcess(mode); p == nil {
			return nil
		}
	} else {
		c.switchMode(p, mode)
	}
	// 后台运行的权限规则走 apply_flag_settings 的 permissions：和用户 settings.json 里的规则叠加，撤时只撤这一层
	if len(rules) == 0 {
		rules = json.RawMessage("null")
	}
	p.request(control("apply_flag_settings", "settings", map[string]any{"permissions": rules}), nil)
	return p
}

func (c *Conversation) switchMode(p *process, mode string) {
	if c.modes.switchTo(mode) {
		p.request(control("set_permission_mode", "mode", mode), nil)
	}
}

// stage：一条要发的消息先进列表（回答进行中时进排队，等 Claude Code 真正取走才进列表）。
func (c *Conversation) stage(text string, images []Image, files []string) *outgoing {
	m := &outgoing{uuid: newUUID(), text: text, images: images, files: files}
	if c.busy {
		c.queued = append(c.queued, m)
	} else {
		m.item = c.r.AddUser(text, m.inlineImages(), files, m.uuid)
	}
	c.interruptRequested = false
	c.status = ""
	// 回答进行中排队的也算有动静（setBusy 只管一轮开始）
	c.lastActivity = c.deps.now()
	c.setBusy()
	c.changed()
	return m
}

func (c *Conversation) transmit(p *process, m *outgoing) {
	if p != c.proc {
		return
	}
	c.unsent = append(c.unsent, m)
	p.link.Send(m.line())
}

// runShell 执行输入栏里 ! 开头的命令（stream-json 的 bash_command）。回答进行中不收，原样放回输入框。
func (c *Conversation) runShell(command, typed string) {
	refuse := map[bool]string{true: "回答进行中不能直接执行命令，等这一轮结束再发"}[c.busy]
	// Claude Code 同时只跑一条 ! 命令，第二条它答「Not run」：先在这里拦下，免得两条的命令和输出配错对
	if refuse == "" && c.shellInput != "" {
		refuse = "上一条 ! 命令还在跑，等它出结果再发"
	}
	if refuse != "" {
		c.recalled = merge(c.recalled, &outgoing{uuid: newUUID(), text: typed})
		c.r.AddNotice(refuse, true)
		c.changed()
		return
	}
	p := c.authorize("", nil)
	if p == nil {
		return
	}
	id := newUUID()
	c.shellCommands[id] = true
	c.interruptRequested = false
	c.status = ""
	c.setBusy()
	b, _ := json.Marshal(map[string]string{"type": "bash_command", "uuid": id, "command": command})
	p.link.Send(b)
	c.changed()
}

// interrupt 停止这一轮：排队的消息一并撤回放回输入框（和终端里按 Esc 一样），等着的请求一并拒绝。
func (c *Conversation) interrupt() {
	c.cancelPhone()
	p := c.proc
	// 改写的消息还在等 Claude Code 剪会话、没有交出去时，没有可打断的
	if p == nil || !c.busy || c.rewind != nil {
		return
	}
	for _, m := range c.queued {
		c.cancelQueued(m.uuid, true)
	}
	c.steering = false
	c.interruptRequested = true
	p.request(control("interrupt"), nil)
	c.dismissPrompts()
	c.changed()
}

// cancelPhone：暂停、接管着的手机操作一并作废，否则放开后它会接着点下去。
func (c *Conversation) cancelPhone() {
	c.broadcast(func(name string) any { return map[string]string{"type": "conv.phone_cancel", "conv": name} }, true)
}

// steer 立即插话，同终端里排队提示上的 send now：排队的消息马上交给 Claude Code，不必等手上的工具跑完。
// Claude Code 认 send_now 时由它决定怎么交：正在跑的工具能挪到后台就挪走，消息并进这一轮，这一轮不停（delivering）；
// 挪不了就停下这一轮，排队的消息接着开始（stopped、interrupted），这时才提示「已打断」。不认的只能发普通的 interrupt。
func (c *Conversation) steer() {
	p := c.proc
	if p == nil || !c.busy || len(c.queued) == 0 || c.steering {
		return
	}
	c.steering = true
	c.changed()
	if !c.sendNow {
		c.stopForSteer()
		p.request(control("interrupt"), nil)
		return
	}
	body := control("interrupt", "send_now", true, "message_uuid", c.queued[0].uuid)
	p.request(body, func(r controlResponse) {
		var answer struct {
			SendNow string `json:"send_now"`
		}
		_ = json.Unmarshal(r.Response, &answer)
		switch answer.SendNow {
		case "delivering":
		case "stopped", "interrupted":
			if c.busy {
				c.stopForSteer()
			}
		default: // nothing_waiting（已经开始了，或已被撤回）、出错
			c.steering = false
		}
		c.changed()
	})
}

// stopForSteer：插话停下了这一轮，以「已打断，先处理插话」收尾；等着的确认和手机操作一并作废。
func (c *Conversation) stopForSteer() {
	c.cancelPhone()
	c.interruptRequested = true
	c.dismissPrompts()
}

// cancelQueued 撤回一条排队的消息；recall 时放回输入框，否则丢掉。已被取走的撤不回，照常出现在列表里。
func (c *Conversation) cancelQueued(id string, recall bool) {
	if c.proc == nil || !slices.ContainsFunc(c.queued, func(m *outgoing) bool { return m.uuid == id }) {
		return
	}
	if recall {
		c.recalling[id] = true
	}
	c.proc.request(control("cancel_async_message", "message_uuid", id), nil)
}

// takeRecalled：输入框取走了撤回的消息。
func (c *Conversation) takeRecalled() *messageView {
	m := c.recalled
	c.recalled = nil
	c.changed()
	if m == nil {
		return nil
	}
	v := m.view()
	return &v
}

// defaultModel 是选择器里 Claude Code 默认模型那一项，也是 set_model、--model 认的写法。
const defaultModel = "default"

// chooseModel：用户在这个对话里选了模型（空是默认模型），只管这个对话。新对话选默认就是不指定；
// 接着已有会话的要明说 default，否则 Claude Code 会照会话记录恢复原来的模型。
func (c *Conversation) chooseModel(value string) {
	chosen := value
	if chosen == defaultModel {
		chosen = ""
	}
	switch {
	case chosen != "":
		c.model = &chosen
	case c.resumed():
		d := defaultModel
		c.model = &d
	default:
		c.model = nil
	}
	// 上次 init 报的模型作废，下一轮的 init 再报实际用的
	c.reportedModel = ""
	if p := c.proc; p != nil {
		if chosen == "" {
			chosen = defaultModel
		}
		p.request(control("set_model", "model", chosen), nil)
		c.queryUsage(p)
	}
	c.changed()
}

// resumed：接着已有的会话（打开时有会话号）。
func (c *Conversation) resumed() bool { return c.openedSession != "" }

// chooseEffort：思考强度只管这个对话，空是回到 Claude Code 自己的设置。进程已经起来就当场改，没起来时起进程的参数带上。
func (c *Conversation) chooseEffort(value string) {
	c.effort = value
	c.changed()
	p := c.proc
	if p == nil {
		return
	}
	var level any
	if value != "" {
		level = value
	}
	p.request(control("apply_flag_settings", "settings", map[string]any{"effortLevel": level}), nil)
	c.querySettings(p)
}

// chooseMode：用户在输入栏选的权限模式，只管这个对话，之后按它跑（后台运行这一轮剩下的部分也是）。
func (c *Conversation) chooseMode(value string) {
	c.modes.user = value
	if p := c.proc; p != nil {
		c.switchMode(p, value)
	}
	c.changed()
}

// preset：定时任务、数字助理给这个对话定的模型、思考强度和追加给系统提示的说明（下次起进程时生效）；空的那项照旧。
func (c *Conversation) preset(model, effort, hint string) {
	c.launchHint = hint
	if model != "" {
		c.model = &model
		if p := c.proc; p != nil {
			p.request(control("set_model", "model", model), nil)
		}
	}
	if effort != "" {
		c.chooseEffort(effort)
	}
	c.changed()
}

// compact 一键压缩：和在输入框里发 /compact 一样，只是列表里不留这条命令。
func (c *Conversation) compact(client int) error {
	switch {
	case c.blocked:
		return errBlocked
	case c.loadingHistory:
		return errLoading
	case c.busy || len(c.r.Items) == 0:
		return nil
	}
	c.startedBy(client)
	p := c.ensureProcess(c.modes.forUser())
	if p == nil {
		return nil
	}
	c.interruptRequested = false
	c.status = "正在压缩上下文"
	c.setBusy()
	p.link.Send(userLine(newUUID(), []map[string]string{{"type": "text", "text": "/compact"}}))
	c.changed()
	return nil
}

// prepare 只把进程起好、不发消息，好让 commands、models 在第一次发消息前就有（输入斜杠命令、看模型清单时）。
// 已有进程、会话被别处占着、历史还在载入、正在剪会话时什么也不做；起不来时和发消息时一样在列表里说明原因。
// 起好的进程闲着，没人拿着时照常满 releaseAfter 断开。
func (c *Conversation) prepare(client int) {
	if c.proc != nil || c.blocked || c.loadingHistory || c.rewind != nil {
		return
	}
	c.startedBy(client)
	c.ensureProcess(c.modes.forUser())
}

// stopTask 停掉后台任务 id；结果看随后的 task_updated、task_notification。
func (c *Conversation) stopTask(id string) {
	p := c.proc
	if p == nil {
		return
	}
	c.tasks.StopRequested(id)
	p.request(control("stop_task", "task_id", id), func(r controlResponse) {
		if c.tasks.StopAnswered(id, raw(r)) {
			c.changed()
		}
	})
	c.changed()
}

// taskOutput 读后台命令、监视器 id 输出的最后 8 KiB；应答到了从返回的通道里给出来（进程不在时马上给 nil）。
func (c *Conversation) taskOutput(id string) <-chan answer {
	out := make(chan answer, 1)
	p := c.proc
	if p == nil {
		out <- answer{}
		return out
	}
	p.request(control("get_task_output", "task_id", id), func(r controlResponse) {
		o := tasks.ParseOutput(raw(r))
		o.Text = tasks.PlainTerminalText(o.Text)
		out <- answer{v: o}
	})
	return out
}

var errNoProcess = errors.New("这个对话没有开着的 Claude Code 进程")

// usageRequest 是问订阅额度的控制请求：skip_behaviors 让 Claude Code 不去扫本机的会话记录，只取额度。
func usageRequest() map[string]any { return control("get_usage", "skip_behaviors", true) }

// queryQuota 借这个对话开着的进程问一次所在主机的订阅额度（get_usage），应答里的 response 原样交回客户端。
func (c *Conversation) queryQuota() <-chan answer {
	out := make(chan answer, 1)
	p := c.proc
	if p == nil {
		out <- answer{err: errNoProcess}
		return out
	}
	p.request(usageRequest(), func(r controlResponse) {
		if !r.ok() {
			out <- answer{err: errors.New("Claude Code 没有给额度：" + r.Error)}
			return
		}
		out <- answer{v: r.Response}
	})
	return out
}
