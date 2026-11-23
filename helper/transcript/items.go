// Package transcript 把 Claude Code 的 stream-json 事件和会话记录归并成聊天列表的条目。条目的 JSON 就是
// zeta-helper serve 推给客户端的格式（见 docs/core.md），客户端照字段名读，没写出的字段是它的零值。
//
// 对照用例在 ../testdata/transcript，期望结果的录法见 parity_test.go。
package transcript

import "encoding/json"

// Kind 是条目的种类。
type Kind string

const (
	KindUser   Kind = "user"   // 用户发的一条消息
	KindAnswer Kind = "answer" // 一段回答文字
	KindNotice Kind = "notice" // Zeta 自己加的提示（出错、已停止等）
	KindWork   Kind = "work"   // 连续的思考与工具调用折成的一组
	// KindThought 是写给用户看的一段中间说明：带文字的思考块。Claude Code 的思考块平时只有签名、没有文字（界面上折进工作组，
	// 显示「思考已完成」）；带文字的是 Claude 在工具调用之间说它在做什么、接下来做什么，同 ChatGPT 流式输出里的中间消息，
	// 不折起来，单独一项排在时间线上。
	KindThought Kind = "thought"
)

// Item 是聊天列表的一项。各种类只用到自己那几个字段。
type Item struct {
	Kind Kind   `json:"kind"`
	ID   string `json:"id"`
	Text string `json:"text,omitempty"` // user、answer、notice、thought

	Images []Image  `json:"images,omitempty"` // user
	Files  []string `json:"files,omitempty"`  // user：随消息附带的路径（附件行，见 SplitAttachedFiles）
	UUID   string   `json:"uuid,omitempty"`   // user：这条消息在会话记录里的 uuid
	Peer   string   `json:"peer,omitempty"`   // user：另一个 Claude Code 会话发来的（会话互通）时，发信会话的名字

	// user：这一轮能做什么。归约器不管，由核心按对话的规则算好（SetRound，见 docs/core.md 的 conv.items）
	Rewindable bool   `json:"rewindable,omitempty"`
	Forkable   bool   `json:"forkable,omitempty"`
	RoundLast  string `json:"round_last,omitempty"`

	Streaming bool `json:"streaming,omitempty"` // answer、thought：还在逐字送来
	Error     bool `json:"error,omitempty"`     // notice

	Steps []*Step `json:"steps,omitempty"` // work

	rev int
}

// Rev 是这一项改过几次：归约器每改一次加一，推送时据此只送变了的项。
func (it *Item) Rev() int { return it.rev }

// Round 是用户消息所在这一轮能做什么（Item 的 Rewindable、Forkable、RoundLast）。
type Round struct {
	Rewindable bool
	Forkable   bool
	Last       string
}

// SetRound 记下这一轮能做什么；变了才算改过一次，推送时据此重推这一项。
func (it *Item) SetRound(r Round) {
	if it.Rewindable == r.Rewindable && it.Forkable == r.Forkable && it.RoundLast == r.Last {
		return
	}
	it.Rewindable, it.Forkable, it.RoundLast = r.Rewindable, r.Forkable, r.Last
	touch(it)
}

// Running：工作组里还有在跑的一步。
func (it *Item) Running() bool {
	for _, s := range it.Steps {
		if s.Running || s.Status == ToolRunning {
			return true
		}
	}
	return false
}

// StepKind 是工作组里一步的种类。
type StepKind string

const (
	StepThinking StepKind = "thinking"
	StepTool     StepKind = "tool"
)

// ToolStatus 是工具调用的状态。
type ToolStatus string

const (
	ToolRunning ToolStatus = "running"
	ToolSuccess ToolStatus = "success"
	ToolFailed  ToolStatus = "failed"
)

// Step 是工作组里的一步：一段思考，或一次工具调用。
type Step struct {
	Kind StepKind `json:"kind"`
	ID   string   `json:"id"`

	Text    string `json:"text,omitempty"`    // thinking
	Running bool   `json:"running,omitempty"` // thinking：还在想
	Seconds int    `json:"seconds,omitempty"` // thinking：想了几秒，只有实时看着它想完的才知道

	Name   string          `json:"name,omitempty"`   // tool
	Input  json.RawMessage `json:"input,omitempty"`  // tool：调用的参数（对象），还没收到时没有
	Status ToolStatus      `json:"status,omitempty"` // tool
	Result string          `json:"result,omitempty"` // tool：结果的文字，status 不是 running 时才有意义
	Images []Image         `json:"images,omitempty"` // tool：结果里的图
	Patch  *Patch          `json:"patch,omitempty"`  // tool：改文件的工具给出的改动
}

// Image 是聊天里的一张图：实时收到的带着数据，会话记录里的只有位置。
type Image struct {
	Kind string `json:"kind"`           // inline 或 session
	Key  string `json:"key,omitempty"`  // inline：缓存键
	Data string `json:"data,omitempty"` // inline：base64
	File string `json:"file,omitempty"` // session：会话记录
	Ref  string `json:"ref,omitempty"`  // session：在记录里的位置（zeta-helper history 给的）
}

// Patch 是改文件的工具的结果里 Claude Code 给出的改动（tool_use_result 的 structuredPatch）。
type Patch struct {
	Created bool   `json:"created,omitempty"` // Write 新建了文件
	Hunks   []Hunk `json:"hunks"`
}

// Hunk 是一段连续的改动连同上下文。
type Hunk struct {
	Lines []DiffLine `json:"lines"`
}

// DiffLine 是对比里的一行。Old、New 是它在原文件、新文件里的行号（从 1 起），不适用时为 0。
type DiffLine struct {
	Kind string `json:"kind"` // context、added、removed
	Text string `json:"text"`
	Old  int    `json:"old,omitempty"`
	New  int    `json:"new,omitempty"`
}
