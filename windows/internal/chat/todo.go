package chat

import (
	"cmp"
	"encoding/json"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/transcript"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// 任务清单：从对话里 Claude 的清单工具调用还原它当前的清单（同手机版 core/TodoBoard.kt），输入栏上方的任务清单条用。
//
// Claude Code 有两套清单工具，同一时间只开其中一套：
//   - 任务工具（默认）：TaskCreate（subject、activeForm，结果文字「Task #<id> created successfully: …」给出编号）逐项新建，
//     TaskUpdate（taskId，可选 status、subject、activeForm；status 为 deleted 时删掉）逐项改；
//   - TodoWrite（设了 CLAUDE_CODE_ENABLE_TASKS=false 时）：每次给出整份清单 todos: [{content, status, activeForm}]。
//
// 归并照 Claude Code 自己给远程会话还原清单的做法：任务工具的清单不空就用它，否则用最后一次 TodoWrite。
// 另加一条：旧清单全部做完后又新建任务，算开了一份新清单，旧的那些不再显示。

// TodoStatus 是清单里一项的状态。
type TodoStatus string

const (
	TodoPending    TodoStatus = "pending"
	TodoInProgress TodoStatus = "in_progress"
	TodoCompleted  TodoStatus = "completed"
)

// Todo 是清单里的一项。ActiveForm 是进行中时的说法（「正在运行测试」），没有时同 Content。
type Todo struct {
	Key        string
	Content    string
	ActiveForm string
	Status     TodoStatus
}

// TodoList 是 Claude 当前的任务清单，按 Claude 列的先后。
type TodoList []Todo

// Done 是做完了几项。
func (l TodoList) Done() int {
	n := 0
	for _, t := range l {
		if t.Status == TodoCompleted {
			n++
		}
	}
	return n
}

// AllDone：全部做完了。
func (l TodoList) AllDone() bool { return l.Done() == len(l) }

// Current 是正在做的那一项，没有时为 nil。
func (l TodoList) Current() *Todo { return l.first(TodoInProgress) }

// Next 是接下来要做的那一项，没有时为 nil。
func (l TodoList) Next() *Todo { return l.first(TodoPending) }

func (l TodoList) first(s TodoStatus) *Todo {
	for i := range l {
		if l[i].Status == s {
			return &l[i]
		}
	}
	return nil
}

// Signature 认这一份清单的内容：各项的编号、状态、内容一样时相同（用户收掉的是哪一份）。
func (l TodoList) Signature() string {
	var b strings.Builder
	for _, t := range l {
		b.WriteString(t.Key + "\x1f" + string(t.Status) + "\x1f" + t.Content + "\x1e")
	}
	return b.String()
}

// todoTools 是清单工具。
var todoTools = map[string]bool{"TodoWrite": true, "TaskCreate": true, "TaskUpdate": true}

// Todos 是聊天条目（前面接上没载入的历史里的清单工具调用 seed，见 TodoSeed）里最新的清单；没有清单时为 nil。
func Todos(list []items.Item, seed []items.Step) TodoList {
	var b todoBoard
	for _, s := range seed {
		b.apply(s)
	}
	for _, it := range list {
		if it.Kind != items.Work {
			continue
		}
		for _, s := range it.Steps {
			if s.Kind == items.Tool && todoTools[s.Name] {
				b.apply(s)
			}
		}
	}
	return b.list()
}

// TodoSeed 把 conv.state 的 todo_seed（没载入的历史里的清单工具调用和结果，Claude Code 的 assistant、user 事件）
// 还原成工具步骤：用 serve 归约条目的同一个归约器，步骤和聊天条目里的一样。解不开的事件跳过。
func TodoSeed(raw json.RawMessage) []items.Step {
	var events []struct {
		Type    string             `json:"type"`
		Message transcript.Message `json:"message"`
	}
	if json.Unmarshal(raw, &events) != nil || len(events) == 0 {
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
	data, err := json.Marshal(r.Items)
	if err != nil {
		return nil
	}
	reduced, err := items.Decode(data)
	if err != nil {
		return nil
	}
	var steps []items.Step
	for _, it := range reduced {
		for _, s := range it.Steps {
			if s.Kind == items.Tool {
				steps = append(steps, s)
			}
		}
	}
	return steps
}

// createdID 认 TaskCreate 的结果里给的编号。
var createdID = regexp.MustCompile(`^Task #(\S+) created successfully`)

// todoBoard 按先后套用清单工具调用。
type todoBoard struct {
	// tasks 是已拿到编号的任务（键是编号），order 是它们新建的先后；creating 是发出了、还没拿到编号的 TaskCreate。
	tasks    map[string]Todo
	order    []string
	creating []Todo
	// written 是最后一次 TodoWrite 的整份清单。
	written TodoList
}

func (b *todoBoard) apply(s items.Step) {
	if s.Status == items.ToolFailed || s.Input == nil {
		return
	}
	switch s.Name {
	case "TaskCreate":
		b.create(s)
	case "TaskUpdate":
		b.update(s)
	case "TodoWrite":
		b.written = todoWrite(s)
	}
}

func todoWrite(s items.Step) TodoList {
	raw, _ := s.Input["todos"].([]any)
	list := TodoList{}
	for i, v := range raw {
		todo, _ := v.(map[string]any)
		content := str(todo, "content")
		if content == "" {
			continue
		}
		status := todoStatus(str(todo, "status"))
		if status == "" {
			status = TodoPending
		}
		list = append(list, Todo{Key: "todo-" + strconv.Itoa(i), Content: content, ActiveForm: cmp.Or(str(todo, "activeForm"), content), Status: status})
	}
	return list
}

func (b *todoBoard) create(s items.Step) {
	subject := cmp.Or(str(s.Input, "subject"), str(s.Input, "title"), str(s.Input, "name"))
	if subject == "" {
		return
	}
	active := cmp.Or(str(s.Input, "activeForm"), str(s.Input, "active_form"), subject)
	// 上一份清单已经全部做完：这是一份新清单
	if len(b.creating) == 0 && len(b.order) > 0 && b.allDone() {
		b.tasks, b.order = nil, nil
	}
	todo := Todo{Content: subject, ActiveForm: active, Status: TodoPending}
	m := createdID.FindStringSubmatch(strings.TrimSpace(s.Result))
	if s.Status != items.ToolSuccess || m == nil {
		todo.Key = s.ID
		b.creating = append(b.creating, todo)
		return
	}
	if _, ok := b.tasks[m[1]]; !ok {
		todo.Key = m[1]
		b.put(todo)
	}
}

func (b *todoBoard) update(s items.Step) {
	id := cmp.Or(str(s.Input, "taskId"), str(s.Input, "task_id"), str(s.Input, "id"))
	if id == "" {
		return
	}
	requested := str(s.Input, "status")
	if requested == "deleted" {
		b.remove(id)
		return
	}
	old, known := b.tasks[id]
	subject := str(s.Input, "subject")
	content := cmp.Or(subject, old.Content, "任务 #"+id)
	active := cmp.Or(str(s.Input, "activeForm"), str(s.Input, "active_form"))
	// 原来的进行中说法就是旧标题（没单写过）时，换了标题它也跟着换
	if active == "" && known && !(subject != "" && old.ActiveForm == old.Content) {
		active = old.ActiveForm
	}
	status := todoStatus(requested)
	if status == "" {
		status = old.Status
	}
	if status == "" {
		status = TodoPending
	}
	b.put(Todo{Key: id, Content: content, ActiveForm: cmp.Or(active, content), Status: status})
}

func (b *todoBoard) put(t Todo) {
	if b.tasks == nil {
		b.tasks = map[string]Todo{}
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
		if t.Status != TodoCompleted {
			return false
		}
	}
	return true
}

func (b *todoBoard) list() TodoList {
	var out TodoList
	for _, k := range b.order {
		out = append(out, b.tasks[k])
	}
	out = append(out, b.creating...)
	if len(out) == 0 {
		out = b.written
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func todoStatus(s string) TodoStatus {
	switch TodoStatus(s) {
	case TodoPending, TodoInProgress, TodoCompleted:
		return TodoStatus(s)
	}
	return ""
}

// str 是 m 里 key 的字符串值，去掉两头空白后为空时当没有。
func str(m map[string]any, key string) string {
	s, _ := m[key].(string)
	if strings.TrimSpace(s) == "" {
		return ""
	}
	return s
}
