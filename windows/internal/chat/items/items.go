// Package items 是聊天列表的条目：zeta-helper 的 history 和 serve 推来的条目 JSON（格式见 helper/transcript/items.go）
// 解码成的结构体。只依赖 JSON：没写出的字段、写成 null 的字段都按零值处理。
package items

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Kind 是条目的种类。
type Kind string

const (
	User   Kind = "user"   // 用户发的一条消息
	Answer Kind = "answer" // 一段回答
	Notice Kind = "notice" // Zeta 自己加的提示（出错、已停止等）
	Work   Kind = "work"   // 连续的思考与工具调用折成的一组
)

// Item 是聊天列表的一项。各种类只用到其中几个字段，没用到的是零值。
type Item struct {
	Kind Kind   `json:"kind"`
	ID   string `json:"id"`
	// Text：user、answer、notice 的文字。
	Text string `json:"text"`
	// Images、Files、UUID：user 随消息带的图、附带的路径（见 Kotlin 的 AttachedPaths）、在会话记录里的 uuid。
	Images []Image  `json:"images"`
	Files  []string `json:"files"`
	UUID   string   `json:"uuid"`
	// Rewindable、Forkable、RoundLast：user 这一轮能做什么，核心算好的（docs/core.md 的 conv.items）。Rewindable 是能编辑、
	// 重新生成、删除这一轮、还原代码；Forkable 是能从这一轮之后分支；RoundLast 是这一轮的操作排挂在哪一项下面，
	// 这一轮除了用户消息只有灰色提示时为空。真能做还要 conv.state 的 can_rewind 为真。
	Rewindable bool   `json:"rewindable"`
	Forkable   bool   `json:"forkable"`
	RoundLast  string `json:"round_last"`
	// Streaming：answer 还在逐字送来。
	Streaming bool `json:"streaming"`
	// Error：notice 是出错的提示。
	Error bool `json:"error"`
	// Steps：work 里的各步。
	Steps []Step `json:"steps"`
}

// Running 报告工作组里还有没有在跑的一步。
func (it Item) Running() bool {
	for _, s := range it.Steps {
		if s.Running() {
			return true
		}
	}
	return false
}

// StepKind 是工作组里一步的种类。
type StepKind string

const (
	Thinking StepKind = "thinking"
	Tool     StepKind = "tool"
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
	// 思考：Text 是想的内容，Thinking 是还在想，Seconds 是想了几秒（只有实时看着它想完的才知道，否则为 nil）。
	Text     string `json:"text"`
	Thinking bool   `json:"running"`
	Seconds  *int   `json:"seconds"`
	// 工具调用：Name 是工具名，Input 是参数（还没收到时为 nil），Result 是结果的文字，Images 是结果里的图，
	// Patch 是改文件工具给出的改动。
	Name   string         `json:"name"`
	Input  map[string]any `json:"input"`
	Status ToolStatus     `json:"status"`
	Result string         `json:"result"`
	Images []Image        `json:"images"`
	Patch  *Patch         `json:"patch"`
}

// Running 报告这一步还在进行：思考还在想，或工具还在跑。
func (s Step) Running() bool {
	if s.Kind == Thinking {
		return s.Thinking
	}
	return s.Status == ToolRunning
}

// Str 取参数里的一个字符串；没有或不是字符串时返回空串。
func (s Step) Str(key string) string {
	v, _ := s.Input[key].(string)
	return v
}

// Bool 取参数里的一个布尔值；没有或不是布尔值时为 false。
func (s Step) Bool(key string) bool {
	v, _ := s.Input[key].(bool)
	return v
}

// ImageKind 是图片从哪来。
type ImageKind string

const (
	Inline  ImageKind = "inline"  // 实时收到的，带着数据
	Session ImageKind = "session" // 会话记录里的，只有位置，要另外取
	// Linked 是回答的 Markdown 里写的图（客户端从正文里取出来的，serve 不发）：URL 是主机上的路径（绝对路径或 ~ 开头）
	// 或 http、https 的网址。
	Linked ImageKind = "linked"
)

// Image 是聊天里的一张图。
type Image struct {
	Kind ImageKind `json:"kind"`
	Key  string    `json:"key"`  // inline：缓存键
	Data string    `json:"data"` // inline：base64
	File string    `json:"file"` // session：会话记录的路径
	Ref  string    `json:"ref"`  // session：在记录里的位置
	URL  string    `json:"url"`  // linked：主机上的路径或网址
}

// CacheKey 是认这张图的键：同一张图在列表里出现几次、条目被整份换掉，都只取一次。
func (im Image) CacheKey() string {
	switch im.Kind {
	case Inline:
		return "inline:" + im.Key
	case Linked:
		return "linked:" + im.URL
	}
	return "session:" + im.File + "#" + im.Ref
}

// Remote：网络上的图（linked 的 http、https 网址）。取它会把这台电脑的 IP 交给对方的服务器，显示前先等用户点一下。
func (im Image) Remote() bool {
	return im.Kind == Linked && (strings.HasPrefix(im.URL, "https://") || strings.HasPrefix(im.URL, "http://"))
}

// Patch 是改文件工具的结果里 Claude Code 给出的改动（tool_use_result 的 structuredPatch）。
type Patch struct {
	Created bool   `json:"created"`
	Hunks   []Hunk `json:"hunks"`
}

// Hunk 是一段连续的改动连同上下文。
type Hunk struct {
	Lines []Line `json:"lines"`
}

// LineKind 是对比里一行的种类。
type LineKind string

const (
	Context LineKind = "context"
	Added   LineKind = "added"
	Removed LineKind = "removed"
)

// Line 是对比里的一行。Old、New 是它在原文件、新文件里的行号（从 1 起），不适用时为 0。
type Line struct {
	Kind LineKind `json:"kind"`
	Text string   `json:"text"`
	Old  int      `json:"old"`
	New  int      `json:"new"`
}

// Decode 解码一份条目列表（JSON 数组）。
func Decode(data []byte) ([]Item, error) {
	var list []Item
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, fmt.Errorf("解码聊天条目：%w", err)
	}
	return list, nil
}

// Splice 把 list 从下标 at 起的 remove 项换成 add，返回新的列表；不改 list 本身。
// at、remove 超出列表时返回错误（推送和本地的列表对不上了，调用方该整份重取）。
func Splice(list []Item, at, remove int, add []Item) ([]Item, error) {
	if at < 0 || remove < 0 || at+remove > len(list) {
		return nil, fmt.Errorf("聊天条目的替换越界：从 %d 起去掉 %d 项，列表只有 %d 项", at, remove, len(list))
	}
	out := make([]Item, 0, len(list)-remove+len(add))
	out = append(out, list[:at]...)
	out = append(out, add...)
	return append(out, list[at+remove:]...), nil
}
