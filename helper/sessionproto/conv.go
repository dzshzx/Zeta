package sessionproto

// Todo 是 Claude 当前任务清单里的一项（conv.state 的 todos）。ActiveForm 是进行中时的说法（「正在运行测试」），没有时同 Content。
type Todo struct {
	Key        string `json:"key"`
	Content    string `json:"content"`
	ActiveForm string `json:"active_form"`
	// Status 是 TodoPending、TodoInProgress、TodoCompleted 之一
	Status string `json:"status"`
}

// 任务清单里一项的状态（Todo.Status）。
const (
	TodoPending    = "pending"
	TodoInProgress = "in_progress"
	TodoCompleted  = "completed"
)

// 子代理查看页的状态（conv.subagent 的 state），核心按下面的先后定，客户端只把它换成文案：
// 在跑优先；还没读到记录时是正在读取，读失败的是 read_failed；记录文件没有时是 missing；
// 再看结局；不在跑又不知道结局的，是进程没等它做完就没了（unfinished）。
const (
	SubagentRunning    = "running"
	SubagentLoading    = "loading"
	SubagentReadFailed = "read_failed"
	SubagentMissing    = "missing"
	SubagentCompleted  = "completed"
	SubagentFailed     = "failed"
	SubagentStopped    = "stopped"
	SubagentUnfinished = "unfinished"
)
