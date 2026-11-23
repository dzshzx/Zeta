package transcript

import (
	"github.com/dzshzx/Zeta/helper/locales"
	"strconv"
	"strings"
)

// Reducer 把 stream-json 事件归并成 Items。
//
// 实时会话同时收到两种东西：stream_event（逐字增量）和 assistant（一个内容块的完整终稿）。增量用来即时显示，
// 终稿到达后覆盖同一位置的内容。会话记录里只有终稿。条目都是指针，归约器原地改，改一次 Rev 加一。
type Reducer struct {
	Items []*Item

	prefix string
	seq    int

	message string                  // 正在流式送来的消息的 id；没有时为空
	live    map[int]*liveBlock      // 这条消息里各下标的增量块
	order   map[string][]*liveBlock // 各条消息的增量块，按到达顺序：终稿按种类依次认领
	claimed map[string]int          // 各条消息的终稿已经认领到第几个增量块

	tools map[string]stepRef // 工具调用 id → 它那一步
	// 会话记录里载入的回答（消息 id → 文字）：重新接上进程时，输出日志会把同样的块再送一遍
	historyTexts map[string]map[string]bool
	shell        *stepRef // 上一条 ! 命令那一步，等它的输出那行填结果
}

type liveBlock struct {
	kind    string // text、thinking、tool_use
	item    *Item
	step    *Step // thinking（还没有文字、在工作组里时）、tool_use
	started int64 // thinking：开始的时刻（毫秒）
}

type stepRef struct {
	item *Item
	step *Step
}

// New 建一个归约器；条目编号都以 prefix 开头（同一列表里几段分别归约时彼此错开）。
func New(prefix string) *Reducer {
	return &Reducer{prefix: prefix, live: map[int]*liveBlock{}, order: map[string][]*liveBlock{},
		claimed: map[string]int{}, tools: map[string]stepRef{}, historyTexts: map[string]map[string]bool{}}
}

func (r *Reducer) nextID(kind string) string {
	r.seq++
	return r.prefix + kind + "-" + strconv.Itoa(r.seq)
}

func touch(it *Item) { it.rev++ }

// AddUser 加一条用户消息。
func (r *Reducer) AddUser(text string, images []Image, files []string, uuid string) *Item {
	it := &Item{Kind: KindUser, ID: r.nextID("u"), Text: text, Images: images, Files: files, UUID: uuid}
	r.Items = append(r.Items, it)
	return it
}

// AddNotice 加一条提示（原文，比如 Claude Code 给的出错，不翻译）。
func (r *Reducer) AddNotice(text string, isError bool) *Item {
	it := &Item{Kind: KindNotice, ID: r.nextID("n"), Text: text, Error: isError}
	r.Items = append(r.Items, it)
	return it
}

// AddNoticeMsg 加一条核心自己说的提示：客户端按种类码出字，Text 是源语言。
func (r *Reducer) AddNoticeMsg(m locales.Msg, isError bool) *Item {
	it := r.AddNotice(m.String(), isError)
	it.Code, it.Args = m.Key, m.Args
	return it
}

// appendStep 把一步并进末尾的工作组（末尾不是工作组时新开一组），返回所在的组。
func (r *Reducer) appendStep(s *Step) *Item {
	if n := len(r.Items); n > 0 && r.Items[n-1].Kind == KindWork {
		last := r.Items[n-1]
		last.Steps = append(last.Steps, s)
		touch(last)
		return last
	}
	it := &Item{Kind: KindWork, ID: "w:" + s.ID, Steps: []*Step{s}}
	r.Items = append(r.Items, it)
	return it
}

// ApplyStream 处理 stream_event 里的一个事件；now 是收到它的时刻（毫秒），思考用了几秒按它算。
func (r *Reducer) ApplyStream(e StreamEvent, now int64) {
	switch e.Type {
	case "message_start":
		r.message = ""
		if e.Message != nil {
			r.message = e.Message.ID
		}
		clear(r.live)
	case "content_block_start":
		r.startBlock(e, now)
	case "content_block_delta":
		r.deltaBlock(e)
	case "content_block_stop":
		r.stopBlock(e, now)
	}
}

func (r *Reducer) startBlock(e StreamEvent, now int64) {
	if r.message == "" || e.Index == nil || e.ContentBlock == nil {
		return
	}
	b := e.ContentBlock
	at := r.message + ":" + strconv.Itoa(*e.Index)
	var lb *liveBlock
	switch b.Type {
	case "text":
		it := &Item{Kind: KindAnswer, ID: at, Text: b.Text, Streaming: true}
		r.Items = append(r.Items, it)
		lb = &liveBlock{kind: b.Type, item: it}
	case "thinking":
		if strings.TrimSpace(b.Thinking) != "" {
			it := &Item{Kind: KindThought, ID: at, Text: b.Thinking, Streaming: true}
			r.Items = append(r.Items, it)
			lb = &liveBlock{kind: b.Type, item: it}
			break
		}
		s := &Step{Kind: StepThinking, ID: at, Text: b.Thinking, Running: true}
		lb = &liveBlock{kind: b.Type, item: r.appendStep(s), step: s, started: now}
	case "tool_use":
		id := b.ID
		if id == "" {
			id = at
		}
		if _, known := r.tools[id]; known {
			return
		}
		s := &Step{Kind: StepTool, ID: id, Name: b.Name, Status: ToolRunning}
		lb = &liveBlock{kind: b.Type, item: r.appendStep(s), step: s}
		r.tools[id] = stepRef{lb.item, s}
	default:
		return
	}
	r.live[*e.Index] = lb
	r.order[r.message] = append(r.order[r.message], lb)
}

func (r *Reducer) liveAt(e StreamEvent) *liveBlock {
	if e.Index == nil {
		return nil
	}
	return r.live[*e.Index]
}

func (r *Reducer) deltaBlock(e StreamEvent) {
	lb := r.liveAt(e)
	if lb == nil || e.Delta == nil {
		return
	}
	switch {
	case e.Delta.Type == "text_delta" && lb.kind == "text":
		lb.item.Text += e.Delta.Text
	case e.Delta.Type == "thinking_delta" && lb.kind == "thinking" && lb.step == nil:
		lb.item.Text += e.Delta.Thinking
	case e.Delta.Type == "thinking_delta" && lb.kind == "thinking":
		lb.step.Text += e.Delta.Thinking
		if strings.TrimSpace(lb.step.Text) != "" {
			r.toThought(lb)
			return
		}
	default:
		return
	}
	touch(lb.item)
}

func (r *Reducer) stopBlock(e StreamEvent, now int64) {
	lb := r.liveAt(e)
	switch {
	case lb == nil:
		return
	case lb.kind == "text", lb.kind == "thinking" && lb.step == nil:
		lb.item.Streaming = false
	case lb.kind == "thinking" && lb.step.Running:
		lb.step.Running = false
		lb.step.Seconds = max(int((now-lb.started)/1000), 1)
	default:
		return
	}
	touch(lb.item)
}

// ApplyAssistant 处理一条完整的 assistant 消息（实时会话里每个内容块各来一条）。history 表示来自会话记录：
// 之后实时送来的同一块内容会被认出来丢掉。
func (r *Reducer) ApplyAssistant(m Message, history bool) {
	id := m.ID
	if id == "" {
		id = r.nextID("m")
	}
	order := r.order[id]
	for _, b := range m.Content.Blocks {
		lb := r.claim(id, order, b.Type)
		switch b.Type {
		case "text":
			r.finalizeText(id, b, lb, history)
		case "thinking":
			r.finalizeThinking(id, b, lb)
		case "tool_use":
			r.finalizeToolUse(id, b, lb)
		}
	}
}

// claim 是这条消息里下一个还没收到终稿、种类为 kind 的增量块；找到就记下认领到了哪里。
func (r *Reducer) claim(id string, order []*liveBlock, kind string) *liveBlock {
	for i := r.claimed[id]; i < len(order); i++ {
		if order[i].kind == kind {
			r.claimed[id] = i + 1
			return order[i]
		}
	}
	return nil
}

func (r *Reducer) finalizeText(id string, b Block, lb *liveBlock, history bool) {
	duplicate := !history && r.historyTexts[id][b.Text]
	if history {
		if r.historyTexts[id] == nil {
			r.historyTexts[id] = map[string]bool{}
		}
		r.historyTexts[id][b.Text] = true
	}
	switch {
	case lb != nil && duplicate:
		r.Remove(lb.item)
	case lb != nil:
		lb.item.Text, lb.item.Streaming = b.Text, false
		touch(lb.item)
	case !duplicate && strings.TrimSpace(b.Text) != "":
		r.Items = append(r.Items, &Item{Kind: KindAnswer, ID: r.nextID(id), Text: b.Text})
	}
}

func (r *Reducer) finalizeThinking(id string, b Block, lb *liveBlock) {
	switch {
	case lb != nil && lb.step == nil:
		if b.Thinking != "" {
			lb.item.Text = b.Thinking
		}
		lb.item.Streaming = false
		touch(lb.item)
	case lb != nil && b.Thinking != "":
		lb.step.Text = b.Thinking
		touch(lb.item)
		if strings.TrimSpace(b.Thinking) != "" {
			r.toThought(lb)
			lb.item.Streaming = false
		}
	case lb == nil && strings.TrimSpace(b.Thinking) != "":
		r.Items = append(r.Items, &Item{Kind: KindThought, ID: r.nextID(id), Text: b.Thinking})
	}
}

// toThought 把一段思考从工作组里拿出来，换成单独的中间说明（它有了文字，见 KindThought）。这一段是正在送来的那块，
// 所在的工作组在列表末尾，说明接在它后面；组里只有它时整组去掉。之后的工具调用另开一组。
func (r *Reducer) toThought(lb *liveBlock) {
	group, s := lb.item, lb.step
	for i, x := range group.Steps {
		if x == s {
			group.Steps = append(group.Steps[:i], group.Steps[i+1:]...)
			break
		}
	}
	if len(group.Steps) == 0 {
		r.Remove(group)
	} else {
		touch(group)
	}
	it := &Item{Kind: KindThought, ID: s.ID, Text: s.Text, Streaming: s.Running}
	r.Items = append(r.Items, it)
	lb.item, lb.step = it, nil
}

func (r *Reducer) finalizeToolUse(id string, b Block, lb *liveBlock) {
	input := b.input()
	if lb != nil {
		lb.step.Input, lb.step.Clipped = input, b.Clipped
		touch(lb.item)
		return
	}
	stepID := b.ID
	if stepID == "" {
		stepID = r.nextID(id)
	}
	ref, known := r.tools[stepID]
	switch {
	case !known:
		s := &Step{Kind: StepTool, ID: stepID, Name: b.Name, Input: input, Clipped: b.Clipped, Status: ToolRunning}
		r.tools[stepID] = stepRef{r.appendStep(s), s}
	case ref.step.Input == nil:
		ref.step.Input, ref.step.Clipped = input, b.Clipped
		touch(ref.item)
	}
}

// UserOptions 是 ApplyUser 的可选参数。
type UserOptions struct {
	SessionFile string         // 这条消息所在的会话记录：历史里的图片只带位置，要靠它去取
	UUID        string         // 这条消息在记录里的 uuid
	Peer        string         // 另一个会话发来的（会话互通）时，发信会话的名字
	Result      *ToolUseResult // 事件上的 tool_use_result
}

// ApplyUser 处理一条 user 消息：工具结果回填到对应的那一步；replay（会话记录里的）时文字内容是用户当时的输入。
func (r *Reducer) ApplyUser(m Message, replay bool, opts UserOptions) {
	blocks := m.Content.Blocks
	if replay && r.replayUser(blocks, opts) {
		return
	}
	// 结构化的改动是整条消息的，只有一个工具结果时才知道它属于谁
	var patch *Patch
	if countResults(blocks) == 1 {
		patch = parsePatch(opts.Result)
	}
	for _, b := range blocks {
		if b.Type != "tool_result" || b.ToolUseID == "" {
			continue
		}
		ref, ok := r.tools[b.ToolUseID]
		if !ok {
			continue
		}
		var images []Image
		for i, inner := range b.Content.Blocks {
			if im := r.image(inner, opts.SessionFile, b.ToolUseID+":"+strconv.Itoa(i)); im != nil {
				images = append(images, *im)
			}
		}
		s := ref.step
		s.Status, s.Result, s.Images, s.Patch = ToolSuccess, b.Content.Flatten(), images, patch
		if b.IsError {
			s.Status = ToolFailed
		}
		touch(ref.item)
	}
}

// replayUser 把会话记录里用户当时的输入加成一条用户消息；是 ! 命令的那两行时画成命令那一步，返回 true（这条消息到此为止）。
func (r *Reducer) replayUser(blocks []Block, opts UserOptions) bool {
	var images []Image
	var texts []string
	for _, b := range blocks {
		if im := r.image(b, opts.SessionFile, ""); im != nil {
			images = append(images, *im)
		}
		if b.Type == "text" {
			texts = append(texts, b.Text)
		}
	}
	raw := strings.TrimSpace(strings.Join(texts, "\n"))
	if len(images) == 0 && r.ApplyShell(raw) {
		return true
	}
	files, text := splitAttachedFiles(raw)
	if len(images) > 0 || len(files) > 0 || text != "" {
		r.AddUser(text, images, files, opts.UUID).Peer = opts.Peer
	}
	return false
}

func countResults(blocks []Block) int {
	n := 0
	for _, b := range blocks {
		if b.Type == "tool_result" {
			n++
		}
	}
	return n
}

// image：会话记录里的图片块只有位置（知道是哪份记录才取得到），实时事件里的带着数据。
func (r *Reducer) image(b Block, sessionFile, liveKey string) *Image {
	switch {
	case b.Type != "image":
		return nil
	case b.Ref != "":
		if sessionFile == "" {
			return nil
		}
		return &Image{Kind: "session", File: sessionFile, Ref: b.Ref}
	case b.Source.Data != "":
		return &Image{Kind: "inline", Key: r.prefix + liveKey, Data: b.Source.Data}
	}
	return nil
}

// FinishTurn：一轮结束（或进程退出），把还显示为进行中的内容收口，去掉空的回答。keepRunningTools 用于载入历史后
// 还要接着跟读进程的情况：最后那几步其实还在跑。
func (r *Reducer) FinishTurn(keepRunningTools bool) {
	kept := r.Items[:0]
	for _, it := range r.Items {
		switch {
		case (it.Kind == KindAnswer || it.Kind == KindThought) && it.Streaming:
			it.Streaming = false
			touch(it)
		case it.Kind == KindWork && it.Running():
			for _, s := range it.Steps {
				finishStep(s, keepRunningTools)
			}
			touch(it)
		}
		if it.Kind == KindAnswer && strings.TrimSpace(it.Text) == "" {
			continue
		}
		kept = append(kept, it)
	}
	clear(r.Items[len(kept):])
	r.Items = kept
}

// finishStep 收口一步：思考停下；还在跑的工具算中断（keepRunningTools 时不动）。
func finishStep(s *Step, keepRunningTools bool) {
	s.Running = false
	if s.Status == ToolRunning && !keepRunningTools {
		s.Status = ToolFailed
		if s.Result == "" {
			s.Result = "已中断"
		}
	}
}

// Remove 从列表里去掉 it。
func (r *Reducer) Remove(it *Item) {
	for i, x := range r.Items {
		if x == it {
			r.Items = append(r.Items[:i], r.Items[i+1:]...)
			return
		}
	}
}
