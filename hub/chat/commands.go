package chat

import (
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/hub/locales"
)

// Command 是 Claude Code 报的一个斜杠命令或技能（initialize 应答里 commands 的一项，键是它的驼峰写法）。
type Command struct {
	Name         string `json:"name"`
	Description  string `json:"description"`
	ArgumentHint string `json:"argumentHint"`
}

// Slash 是输入框里光标前正在打的斜杠命令（同 SlashToken.kt）。
// 照 Claude Code 终端界面（2.1.330 上实测）：光标前那个词以 / 开头、在开头或空白后面就提示，句中空一格再打 / 也算；
// 紧挨着别的字（「hello/co」「你好，/co」）或光标前已经打了空白时不提示。
type Slash struct {
	// Prefix 是 / 到光标的命令名前缀（小写）。
	Prefix string
	// Start、End 是选了命令要换掉的那一段（按字符）：从 / 到光标后面连着的命令名字符（isNameChar）为止。
	// 紧跟在后面的别的字（中文句子里没有空格）不算进去，留在原处。
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
	for end < len(r) && isNameChar(r[end]) {
		end++
	}
	return Slash{Prefix: strings.ToLower(string(r[start+1 : caret])), Start: start, End: end}, true
}

// isNameChar 说 c 是不是命令名里会出现的字符：ASCII 字母、数字和 - _ : .（插件的技能写成 plugin:skill）。
func isNameChar(c rune) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("-_:.", c)
}

// Complete 是选了命令 name 以后输入框里的字和光标：那一段换成「/name」，后面接着空白时用它、不然补一个空格，光标放在空格后面接着打参数。
// / 后面的字正好以 name 开头时整个名字一起换掉：名字里有命令名字符以外的字（中文名）、光标停在名字中间时不留下后半截。
func (s Slash) Complete(text, name string) (string, int) {
	r := []rune(text)
	end := s.End
	if strings.HasPrefix(string(r[s.Start+1:]), name) {
		end = max(end, s.Start+1+utf8.RuneCountInString(name))
	}
	rest := r[end:]
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

// Zeta 自己的两条斜杠命令（桌面版、iOS 版）：单独发出 /config 打开这台设备的 Claude Code 配置页，/permissions 打开工具权限页，
// 都不发给 Claude Code。提示时跟在 Claude Code 的命令后面（Suggest 的 local）。
const (
	ConfigCommand      = "config"
	PermissionsCommand = "permissions"
)

// SettingsCommands 是提示里列的那两条，说明按界面语言出。
func SettingsCommands() []Command {
	return []Command{
		{Name: ConfigCommand, Description: locales.ChatSlashConfig()},
		{Name: PermissionsCommand, Description: locales.ChatSlashPermissions()},
	}
}

// SettingsCommand 是 text 单独是哪条设置命令（前后的空白不算），不是时为空。
func SettingsCommand(text string) string {
	switch text = strings.TrimSpace(text); text {
	case "/" + ConfigCommand, "/" + PermissionsCommand:
		return text[1:]
	}
	return ""
}
