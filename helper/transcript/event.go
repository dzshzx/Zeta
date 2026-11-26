package transcript

import (
	"bytes"
	"encoding/json"
)

// Message 是 assistant、user 事件里（和会话记录里）的一条消息。
type Message struct {
	ID string `json:"id"`
	// Model 是回答用的模型（assistant 才有；子代理查看页显示它）
	Model   string  `json:"model"`
	Content Content `json:"content"`
}

// Content 是消息或工具结果的内容。Claude Code 有时写成一个字符串，有时写成若干块。
type Content struct {
	Text   string  // 写成字符串时
	Blocks []Block // 写成若干块时；不是对象、没有 type 的块丢掉
}

// UnmarshalJSON 认字符串和数组，别的（null、数字、对象）当作没有内容。一块解不出来只丢这一块，不连累整条消息。
func (c *Content) UnmarshalJSON(data []byte) error {
	*c = Content{}
	switch {
	case bytes.HasPrefix(data, []byte(`"`)):
		return json.Unmarshal(data, &c.Text)
	case bytes.HasPrefix(data, []byte(`[`)):
		var raw []json.RawMessage
		if err := json.Unmarshal(data, &raw); err != nil {
			return err
		}
		for _, r := range raw {
			var b Block
			if json.Unmarshal(r, &b) == nil && b.Type != "" {
				c.Blocks = append(c.Blocks, b)
			}
		}
	}
	return nil
}

// Flatten 是内容的文字：字符串本身，或各块的 text 用换行连起来。
func (c Content) Flatten() string {
	if c.Blocks == nil {
		return c.Text
	}
	var buf bytes.Buffer
	n := 0
	for _, b := range c.Blocks {
		if b.Type != "text" {
			continue
		}
		if n > 0 {
			buf.WriteByte('\n')
		}
		buf.WriteString(b.Text)
		n++
	}
	return buf.String()
}

// Block 是内容里的一块：text、thinking、tool_use、tool_result、image 等，各类型只用到自己那几项。
type Block struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     json.RawMessage `json:"input"`
	Clipped   bool            `json:"clipped"` // tool_use：zeta-helper history 截断过参数里的长字符串
	ToolUseID string          `json:"tool_use_id"`
	IsError   bool            `json:"is_error"`
	Content   Content         `json:"content"`
	Source    struct {
		Data string `json:"data"`
	} `json:"source"`
	// Ref 是会话记录里图片的位置：zeta-helper history 把图片的数据换成它，界面要看时再去取
	Ref string `json:"ref"`
}

// input 是工具调用的参数，不是对象时当作还没有。
func (b Block) input() json.RawMessage {
	if bytes.HasPrefix(bytes.TrimSpace(b.Input), []byte("{")) {
		return b.Input
	}
	return nil
}

// StreamEvent 是 stream_event 里的那个事件（Messages API 的流式事件）。
type StreamEvent struct {
	Type         string   `json:"type"`
	Message      *Message `json:"message"`       // message_start
	Index        *int     `json:"index"`         // content_block_*
	ContentBlock *Block   `json:"content_block"` // content_block_start
	Delta        *struct {
		Type     string `json:"type"`
		Text     string `json:"text"`
		Thinking string `json:"thinking"`
	} `json:"delta"` // content_block_delta
}

// ToolUseResult 是 user 事件上的 tool_use_result 里归约要用的部分：改文件的工具在这里给出结构化的改动。
// Claude Code 在工具出错时把它写成一个字符串，那时什么也没有。
type ToolUseResult struct {
	Type            string            `json:"type"`
	StructuredPatch []json.RawMessage `json:"structuredPatch"` // 一段一项，解不出的段跳过
}

// UnmarshalJSON 只认对象，别的当作没有。
func (r *ToolUseResult) UnmarshalJSON(data []byte) error {
	*r = ToolUseResult{}
	if !bytes.HasPrefix(data, []byte("{")) {
		return nil
	}
	type plain ToolUseResult
	if json.Unmarshal(data, (*plain)(r)) != nil {
		*r = ToolUseResult{}
	}
	return nil
}

type patchHunk struct {
	OldStart *int     `json:"oldStart"`
	NewStart *int     `json:"newStart"`
	Lines    []string `json:"lines"`
}
