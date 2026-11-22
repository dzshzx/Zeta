package transcript

import "strings"

// Reducer 把 stream-json 事件归并成 Items。
//
// 实时会话同时收到两种东西：stream_event（逐字增量）和 assistant（一个内容块的完整终稿）。
// 增量用来即时显示，终稿到达后覆盖同一位置的内容。历史记录只有终稿。
type Reducer struct {
	Items []Item

	prefix          string
	streamMessageID *string
	liveByIndex     map[int]*live
	liveOrder       map[string][]*live
	finalizedCount  map[string]int
	localSeq        int
	// 从历史记录载入的内容（消息 id → 文字块），重新接上进程时用来丢掉日志里重复送来的块
	historyTexts map[string]map[string]bool
	knownToolIDs map[string]bool
	// 上一条 ! 命令那一步：等它的输出那行填结果
	shellStep *string
}

type live struct {
	kind      string
	itemID    string
	stepID    string
	startedAt int64
}

// New 建一个归约器；条目编号都以 prefix 开头（同一列表里几段分别归约时彼此错开）。
func New(prefix string) *Reducer {
	return &Reducer{prefix: prefix, liveByIndex: map[int]*live{}, liveOrder: map[string][]*live{},
		finalizedCount: map[string]int{}, historyTexts: map[string]map[string]bool{}, knownToolIDs: map[string]bool{}}
}

func (r *Reducer) nextID(kind string) string {
	r.localSeq++
	return r.prefix + kind + "-" + intToString(r.localSeq)
}

// AddUser 加一条用户消息，返回它的编号。
func (r *Reducer) AddUser(text string, images []Image, files []string, uuid *string) string {
	id := r.nextID("u")
	r.Items = append(r.Items, UserItem{ID: id, Text: text, Images: images, Files: files, UUID: uuid})
	return id
}

// AddNotice 加一条提示。
func (r *Reducer) AddNotice(text string, isError bool) {
	r.Items = append(r.Items, NoticeItem{ID: r.nextID("n"), Text: text, Error: isError})
}

// ApplyShell 处理用户在输入栏执行的命令（TUI 的 !）：Claude Code 记成两条用户消息，
// <bash-input>命令</bash-input> 和 <bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr><bash-exit-code>N</bash-exit-code>，
// 画成单独一组里的一个命令步骤。不是这两种的返回 false。
func (r *Reducer) ApplyShell(text string) bool {
	trimmed := trim(text)
	switch {
	case strings.HasPrefix(trimmed, "<bash-input>"):
		command, _ := tagged(trimmed, "bash-input")
		id := r.nextID("sh")
		step := ToolStep{ID: id, Name: "Bash", Status: ToolRunning,
			Input: obj{"command": command, "description": "! " + firstLine(command)}}
		r.Items = append(r.Items, WorkItem{ID: "w:" + id, Steps: []Step{step}})
		r.shellStep = &id
	case strings.HasPrefix(trimmed, "<bash-stdout>") || strings.HasPrefix(trimmed, "<bash-stderr>"):
		if r.shellStep == nil {
			return true
		}
		id := *r.shellStep
		r.shellStep = nil
		r.finishShell(id, trimmed)
	default:
		return false
	}
	return true
}

func (r *Reducer) finishShell(id, text string) {
	out, _ := tagged(text, "bash-stdout")
	errText, _ := tagged(text, "bash-stderr")
	code := 0
	if raw, ok := tagged(text, "bash-exit-code"); ok {
		if n, ok := integer(obj{"v": trim(raw)}, "v"); ok {
			code = n
		}
	}
	var parts []string
	for _, p := range []string{trimEnd(out), trimEnd(errText)} {
		if p != "" {
			parts = append(parts, p)
		}
	}
	result := strings.Join(parts, "\n")
	if result == "" {
		result = "（没有输出）"
	}
	status := ToolSuccess
	if code != 0 {
		result += "\n退出码 " + intToString(code)
		status = ToolFailed
	}
	r.updateToolStep(id, func(s ToolStep) ToolStep {
		s.Status, s.Result = status, &result
		return s
	})
}

// tagged 是 <tag> 之后到 </tag> 之前的文字（没有 </tag> 就到结尾）；没有 <tag> 时 ok 为假。
func tagged(text, tag string) (string, bool) {
	_, after, ok := strings.Cut(text, "<"+tag+">")
	if !ok {
		return "", false
	}
	before, _, _ := strings.Cut(after, "</"+tag+">")
	return before, true
}

// ApplyStream 处理 stream_event 里的一个事件；now 是收到它的时刻（毫秒），思考用了几秒按它算。
func (r *Reducer) ApplyStream(event obj, now int64) {
	switch strOr(event, "type") {
	case "message_start":
		r.streamMessageID = nil
		if id, ok := str(object(event, "message"), "id"); ok {
			r.streamMessageID = &id
		}
		clear(r.liveByIndex)
	case "content_block_start":
		r.startBlock(event, now)
	case "content_block_delta":
		r.deltaBlock(event)
	case "content_block_stop":
		r.stopBlock(event, now)
	}
}

func (r *Reducer) startBlock(event obj, now int64) {
	if r.streamMessageID == nil {
		return
	}
	messageID := *r.streamMessageID
	index, ok := integer(event, "index")
	block := object(event, "content_block")
	if !ok || block == nil {
		return
	}
	at := messageID + ":" + intToString(index)
	var l *live
	switch strOr(block, "type") {
	case "text":
		r.Items = append(r.Items, AnswerItem{ID: at, Text: strOr(block, "text"), Streaming: true})
		l = &live{kind: "text", itemID: at}
	case "thinking":
		itemID := r.appendStep(ThinkingStep{ID: at, Text: strOr(block, "thinking"), Running: true})
		l = &live{kind: "thinking", itemID: itemID, stepID: at, startedAt: now}
	case "tool_use":
		stepID, ok := str(block, "id")
		if !ok {
			stepID = at
		}
		if r.knownToolIDs[stepID] {
			return
		}
		r.knownToolIDs[stepID] = true
		itemID := r.appendStep(ToolStep{ID: stepID, Name: strOr(block, "name"), Status: ToolRunning})
		l = &live{kind: "tool_use", itemID: itemID, stepID: stepID}
	default:
		return
	}
	r.liveByIndex[index] = l
	r.liveOrder[messageID] = append(r.liveOrder[messageID], l)
}

func (r *Reducer) liveAt(event obj) *live {
	index, ok := integer(event, "index")
	if !ok {
		return nil
	}
	return r.liveByIndex[index]
}

func (r *Reducer) deltaBlock(event obj) {
	l := r.liveAt(event)
	delta := object(event, "delta")
	if l == nil || delta == nil {
		return
	}
	switch strOr(delta, "type") {
	case "text_delta":
		r.updateAnswer(l.itemID, func(a AnswerItem) AnswerItem {
			a.Text += strOr(delta, "text")
			return a
		})
	case "thinking_delta":
		r.updateStep(l, func(s Step) Step {
			if t, ok := s.(ThinkingStep); ok {
				t.Text += strOr(delta, "thinking")
				return t
			}
			return s
		})
	}
}

func (r *Reducer) stopBlock(event obj, now int64) {
	l := r.liveAt(event)
	if l == nil {
		return
	}
	switch l.kind {
	case "text":
		r.updateAnswer(l.itemID, func(a AnswerItem) AnswerItem {
			a.Streaming = false
			return a
		})
	case "thinking":
		r.updateStep(l, func(s Step) Step {
			t, ok := s.(ThinkingStep)
			if !ok || !t.Running {
				return s
			}
			t.Running = false
			t.Seconds = ptr(max(int((now-l.startedAt)/1000), 1))
			return t
		})
	}
}

// ApplyAssistant 处理一条完整的 assistant 消息（实时模式下每个内容块各来一条）。history 表示来自会话记录；
// 之后实时送来的同一块内容会被认出来并丢掉。
func (r *Reducer) ApplyAssistant(message obj, history bool) {
	messageID, ok := str(message, "id")
	if !ok {
		messageID = r.nextID("m")
	}
	order, hasOrder := r.liveOrder[messageID]
	for _, e := range array(message, "content") {
		block, ok := e.(map[string]any)
		if !ok {
			continue
		}
		kind, ok := str(block, "type")
		if !ok {
			continue
		}
		var l *live
		if hasOrder {
			l = r.claimLive(messageID, order, kind)
		}
		switch kind {
		case "text":
			r.finalizeText(messageID, block, l, history)
		case "thinking":
			r.finalizeThinking(messageID, block, l)
		case "tool_use":
			r.finalizeToolUse(messageID, block, l)
		}
	}
}

// claimLive 是这条消息里下一个还没收到终稿、类型为 kind 的增量块；找到就记下已经对到了哪里。
func (r *Reducer) claimLive(messageID string, list []*live, kind string) *live {
	cursor := r.finalizedCount[messageID]
	for cursor < len(list) && list[cursor].kind != kind {
		cursor++
	}
	if cursor >= len(list) {
		return nil
	}
	r.finalizedCount[messageID] = cursor + 1
	return list[cursor]
}

func (r *Reducer) finalizeText(messageID string, block obj, l *live, history bool) {
	text := strOr(block, "text")
	duplicate := !history && r.historyTexts[messageID][text]
	if history {
		if r.historyTexts[messageID] == nil {
			r.historyTexts[messageID] = map[string]bool{}
		}
		r.historyTexts[messageID][text] = true
	}
	switch {
	case l != nil && duplicate:
		r.removeItems(l.itemID)
	case l != nil:
		r.updateAnswer(l.itemID, func(a AnswerItem) AnswerItem {
			a.Text, a.Streaming = text, false
			return a
		})
	case !isBlank(text) && !duplicate:
		r.Items = append(r.Items, AnswerItem{ID: r.nextID(messageID), Text: text})
	}
}

func (r *Reducer) finalizeThinking(messageID string, block obj, l *live) {
	text := strOr(block, "thinking")
	if l != nil {
		r.updateStep(l, func(s Step) Step {
			if t, ok := s.(ThinkingStep); ok && text != "" {
				t.Text = text
				return t
			}
			return s
		})
	} else if !isBlank(text) {
		r.appendStep(ThinkingStep{ID: r.nextID(messageID), Text: text})
	}
}

func (r *Reducer) finalizeToolUse(messageID string, block obj, l *live) {
	input := object(block, "input")
	if l != nil {
		r.updateStep(l, func(s Step) Step {
			if t, ok := s.(ToolStep); ok {
				t.Input = input
				return t
			}
			return s
		})
		return
	}
	stepID, ok := str(block, "id")
	if !ok {
		stepID = r.nextID(messageID)
	}
	if !r.knownToolIDs[stepID] {
		r.knownToolIDs[stepID] = true
		r.appendStep(ToolStep{ID: stepID, Name: strOr(block, "name"), Input: input, Status: ToolRunning})
		return
	}
	r.updateToolStep(stepID, func(t ToolStep) ToolStep {
		if t.Input == nil {
			t.Input = input
		}
		return t
	})
}

// UserOptions 是 ApplyUser 的可选参数：SessionFile 是这条消息所在的会话记录（历史里的图片只带位置，要靠它去取），
// UUID 是它在记录里的 uuid，ToolUseResult 是事件上的 tool_use_result（改文件的工具带 structuredPatch）。
type UserOptions struct {
	SessionFile   *string
	UUID          *string
	ToolUseResult obj
}

// ApplyUser 处理一条 user 消息：工具结果回填到对应步骤；replay 时文字内容是用户当时的输入。
func (r *Reducer) ApplyUser(message obj, replay bool, opts UserOptions) {
	blocks := array(message, "content")
	// 结构化结果是整条消息的；只有一个工具结果时才知道它属于谁
	var patch *EditPatch
	if p := parsePatch(opts.ToolUseResult); p != nil && countToolResults(blocks) == 1 {
		patch = p
	}
	if replay && r.replayUser(blocks, opts) {
		return
	}
	for _, e := range blocks {
		block, ok := e.(map[string]any)
		if !ok || strOr(block, "type") != "tool_result" {
			continue
		}
		toolUseID, ok := str(block, "tool_use_id")
		if !ok {
			continue
		}
		failed, _ := boolean(block, "is_error")
		result := flattenText(block["content"])
		var images []Image
		inner, _ := block["content"].([]any)
		for index, e := range inner {
			b, _ := e.(map[string]any)
			if im := r.imageRef(b, opts.SessionFile, toolUseID+":"+intToString(index)); im != nil {
				images = append(images, *im)
			}
		}
		status := ToolSuccess
		if failed {
			status = ToolFailed
		}
		r.updateToolStep(toolUseID, func(t ToolStep) ToolStep {
			t.Status, t.Result, t.Images, t.Patch = status, &result, images, patch
			return t
		})
	}
}

// replayUser 把会话记录里用户当时的输入加成一条用户消息；是 ! 命令的那两行时画成命令步骤，返回 true（这条消息到此为止）。
func (r *Reducer) replayUser(blocks []any, opts UserOptions) bool {
	var images []Image
	var texts []string
	for _, e := range blocks {
		b, _ := e.(map[string]any)
		if im := r.imageRef(b, opts.SessionFile, ""); im != nil {
			images = append(images, *im)
		}
		if b != nil && strOr(b, "type") == "text" {
			if t, ok := str(b, "text"); ok {
				texts = append(texts, t)
			}
		}
	}
	raw := trim(strings.Join(texts, "\n"))
	if len(images) == 0 && r.ApplyShell(raw) {
		return true
	}
	files, text := splitAttachedFiles(raw)
	if len(images) > 0 || len(files) > 0 || text != "" {
		r.AddUser(text, images, files, opts.UUID)
	}
	return false
}

func countToolResults(blocks []any) int {
	n := 0
	for _, e := range blocks {
		if b, ok := e.(map[string]any); ok && strOr(b, "type") == "tool_result" {
			n++
		}
	}
	return n
}

// imageRef：历史里的图片块只有位置（要知道会话记录才取得到），实时事件里的带着数据。
func (r *Reducer) imageRef(block obj, sessionFile *string, liveKey string) *Image {
	if block == nil || strOr(block, "type") != "image" {
		return nil
	}
	if ref, ok := str(block, "ref"); ok {
		if sessionFile == nil {
			return nil
		}
		return &Image{File: *sessionFile, Ref: ref}
	}
	data, ok := str(object(block, "source"), "data")
	if !ok {
		return nil
	}
	return &Image{Inline: true, Key: r.prefix + liveKey, Data: data}
}

// FinishTurn：一轮结束（或进程退出），把还显示为进行中的内容收口。keepRunningTools 用于载入历史后还要接着跟读的情况：
// 最后那几步其实还在跑。
func (r *Reducer) FinishTurn(keepRunningTools bool) {
	for i, item := range r.Items {
		switch it := item.(type) {
		case AnswerItem:
			it.Streaming = false
			r.Items[i] = it
		case WorkItem:
			if it.Running() {
				steps := make([]Step, len(it.Steps))
				for j, s := range it.Steps {
					steps[j] = finishStep(s, keepRunningTools)
				}
				it.Steps = steps
				r.Items[i] = it
			}
		}
	}
	kept := r.Items[:0]
	for _, item := range r.Items {
		if a, ok := item.(AnswerItem); ok && isBlank(a.Text) {
			continue
		}
		kept = append(kept, item)
	}
	r.Items = kept
}

// finishStep 收口一步：思考停下；还在跑的工具算中断（keepRunningTools 时不动）。
func finishStep(s Step, keepRunningTools bool) Step {
	switch t := s.(type) {
	case ThinkingStep:
		t.Running = false
		return t
	case ToolStep:
		if t.Status == ToolRunning && !keepRunningTools {
			t.Status = ToolFailed
			if t.Result == nil {
				t.Result = ptr("已中断")
			}
		}
		return t
	}
	return s
}

// appendStep 把步骤并入末尾的工作组（没有就新开一组），返回所在工作组的编号。
func (r *Reducer) appendStep(s Step) string {
	if n := len(r.Items); n > 0 {
		if last, ok := r.Items[n-1].(WorkItem); ok {
			last.Steps = append(append([]Step(nil), last.Steps...), s)
			r.Items[n-1] = last
			return last.ID
		}
	}
	id := "w:" + s.StepID()
	r.Items = append(r.Items, WorkItem{ID: id, Steps: []Step{s}})
	return id
}

func (r *Reducer) removeItems(id string) {
	kept := r.Items[:0]
	for _, item := range r.Items {
		if item.ItemID() != id {
			kept = append(kept, item)
		}
	}
	r.Items = kept
}

func (r *Reducer) updateAnswer(itemID string, transform func(AnswerItem) AnswerItem) {
	for i := len(r.Items) - 1; i >= 0; i-- {
		if a, ok := r.Items[i].(AnswerItem); ok && a.ID == itemID {
			r.Items[i] = transform(a)
			return
		}
	}
}

func (r *Reducer) updateStep(l *live, transform func(Step) Step) {
	for i := len(r.Items) - 1; i >= 0; i-- {
		w, ok := r.Items[i].(WorkItem)
		if !ok || w.ID != l.itemID {
			continue
		}
		steps := make([]Step, len(w.Steps))
		for j, s := range w.Steps {
			if s.StepID() == l.stepID {
				s = transform(s)
			}
			steps[j] = s
		}
		w.Steps = steps
		r.Items[i] = w
		return
	}
}

func (r *Reducer) updateToolStep(toolUseID string, transform func(ToolStep) ToolStep) {
	for i := len(r.Items) - 1; i >= 0; i-- {
		w, ok := r.Items[i].(WorkItem)
		if !ok || !hasStep(w, toolUseID) {
			continue
		}
		steps := make([]Step, len(w.Steps))
		for j, s := range w.Steps {
			if t, ok := s.(ToolStep); ok && t.ID == toolUseID {
				s = transform(t)
			}
			steps[j] = s
		}
		w.Steps = steps
		r.Items[i] = w
		return
	}
}

func hasStep(w WorkItem, id string) bool {
	for _, s := range w.Steps {
		if s.StepID() == id {
			return true
		}
	}
	return false
}
