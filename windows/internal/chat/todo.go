package chat

// 任务清单：Claude 当前的任务清单由核心从清单工具调用归并好（conv.state 的 todos，规则见 docs/core.md「对话」），
// 输入栏上方的任务清单条照它画。

// TodoStatus 是清单里一项的状态。
type TodoStatus string

const (
	TodoPending    TodoStatus = "pending"
	TodoInProgress TodoStatus = "in_progress"
	TodoCompleted  TodoStatus = "completed"
)

// Todo 是清单里的一项。ActiveForm 是进行中时的说法（「正在运行测试」），没有时同 Content。
type Todo struct {
	Key        string     `json:"key"`
	Content    string     `json:"content"`
	ActiveForm string     `json:"active_form"`
	Status     TodoStatus `json:"status"`
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
