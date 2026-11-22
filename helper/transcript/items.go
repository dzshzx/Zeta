// Package transcript 把 Claude Code 的 stream-json 事件归并成聊天列表的条目：Kotlin 一侧
// TranscriptReducer（shared/…/core/TranscriptReducer.kt）的 Go 版。两边由 testdata 下的对照用例钉成一样
// （parity_test.go 和 app 的 TranscriptParityTest 读同一份），条目的 JSON 也就是 serve 推给客户端的格式。
package transcript

import "encoding/json"

// Item 是聊天列表的一项：UserItem、AnswerItem、NoticeItem、WorkItem。
type Item interface {
	ItemID() string
}

// UserItem 是用户发的一条消息。Files 是随消息附带的路径（见 Kotlin 的 AttachedPaths），UUID 是它在会话记录里的 uuid。
type UserItem struct {
	ID     string
	Text   string
	Images []Image
	Files  []string
	UUID   *string
}

// AnswerItem 是一段回答文字；Streaming 时还在逐字送来。
type AnswerItem struct {
	ID        string
	Text      string
	Streaming bool
}

// NoticeItem 是 Zeta 自己加的提示（出错、已停止等）。
type NoticeItem struct {
	ID    string
	Text  string
	Error bool
}

// WorkItem 是连续的思考与工具调用折成的一组。
type WorkItem struct {
	ID    string
	Steps []Step
}

func (i UserItem) ItemID() string   { return i.ID }
func (i AnswerItem) ItemID() string { return i.ID }
func (i NoticeItem) ItemID() string { return i.ID }
func (i WorkItem) ItemID() string   { return i.ID }

// Running：组里还有在跑的一步。
func (i WorkItem) Running() bool {
	for _, s := range i.Steps {
		if s.StepRunning() {
			return true
		}
	}
	return false
}

// Step 是工作组里的一步：ThinkingStep 或 ToolStep。
type Step interface {
	StepID() string
	StepRunning() bool
}

// ThinkingStep 是一段思考；Seconds 是想了几秒（只有实时看着它想完的才知道）。
type ThinkingStep struct {
	ID      string
	Text    string
	Running bool
	Seconds *int
}

// ToolStatus 是工具调用的状态。
type ToolStatus string

const (
	ToolRunning ToolStatus = "running"
	ToolSuccess ToolStatus = "success"
	ToolFailed  ToolStatus = "failed"
)

// ToolStep 是一次工具调用。Input 是调用的参数（还没收到时为 nil），Result 是结果的文字，Patch 是改文件工具给出的改动。
type ToolStep struct {
	ID     string
	Name   string
	Input  map[string]any
	Status ToolStatus
	Result *string
	Images []Image
	Patch  *EditPatch
}

func (s ThinkingStep) StepID() string    { return s.ID }
func (s ThinkingStep) StepRunning() bool { return s.Running }
func (s ToolStep) StepID() string        { return s.ID }
func (s ToolStep) StepRunning() bool     { return s.Status == ToolRunning }

// Image 是聊天里的一张图：实时收到的带着数据（Data），会话记录里的只有位置（File、Ref）。
type Image struct {
	Inline bool
	Key    string // Inline 时：缓存键
	Data   string // Inline 时：base64
	File   string // 会话记录
	Ref    string // 在记录里的位置（zeta-helper history 给的）
}

// EditPatch 是改文件工具的结果里 Claude Code 给出的改动（tool_use_result 的 structuredPatch）。
type EditPatch struct {
	Created bool
	Hunks   []Hunk
}

// Hunk 是一段连续的改动连同上下文。
type Hunk struct {
	Lines []DiffLine
}

// DiffLine 是对比里的一行；Old、New 是它在原文件、新文件里的行号，不适用时为 nil。
type DiffLine struct {
	Kind string // context、added、removed
	Text string
	Old  *int
	New  *int
}

// 下面是各类型的 JSON：每种都写出全部字段（没有的是 null 或空数组），客户端照字段名读。

func (i UserItem) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": "user", "id": i.ID, "text": i.Text,
		"images": nonNil(i.Images), "files": nonNil(i.Files), "uuid": i.UUID})
}

func (i AnswerItem) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": "answer", "id": i.ID, "text": i.Text, "streaming": i.Streaming})
}

func (i NoticeItem) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": "notice", "id": i.ID, "text": i.Text, "error": i.Error})
}

func (i WorkItem) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": "work", "id": i.ID, "steps": nonNil(i.Steps)})
}

func (s ThinkingStep) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": "thinking", "id": s.ID, "text": s.Text, "running": s.Running,
		"seconds": s.Seconds})
}

func (s ToolStep) MarshalJSON() ([]byte, error) {
	var input any
	if s.Input != nil {
		input = s.Input
	}
	return json.Marshal(map[string]any{"kind": "tool", "id": s.ID, "name": s.Name, "input": input,
		"status": s.Status, "result": s.Result, "images": nonNil(s.Images), "patch": s.Patch})
}

func (im Image) MarshalJSON() ([]byte, error) {
	if im.Inline {
		return json.Marshal(map[string]any{"kind": "inline", "key": im.Key, "data": im.Data})
	}
	return json.Marshal(map[string]any{"kind": "session", "file": im.File, "ref": im.Ref})
}

func (p EditPatch) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"created": p.Created, "hunks": nonNil(p.Hunks)})
}

func (h Hunk) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"lines": nonNil(h.Lines)})
}

func (l DiffLine) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"kind": l.Kind, "text": l.Text, "old": l.Old, "new": l.New})
}

// nonNil：nil 切片写成 []，不写成 null。
func nonNil[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}
