package chat

import (
	"context"
	"encoding/json"
	"strings"
	"unicode"
)

// Command 是 Claude Code 报的一个斜杠命令或技能（initialize 应答里 commands 的一项，键是它的驼峰写法）。
type Command struct {
	Name         string `json:"name"`
	Description  string `json:"description"`
	ArgumentHint string `json:"argumentHint"`
}

// ParseCommands 解出 conv.state 的 commands（也是设置里各设备存的那份）：不是数组、没报时为空，没有名字的项跳过。
func ParseCommands(raw json.RawMessage) []Command {
	var list []Command
	if isNull(raw) || json.Unmarshal(raw, &list) != nil {
		return nil
	}
	out := list[:0]
	for _, c := range list {
		if c.Name != "" {
			out = append(out, c)
		}
	}
	return out
}

// SlashQuery 是输入框里只有「/名字」、还没打空白时要提示的命令名前缀（小写）；不是这样时 ok 为假。
func SlashQuery(typed string) (prefix string, ok bool) {
	if !strings.HasPrefix(typed, "/") || strings.IndexFunc(typed, unicode.IsSpace) >= 0 {
		return "", false
	}
	return strings.ToLower(typed[1:]), true
}

// Suggest 是要提示的命令：名字以 prefix 开头的排前面，名字里含有它的跟在后面，各自照原来的顺序；内部命令（「__」开头）不列。
func Suggest(commands []Command, prefix string) []Command {
	var starts, contains []Command
	for _, c := range commands {
		name := strings.ToLower(c.Name)
		switch {
		case strings.HasPrefix(c.Name, "__"):
		case strings.HasPrefix(name, prefix):
			starts = append(starts, c)
		case strings.Contains(name, prefix):
			contains = append(contains, c)
		}
	}
	return append(starts, contains...)
}

// Prepare 只起好进程、不发消息（conv.prepare），commands、models 随 initialize 的应答到来：输入斜杠命令时用。
// 已有进程、会话被别处占着时核心什么也不做。
func (c *Conversation) Prepare(ctx context.Context) error { return c.call(ctx, "conv.prepare", nil) }
