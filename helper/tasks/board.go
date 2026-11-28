package tasks

import (
	"slices"
	"strings"
)

// DefaultKeepFinished 是 New 建的看板留下的已结束任务数。
const DefaultKeepFinished = 20

// maxRemembered 是记着的工具调用、还没进清单的 task_started 各自最多几个。
const maxRemembered = 64

// taskTools 是会起后台任务的工具。Task 是 Agent 的旧名；同时兼容 Antigravity 工具名。
var taskTools = map[string]bool{
	"Bash": true, "Monitor": true, "Agent": true, "Task": true,
	"run_command": true, "invoke_subagent": true, "run_workflow": true, "manage_task": true,
}

// Board 把 Claude Code 的事件归约成后台任务清单。
//
// 列出的是进过 background_tasks_changed 清单的任务：前台跑着的（比如没放后台的子代理）只记下它的 task_started，
// 等它转到后台进了清单再列出来。结束了的留最近 keepFinished 个，好让用户看结局和输出。
type Board struct {
	keepFinished int
	order        []string // 任务按第一次列出的先后
	entries      map[string]*Task
	// started 是还没进清单的任务的 task_started 和收到它的时间。
	started *recent[startedTask]
	// calls 是主对话最近发起的、可能成为后台任务的工具调用（tool_use id → 工具名和参数），命令要从这里取。
	calls *recent[call]
}

type startedTask struct {
	event *event
	at    int64
}

type call struct {
	name  string
	input *toolInput
}

// toolInput 是工具调用参数里取命令要用的几项。
type toolInput struct {
	Command     *string `json:"command"`
	CommandLine *string `json:"CommandLine"`
	Prompt      *string `json:"prompt"`
	PromptUpper *string `json:"Prompt"`
	Role        *string `json:"Role"`
	ScriptPath  *string `json:"ScriptPath"`
	WS          *struct {
		URL *string `json:"url"`
	} `json:"ws"`
}

// New 建一个看板，已结束的留 DefaultKeepFinished 个。
func New() *Board { return NewKeeping(DefaultKeepFinished) }

// NewKeeping 建一个看板，已结束的留 keepFinished 个。
func NewKeeping(keepFinished int) *Board {
	return &Board{keepFinished: keepFinished, entries: map[string]*Task{},
		started: newRecent[startedTask](maxRemembered), calls: newRecent[call](maxRemembered)}
}

// Tasks 是现在的清单：在跑的排前面，各自按开始时间新的在前（一样时按列出的先后）。返回的是副本。
func (b *Board) Tasks() []Task {
	list := make([]Task, 0, len(b.order))
	for _, id := range b.order {
		list = append(list, *b.entries[id])
	}
	slices.SortStableFunc(list, func(x, y Task) int {
		if x.Running() != y.Running() {
			if x.Running() {
				return -1
			}
			return 1
		}
		switch {
		case x.StartedAt > y.StartedAt:
			return -1
		case x.StartedAt < y.StartedAt:
			return 1
		}
		return 0
	})
	return list
}

// Running 是在跑的任务数。
func (b *Board) Running() int {
	n := 0
	for _, t := range b.entries {
		if t.Running() {
			n++
		}
	}
	return n
}

// event 是一行输出事件里后台任务要看的部分。
type event struct {
	Type            string  `json:"type"`
	Subtype         string  `json:"subtype"`
	ParentToolUseID *string `json:"parent_tool_use_id"`
	Message         *struct {
		Content []struct {
			Type  string     `json:"type"`
			ID    string     `json:"id"`
			Name  string     `json:"name"`
			Input *toolInput `json:"input"`
		} `json:"content"`
	} `json:"message"` // assistant

	TaskID       string  `json:"task_id"`
	ToolUseID    string  `json:"tool_use_id"`
	TaskType     string  `json:"task_type"`
	Description  *string `json:"description"`
	SubagentType string  `json:"subagent_type"`
	Prompt       *string `json:"prompt"`
	// 接回后台进程时，zeta-helper hosts 给 task_started 带上发起它的工具调用和当时的记录时间
	ZetaTool *struct {
		Name  string     `json:"name"`
		Input *toolInput `json:"input"`
	} `json:"zeta_tool"`
	ZetaAt *int64 `json:"zeta_at"`

	Tasks []struct { // background_tasks_changed
		TaskID       string `json:"task_id"`
		TaskType     string `json:"task_type"`
		Description  string `json:"description"`
		SubagentType string `json:"subagent_type"`
	} `json:"tasks"`
	Usage *struct { // task_progress
		ToolUses *int `json:"tool_uses"`
	} `json:"usage"`
	Patch *struct { // task_updated
		Status      *string `json:"status"`
		EndTime     *int64  `json:"end_time"`
		Description string  `json:"description"`
		Error       *string `json:"error"`
	} `json:"patch"`
	Status  string  `json:"status"`  // task_notification
	Summary *string `json:"summary"` // task_notification
}

// Observe 处理一行输出事件，返回清单有没有变。now 是收到它的时间（毫秒）。
func (b *Board) Observe(line []byte, now int64) bool {
	var e event
	if !decode(line, &e) {
		return false
	}
	switch e.Type {
	case "assistant":
		b.rememberCalls(&e)
	case "system":
		switch e.Subtype {
		case "task_started":
			return b.taskStarted(&e, now)
		case "background_tasks_changed":
			return b.listed(&e, now)
		case "task_progress":
			return b.progressed(&e)
		case "task_updated":
			return b.patched(&e)
		case "task_notification":
			return b.notified(&e, now)
		}
	}
	return false
}

// StopRequested：发出了 stop_task，先显示「正在停止」。返回清单有没有变。
func (b *Board) StopRequested(id string) bool { return b.markStopping(id, true) }

// StopAnswered 处理 stop_task 的应答（control_response 里的 response 一项）。Claude Code 对不认识的任务也答 success，
// 真停没停看随后的 task_updated；答错时把「正在停止」撤掉。返回清单有没有变。
func (b *Board) StopAnswered(id string, answer []byte) bool {
	var a struct {
		Subtype string `json:"subtype"`
	}
	decode(answer, &a)
	return a.Subtype == "error" && b.markStopping(id, false)
}

func (b *Board) markStopping(id string, stopping bool) bool {
	t := b.entries[id]
	if t == nil || !t.Running() || t.Stopping == stopping {
		return false
	}
	t.Stopping = stopping
	return true
}

// ProcessEnded：Claude Code 进程没了，它的后台任务也都跟着没了。返回清单有没有变。
func (b *Board) ProcessEnded(now int64) bool {
	changed := false
	for _, t := range b.entries {
		if t.Running() {
			t.State, t.EndedAt, t.Stopping = StateEnded, ptr(now), false
			changed = true
		}
	}
	b.started.clear()
	return changed
}

// ClearFinished 清掉已结束的，返回有没有清掉。
func (b *Board) ClearFinished() bool {
	return b.removeWhere(func(t *Task) bool { return !t.Running() })
}

func (b *Board) removeWhere(drop func(*Task) bool) bool {
	kept := b.order[:0]
	for _, id := range b.order {
		if drop(b.entries[id]) {
			delete(b.entries, id)
		} else {
			kept = append(kept, id)
		}
	}
	removed := len(kept) < len(b.order)
	b.order = kept
	return removed
}

func (b *Board) rememberCalls(e *event) {
	// 子代理里的调用不是主对话的；它起的后台任务就只显示说明
	if e.ParentToolUseID != nil || e.Message == nil {
		return
	}
	for _, use := range e.Message.Content {
		if use.Type == "tool_use" && taskTools[use.Name] && use.ID != "" {
			b.calls.put(use.ID, call{use.Name, use.Input})
		}
	}
}

func (b *Board) taskStarted(e *event, now int64) bool {
	if e.TaskID == "" {
		return false
	}
	if tool := e.ZetaTool; tool != nil && e.ToolUseID != "" {
		if _, known := b.calls.get(e.ToolUseID); !known {
			b.calls.put(e.ToolUseID, call{tool.Name, tool.Input})
		}
	}
	at := now
	if e.ZetaAt != nil {
		at = *e.ZetaAt
	}
	t := b.entries[e.TaskID]
	if t == nil {
		// 还没进后台清单（前台的，或者清单那条还没到）：先记着；记过的不换（续派又来一次，见 enrich）
		if _, seen := b.started.get(e.TaskID); !seen {
			b.started.put(e.TaskID, startedTask{e, at})
		}
		return false
	}
	b.enrich(t, e)
	return true
}

// enrich 用 task_started 补全：发起它的调用、命令、子代理类型。发起它的调用以第一次为准：停下的子代理被 SendMessage
// 续派时，Claude Code 对同一个任务再发一次 task_started，带的是那次 SendMessage 调用（2.1.330 实测），
// 换成它的话子代理查看页按调用找记录就对不上了。
func (b *Board) enrich(t *Task, start *event) {
	use := t.ToolUseID
	if use == "" {
		use = start.ToolUseID
	}
	c, _ := b.calls.get(use)
	if blank(t.Type) {
		t.Type = start.TaskType
	}
	t.Kind = kindOf(t.Type, c.name)
	t.ToolUseID = use
	if t.Command == "" {
		t.Command = commandOf(c)
	}
	if t.Command == "" && start.Prompt != nil {
		t.Command = *start.Prompt
	}
	if t.AgentType == "" {
		t.AgentType = start.SubagentType
	}
	if blank(t.Description) {
		t.Description = ""
		if start.Description != nil {
			t.Description = *start.Description
		}
	}
}

func (b *Board) listed(e *event, now int64) bool {
	listed := map[string]bool{}
	changed := false
	for _, item := range e.Tasks {
		if item.TaskID == "" {
			continue
		}
		listed[item.TaskID] = true
		t := b.entries[item.TaskID]
		switch {
		case t == nil:
			created := &Task{ID: item.TaskID, Type: item.TaskType, Kind: kindOf(item.TaskType, ""),
				Description: item.Description, AgentType: item.SubagentType, StartedAt: now, State: StateRunning}
			if s, ok := b.started.remove(item.TaskID); ok {
				created.StartedAt = s.at
				b.enrich(created, s.event)
			}
			b.entries[item.TaskID] = created
			b.order = append(b.order, item.TaskID)
			changed = true
		case !t.Running():
			// 结束了又回到清单（续跑的子代理）：重新算在跑
			t.State, t.EndedAt, t.Stopping, t.Summary, t.Error = StateRunning, nil, false, "", ""
			changed = true
		case !blank(item.Description) && item.Description != t.Description:
			t.Description = item.Description
			changed = true
		}
	}
	// 从清单里消失、又没收到结局的：结束了，但不知道怎么结束的
	for id, t := range b.entries {
		if t.Running() && !listed[id] {
			t.State, t.Stopping = StateEnded, false
			if t.EndedAt == nil {
				t.EndedAt = ptr(now)
			}
			changed = true
		}
	}
	return b.trim() || changed
}

func (b *Board) progressed(e *event) bool {
	t := b.entries[e.TaskID]
	if t == nil {
		return false
	}
	before := *t
	if e.Description != nil && !blank(*e.Description) {
		t.Progress = *e.Description
	}
	if e.Usage != nil && e.Usage.ToolUses != nil {
		t.ToolUses = ptr(*e.Usage.ToolUses)
	}
	return !same(before, *t)
}

func (b *Board) patched(e *event) bool {
	t := b.entries[e.TaskID]
	if t == nil || e.Patch == nil {
		return false
	}
	before := *t
	p := e.Patch
	if p.Status != nil {
		t.State = stateOf(*p.Status)
	}
	t.Stopping = t.Stopping && t.Running()
	switch {
	case t.Running():
		t.EndedAt = nil
	case p.EndTime != nil:
		t.EndedAt = ptr(*p.EndTime)
	}
	if !blank(p.Description) {
		t.Description = p.Description
	}
	if p.Error != nil {
		t.Error = *p.Error
	}
	return !same(before, *t)
}

func (b *Board) notified(e *event, now int64) bool {
	t := b.entries[e.TaskID]
	if t == nil {
		return false
	}
	before := *t
	switch e.Status {
	case "completed":
		t.State = StateCompleted
	case "failed":
		t.State = StateFailed
	case "stopped":
		t.State = StateStopped
	}
	t.Stopping = false
	switch {
	case t.Running():
		t.EndedAt = nil
	case t.EndedAt == nil:
		t.EndedAt = ptr(now)
	}
	if e.Summary != nil {
		if s := strings.TrimSpace(*e.Summary); s != "" && s != t.Description {
			t.Summary = s
		}
	}
	trimmed := b.trim()
	return trimmed || !same(before, *t)
}

// trim：已结束的只留最近 keepFinished 个（按结束时间，没有时按开始时间）。返回有没有去掉。
func (b *Board) trim() bool {
	var finished []*Task
	for _, id := range b.order {
		if t := b.entries[id]; !t.Running() {
			finished = append(finished, t)
		}
	}
	if len(finished) <= b.keepFinished {
		return false
	}
	slices.SortStableFunc(finished, func(x, y *Task) int {
		switch ex, ey := finishedAt(x), finishedAt(y); {
		case ex > ey:
			return -1
		case ex < ey:
			return 1
		}
		return 0
	})
	drop := map[*Task]bool{}
	for _, t := range finished[b.keepFinished:] {
		drop[t] = true
	}
	return b.removeWhere(func(t *Task) bool { return drop[t] })
}

func finishedAt(t *Task) int64 {
	if t.EndedAt != nil {
		return *t.EndedAt
	}
	return t.StartedAt
}

func blank(s string) bool { return strings.TrimSpace(s) == "" }

// kindOf 由 task_type 和发起它的工具（不知道时为空）定种类：local_bash 由 Monitor 起的是监视器。
func kindOf(taskType, tool string) Kind {
	switch taskType {
	case "local_bash":
		if tool == "Monitor" {
			return KindMonitor
		}
		return KindCommand
	case "local_agent":
		return KindAgent
	}
	return KindOther
}

// commandOf 是工具调用里的命令：Bash 的 command、Monitor 的 command（没有时是它盯的 ws 地址），别的工具是 prompt。
func commandOf(c call) string {
	in := c.input
	if in == nil {
		return ""
	}
	var command *string
	switch c.name {
	case "Bash":
		command = in.Command
	case "run_command":
		command = firstNonBlankPtr(in.CommandLine, in.Command)
	case "Monitor":
		command = in.Command
		if command == nil && in.WS != nil {
			command = in.WS.URL
		}
	case "run_workflow":
		command = firstNonBlankPtr(in.ScriptPath, in.PromptUpper, in.Prompt)
	default:
		command = firstNonBlankPtr(in.Prompt, in.PromptUpper, in.Role)
	}
	if command == nil || blank(*command) {
		return ""
	}
	return *command
}

func firstNonBlankPtr(ptrs ...*string) *string {
	for _, p := range ptrs {
		if p != nil && !blank(*p) {
			return p
		}
	}
	return nil
}

// stateOf 是 task_updated 的 status 对应的状态：pending、running、paused 都算在跑。
func stateOf(status string) State {
	switch status {
	case "completed":
		return StateCompleted
	case "failed":
		return StateFailed
	case "killed":
		return StateStopped
	}
	return StateRunning
}

// recent 是按放入先后排的小表：同一个键再放一次挪到最后，超过 limit 个时丢最早的。
type recent[V any] struct {
	limit int
	keys  []string
	vals  map[string]V
}

func newRecent[V any](limit int) *recent[V] { return &recent[V]{limit: limit, vals: map[string]V{}} }

func (r *recent[V]) get(key string) (V, bool) {
	v, ok := r.vals[key]
	return v, ok
}

func (r *recent[V]) put(key string, v V) {
	r.remove(key)
	r.keys = append(r.keys, key)
	r.vals[key] = v
	for len(r.keys) > r.limit {
		delete(r.vals, r.keys[0])
		r.keys = r.keys[1:]
	}
}

func (r *recent[V]) remove(key string) (V, bool) {
	v, ok := r.vals[key]
	if ok {
		delete(r.vals, key)
		r.keys = slices.DeleteFunc(r.keys, func(k string) bool { return k == key })
	}
	return v, ok
}

func (r *recent[V]) clear() {
	r.keys = nil
	clear(r.vals)
}
