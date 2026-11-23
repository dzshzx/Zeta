package chat

import (
	"encoding/json"
	"reflect"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/helper/sessionproto"
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
	// EarlierLoading：正在载入更早的一页（conv.earlier）。
	EarlierLoading bool `json:"earlier_loading"`
	// Queued 是排队等发的消息，Recalled 是撤回放回输入框、还没取走的那条。
	Queued   []coreapi.Message `json:"queued"`
	Recalled *coreapi.Message  `json:"recalled"`
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
	// Models 是 Claude Code 报的可选模型，进程还没起过时为空。
	Models []ModelOption `json:"models"`
	// Commands 是斜杠命令：Claude Code 报的，还没起进程时是核心记着的这个工作目录（或这台主机）上次那份；内部命令已滤掉。
	Commands []Command `json:"commands"`
	// Todos 是 Claude 当前的任务清单（核心归并好的），没有时为空。
	Todos []sessionproto.Todo `json:"todos"`
	// Occupant 是会话被别处占着（Blocked）时那个进程的样子，核心盯着、变了才推；不占着或还没查到时为 nil。
	Occupant *sessionproto.Occupancy `json:"occupant"`
	// Key 是这个对话在核心里的标识：还没有会话号的分支断线重连时按它重新打开。
	Key string `json:"key"`
	// CanRewind：现在能编辑、重新生成、删除一轮、还原代码、分支。Rewinding：正在等 Claude Code 剪会话或还原代码。
	CanRewind bool `json:"can_rewind"`
	Rewinding bool `json:"rewinding"`
	// LastActivity 是核心记的最近一次有动静的时刻（主机的 Unix 毫秒，取整到秒）：发出消息、一轮开始、输出改了条目、重试、压缩时更新。
	// 界面只看它变没变（主机和这台电脑的钟可能对不上），算「已等待 N 秒」。
	LastActivity int64 `json:"last_activity"`
}

// Mode 是权限模式：User 是用户选的，Process 是进程此刻实际在用的（计划模式批准后等会变）。
type Mode struct {
	User    string `json:"user"`
	Process string `json:"process"`
}

// stateFields 是 State 各字段的 json 名 → 字段下标（applyState 清掉增量里出现的字段用）。
var stateFields = func() map[string]int {
	t := reflect.TypeFor[State]()
	m := make(map[string]int, t.NumField())
	for i := range t.NumField() {
		name, _, _ := strings.Cut(t.Field(i).Tag.Get("json"), ",")
		m[name] = i
	}
	return m
}()

// applyState 把一条 conv.state 推送 line 合到 s 上，返回新的一份：full 时从零值解，否则先把增量里出现的字段清成零值
// （切片、指针、结构体都换成新解出来的，不往旧的里面写：旧的那份界面可能还拿着），再只解这一行。
func applyState(s State, line []byte, full bool) (State, error) {
	if full {
		s = State{}
	} else {
		var present map[string]json.RawMessage
		if err := json.Unmarshal(line, &present); err != nil {
			return State{}, err
		}
		v := reflect.ValueOf(&s).Elem()
		for name := range present {
			if i, ok := stateFields[name]; ok {
				v.Field(i).SetZero()
			}
		}
	}
	err := json.Unmarshal(line, &s)
	return s, err
}

// RunningTasks 是还在跑的后台任务数。
func (s State) RunningTasks() int {
	n := 0
	for _, t := range s.Tasks {
		if t.Running() {
			n++
		}
	}
	return n
}

// ModelOption 是模型菜单里的一项：Claude Code 的 initialize 应答里 models 的一项，原样转来（键是它的驼峰写法）。
type ModelOption struct {
	Value       string `json:"value"`
	DisplayName string `json:"displayName"`
	Description string `json:"description"`
	// ResolvedModel 是这一项现在对应的完整模型 ID，没报时为空。
	ResolvedModel string `json:"resolvedModel"`
}

// DefaultModel 是模型菜单里「默认」那一项的值（Claude Code 认的写法）。
const DefaultModel = "default"
