package conv

import (
	"bytes"
	"encoding/json"
	"errors"
	"slices"
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 子代理查看页：客户端发 conv.subagent {tool_use_id, watch} 开始或停止看一个子代理，核心在有客户端看着时
// 增量读它的记录（经 Deps.Subagent，即 zeta-helper subagent），把归约出的条目和概要推给看着的客户端（conv.subagent）。
// 没人看着就不读；再有人看时从上次读到的地方接着读。输出流里子代理的事件、结局也在这里跟着。

const (
	subagentMaxLogs = 8   // 看过的子代理留这么多个（按最近看的），再看时从上次读到的地方接着读
	subagentMaxIDs  = 128 // Agent 调用与子代理 id 的对应、报了结局的子代理，各留这么多个
)

// subagentPace 是读记录的节奏：在跑时隔 poll 读一次，连着 slowAfter 次没新内容就放慢到 slow；
// 不在跑了但记录还没收齐（最后几行可能还没写下），隔 settle 很快再看一眼。
type subagentPace struct {
	poll, slow, settle time.Duration
	slowAfter          int
}

var defaultSubagentPace = subagentPace{poll: 1500 * time.Millisecond, slow: 4 * time.Second,
	settle: 700 * time.Millisecond, slowAfter: 4}

func (p subagentPace) delay(running bool, idle int) time.Duration {
	switch {
	case !running:
		return p.settle
	case idle < p.slowAfter:
		return p.poll
	}
	return p.slow
}

// subagentTracker 是一个对话里的子代理跟踪：看过的子代理、Agent 调用和子代理 id 的对应、输出流里报了结局的子代理，
// 以及 /clear 之前用过的会话号（那之前的子代理记录在那些会话的目录里）。零值可用，只在对话的 goroutine 里用。
type subagentTracker struct {
	logs         map[string]*subagentLog // 发起它的 Agent 调用 → 子代理
	used         int                     // 看过的次序，留哪几个按它
	ids          recent                  // Agent 调用 → 子代理 id（task_started 的 task_id、Agent 结果里的 agentId）
	ended        recent                  // 子代理 id → task_notification 报的结局
	pastSessions []string
	pace         subagentPace // 零值时用 defaultSubagentPace；测试里调快
}

// subagentLog 是一个子代理：归约前的内容、读记录的进度，和看着它的客户端。
type subagentLog struct {
	toolUseID string
	t         subagentTranscript
	used      int
	failure   string // 上次没读成的原因
	running   bool

	watchers map[int]*subagentWatcher // 看着它的客户端

	reading  bool        // 正在读：同一个子代理不同时读两份，偏移才接得上
	timer    *time.Timer // 下一次读
	idle     int         // 连着几次读完条目都没变
	items    []json.RawMessage
	readSeen []json.RawMessage // 上次读完时的条目，看这次读完变没变
	call     *agentCall        // 发起它的 Agent 调用的参数，记录还没读到时先用它说话；还没找到时为 nil
}

// subagentWatcher 记着推给一个客户端的最后一份，下次只推变了的。
type subagentWatcher struct {
	started bool
	items   []json.RawMessage
	status  []byte
}

// subagentStatus 是 conv.subagent 里除条目之外的部分（见 docs/core.md）。
type subagentStatus struct {
	Meta     *subagentMeta `json:"meta"`
	Prompt   string        `json:"prompt"`
	ToolUses int           `json:"tool_uses"`
	Running  bool          `json:"running"`
	Loaded   bool          `json:"loaded"`
	Missing  bool          `json:"missing"`
	Failure  string        `json:"failure,omitempty"`
	File     string        `json:"file"`
	Skipped  int           `json:"skipped"`
	// Outcome 是结局：completed、failed、stopped；还没结束时省掉
	Outcome string `json:"outcome,omitempty"`
	// State 是查看页显示的状态（sessionproto 的 Subagent*），见 subagentState
	State string `json:"state"`
}

// subagentMsg 是 conv.subagent 推送：条目同 conv.items（Full 时整份，否则从 At 起去掉 Remove 项、换成 Items）。
type subagentMsg struct {
	Type      string            `json:"type"`
	Conv      string            `json:"conv"`
	ToolUseID string            `json:"tool_use_id"`
	Full      bool              `json:"full,omitempty"`
	At        int               `json:"at"`
	Remove    int               `json:"remove"`
	Items     []json.RawMessage `json:"items"`
	subagentStatus
}

// log 是 Agent 调用 toolUseID 发起的子代理；没看过时新建，只留最近看的几个（有人看着的不收）。
func (s *subagentTracker) log(toolUseID string) *subagentLog {
	if s.logs == nil {
		s.logs = map[string]*subagentLog{}
	}
	s.used++
	if l := s.logs[toolUseID]; l != nil {
		l.used = s.used
		return l
	}
	l := &subagentLog{toolUseID: toolUseID, used: s.used, watchers: map[int]*subagentWatcher{}}
	s.logs[toolUseID] = l
	for len(s.logs) > subagentMaxLogs {
		var oldest *subagentLog
		for _, x := range s.logs {
			if x != l && len(x.watchers) == 0 && !x.reading && (oldest == nil || x.used < oldest.used) {
				oldest = x
			}
		}
		if oldest == nil {
			break
		}
		delete(s.logs, oldest.toolUseID)
	}
	return l
}

// agentID 是子代理 l 的 id：记录的 meta 里有就用它，没有时用输出流里见过的；都没有时为空。
func (s *subagentTracker) agentID(l *subagentLog) string {
	if l.t.meta != nil && l.t.meta.AgentID != "" {
		return l.t.meta.AgentID
	}
	return s.ids.get(l.toolUseID)
}

// outcome 是子代理 l 的结局：completed、failed、stopped，还没结束时为空。以输出流里报的为准，没报过就看记录的 meta。
func (s *subagentTracker) outcome(l *subagentLog) string {
	if id := s.agentID(l); id != "" {
		if o := s.ended.get(id); o != "" {
			return o
		}
	}
	switch m := l.t.meta; {
	case m == nil:
		return ""
	case m.Stopped:
		return "stopped"
	case m.Finished:
		return "completed"
	}
	return ""
}

// sessionCleared：/clear 换了新会话，previous 里的子代理记录以后也要能找到。
func (s *subagentTracker) sessionCleared(previous string) {
	if previous != "" && !slices.Contains(s.pastSessions, previous) {
		s.pastSessions = append(s.pastSessions, previous)
	}
}

func (s *subagentTracker) currentPace() subagentPace {
	if s.pace == (subagentPace{}) {
		return defaultSubagentPace
	}
	return s.pace
}

// subagentSessions 是找子代理记录用的会话号：这个对话现在的，和分支的原会话。
func (c *Conversation) subagentSessions() []string {
	var out []string
	// 分支的原会话：还没发过消息的分支里看到的子代理在那边的记录里
	for _, s := range []string{c.session, c.forkSource} {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

// watchSubagent：客户端 client 开始（watch）或停止看 Agent 调用 toolUseID 发起的子代理。开始时先推一份整份的，
// 再接着读记录；再发一次开始也是从整份重来。
func (c *Conversation) watchSubagent(client int, toolUseID string, watch bool) error {
	if toolUseID == "" {
		return errors.New("缺 tool_use_id")
	}
	if !watch {
		if l := c.subagents.logs[toolUseID]; l != nil {
			c.unwatchSubagent(l, client)
		}
		return nil
	}
	l := c.subagents.log(toolUseID)
	l.watchers[client] = &subagentWatcher{}
	c.publishSubagent(l)
	c.readSubagentNow(l)
	return nil
}

func (c *Conversation) unwatchSubagent(l *subagentLog, client int) {
	delete(l.watchers, client)
	if len(l.watchers) == 0 && l.timer != nil {
		l.timer.Stop()
		l.timer = nil
	}
}

// unwatchSubagents：客户端 client 放开了这个对话（或断开了），它看着的子代理都不再推给它。
func (c *Conversation) unwatchSubagents(client int) {
	for _, l := range c.subagents.logs {
		c.unwatchSubagent(l, client)
	}
}

// followSubagent：输出流里有了它的消息、结局之后，有人看着、又还没收齐的子代理已经停下不读的，重新开始读
// （比如停下读以后它又被续派了消息）。还在按节奏读的不打乱。
func (c *Conversation) followSubagent(l *subagentLog) {
	if len(l.watchers) == 0 || l.reading || l.timer != nil || c.subagentSettled(l) {
		return
	}
	c.readSubagent(l)
}

// readSubagentNow：有人看着的子代理马上读一次；正在读的等它读完再定下一次，排着下一次的提前到现在。
func (c *Conversation) readSubagentNow(l *subagentLog) {
	if len(l.watchers) == 0 || l.reading {
		return
	}
	if l.timer != nil {
		if !l.timer.Stop() {
			return // 已经到点了，排着的那次马上就读
		}
		l.timer = nil
	}
	c.readSubagent(l)
}

// subagentSettled：子代理 l 不用再读了，不在跑，记录也收齐了（没读到过、读不了时也算）。
func (c *Conversation) subagentSettled(l *subagentLog) bool {
	return !l.running && (!l.t.loaded || l.t.settled() || l.failure != "")
}

// readSubagent 在后台增量读一次子代理 l 的记录（从上次读到的地方接着读），读完排回对话的 goroutine。
// 还不知道会话号时只更新状态。
func (c *Conversation) readSubagent(l *subagentLog) {
	if len(l.watchers) == 0 {
		return
	}
	sessions := c.subagentSessions()
	for _, s := range c.subagents.pastSessions {
		if !slices.Contains(sessions, s) {
			sessions = append(sessions, s)
		}
	}
	if len(sessions) == 0 || c.deps.Subagent == nil {
		l.t.more = false // 这次读不了，没读完的等下次
		c.publishSubagent(l)
		c.scheduleSubagent(l)
		return
	}
	agent := c.subagents.agentID(l)
	if agent == "" {
		agent = "-"
	}
	args := append([]string{strconv.FormatInt(l.t.offset, 10), strconv.FormatInt(l.t.line, 10), l.toolUseID, agent},
		sessions...)
	l.reading = true
	go func() {
		out, err := c.deps.Subagent(args...)
		c.do(func() {
			l.reading = false
			if err != nil {
				l.failure = err.Error()
			} else {
				l.failure = ""
				l.t.applyRead(out)
			}
			c.publishSubagent(l)
			c.scheduleSubagent(l)
		})
	}()
}

// scheduleSubagent：读完一次后定下一次。后面还有没读完的马上接着读；收齐了就停；否则按节奏等一会儿再读。
func (c *Conversation) scheduleSubagent(l *subagentLog) {
	_, _, _, changed := rawDelta(l.readSeen, l.items)
	l.readSeen = l.items
	if changed {
		l.idle = 0
	} else {
		l.idle++
	}
	if len(l.watchers) == 0 {
		return
	}
	if l.t.more {
		c.readSubagent(l)
		return
	}
	if c.subagentSettled(l) {
		return
	}
	var t *time.Timer
	t = time.AfterFunc(c.subagents.currentPace().delay(l.running, l.idle), func() {
		c.do(func() {
			if l.timer != t {
				return
			}
			l.timer = nil
			c.readSubagent(l)
		})
	})
	l.timer = t
}

// publishSubagent 更新子代理 l 在不在跑，把变了的推给看着它的客户端。
// 没有结局的子代理在对话的进程活着、在回答或有后台任务时算在跑（前台的在回答里，后台的在后台任务里）。
func (c *Conversation) publishSubagent(l *subagentLog) {
	outcome := c.subagents.outcome(l)
	l.running = outcome == "" && c.proc != nil && (c.busy || c.backgroundTasks > 0)
	if len(l.watchers) == 0 {
		return
	}
	items := l.t.items(l.running)
	l.items = make([]json.RawMessage, len(items))
	for i, it := range items {
		l.items[i] = raw(it)
	}
	status := subagentStatus{
		Prompt: l.t.prompt, ToolUses: l.t.toolUses(), Running: l.running, Loaded: l.t.loaded,
		Missing: l.t.missing && l.t.meta == nil, Failure: l.failure, File: l.t.file, Skipped: l.t.skipped, Outcome: outcome,
	}
	status.State = subagentState(status)
	c.fillSubagentStatus(l, &status)
	encoded := raw(status)
	for client, w := range l.watchers {
		name, ok := c.holders[client]
		if !ok {
			continue
		}
		if msg := w.next(l.items, encoded); msg != nil {
			msg.Type, msg.Conv, msg.ToolUseID, msg.subagentStatus = "conv.subagent", name, l.toolUseID, status
			c.send(client, msg)
		}
	}
}

// next 是这次要推给这个客户端的：第一次是整份，之后只有变了的条目；什么都没变时为 nil。
func (w *subagentWatcher) next(items []json.RawMessage, status []byte) *subagentMsg {
	if !w.started {
		w.started, w.items, w.status = true, items, status
		return &subagentMsg{Full: true, Items: nonNilSlice(items)}
	}
	at, remove, replaced, changed := rawDelta(w.items, items)
	if !changed && bytes.Equal(status, w.status) {
		return nil
	}
	w.items, w.status = items, status
	return &subagentMsg{At: at, Remove: remove, Items: nonNilSlice(replaced)}
}

// rawDelta 是从 old 到 now 的一次替换（同 publishedItems.delta，按编好的 JSON 比）：从 at 起去掉 remove 项、
// 换成 replaced。没变时 changed 为 false。
func rawDelta(old, now []json.RawMessage) (at, remove int, replaced []json.RawMessage, changed bool) {
	for at < len(now) && at < len(old) && bytes.Equal(now[at], old[at]) {
		at++
	}
	tail := 0
	for tail < len(now)-at && tail < len(old)-at && bytes.Equal(now[len(now)-1-tail], old[len(old)-1-tail]) {
		tail++
	}
	if at == len(now) && at == len(old) {
		return 0, 0, nil, false
	}
	return at, len(old) - at - tail, now[at : len(now)-tail], true
}

// recent 是只留最近放进去的 subagentMaxIDs 项的字符串表；零值可用。
type recent struct {
	keys   []string
	values map[string]string
}

func (r *recent) get(key string) string { return r.values[key] }

// keyOf 是值为 value 的那一项的键（几项都是时取最近放进去的）；没有时为空。
func (r *recent) keyOf(value string) string {
	for _, k := range slices.Backward(r.keys) {
		if r.values[k] == value {
			return k
		}
	}
	return ""
}

// put 记下 key → value，算作最近的一项；超出上限时去掉最早的。
func (r *recent) put(key, value string) {
	if r.values == nil {
		r.values = map[string]string{}
	}
	if _, ok := r.values[key]; ok {
		r.keys = slices.DeleteFunc(r.keys, func(k string) bool { return k == key })
	}
	r.keys = append(r.keys, key)
	r.values[key] = value
	for len(r.keys) > subagentMaxIDs {
		delete(r.values, r.keys[0])
		r.keys = r.keys[1:]
	}
}

// subagentState 是查看页显示的状态（sessionproto 的 Subagent*），客户端只把它换成文案：在跑优先；还没读到记录时是正在读取，
// 读失败了是 read_failed；记录文件没有时是 missing；再看结局；不在跑又不知道结局的，是进程没等它做完就没了。
func subagentState(s subagentStatus) string {
	switch {
	case s.Running:
		return sessionproto.SubagentRunning
	case !s.Loaded && s.Failure != "":
		return sessionproto.SubagentReadFailed
	case !s.Loaded:
		return sessionproto.SubagentLoading
	case s.Missing:
		return sessionproto.SubagentMissing
	}
	switch s.Outcome {
	case sessionproto.SubagentCompleted, sessionproto.SubagentFailed, sessionproto.SubagentStopped:
		return s.Outcome
	}
	return sessionproto.SubagentUnfinished
}
