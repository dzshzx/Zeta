package conv

import (
	"bytes"
	"encoding/json"
	"strings"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// 子代理查看页的内容：一个子代理在做什么，归约成和聊天里一样的条目。
//
// 两个来源：Claude Code 写在 <会话目录>/subagents/agent-<id>.jsonl 的记录（zeta-helper subagent 按偏移增量读出），
// 这是全的；输出流里 parent_tool_use_id 是它的事件，比读文件及时，但不全（前台子代理的文字不进输出流），
// 只在文件还没读到时补上，文件里有了就丢掉。

const (
	// subagentKeep：最多留这么多条记录事件，更早的丢掉、计进 skipped
	subagentKeep = 800
	// subagentSettleMs：记录这么久（毫秒）没写就算收齐了。子代理的结局（meta 的 finished、输出流的 task_notification）
	// 可能比最后几行先写下，结束后要等记录停下来再停止读
	subagentSettleMs = 2000
)

// subagentMeta 是记录旁 meta.json 里查看页要的几项（推送里的 meta）。
type subagentMeta struct {
	AgentID     string `json:"agent_id"`
	AgentType   string `json:"agent_type,omitempty"`
	Description string `json:"description,omitempty"`
	// Model、Effort 是模型和思考强度：记录里最近一次回答实际用的；还没有回答时是派它时指定的（没指定时空着）
	Model      string `json:"model,omitempty"`
	Effort     string `json:"effort,omitempty"`
	Finished   bool   `json:"finished"` // 做完了：Claude Code 在子代理结束时写 finished
	Stopped    bool   `json:"stopped"`  // 被停下了：stoppedByUser
	Background *bool  `json:"background"`
}

// subagentHead 是 zeta-helper subagent 输出的第一行（见 helper/subagent.go 的 cmdSubagent）。
type subagentHead struct {
	Type    string `json:"type"`
	Missing bool   `json:"missing"`
	File    string `json:"file"`
	AgentID string `json:"agent_id"`
	Meta    struct {
		AgentType     string  `json:"agentType"`
		Description   string  `json:"description"`
		Model         string  `json:"model"`
		Effort        string  `json:"effort"`
		Finished      bool    `json:"finished"`
		StoppedByUser bool    `json:"stoppedByUser"`
		RequestShape  *string `json:"requestShape"` // 起的时候放没放后台；没有时不知道
	} `json:"meta"`
	Offset  int64 `json:"offset"`
	Line    int64 `json:"line"`
	More    bool  `json:"more"`
	Skipped int   `json:"skipped"`
	Reset   bool  `json:"reset"`
	IdleMs  int64 `json:"idle_ms"`
}

func (h *subagentHead) meta() *subagentMeta {
	m := &subagentMeta{AgentID: h.AgentID, AgentType: h.Meta.AgentType, Description: h.Meta.Description,
		Model: h.Meta.Model, Effort: h.Meta.Effort, Finished: h.Meta.Finished, Stopped: h.Meta.StoppedByUser}
	if h.Meta.RequestShape != nil {
		background := *h.Meta.RequestShape == "background"
		m.Background = &background
	}
	return m
}

// subagentEvent 是子代理的一条 assistant、user 事件：记录里读出来的（形状同 history），或输出流里的。
type subagentEvent struct {
	Type          string                    `json:"type"`
	Prompt        bool                      `json:"prompt"` // 交给它的指令（记录的第一行）
	Effort        string                    `json:"effort"` // assistant：这次回答实际用的思考强度（模型在 message 里）
	Message       transcript.Message        `json:"message"`
	ToolUseResult *transcript.ToolUseResult `json:"tool_use_result"`
}

// subagentTranscript 是一个子代理归约前的内容和读记录的进度。
type subagentTranscript struct {
	file    string        // 记录文件（主机上的路径），历史图片要靠它去取；还没找到时为空
	meta    *subagentMeta // 还没读到时为 nil
	prompt  string        // 交给它的指令
	offset  int64         // 下次从记录的这个字节接着读
	line    int64         // 和它是第几行（图片的位置要用行号）
	more    bool          // 上次只读了一段，后面还有，接着读不用等
	missing bool          // 找过了，没有这个子代理的记录（还没写出来，或者会话已经删了）
	loaded  bool          // 读到过记录（不管有没有找到）
	idleMs  int64         // 上次读的时候，记录已经多久没写了
	skipped int           // 没留下的更早的记录事件数
	model   string        // 记录里最近一次回答实际用的模型和思考强度，meta 里的是派它时指定的
	effort  string

	fileEvents []subagentEvent
	liveEvents []subagentEvent

	// 文件里已经有的 assistant 消息、工具调用、工具结果：输出流里同样的据此丢掉
	fileMessages map[string]bool
	fileToolUses map[string]bool
	fileResults  map[string]bool
}

// settled：记录收齐了，没找到，或者已经有一会儿没写了。
func (t *subagentTranscript) settled() bool { return t.missing || t.idleMs >= subagentSettleMs }

// applyRead 收下一次 zeta-helper subagent 的输出：开头一行是概要，之后一行一个事件。没有概要行时什么也不改。
func (t *subagentTranscript) applyRead(out []byte) {
	var head *subagentHead
	var events []subagentEvent
	for line := range bytes.Lines(out) {
		if head == nil {
			var h subagentHead
			if json.Unmarshal(line, &h) == nil && h.Type == "zeta_subagent" {
				head = &h
			}
			continue
		}
		var e subagentEvent
		if json.Unmarshal(line, &e) == nil {
			events = append(events, e)
		}
	}
	if head == nil {
		return
	}
	t.loaded = true
	if head.Missing {
		// 之前读到过的留着（比如会话刚被删），只是这次没找到
		t.missing = true
		return
	}
	t.missing = false
	t.applyHead(head)
	t.appendFileEvents(events)
	// 文件追上了的输出流事件不再要
	t.liveEvents = t.uncoveredAll(t.liveEvents)
}

// applyHead：换了记录文件就从头来，记下元数据和读到的位置。
func (t *subagentTranscript) applyHead(h *subagentHead) {
	if h.Reset || (t.file != "" && h.File != t.file) {
		t.reset()
	}
	t.file = h.File
	t.meta = h.meta()
	t.offset, t.line = h.Offset, h.Line
	t.idleMs = h.IdleMs
	t.more = h.More
	t.skipped += h.Skipped
}

func (t *subagentTranscript) reset() {
	t.fileEvents = nil
	t.fileMessages, t.fileToolUses, t.fileResults = nil, nil, nil
	t.prompt = ""
	t.skipped = 0
	t.model, t.effort = "", ""
}

// appendFileEvents：开头的指令单独记，其余的收下，只留最后 subagentKeep 条。
func (t *subagentTranscript) appendFileEvents(events []subagentEvent) {
	for _, e := range events {
		if e.Prompt {
			t.prompt = promptText(e.Message)
			continue
		}
		if e.Message.Model != "" {
			t.model, t.effort = e.Message.Model, e.Effort
		}
		t.fileEvents = append(t.fileEvents, e)
		t.index(e)
	}
	if drop := len(t.fileEvents) - subagentKeep; drop > 0 {
		t.fileEvents = append(t.fileEvents[:0:0], t.fileEvents[drop:]...)
		t.skipped += drop
	}
}

func promptText(m transcript.Message) string {
	var texts []string
	for _, b := range m.Content.Blocks {
		if b.Type == "text" {
			texts = append(texts, b.Text)
		}
	}
	text := strings.Join(texts, "\n")
	if strings.TrimSpace(text) == "" {
		return ""
	}
	return text
}

// index 记下文件里这条事件有的消息、工具调用、工具结果。
func (t *subagentTranscript) index(e subagentEvent) {
	if t.fileMessages == nil {
		t.fileMessages, t.fileToolUses, t.fileResults = map[string]bool{}, map[string]bool{}, map[string]bool{}
	}
	switch e.Type {
	case "assistant":
		if e.Message.ID != "" {
			t.fileMessages[e.Message.ID] = true
		}
		for _, b := range e.Message.Content.Blocks {
			if b.Type == "tool_use" && b.ID != "" {
				t.fileToolUses[b.ID] = true
			}
		}
	case "user":
		for _, b := range e.Message.Content.Blocks {
			if b.Type == "tool_result" && b.ToolUseID != "" {
				t.fileResults[b.ToolUseID] = true
			}
		}
	}
}

// observeLive 收下输出流里子代理的一条事件，返回有没有留下（文件里都有了就不留）。
func (t *subagentTranscript) observeLive(e subagentEvent) bool {
	kept, ok := t.uncovered(e)
	if ok {
		t.liveEvents = append(t.liveEvents, kept)
	}
	return ok
}

func (t *subagentTranscript) uncoveredAll(events []subagentEvent) []subagentEvent {
	var kept []subagentEvent
	for _, e := range events {
		if u, ok := t.uncovered(e); ok {
			kept = append(kept, u)
		}
	}
	return kept
}

// uncovered 是输出流事件里文件还没有的部分：assistant 留文件里没有的工具调用，和文件里没有的消息的文字、思考；
// user 只留工具结果（文字是交给它的指令，文件里有）。什么都不剩时 ok 为 false。
func (t *subagentTranscript) uncovered(e subagentEvent) (subagentEvent, bool) {
	seen := e.Message.ID != "" && t.fileMessages[e.Message.ID]
	var kept []transcript.Block
	for _, b := range e.Message.Content.Blocks {
		var keep bool
		switch {
		case e.Type == "assistant" && b.Type == "tool_use":
			keep = !t.fileToolUses[b.ID]
		case e.Type == "assistant" && (b.Type == "text" || b.Type == "thinking"):
			keep = !seen
		case e.Type == "user" && b.Type == "tool_result":
			keep = !t.fileResults[b.ToolUseID]
		}
		if keep {
			kept = append(kept, b)
		}
	}
	if len(kept) == 0 {
		return subagentEvent{}, false
	}
	e.Message.Content = transcript.Content{Blocks: kept}
	return e, true
}

// items 归约出条目。running 时最后还没有结果的工具调用显示为进行中，否则收口成已中断。
func (t *subagentTranscript) items(running bool) []*transcript.Item {
	r := transcript.New("sa-")
	for _, e := range t.fileEvents {
		e.apply(r, t.file)
	}
	for _, e := range t.liveEvents {
		e.apply(r, "")
	}
	r.FinishTurn(running)
	return r.Items
}

// apply 把一条事件交给归约器；sessionFile 是它所在的记录（输出流里的为空）。
// 记录里 user 的文字是之后续派给它的消息（第一条指令已经拿出去了）。
func (e subagentEvent) apply(r *transcript.Reducer, sessionFile string) {
	switch e.Type {
	case "assistant":
		r.ApplyAssistant(e.Message, false)
	case "user":
		r.ApplyUser(e.Message, sessionFile != "", transcript.UserOptions{SessionFile: sessionFile, Result: e.ToolUseResult})
	}
}

// toolUses 是读到的工具调用一共几次（含没留下的更早的那些）。
func (t *subagentTranscript) toolUses() int {
	n := len(t.fileToolUses)
	for _, e := range t.liveEvents {
		for _, b := range e.Message.Content.Blocks {
			if b.Type == "tool_use" {
				n++
			}
		}
	}
	return n
}
