// Package usage 从 Claude Code 的 stream-json 事件（以及会话记录）累计一个对话的上下文占用与花费，
// 算成 Usage 推给客户端（docs/core.md 的 conv.state 的 usage）。各台主机学到的上下文窗口记在 Windows 里。
//
// 用法：起进程时调 ProcessStarted；每行输出事件交给 Observe；get_context_usage、get_settings 的应答交给
// ContextUsageAnswered、SettingsAnswered；载入会话记录时另建一个 Tracker 逐行 ObserveHistory、FinishHistory，
// 再用 PrependHistory 接到实时的那个前面。Version 变了就推一份新的：先把 LearnedWindow 记进 Windows，再 Snapshot。
// Tracker 不加锁，只在对话自己的那个 goroutine 里用。
package usage

import "strconv"

// Tracker 累计一个对话的用量。
//
//   - 上下文占用：主对话最近一次请求的 usage 四项之和（提示词含缓存读写，加上输出）。这正是 Claude Code 判断自动压缩用的数，
//     压缩前的 pre_tokens 与它一致。压缩后的 post_tokens 只算消息、不含系统提示和工具定义（实测压缩后下一次请求比它多两万多），
//     所以压缩后先显示它，再用 get_context_usage 的估算（含这些固定开销）替换，下一次请求的实测数到了再以实测为准。
//   - 每轮用量：result 行的 modelUsage 和 total_cost_usd 是累计（含子代理和压缩请求），相邻两个 result 相减就是这一轮。
//     全新会话的进程从零开始累计；--resume 续上（含 --fork-session 分支）时，Claude Code 从那份会话记录里最后一行 cost-state
//     接着累计，起进程前读同一行就知道第一轮的基线（Carried、LastCostState）。接上已在跑的进程时不知道它之前累计到哪，
//     这时第一轮的基线未知：token 改用 result.usage（只含主对话这一轮），费用只在累计与这一轮完全对得上时才算。
//   - 会话记录里没有费用，从记录恢复的轮次按各条消息的模型和 token 数估（prices）；实时的一轮算不出来时也这样估。
//     子代理的消息不在主记录里：zeta-helper history 在发起子代理的回答后面跟一行 subagent_usage（它和它又派的子代理各模型的
//     token 数），算进那一轮。
type Tracker struct {
	contextTokens *int64
	// estimated：contextTokens 不是请求实测的（还没有、或是压缩后的数），Claude Code 的估算到了就换上。
	estimated         bool
	claudeWindow      *int64
	modelWindow       *int64
	model             string
	turns             []Turn
	compactions       int
	appliedEffort     string
	autoCompact       *bool
	reportedThreshold *int64
	compactPercent    int
	baseTokens        *Tokens
	baseCost          *float64
	// prevTokens、prevCost 是起这个进程之前的基线（上一个进程最后一个 result 的累计），见 Joined
	prevTokens *Tokens
	prevCost   *float64

	// historyTurn 是从会话记录读到的、还没结束的一轮，按消息 id 记（一条消息按内容块拆成几行，每行都带同一份 usage），按第一次出现的先后排。
	historyTurn  []historyMessage
	historyIndex map[string]int

	version       int
	learnedModel  string
	learnedWindow int64
}

// historyMessage 是会话记录里一条消息的 token 数和估出来的费用（估不出来时 cost 为 nil）。
type historyMessage struct {
	tokens Tokens
	cost   *float64
}

// New 建一个空的 Tracker。
func New() *Tracker { return &Tracker{estimated: true, historyIndex: map[string]int{}} }

// Version 每有一次变化加一，调用方据此决定要不要推送。
func (t *Tracker) Version() int { return t.version }

// LearnedWindow 是 Claude Code 最近告诉我们的模型和它的窗口，调用方记进 Windows，给还没起进程的旧会话用。
func (t *Tracker) LearnedWindow() (model string, window int64, ok bool) {
	return t.learnedModel, t.learnedWindow, t.learnedModel != ""
}

func (t *Tracker) changed() bool {
	t.version++
	return true
}

// ProcessStarted：起了一个新进程。carried 是它起来时带着的累计（见 Carried），不知道时为 nil。compactPercent 是给这个进程的
// 自动压缩百分比（CLAUDE_AUTOCOMPACT_PCT_OVERRIDE，0 是没给），Claude Code 报的压缩点不含它，算压缩点要用。
func (t *Tracker) ProcessStarted(carried *Carried, compactPercent int) {
	t.prevTokens, t.prevCost = t.baseTokens, t.baseCost
	t.baseTokens, t.baseCost = nil, nil
	if carried != nil {
		t.baseTokens, t.baseCost = ptr(carried.Tokens), ptr(carried.CostUSD)
	}
	t.compactPercent = compactPercent
	// 新进程的开关和压缩点要等它自己报
	t.autoCompact = nil
	t.reportedThreshold = nil
}

// Joined：托管脚本没起新进程，接上的是已在跑的那个，起进程时给的基线不算数。same 表示它就是这个对话上次接着的那个进程
// （放开连接后又回来）：它的累计接着上次最后一个 result，基线照上次的；不是时不知道它累计到哪，下一轮的基线算未知。
func (t *Tracker) Joined(same bool) {
	t.baseTokens, t.baseCost = nil, nil
	if same {
		t.baseTokens, t.baseCost = t.prevTokens, t.prevCost
	}
}

// message 是 assistant 事件和 message_start 里的消息。
type message struct {
	ID      string    `json:"id"`
	Model   string    `json:"model"`
	Usage   *apiUsage `json:"usage"`
	Content []struct {
		Type string `json:"type"`
	} `json:"content"`
}

type compactMetadata struct {
	Trigger    string `json:"trigger"`
	PreTokens  *int64 `json:"pre_tokens"`
	PostTokens *int64 `json:"post_tokens"`
}

// event 是一行输出事件里用量要看的部分。
type event struct {
	Type            string  `json:"type"`
	Subtype         string  `json:"subtype"`
	ParentToolUseID *string `json:"parent_tool_use_id"`
	Event           *struct {
		Type    string    `json:"type"`
		Message *message  `json:"message"` // message_start
		Usage   *apiUsage `json:"usage"`   // message_delta
	} `json:"event"` // stream_event
	Message         *message         `json:"message"` // assistant、user
	CompactMetadata *compactMetadata `json:"compact_metadata"`
	// result 行
	ModelUsage   map[string]modelUsage `json:"modelUsage"`
	TotalCostUSD *float64              `json:"total_cost_usd"`
	Usage        *apiUsage             `json:"usage"`
	DurationMs   *int64                `json:"duration_ms"`
	// subagent_usage 行（zeta-helper history 跟在发起子代理的回答后面的，见 helper/subagent_usage.go）
	ToolUseID string          `json:"tool_use_id"`
	Models    []subagentModel `json:"models"`
}

// Observe 处理一行实时输出事件，返回用量有没有变化。
func (t *Tracker) Observe(line []byte) bool {
	var e event
	if !decode(line, &e) {
		return false
	}
	main := e.ParentToolUseID == nil
	switch e.Type {
	case "stream_event":
		return main && e.Event != nil && t.observeStream(e.Event.Type, e.Event.Message, e.Event.Usage)
	case "assistant":
		return main && t.observeMessage(e.Message)
	case "result":
		return t.observeResult(&e)
	case "system":
		if e.Subtype != "compact_boundary" {
			return false
		}
		t.compactions++
		t.contextTokens = postTokens(e.CompactMetadata)
		t.estimated = true
		return t.changed()
	case "conversation_reset":
		// /clear 换了新会话，之前的上下文都不在了；Claude Code 的累计也可能跟着清零，下一轮的基线算未知
		t.contextTokens = nil
		t.estimated = true
		t.baseTokens, t.baseCost = nil, nil
		return t.changed()
	}
	return false
}

func postTokens(m *compactMetadata) *int64 {
	if m == nil {
		return nil
	}
	return m.PostTokens
}

func (t *Tracker) observeStream(kind string, m *message, u *apiUsage) bool {
	switch kind {
	case "message_start":
		return t.observeMessage(m)
	case "message_delta":
		// 消息结束时的 usage 是最终数（含完整的输出）
		if tokens, ok := u.tokens(); ok {
			return t.setContext(tokens.Total())
		}
	}
	return false
}

func (t *Tracker) observeMessage(m *message) bool {
	if m == nil {
		return false
	}
	if m.Model != "" {
		t.model = m.Model
	}
	if tokens, ok := m.Usage.tokens(); ok {
		return t.setContext(tokens.Total())
	}
	return false
}

func (t *Tracker) setContext(tokens int64) bool {
	if tokens <= 0 {
		return false
	}
	t.estimated = false
	if t.contextTokens != nil && *t.contextTokens == tokens {
		return false
	}
	t.contextTokens = ptr(tokens)
	return t.changed()
}

func (t *Tracker) observeResult(e *event) bool {
	cumulative, hasCumulative := sumModels(e.ModelUsage)
	own, _ := e.Usage.tokens()
	baseKnown := t.baseTokens != nil && hasCumulative && cumulative.Covers(*t.baseTokens)
	tokens := own
	if baseKnown {
		tokens = cumulative.Sub(*t.baseTokens)
	}
	var turnCost *float64
	switch cost := e.TotalCostUSD; {
	case cost == nil:
	case baseKnown:
		if t.baseCost != nil && *cost >= *t.baseCost {
			turnCost = ptr(*cost - *t.baseCost)
		}
	case hasCumulative && cumulative == own:
		turnCost = ptr(*cost)
	}
	if hasCumulative {
		t.baseTokens = ptr(cumulative)
	}
	if e.TotalCostUSD != nil {
		t.baseCost = ptr(*e.TotalCostUSD)
	}
	if w := t.windowOf(e.ModelUsage); w != nil {
		t.modelWindow = w
	}
	// 基线未知的一轮：token 用的是 result.usage（只含主对话这一轮），照它估
	estimated := false
	if turnCost == nil && !baseKnown {
		if usd, ok := estimateCost(t.model, e.Usage); ok {
			turnCost, estimated = ptr(usd), true
		}
	}
	// 斜杠命令（/context 之类）也会出一个 result，但没有请求模型
	if tokens.Total() > 0 || (turnCost != nil && *turnCost > 0) {
		t.turns = append(t.turns, Turn{Tokens: tokens, CostUSD: turnCost, DurationMs: e.DurationMs, CostEstimated: estimated})
	}
	return t.changed()
}

// windowOf 是 modelUsage 里主对话模型的 contextWindow，没有时取各模型里最大的。
func (t *Tracker) windowOf(models map[string]modelUsage) *int64 {
	if m, ok := models[t.model]; ok && t.model != "" && m.ContextWindow != nil {
		return ptr(*m.ContextWindow)
	}
	var widest *int64
	for _, m := range models {
		if m.ContextWindow != nil && (widest == nil || *m.ContextWindow > *widest) {
			widest = ptr(*m.ContextWindow)
		}
	}
	return widest
}

// controlAnswer 是控制请求的应答（control_response 里的 response 一项）。
type controlAnswer[T any] struct {
	Response *T `json:"response"`
}

type contextUsage struct {
	TotalTokens          *int64 `json:"totalTokens"`
	MaxTokens            *int64 `json:"maxTokens"`
	Model                string `json:"model"`
	IsAutoCompactEnabled *bool  `json:"isAutoCompactEnabled"`
	AutoCompactThreshold *int64 `json:"autoCompactThreshold"`
}

// ContextUsageAnswered 处理 get_context_usage 的应答（control_response 里的 response 一项），返回用量有没有变化。
func (t *Tracker) ContextUsageAnswered(answer []byte) bool {
	var a controlAnswer[contextUsage]
	if !decode(answer, &a) || a.Response == nil {
		return false
	}
	r := a.Response
	if r.MaxTokens == nil || *r.MaxTokens <= 0 {
		return false
	}
	t.claudeWindow = ptr(*r.MaxTokens)
	if r.Model != "" {
		t.model = r.Model
		t.learnedModel, t.learnedWindow = r.Model, *r.MaxTokens
	}
	// 还没有实测数时（刚起的新会话、刚压缩完）用 Claude Code 的估算，它含系统提示、工具定义这些固定开销
	if t.estimated && r.TotalTokens != nil && *r.TotalTokens > 0 {
		t.contextTokens = ptr(*r.TotalTokens)
	}
	// 自动压缩关着时没有 autoCompactThreshold
	if r.IsAutoCompactEnabled != nil {
		t.autoCompact = ptr(*r.IsAutoCompactEnabled)
		t.reportedThreshold = nil
		if *r.IsAutoCompactEnabled && r.AutoCompactThreshold != nil && *r.AutoCompactThreshold > 0 {
			t.reportedThreshold = ptr(*r.AutoCompactThreshold)
		}
	}
	return t.changed()
}

type settings struct {
	Applied *struct {
		Effort string `json:"effort"`
	} `json:"applied"`
}

// SettingsAnswered 处理 get_settings 的应答：实际生效的思考强度。返回有没有变化。
func (t *Tracker) SettingsAnswered(answer []byte) bool {
	var a controlAnswer[settings]
	if !decode(answer, &a) || a.Response == nil || a.Response.Applied == nil {
		return false
	}
	if a.Response.Applied.Effort == t.appliedEffort {
		return false
	}
	t.appliedEffort = a.Response.Applied.Effort
	return t.changed()
}

// ObserveHistory 处理会话记录里的一行（形状同 stream-json）。读完后调 FinishHistory。
func (t *Tracker) ObserveHistory(line []byte) {
	var e event
	if !decode(line, &e) {
		return
	}
	switch e.Type {
	case "user":
		// 用户亲手发的消息（不是工具结果）开始新的一轮
		if e.Message != nil && typedByUser(e.Message) {
			t.flushHistoryTurn()
		}
	case "assistant":
		t.historyMessage(e.Message)
	case "subagent_usage":
		t.historySubagent(e.ToolUseID, e.Models)
	case "system":
		if e.Subtype == "compact_boundary" {
			t.compactions++
			t.contextTokens = postTokens(e.CompactMetadata)
			t.estimated = true
		}
	}
}

func typedByUser(m *message) bool {
	for _, b := range m.Content {
		if b.Type == "text" || b.Type == "image" {
			return true
		}
	}
	return false
}

func (t *Tracker) historyMessage(m *message) {
	if m == nil {
		return
	}
	if m.Model != "" {
		t.model = m.Model
	}
	tokens, ok := m.Usage.tokens()
	if !ok {
		return
	}
	var cost *float64
	if usd, ok := estimateCost(m.Model, m.Usage); ok {
		cost = ptr(usd)
	}
	id := m.ID
	if id == "" {
		id = "m" + strconv.Itoa(len(t.historyTurn))
	}
	t.addHistoryMessage(id, historyMessage{tokens, cost})
	if tokens.Total() > 0 {
		t.contextTokens = ptr(tokens.Total())
		t.estimated = false
	}
}

// subagentModel 是 subagent_usage 行里一个模型的 token 数。
type subagentModel struct {
	Model string    `json:"model"`
	Usage *apiUsage `json:"usage"`
}

// historySubagent 把一次 Agent 调用发起的子代理（连同它又派的）的用量算进这一轮，按各自的模型估费用；
// 上下文占用只看主对话，不动。
func (t *Tracker) historySubagent(toolUseID string, models []subagentModel) {
	for _, m := range models {
		tokens, ok := m.Usage.tokens()
		if !ok {
			continue
		}
		var cost *float64
		if usd, ok := estimateCost(m.Model, m.Usage); ok {
			cost = ptr(usd)
		}
		t.addHistoryMessage("subagent:"+toolUseID+":"+m.Model, historyMessage{tokens, cost})
	}
}

// addHistoryMessage 记下这一轮里的一条消息；同一个 id 再来时替换。
func (t *Tracker) addHistoryMessage(id string, msg historyMessage) {
	if i, seen := t.historyIndex[id]; seen {
		t.historyTurn[i] = msg
	} else {
		t.historyIndex[id] = len(t.historyTurn)
		t.historyTurn = append(t.historyTurn, msg)
	}
}

// FinishHistory 结束会话记录的读取：最后一轮也算上。
func (t *Tracker) FinishHistory() {
	t.flushHistoryTurn()
	t.changed()
}

func (t *Tracker) flushHistoryTurn() {
	if len(t.historyTurn) == 0 {
		return
	}
	var tokens Tokens
	cost := ptr(0.0)
	for _, m := range t.historyTurn {
		tokens = tokens.Add(m.tokens)
		// 有一条消息估不出来，这一轮就不报费用
		if m.cost == nil || cost == nil {
			cost = nil
		} else {
			*cost += *m.cost
		}
	}
	t.historyTurn = t.historyTurn[:0]
	clear(t.historyIndex)
	if tokens.Total() > 0 {
		t.turns = append(t.turns, Turn{Tokens: tokens, CostUSD: cost, CostEstimated: cost != nil})
	}
}

// PrependHistory 把载入的历史接到前面：历史的轮次在前；上下文占用、模型以已经收到的实时数据为准。
func (t *Tracker) PrependHistory(history *Tracker) {
	t.turns = append(append([]Turn(nil), history.turns...), t.turns...)
	if t.contextTokens == nil {
		t.contextTokens = history.contextTokens
		t.estimated = history.estimated
	}
	if t.model == "" {
		t.model = history.model
	}
	t.compactions += history.compactions
	t.changed()
}

// Snapshot 是现在的用量。known 是这台主机上以前学到的各模型窗口，这个对话自己还没拿到窗口时用；可以是 nil。
func (t *Tracker) Snapshot(known *Windows) Usage {
	u := Usage{
		ContextTokens:    copyPtr(t.contextTokens),
		ContextEstimated: t.estimated && t.contextTokens != nil,
		Model:            t.model,
		Turns:            append([]Turn(nil), t.turns...),
		Compactions:      t.compactions,
		AppliedEffort:    t.appliedEffort,
		AutoCompact:      copyPtr(t.autoCompact),
		CompactAt:        compactThreshold(t.reportedThreshold, t.compactPercent),
	}
	switch w, ok := known.Window(t.model); {
	case t.claudeWindow != nil:
		u.ContextWindow = ptr(*t.claudeWindow)
	case t.model != "" && ok:
		u.ContextWindow = ptr(w)
	default:
		u.ContextWindow = copyPtr(t.modelWindow)
	}
	u.fill()
	return u
}

func copyPtr[T any](p *T) *T {
	if p == nil {
		return nil
	}
	return ptr(*p)
}
