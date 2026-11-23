package conv

import (
	"encoding/json"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/transcript"
	"github.com/dzshzx/Zeta/helper/usage"
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
		"key":             raw(c.ID),
		"session":         raw(c.session),
		"title":           raw(c.title),
		"cwd":             raw(c.cwd),
		"process":         raw(c.proc != nil),
		"busy":            raw(c.busy),
		"status":          raw(statusText(c.status)),
		"status_code":     raw(c.status.Key),
		"status_args":     raw(c.status.Args),
		"blocked":         raw(c.blocked),
		"host_key":        raw(c.hostKey()),
		"loading_history": raw(c.loadingHistory),
		"earlier":         raw(c.earlier),
		"earlier_loading": raw(c.earlierLoading),
		"todos":           raw(todosOf(c.todoSeed, c.r.Items)),
		"occupant":        raw(c.occupantView()),
		"tasks":           raw(nonNilSlice(c.tasks.Tasks())),
		"queued":          raw(queued),
		"steering":        raw(c.steering),
		"prompts":         raw(nonNilSlice(c.prompts)),
		"recalled":        raw(recalled),
		"model":           raw(c.model),
		"reported_model":  raw(c.reportedModel),
		"effort":          raw(nilIfEmpty(c.effort)),
		"mode":            raw(c.modes.view()),
		"commands":        nonNull(c.commands, "null"),
		"models":          nonNull(c.models, "null"),
		"usage":           raw(c.usageView()),
		"can_rewind":      raw(c.canRewind()),

		"rewinding":          raw(c.rewind != nil),
		"last_activity":      raw(publishedActivity(c.lastActivity)),
		"model_from_session": raw(c.resumed() && c.model == nil),
	}
}

// publishedActivity 是推出去的 last_activity：取整到秒。流式回答时每批输出都更新最近活动的时刻，
// 客户端只拿它算「已等待 N 秒」，取整以后最多每秒多推一次，不是每批输出都推。
func publishedActivity(ms int64) int64 { return ms - ms%1000 }

// nilIfEmpty：没选的（空串）编成 null，和 model 一样。
func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
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
	d := &itemsMsg{At: head, Remove: len(p.items) - head - tail, Items: nonNilSlice(now[head : len(now)-tail])}
	p.items = append(p.items[:0], now...)
	p.revs = p.revs[:0]
	for _, it := range now {
		p.revs = append(p.revs, it.Rev())
	}
	return d
}

// usageView 是用量快照：Claude Code 报了上下文窗口就记进这台主机学到的那份（别的对话、下次进程还没报时用）。
func (c *Conversation) usageView() usage.Usage {
	if c.deps.Windows != nil {
		if model, window, ok := c.usage.LearnedWindow(); ok {
			_ = c.deps.Windows.Learn(model, window)
		}
	}
	return c.usage.Snapshot(c.deps.Windows)
}

// hostKey 是接着的托管进程的标识（zeta_host.sh 的 key，托管目录 ~/.cache/<ZETA_NAME>/hosts/<key>）；没接着进程时为空。
// 客户端拿它找进程（比如看新挂上的共享文件夹进程看不看得到）。
func (c *Conversation) hostKey() string {
	if c.proc == nil {
		return ""
	}
	return c.proc.link.Key()
}

// statusText 是 conv.state 的 status：源语言的那句，给不认 status_code 的旧客户端；没有时为空。
func statusText(m locales.Msg) string {
	if m.Key == "" {
		return ""
	}
	return m.String()
}
