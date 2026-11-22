package chat

import (
	"encoding/json"

	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/helper/usage"
)

// State 是 conv.state 合并到现在的样子（字段见 docs/core.md「对话」）。核心没推来的字段是零值。
// 每次推送都解出一份新的，界面拿到的 State 不会被后来的推送改掉；界面只读它。
type State struct {
	Session string `json:"session"`
	Title   string `json:"title"`
	Cwd     string `json:"cwd"`
	// Process：接着 Claude Code 进程。
	Process bool `json:"process"`
	// Busy：在回答。
	Busy bool `json:"busy"`
	// Status 是等待指示旁的一句，比如正在重试。
	Status string `json:"status"`
	// Blocked：会话正被 Zeta 之外的进程占着。
	Blocked        bool `json:"blocked"`
	LoadingHistory bool `json:"loading_history"`
	// Earlier 是会话记录里还没载入的更早事件数，0 是全载入了。
	Earlier int `json:"earlier"`
	// Queued 是排队等发的消息，Recalled 是撤回放回输入框、还没取走的那条。
	Queued   []Message `json:"queued"`
	Recalled *Message  `json:"recalled"`
	// Steering：立即插话发出去了，还没有结果。
	Steering bool `json:"steering"`
	// Model、Effort 是这个对话指定的（nil 是用默认），ReportedModel 是 Claude Code 报的。
	Model         *string `json:"model"`
	Effort        *string `json:"effort"`
	ReportedModel string  `json:"reported_model"`
	Mode          Mode    `json:"mode"`
	// Prompts 是等用户回应的请求，按到达顺序，界面弹第一个。
	Prompts []prompt.Prompt `json:"prompts"`
	Tasks   []tasks.Task    `json:"tasks"`
	Usage   usage.Usage     `json:"usage"`
	// Commands、Models 是 Claude Code 报的斜杠命令和可选模型，原样。
	Commands json.RawMessage `json:"commands"`
	Models   json.RawMessage `json:"models"`
	// TodoSeed 是没载入的更早历史里的待办清单工具调用，原样，界面拼出当前待办用。
	TodoSeed json.RawMessage `json:"todo_seed"`
}

// Message 是排队的、撤回的一条消息。
type Message struct {
	UUID   string   `json:"uuid"`
	Text   string   `json:"text"`
	Images []Image  `json:"images"`
	Files  []string `json:"files"`
}

// Image 是随消息发的一张图：base64 的数据和它的 MIME 类型。
type Image struct {
	MediaType string `json:"media_type"`
	Data      string `json:"data"`
}

// Mode 是权限模式：User 是用户选的，Process 是进程此刻实际在用的（计划模式批准后等会变）。
type Mode struct {
	User    string `json:"user"`
	Process string `json:"process"`
}

func decodeState(fields map[string]json.RawMessage) (State, error) {
	b, err := json.Marshal(fields)
	if err != nil {
		return State{}, err
	}
	var s State
	err = json.Unmarshal(b, &s)
	return s, err
}
