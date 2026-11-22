package conv

import (
	"crypto/rand"
	"encoding/json"
	"fmt"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// Image 是消息里附带的一张图（客户端传来的 base64）。
type Image struct {
	MediaType string `json:"media_type"`
	Data      string `json:"data"`
}

// outgoing 是一条要发给 Claude Code 的用户消息：回答进行中时先排队（queued），能发了再交给进程。
type outgoing struct {
	uuid   string
	text   string
	images []Image
	files  []string // 主机上的路径（附件行，见 transcript.WithAttachedFiles）
	item   *transcript.Item
}

func (m *outgoing) view() messageView {
	return messageView{UUID: m.uuid, Text: m.text, Images: nonNilSlice(m.images), Files: nonNilSlice(m.files)}
}

func nonNilSlice[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

// inlineImages 是消息里的图在聊天条目里的样子。
func (m *outgoing) inlineImages() []transcript.Image {
	var out []transcript.Image
	for i, im := range m.images {
		out = append(out, transcript.Image{Kind: "inline", Key: fmt.Sprintf("a:%s:%d", m.uuid, i), Data: im.Data})
	}
	return out
}

// line 是发进 Claude Code 标准输入的那一行（stream-json 的 user 消息，uuid 就是它在会话记录里的 uuid）。
func (m *outgoing) line() []byte {
	type source struct {
		Type      string `json:"type"`
		MediaType string `json:"media_type"`
		Data      string `json:"data"`
	}
	type block struct {
		Type   string  `json:"type"`
		Text   string  `json:"text,omitempty"`
		Source *source `json:"source,omitempty"`
	}
	var content []block
	for _, im := range m.images {
		content = append(content, block{Type: "image", Source: &source{"base64", im.MediaType, im.Data}})
	}
	if text := transcript.WithAttachedFiles(m.text, m.files); text != "" {
		content = append(content, block{Type: "text", Text: text})
	}
	return userLine(m.uuid, content)
}

func userLine(uuid string, content any) []byte {
	b, _ := json.Marshal(map[string]any{
		"type": "user", "uuid": uuid,
		"message": map[string]any{"role": "user", "content": content},
	})
	return b
}

// merge 把撤回的消息并进待放回输入框的那份：连续撤回几条时按顺序拼起来。
func merge(before, m *outgoing) *outgoing {
	if before == nil {
		return m
	}
	text := before.text
	switch {
	case text == "":
		text = m.text
	case m.text != "":
		text += "\n" + m.text
	}
	return &outgoing{uuid: before.uuid, text: text,
		images: append(append([]Image{}, before.images...), m.images...),
		files:  append(append([]string{}, before.files...), m.files...)}
}

func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
