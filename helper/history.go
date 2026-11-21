package main

import (
	"fmt"
	"strings"
)

// 历史
//
// 打开对话时先载入最近的一段（maxEvents 条），往上翻到顶附近时界面再要前面的一页（historyPage 条）；
// 对话内查找时一次要走剩下的全部（all），整份记录只读一遍。
// 每次都从头读整份记录：剪切记号可能在后面任何地方，要照它去掉被剪的部分，才知道当前这条线上第几条是什么。
// 剪切只去掉某条用户消息之后的内容，新内容只接在后面，所以已经交给界面的那段之前的部分不会变；
// 界面要前一页时报上它手里最前面那段从第几条开始、前一条是哪一行（uuid），对不上（比如在终端里剪到了更早的地方）就不给。
// 一页的开头尽量落在一轮的开头（用户亲手发的消息或压缩提示），不能把工具调用和它的结果分到两页：结果要回填到调用那一步上。

const (
	maxEvents     = 800  // 打开对话时先载入最近这么多条（子代理查看页也是）
	historyPage   = 1500 // 往上翻时一次再载入这么多条（每次都要从头读整份记录，页大几乎不多花时间）
	maxPatchLines = 2000 // 改文件结果里的对比超过这么多行就不带，界面改用参数里的新旧内容自己比
)

var (
	usageKeys = []string{"input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"}
	todoTools = map[string]bool{"TodoWrite": true, "TaskCreate": true, "TaskUpdate": true}
)

// rewindCut 认 Claude Code 剪会话（改写、删除之前的消息，stream-json 的 rewind_conversation 或终端里的 /rewind）时
// 追加的记号：一行 last-prompt，explicit 且 rewound。被剪掉的内容还留在文件里，只是不在当前这条线上了。
// 返回被剪掉的第一条消息的 uuid、剪完后最后一条消息的 uuid（剪光了是 nil）；不是这种行时 ok 为假。
func rewindCut(line []byte) (cut, leaf any, ok bool) {
	if !has(line, `"rewound":true`) || !has(line, `"last-prompt"`) {
		return nil, nil, false
	}
	o, parsed := parseObject(line)
	if !parsed || o["type"] != "last-prompt" || o["explicit"] != true || o["rewound"] != true {
		return nil, nil, false
	}
	return o["cutUuid"], o["leafUuid"], true
}

func isRewind(line []byte) bool {
	_, _, ok := rewindCut(line)
	return ok
}

// cutAt 照剪切记号算出前面保留几项（owners 是每一项出自哪一行的 uuid）：从被剪的那条消息起都去掉；
// 找不到它时保留到剪完后的最后一条消息；剪光了一项不留。
func cutAt(owners []any, cut, leaf any) int {
	for i, o := range owners {
		if sameValue(o, cut) {
			return i
		}
	}
	if leaf == nil {
		return 0
	}
	for i := len(owners) - 1; i >= 0; i-- {
		if sameValue(owners[i], leaf) {
			return i + 1
		}
	}
	return len(owners)
}

// timeline 是当前这条线上要显示的消息，和每一项出自哪一行（uuid），遇到剪切记号时据此去掉被剪的部分。
type timeline struct {
	events []obj
	owners []any
}

func (t *timeline) add(e obj, owner any) {
	t.events = append(t.events, e)
	t.owners = append(t.owners, owner)
}

func (t *timeline) cut(cut, leaf any) {
	keep := cutAt(t.owners, cut, leaf)
	t.events, t.owners = t.events[:keep], t.owners[:keep]
}

// historyReader 把会话记录读成要显示的消息（形状同 stream-json）。
type historyReader struct {
	timeline
	compacted bool // 刚读到压缩提示，后面紧跟的 /compact 命令不再显示
}

func readEvents(path string) (*timeline, error) {
	f, err := openFile(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	r := &historyReader{}
	lr := newLineReader(f)
	for number := 0; ; number++ {
		line, ok := lr.next()
		if !ok {
			break
		}
		if !hasAny(line, `"type":"user"`, `"type":"assistant"`, `"local_command"`, `"queued_command"`, `"rewound":true`, `"compact_boundary"`) {
			continue
		}
		if cut, leaf, ok := rewindCut(line); ok {
			r.cut(cut, leaf)
			continue
		}
		if o, ok := parseObject(line); ok {
			r.record(o, number)
		}
	}
	if lr.err != nil {
		return nil, lr.err
	}
	return &r.timeline, nil
}

func (r *historyReader) record(o obj, number int) {
	kind := o["type"]
	row := o["uuid"]
	if kind == "system" {
		r.system(o, row)
		return
	}
	var content any
	if kind == "attachment" {
		// 回答进行中插进来的消息：记成 queued_command 附件，不是普通的 user 条目
		attachment := asObj(o["attachment"])
		if attachment["type"] != "queued_command" || truthy(o["isSidechain"]) {
			return
		}
		r.user(o, attachment["prompt"], number, row)
		return
	}
	if (kind != "user" && kind != "assistant") || truthy(o["isSidechain"]) || truthy(o["isMeta"]) {
		return
	}
	message := asObj(o["message"])
	content = message["content"]
	if kind == "user" {
		r.user(o, content, number, row)
		return
	}
	blocks := assistantBlocks(content)
	usage := pickUsage(message["usage"])
	if len(blocks) == 0 && usage == nil {
		return
	}
	e := obj{"type": "assistant", "message": obj{"id": message["id"], "model": message["model"], "usage": usage, "content": blocks}}
	if truthy(o["effort"]) {
		e["effort"] = o["effort"]
	}
	r.add(e, row)
}

func (r *historyReader) system(o obj, row any) {
	switch o["subtype"] {
	case "compact_boundary":
		// 压缩：界面显示一条提示，用量统计据此把上下文占用换成压缩后的大小
		meta := asObj(o["compactMetadata"])
		r.add(obj{"type": "system", "subtype": "compact_boundary", "compact_metadata": obj{
			"trigger": meta["trigger"], "pre_tokens": meta["preTokens"], "post_tokens": meta["postTokens"]}}, row)
		r.compacted = true
	case "local_command":
		// 斜杠命令的输出（/context、/usage 等），当作一段回答显示
		if text := commandOutput(asString(o["content"])); text != "" {
			r.add(obj{"type": "assistant", "message": obj{"id": o["uuid"], "content": []any{obj{"type": "text", "text": clipText(text)}}}}, row)
		}
	}
}

func commandOutput(content string) string {
	var parts []string
	for _, m := range commandOut.FindAllStringSubmatch(content, -1) {
		parts = append(parts, strings.TrimSpace(m[1]))
	}
	return strings.TrimSpace(ansiEscape.ReplaceAllString(strings.Join(parts, "\n"), ""))
}

func (r *historyReader) user(o obj, content any, number int, row any) {
	if hasToolResult(content) {
		r.add(toolResultEvent(o, asList(content), number), row)
		return
	}
	if truthy(o["isCompactSummary"]) {
		return
	}
	if shell := shellText(content); shell != "" {
		r.add(obj{"type": "user", "message": obj{"content": []any{obj{"type": "text", "text": clipText(shell)}}}}, row)
		return
	}
	text := userText(content)
	// 记录里 /compact 命令写在压缩提示之后，那条提示已经说明了，不再显示一遍命令
	if r.compacted && text != "" && strings.SplitN(text, " ", 2)[0] == "/compact" {
		r.compacted = false
		return
	}
	if text != "" {
		r.compacted = false
	}
	blocks := imageRefs(content, number)
	if text != "" {
		blocks = append(blocks, obj{"type": "text", "text": text})
	}
	if len(blocks) == 0 {
		return
	}
	e := obj{"type": "user", "message": obj{"content": blocks}}
	// 改写、分支时要用它告诉 Claude Code 剪哪条（插话并进来的附件行不算一轮的开头，不给）
	if o["type"] == "user" && truthy(row) {
		e["uuid"] = row
	}
	r.add(e, row)
}

// assistantBlocks 是回答里要显示的块（文字、思考、工具调用），长字符串截断，去掉签名。
func assistantBlocks(content any) []any {
	blocks := []any{}
	for _, b := range asList(content) {
		m, ok := b.(obj)
		if !ok || (m["type"] != "text" && m["type"] != "thinking" && m["type"] != "tool_use") {
			continue
		}
		c := clip(m).(obj)
		delete(c, "signature")
		blocks = append(blocks, c)
	}
	return blocks
}

func pickUsage(v any) obj {
	usage, ok := v.(obj)
	if !ok {
		return nil
	}
	out := obj{}
	for _, k := range usageKeys {
		if x, ok := usage[k]; ok {
			out[k] = x
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// imageRefs 是用户消息里的图片：本体不回传（一张就是几百 KB），只给出它在记录里的位置，界面要看时再用 image 命令取。
func imageRefs(content any, number int) []any {
	blocks := []any{}
	for index, b := range asList(content) {
		if m, ok := b.(obj); ok && m["type"] == "image" {
			blocks = append(blocks, obj{"type": "image", "ref": fmt.Sprintf("%d.%d", number, index)})
		}
	}
	return blocks
}

// toolResultEvent 是一行工具结果：每个结果块的内容（图片换成位置），改文件工具还带上对比。
func toolResultEvent(o obj, content []any, number int) obj {
	blocks := []any{}
	for index, b := range content {
		m, ok := b.(obj)
		if !ok || m["type"] != "tool_result" {
			continue
		}
		blocks = append(blocks, obj{"type": "tool_result", "tool_use_id": m["tool_use_id"], "is_error": truthy(m["is_error"]),
			"content": resultContent(m["content"], fmt.Sprintf("%d.%d", number, index))})
	}
	e := obj{"type": "user", "message": obj{"content": blocks}}
	if patch := editPatch(o["toolUseResult"]); patch != nil {
		e["tool_use_result"] = patch
	}
	return e
}

func resultContent(content any, position string) any {
	list, ok := content.([]any)
	if !ok {
		return clip(content)
	}
	blocks := []any{}
	for index, b := range list {
		m, ok := b.(obj)
		if !ok {
			continue
		}
		switch m["type"] {
		case "image":
			blocks = append(blocks, obj{"type": "image", "ref": fmt.Sprintf("%s.%d", position, index)})
		case "text":
			blocks = append(blocks, obj{"type": "text", "text": clip(orValue(m["text"], ""))})
		}
	}
	return blocks
}

// editPatch 是改文件工具（Edit、Write）的结构化结果里界面要用的部分：带真实行号的对比，以及是不是新建。
func editPatch(result any) obj {
	m, ok := result.(obj)
	if !ok {
		return nil
	}
	patch, ok := m["structuredPatch"].([]any)
	if !ok {
		return nil
	}
	lines := 0
	for _, h := range patch {
		if hunk, ok := h.(obj); ok {
			lines += sizeOf(hunk["lines"])
		}
	}
	if lines > maxPatchLines {
		return nil
	}
	return obj{"type": m["type"], "structuredPatch": clip(patch)}
}

// historyPos 是界面要前一页时报上的位置：它手里那段从第 end 条开始、前一条出自 anchor 这一行（"-" 表示不核对）；
// rest 表示一次要走剩下的全部。
type historyPos struct {
	given  bool
	end    int64
	anchor string
	rest   bool
}

func cmdHistory(o *emitter, path string, pos historyPos) error {
	t, err := readEvents(path)
	if err != nil {
		return err
	}
	n := len(t.events)
	var start, end int
	switch {
	case !pos.given:
		end = n
		start = pageStart(pageStarts(t.events), end, maxEvents)
	case pos.end <= 0 || pos.end > int64(n) || (pos.anchor != "-" && !sameValue(t.owners[pos.end-1], pos.anchor)):
		// 记录在界面载入的那段之前被改过：这一页给不出来，界面要重新打开对话
		o.out(obj{"type": "zeta_history", "stale": true})
		return nil
	case pos.rest:
		end = int(pos.end)
	default:
		end = int(pos.end)
		start = pageStart(pageStarts(t.events), end, historyPage)
	}
	var anchor any
	if start > 0 {
		anchor = t.owners[start-1]
	}
	header := obj{"type": "zeta_history", "skipped": start, "anchor": anchor, "todo_seed": todoSeed(t.events[:start])}
	// 模型和思考强度按整个对话（剪切之后的全部消息）算，不只是这一页
	if model, effort, ok := lastAnswer(t.events); ok {
		header["model"], header["effort"] = model, effort
	}
	o.out(header)
	for _, e := range t.events[start:end] {
		o.out(e)
	}
	return nil
}

// pageBounds 记着每个位置能不能当一页的开头：turn 是一轮的开头（用户亲手发的消息或压缩提示）且不拆开工具调用；
// safe 是那里切开后，没有哪次工具调用在前一页、结果在后一页。第 0 条两样都算。
type pageBounds struct {
	turn, safe []bool
}

func pageStarts(events []obj) pageBounds {
	calls := map[string]int{}           // 还没等到结果的工具调用在第几条
	spans := make([]int, len(events)+1) // 差分：从这里切开会拆开几对
	turn := make([]bool, len(events))
	for i, e := range events {
		results := 0
		for _, b := range asList(asObj(e["message"])["content"]) {
			m, _ := b.(obj)
			switch {
			case e["type"] == "assistant" && m["type"] == "tool_use" && truthy(m["id"]):
				calls[valueKey(m["id"])] = i
			case m["type"] == "tool_result":
				results++
				if start, ok := calls[valueKey(m["tool_use_id"])]; ok {
					delete(calls, valueKey(m["tool_use_id"]))
					spans[start+1]++
					spans[i+1]--
				}
			}
		}
		turn[i] = e["type"] == "system" || (e["type"] == "user" && results == 0)
	}
	b := pageBounds{turn: make([]bool, len(events)), safe: make([]bool, len(events))}
	depth := 0
	for i := range events {
		depth += spans[i]
		b.safe[i] = i == 0 || depth == 0
		b.turn[i] = i == 0 || (turn[i] && b.safe[i])
	}
	return b
}

// pageStart 是 [end] 之前的一页从哪开始：载入 size 条左右，开头取离那里最近的一轮的开头，没有就取不拆开工具调用的地方
// （一样近时取前面的）。剩下的不到半页时一起载完。
func pageStart(b pageBounds, end, size int) int {
	target := end - size
	if target <= size/2 {
		return 0
	}
	lo, hi := target-size/2, min(end-1, target+size/2)
	for _, good := range [][]bool{b.turn, b.safe} {
		best := -1
		for i := lo; i <= hi; i++ {
			if good[i] && (best < 0 || abs(i-target) < abs(best-target)) {
				best = i
			}
		}
		if best >= 0 {
			return best
		}
	}
	return target
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// lastAnswer 是主线上（剪切之后、不含子代理）最后一次真实回答用的模型和思考强度：重新打开对话时思考强度照它恢复，
// 模型在 Claude Code 报来之前先显示它。斜杠命令的输出没有模型；Claude Code 合成的回答（出错提示等）模型是 <synthetic>，都跳过。
func lastAnswer(events []obj) (model string, effort any, ok bool) {
	for i := len(events) - 1; i >= 0; i-- {
		e := events[i]
		if e["type"] != "assistant" {
			continue
		}
		if m := asString(asObj(e["message"])["model"]); m != "" && !strings.HasPrefix(m, "<") {
			return m, e["effort"], true
		}
	}
	return "", nil, false
}

// todoSeed 是没载入的那部分历史里的清单工具调用和它们的结果，界面据此接上更早建的任务清单。
func todoSeed(skipped []obj) []any {
	seed := []any{}
	ids := map[string]bool{}
	for _, e := range skipped {
		message := asObj(e["message"])
		var picked []any
		for _, b := range asList(message["content"]) {
			m, ok := b.(obj)
			if !ok {
				continue
			}
			if e["type"] == "assistant" && m["type"] == "tool_use" && todoTools[asString(m["name"])] {
				ids[valueKey(m["id"])] = true
				picked = append(picked, m)
			} else if e["type"] == "user" && m["type"] == "tool_result" && ids[valueKey(m["tool_use_id"])] {
				picked = append(picked, m)
			}
		}
		if len(picked) == 0 {
			continue
		}
		if e["type"] == "assistant" {
			seed = append(seed, obj{"type": "assistant", "message": obj{"id": message["id"], "content": picked}})
		} else {
			seed = append(seed, obj{"type": "user", "message": obj{"content": picked}})
		}
	}
	return seed
}
