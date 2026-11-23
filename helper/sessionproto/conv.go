package sessionproto

import "github.com/dzshzx/Zeta/helper/locales"

// Todo 是 Claude 当前任务清单里的一项（conv.state 的 todos）。ActiveForm 是进行中时的说法（「正在运行测试」），没有时同 Content。
type Todo struct {
	Key        string `json:"key"`
	Content    string `json:"content"`
	ActiveForm string `json:"active_form"`
	// Status 是 TodoPending、TodoInProgress、TodoCompleted 之一
	Status string `json:"status"`
	// ContentCode：Claude 没给标题、Content 是核心起的兜底标题（「任务 #3」）时它的种类码（docs/i18n.md），参数 id 就是 Key；
	// 客户端按自己的语言出字（ContentIn），Content 是源语言，给不认这个码的旧客户端。不另带 args：Todo 要能用 == 比较
	ContentCode string `json:"content_code,omitempty"`
}

// ContentIn 是 Content 在语言 lang 里怎么说：兜底标题按种类码出字，Claude 给的标题照原文。
func (t Todo) ContentIn(lang string) string {
	if t.ContentCode == "" {
		return t.Content
	}
	return locales.Msg{Key: t.ContentCode, Args: map[string]any{"id": t.Key}}.In(lang)
}

// ActiveFormIn 是 ActiveForm 在语言 lang 里怎么说：它就是兜底标题时跟着 ContentIn。
func (t Todo) ActiveFormIn(lang string) string {
	if t.ActiveForm == t.Content {
		return t.ContentIn(lang)
	}
	return t.ActiveForm
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
