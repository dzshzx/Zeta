package conv

import (
	"bytes"
	"encoding/json"
	"slices"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// historyHeader 是 zeta-helper history 输出的第一行。
type historyHeader struct {
	Type          string          `json:"type"`
	Stale         bool            `json:"stale"`
	Skipped       int             `json:"skipped"`
	Anchor        string          `json:"anchor"`
	TodoSeed      json.RawMessage `json:"todo_seed"`
	Model         string          `json:"model"`
	Effort        json.RawMessage `json:"effort"`
	SwitchedModel string          `json:"switched_model"`
}

// historyLine 是 zeta-helper history 输出的一条消息。
type historyLine struct {
	Type            string                    `json:"type"`
	Subtype         string                    `json:"subtype"`
	UUID            string                    `json:"uuid"`
	Message         *transcript.Message       `json:"message"`
	ToolUseResult   *transcript.ToolUseResult `json:"tool_use_result"`
	CompactMetadata json.RawMessage           `json:"compact_metadata"`
}

// historyRead 是读出来的一段历史。
type historyRead struct {
	header  historyHeader
	reducer *transcript.Reducer
	lines   [][]byte // 原样的各行，用量从里面数
}

// readHistory 把 zeta-helper history 的输出归并成条目（编号以 prefix 开头）。keepRunningTools：载入后还要接着跟读进程，最后那几步其实还在跑。
func readHistory(out []byte, file, prefix string, keepRunningTools bool) historyRead {
	r := transcript.New(prefix)
	var read historyRead
	for line := range bytes.Lines(out) {
		var h historyLine
		if json.Unmarshal(line, &h) != nil {
			continue
		}
		if h.Type == "zeta_history" {
			_ = json.Unmarshal(line, &read.header)
			continue
		}
		read.lines = append(read.lines, bytes.Clone(line))
		switch {
		case h.Type == "system" && h.Subtype == "compact_boundary":
			var e event
			_ = json.Unmarshal(line, &e)
			r.AddNotice(compactNotice(e), false)
		case h.Type == "assistant" && h.Message != nil:
			r.ApplyAssistant(*h.Message, true)
		case h.Type == "user" && h.Message != nil:
			r.ApplyUser(*h.Message, true, transcript.UserOptions{SessionFile: file, UUID: h.UUID, Result: h.ToolUseResult})
		}
	}
	r.FinishTurn(keepRunningTools)
	read.reducer = r
	return read
}

// loadHistory 在后台读会话记录（最近的一段），读完排回对话的 goroutine 接到列表前面。
func (c *Conversation) loadHistory(file string) {
	out, err := c.deps.History(file)
	c.do(func() {
		read := readHistory(out, file, "h", c.proc != nil && c.busy)
		// 载入期间用户已经发了新消息的话，新内容接在历史后面
		read.reducer.Items = append(read.reducer.Items, c.r.Items...)
		c.r = read.reducer
		c.usage.prependHistory(read.lines)
		c.earlier, c.earlierAnchor = read.header.Skipped, read.header.Anchor
		c.todoSeed = read.header.TodoSeed
		c.loadingHistory = false
		if err != nil {
			c.r.AddNotice("读取历史记录失败："+err.Error(), true)
		} else {
			c.restoreModel(read.header.Model, read.header.SwitchedModel)
			c.restoreEffort(read.header.Effort)
		}
		c.changed()
	})
}

// restoreModel：载入历史后照会话记录定模型。answered 是最后一次真实回答用的，Claude Code 接着会话时自己恢复它，这里只是先显示；
// switched 是那之后终端里 /model 换成的（显示名），认得出是哪个模型就记下、起进程时指定它（Claude Code 接着会话时不认这次切换）。
func (c *Conversation) restoreModel(answered, switched string) {
	c.switchedInTerminal = modelIDOfName(switched)
	if c.reportedModel == "" {
		c.reportedModel = answered
		if c.switchedInTerminal != "" {
			c.reportedModel = c.switchedInTerminal
		}
	}
}

// effortLevels 是 Claude Code 认的几档思考强度。
var effortLevels = []string{"low", "medium", "high", "xhigh", "max"}

// restoreEffort：思考强度照这个对话主线上最后一次真实回答用的恢复，只改这个对话。进程已经起来了（定时任务不等历史载完就发出消息）不改。
func (c *Conversation) restoreEffort(recorded json.RawMessage) {
	var level string
	if c.proc != nil || json.Unmarshal(recorded, &level) != nil {
		return
	}
	if slices.Contains(effortLevels, level) {
		c.effort = level
	}
}
