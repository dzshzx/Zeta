package conv

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/transcript"
)

// 任务清单（conv.state 的 todos）：从对话里 Claude 的清单工具调用还原它当前的清单，核心归约时顺手维护，客户端照着画。
//
// Claude Code 有两套清单工具，同一时间只开其中一套：
//   - 任务工具（默认）：TaskCreate（subject、activeForm，结果文字「Task #<id> created successfully: …」给出编号）逐项新建，
//     TaskUpdate（taskId，可选 status、subject、activeForm；status 为 deleted 时删掉）逐项改；
//   - TodoWrite（设了 CLAUDE_CODE_ENABLE_TASKS=false 时）：每次给出整份清单 todos: [{content, status, activeForm}]。
//
// 归并照 Claude Code 自己给远程会话还原清单的做法：任务工具的清单不空就用它，否则用最后一次 TodoWrite。
// 另加一条：旧清单全部做完后又新建任务，算开了一份新清单，旧的那些不再显示。
// 接着旧会话时，没载入的更早历史里的清单工具调用由 zeta-helper history 给出（todo_seed），先套用它们。
// 清单的一项是 sessionproto.Todo，客户端照它解。

// todoTools 是清单工具。
var todoTools = map[string]bool{"TodoWrite": true, "TaskCreate": true, "TaskUpdate": true}

// createdID 认 TaskCreate 的结果里给的编号。
var createdID = regexp.MustCompile(`^Task #(\S+) created successfully`)

// todosOf 是 seed（没载入的历史里的清单工具调用）接上列表 items 以后最新的清单；没有清单时是空的。
func todosOf(seed []*transcript.Step, items []*transcript.Item) []sessionproto.Todo {
	var b todoBoard
	for _, s := range seed {
		b.apply(s)
	}
	for _, it := range items {
		if it.Kind != transcript.KindWork {
			continue
		}
		for _, s := range it.Steps {
			if s.Kind == transcript.StepTool && todoTools[s.Name] {
				b.apply(s)
			}
		}
	}
	return b.list()
}

// seedSteps 把 zeta-helper history 给的 todo_seed（Claude Code 的 assistant、user 事件）还原成工具步骤：
// 用归约条目的同一个归约器，步骤和列表里的一样。解不开时没有。
func seedSteps(raw json.RawMessage) []*transcript.Step {
	var events []struct {
		Type    string             `json:"type"`
		Message transcript.Message `json:"message"`
	}
	if json.Unmarshal(raw, &events) != nil {
		return nil
	}
	r := transcript.New("seed")
	for _, e := range events {
		switch e.Type {
		case "assistant":
			r.ApplyAssistant(e.Message, true)
		case "user":
			r.ApplyUser(e.Message, false, transcript.UserOptions{})
		}
	}
	var steps []*transcript.Step
	for _, it := range r.Items {
		for _, s := range it.Steps {
			if s.Kind == transcript.StepTool {
				steps = append(steps, s)
			}
		}
	}
	return steps
}

// todoBoard 按先后套用清单工具调用。
type todoBoard struct {
	tasks    map[string]sessionproto.Todo // 已拿到编号的任务
	order    []string                     // 它们新建的先后
	creating []sessionproto.Todo          // 发出了、还没拿到编号的 TaskCreate（键是工具调用的 id）
	written  []sessionproto.Todo          // 最后一次 TodoWrite 的整份清单
}

func (b *todoBoard) apply(s *transcript.Step) {
	var input map[string]any
	if s.Status == transcript.ToolFailed || json.Unmarshal(s.Input, &input) != nil || input == nil {
		return
	}
	switch s.Name {
	case "TaskCreate":
		b.create(s, input)
	case "TaskUpdate":
		b.update(input)
	case "TodoWrite":
		b.written = todoWrite(input)
	}
}

func todoWrite(input map[string]any) []sessionproto.Todo {
	all, _ := input["todos"].([]any)
	list := []sessionproto.Todo{}
	for i, v := range all {
		item, _ := v.(map[string]any)
		content := str(item, "content")
		if content == "" {
			continue
		}
		list = append(list, sessionproto.Todo{Key: "todo-" + strconv.Itoa(i), Content: content,
			ActiveForm: or(str(item, "activeForm"), content), Status: or(todoStatus(str(item, "status")), sessionproto.TodoPending)})
	}
	return list
}

func (b *todoBoard) create(s *transcript.Step, input map[string]any) {
	subject := or(str(input, "subject"), str(input, "title"), str(input, "name"))
	if subject == "" {
		return
	}
	t := sessionproto.Todo{Content: subject, ActiveForm: or(str(input, "activeForm"), str(input, "active_form"), subject), Status: sessionproto.TodoPending}
	// 上一份清单已经全部做完：这是一份新清单
	if len(b.creating) == 0 && len(b.order) > 0 && b.allDone() {
		b.tasks, b.order = nil, nil
	}
	m := createdID.FindStringSubmatch(strings.TrimSpace(s.Result))
	if s.Status != transcript.ToolSuccess || m == nil {
		t.Key = s.ID
		b.creating = append(b.creating, t)
		return
	}
	if _, ok := b.tasks[m[1]]; !ok {
		t.Key = m[1]
		b.put(t)
	}
}

func (b *todoBoard) update(input map[string]any) {
	id := or(str(input, "taskId"), str(input, "task_id"), str(input, "id"))
	if id == "" {
		return
	}
	requested := str(input, "status")
	if requested == "deleted" {
		b.remove(id)
		return
	}
	old, known := b.tasks[id]
	subject := str(input, "subject")
	content := or(subject, old.Content, "任务 #"+id)
	active := or(str(input, "activeForm"), str(input, "active_form"))
	// 原来的进行中说法就是旧标题（没单写过）时，换了标题它也跟着换
	if active == "" && known && (subject == "" || old.ActiveForm != old.Content) {
		active = old.ActiveForm
	}
	b.put(sessionproto.Todo{Key: id, Content: content, ActiveForm: or(active, content),
		Status: or(todoStatus(requested), old.Status, sessionproto.TodoPending)})
}

func (b *todoBoard) put(t sessionproto.Todo) {
	if b.tasks == nil {
		b.tasks = map[string]sessionproto.Todo{}
	}
	if _, ok := b.tasks[t.Key]; !ok {
		b.order = append(b.order, t.Key)
	}
	b.tasks[t.Key] = t
}

func (b *todoBoard) remove(id string) {
	if _, ok := b.tasks[id]; !ok {
		return
	}
	delete(b.tasks, id)
	for i, k := range b.order {
		if k == id {
			b.order = append(b.order[:i:i], b.order[i+1:]...)
			break
		}
	}
}

func (b *todoBoard) allDone() bool {
	for _, t := range b.tasks {
		if t.Status != sessionproto.TodoCompleted {
			return false
		}
	}
	return true
}

// list 是现在的清单：任务工具的不空就用它，否则用最后一次 TodoWrite。
func (b *todoBoard) list() []sessionproto.Todo {
	out := []sessionproto.Todo{}
	for _, k := range b.order {
		out = append(out, b.tasks[k])
	}
	out = append(out, b.creating...)
	if len(out) == 0 && b.written != nil {
		out = b.written
	}
	return out
}

func todoStatus(s string) string {
	switch s {
	case sessionproto.TodoPending, sessionproto.TodoInProgress, sessionproto.TodoCompleted:
		return s
	}
	return ""
}

// str 是 m 里 key 的字符串值，只有空白时当没有。
func str(m map[string]any, key string) string {
	s, _ := m[key].(string)
	if strings.TrimSpace(s) == "" {
		return ""
	}
	return s
}

// or 是第一个不空的。
func or(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// setTodoSeed 换上没载入的历史里的清单工具调用（载入历史、往前翻一页时，zeta-helper history 的 todo_seed），还原成步骤。
func (c *Conversation) setTodoSeed(raw json.RawMessage) {
	c.todoSeed = seedSteps(raw)
}
