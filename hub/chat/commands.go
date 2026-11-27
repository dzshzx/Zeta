package chat

import (
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Command 是 Claude Code 报的一个斜杠命令或技能（initialize 应答里 commands 的一项，键是它的驼峰写法）。
type Command struct {
	Name         string `json:"name"`
	Description  string `json:"description"`
	ArgumentHint string `json:"argumentHint"`
}

// Slash 是输入框里光标前正在打的斜杠命令（同 Composer.kt 的 slashAt）。
// 照 Claude Code 终端界面（2.1.330 上实测）：光标前那个词以 / 开头、在开头或空白后面就提示，句中空一格再打 / 也算；
// 紧挨着别的字（「hello/co」「你好，/co」）或光标前已经打了空白时不提示。
type Slash struct {
	// Prefix 是 / 到光标的命令名前缀（小写）。
	Prefix string
	// Start、End 是那个词在输入框里的范围（按字符），End 到词尾：选了命令就换掉这一段。
	Start, End int
}

// SlashAt 找 text 里光标 caret（按字符）前正在打的斜杠命令；不是在打时 ok 为假。
func SlashAt(text string, caret int) (s Slash, ok bool) {
	r := []rune(text)
	if caret < 0 || caret > len(r) {
		return Slash{}, false
	}
	start := caret
	for start > 0 && !unicode.IsSpace(r[start-1]) {
		start--
	}
	if start == caret || r[start] != '/' {
		return Slash{}, false
	}
	end := caret
	for end < len(r) && !unicode.IsSpace(r[end]) {
		end++
	}
	return Slash{Prefix: strings.ToLower(string(r[start+1 : caret])), Start: start, End: end}, true
}

// Complete 是选了命令 name 以后输入框里的字和光标：那个词换成「/name」，后面接着空白时用它、不然补一个空格，光标放在空格后面接着打参数。
func (s Slash) Complete(text, name string) (string, int) {
	r := []rune(text)
	rest := r[s.End:]
	ins := "/" + name
	if len(rest) == 0 || !unicode.IsSpace(rest[0]) {
		ins += " "
		return string(r[:s.Start]) + ins + string(rest), s.Start + utf8.RuneCountInString(ins)
	}
	return string(r[:s.Start]) + ins + string(rest), s.Start + utf8.RuneCountInString(ins) + 1
}

// Suggest 是要提示的命令：名字以 prefix 开头的排前面，名字里含有它的跟在后面，各自照原来的顺序（没有名字的核心已经滤掉）。本地命令排在核心命令后，替代同名的核心项。
func Suggest(commands []Command, prefix string, local ...Command) []Command {
	var starts, contains []Command
	add := func(c Command) {
		name := strings.ToLower(c.Name)
		switch {
		case strings.HasPrefix(name, prefix):
			starts = append(starts, c)
		case strings.Contains(name, prefix):
			contains = append(contains, c)
		}
	}
	for _, c := range commands {
		if !slices.ContainsFunc(local, func(l Command) bool { return strings.EqualFold(c.Name, l.Name) }) {
			add(c)
		}
	}
	for _, c := range local {
		add(c)
	}
	return append(starts, contains...)
}
