package settings

import (
	"encoding/json"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// 常用指令（用户存下来的提问），同 shared 的 SavedPrompts.kt：存在设置文件的 savedPrompts 下（JSON 数组的字符串），
// 和手机版的写法一样。顺序就是用户排的顺序，新加的放最前面。

const keyPrompts = "savedPrompts"

// 常用指令的上限。
const (
	MaxPrompts    = 100
	MaxPromptName = 40
	MaxPromptText = 20_000
	// promptNameChars：名字空着时取内容开头这么多个字。
	promptNameChars = 12
)

// SavedPrompt 是一条常用指令：Name 可以空着，显示时取内容的开头（DisplayName）。内容原样填进输入框。
type SavedPrompt struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Text string `json:"text"`
}

// Prompts 是常用指令的清单；坏的条目跳过，不让一条坏数据弄丢全部。
func (s *Settings) Prompts() []SavedPrompt {
	text, _ := s.store.String(keyPrompts)
	return DecodePrompts(text)
}

// SetPrompts 存常用指令的清单。
func (s *Settings) SetPrompts(list []SavedPrompt) error {
	return s.store.SetString(keyPrompts, EncodePrompts(list))
}

// DecodePrompts 把存储里的文字读成清单：没有文字的跳过，同一个 id 只留第一条。
func DecodePrompts(raw string) []SavedPrompt {
	var entries []map[string]any
	if json.Unmarshal([]byte(raw), &entries) != nil {
		return nil
	}
	var out []SavedPrompt
	seen := map[string]bool{}
	for _, e := range entries {
		text, _ := e["text"].(string)
		if strings.TrimSpace(text) == "" {
			continue
		}
		id, _ := e["id"].(string)
		if id == "" {
			id = "p-" + text // Kotlin 版用 text.hashCode()；这里只要求同一份清单里不重复
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		name, _ := e["name"].(string)
		out = append(out, SavedPrompt{ID: id, Name: name, Text: text})
	}
	return out
}

// EncodePrompts 是存进设置的文字。
func EncodePrompts(list []SavedPrompt) string {
	if list == nil {
		list = []SavedPrompt{}
	}
	data, _ := json.Marshal(list)
	return string(data)
}

// DefaultPromptName 是内容的第一行非空文字，压掉多余空白，最多 12 个字（按字符数，不切开表情）。
func DefaultPromptName(text string) string {
	for _, line := range strings.Split(text, "\n") {
		line = strings.Join(strings.Fields(line), " ")
		if line == "" {
			continue
		}
		if utf8.RuneCountInString(line) <= promptNameChars {
			return line
		}
		return strings.TrimRight(string([]rune(line)[:promptNameChars]), " ") + "…"
	}
	return ""
}

// DisplayName 是列表里显示的名字：起了名字用名字，没起取内容开头。
func (p SavedPrompt) DisplayName() string {
	if name := strings.TrimSpace(p.Name); name != "" {
		return name
	}
	return DefaultPromptName(p.Text)
}

// PromptProblem 是新建或保存编辑时的问题，空的表示可以存。editing 是正在编辑的那条的 id（新建时空着）。
func PromptProblem(list []SavedPrompt, name, text, editing string) string {
	switch {
	case strings.TrimSpace(text) == "":
		return locales.PromptCheckEmpty()
	case utf8.RuneCountInString(text) > MaxPromptText:
		return locales.PromptCheckTooLong()
	case utf8.RuneCountInString(strings.TrimSpace(name)) > MaxPromptName:
		return locales.PromptCheckNameTooLong()
	case editing == "" && len(list) >= MaxPrompts:
		return locales.PromptCheckFull()
	}
	return ""
}

// HasSamePrompt：清单里已经有同样内容的一条（比较时忽略首尾空白，同 Kotlin 的 SavedPrompts.findSame）。
func HasSamePrompt(list []SavedPrompt, text string) bool {
	text = strings.TrimSpace(text)
	for _, p := range list {
		if strings.TrimSpace(p.Text) == text {
			return true
		}
	}
	return false
}

// AddPrompt 加到最前面。名字和默认名一样时存成空的，以后改了内容名字跟着变。
func AddPrompt(list []SavedPrompt, id, name, text string) []SavedPrompt {
	return append([]SavedPrompt{{ID: id, Name: storedName(name, text), Text: text}}, list...)
}

// UpdatePrompt 改 id 那条的名字和内容。
func UpdatePrompt(list []SavedPrompt, id, name, text string) []SavedPrompt {
	out := make([]SavedPrompt, len(list))
	for i, p := range list {
		if p.ID == id {
			p.Name, p.Text = storedName(name, text), text
		}
		out[i] = p
	}
	return out
}

// RemovePrompt 去掉 id 那条。
func RemovePrompt(list []SavedPrompt, id string) []SavedPrompt {
	var out []SavedPrompt
	for _, p := range list {
		if p.ID != id {
			out = append(out, p)
		}
	}
	return out
}

// MovePrompt 把 id 那条往前（delta < 0）或往后挪；挪不动时原样返回。
func MovePrompt(list []SavedPrompt, id string, delta int) []SavedPrompt {
	from := -1
	for i, p := range list {
		if p.ID == id {
			from = i
		}
	}
	to := min(max(from+delta, 0), len(list)-1)
	if from < 0 || to == from {
		return list
	}
	out := append([]SavedPrompt(nil), list...)
	p := out[from]
	out = append(out[:from], out[from+1:]...)
	return append(out[:to], append([]SavedPrompt{p}, out[to:]...)...)
}

func storedName(name, text string) string {
	name = strings.TrimSpace(name)
	if name == DefaultPromptName(text) {
		return ""
	}
	return name
}
