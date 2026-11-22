package conv

import (
	"encoding/json"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// stateFields 是 conv.state 的各个字段（见 docs/core.md）。每个字段都写出来，变成零值时客户端也收得到。
func (c *Conversation) stateFields() map[string]json.RawMessage {
	queued := make([]messageView, len(c.queued))
	for i, m := range c.queued {
		queued[i] = m.view()
	}
	var recalled *messageView
	if c.recalled != nil {
		v := c.recalled.view()
		recalled = &v
	}
	return map[string]json.RawMessage{
		"session":         raw(c.session),
		"title":           raw(c.title),
		"cwd":             raw(c.cwd),
		"process":         raw(c.proc != nil),
		"busy":            raw(c.busy),
		"status":          raw(c.status),
		"blocked":         raw(c.blocked),
		"loading_history": raw(c.loadingHistory),
		"earlier":         raw(c.earlier),
		"todo_seed":       nonNull(c.todoSeed, "[]"),
		"tasks":           raw(c.tasks.view()),
		"queued":          raw(queued),
		"steering":        raw(c.steering),
		"prompts":         raw(nonNilSlice(c.prompts)),
		"recalled":        raw(recalled),
		"model":           raw(c.model),
		"reported_model":  raw(c.reportedModel),
		"effort":          raw(c.effort),
		"mode":            raw(map[string]string{"user": c.modes.user, "process": c.modes.process}),
		"commands":        nonNull(c.commands, "null"),
		"models":          nonNull(c.models, "null"),
		"usage":           raw(c.usage.view()),
	}
}

func nonNull(v json.RawMessage, empty string) json.RawMessage {
	if len(v) == 0 {
		return json.RawMessage(empty)
	}
	return v
}

// messageView 是排队的、撤回的消息在 conv.state 里的样子。
type messageView struct {
	UUID   string   `json:"uuid"`
	Text   string   `json:"text"`
	Images []Image  `json:"images"`
	Files  []string `json:"files"`
}

// itemsMsg 是 conv.items：Full 时 Items 是整份，否则从 At 起去掉 Remove 项、换成 Items。
type itemsMsg struct {
	Type   string             `json:"type"`
	Conv   string             `json:"conv"`
	Full   bool               `json:"full,omitempty"`
	At     int                `json:"at"`
	Remove int                `json:"remove"`
	Items  []*transcript.Item `json:"items"`
}

// publishedItems 记着上次推出去的条目（哪一项、改到第几次），下次只推变了的那一段。
type publishedItems struct {
	items []*transcript.Item
	revs  []int
}

// delta 是从上次推出去的到 now 的一次替换：前后都没变的部分不动，中间这段换掉。没变时为 nil。
// 流式回答时变的是最后一两项；往前接更早的一页时是在开头插入。
func (p *publishedItems) delta(now []*transcript.Item) *itemsMsg {
	same := func(i, j int) bool { return now[i] == p.items[j] && now[i].Rev() == p.revs[j] }
	head := 0
	for head < len(now) && head < len(p.items) && same(head, head) {
		head++
	}
	tail := 0
	for tail < len(now)-head && tail < len(p.items)-head && same(len(now)-1-tail, len(p.items)-1-tail) {
		tail++
	}
	if head == len(now) && head == len(p.items) {
		return nil
	}
	d := &itemsMsg{At: head, Remove: len(p.items) - head - tail, Items: now[head : len(now)-tail]}
	p.items = append(p.items[:0], now...)
	p.revs = p.revs[:0]
	for _, it := range now {
		p.revs = append(p.revs, it.Rev())
	}
	return d
}
