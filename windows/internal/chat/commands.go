package chat

import (
	"strings"
	"unicode"
)

// Command 是 Claude Code 报的一个斜杠命令或技能（initialize 应答里 commands 的一项，键是它的驼峰写法）。
type Command struct {
	Name         string `json:"name"`
	Description  string `json:"description"`
	ArgumentHint string `json:"argumentHint"`
}

// SlashQuery 是输入框里只有「/名字」、还没打空白时要提示的命令名前缀（小写）；不是这样时 ok 为假。
func SlashQuery(typed string) (prefix string, ok bool) {
	if !strings.HasPrefix(typed, "/") || strings.IndexFunc(typed, unicode.IsSpace) >= 0 {
		return "", false
	}
	return strings.ToLower(typed[1:]), true
}

// Suggest 是要提示的命令：名字以 prefix 开头的排前面，名字里含有它的跟在后面，各自照原来的顺序（没有名字的核心已经滤掉）。
func Suggest(commands []Command, prefix string) []Command {
	var starts, contains []Command
	for _, c := range commands {
		name := strings.ToLower(c.Name)
		switch {
		case strings.HasPrefix(name, prefix):
			starts = append(starts, c)
		case strings.Contains(name, prefix):
			contains = append(contains, c)
		}
	}
	return append(starts, contains...)
}
