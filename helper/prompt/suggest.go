package prompt

import "encoding/json"

// suggestion 是 permission_suggestions 的一项，各类型只用到自己那几项。
type suggestion struct {
	Type        string        `json:"type"`
	Rules       objects[rule] `json:"rules"`       // addRules
	Behavior    string        `json:"behavior"`    // addRules
	Directories []scalar      `json:"directories"` // addDirectories
	Mode        string        `json:"mode"`        // setMode
	Destination string        `json:"destination"` // 规则记在哪
}

type rule struct {
	ToolName    *string `json:"toolName"`
	RuleContent *string `json:"ruleContent"`
}

// describeSuggestions 把「以后不再问」会做的几件事逐条写成大白话。不是对象的项跳过，认不出的类型照原样写类型名。
func describeSuggestions(list []json.RawMessage) []string {
	var lines []string
	for _, raw := range list {
		var s suggestion
		if isObject(raw) && decode(raw, &s) {
			lines = append(lines, s.describe()...)
		}
	}
	return lines
}

func (s *suggestion) describe() []string {
	switch s.Type {
	case "addRules":
		verb := ruleVerb(s.Behavior)
		var lines []string
		for _, r := range s.Rules {
			if r.ToolName == nil {
				continue
			}
			target := *r.ToolName
			if r.RuleContent != nil {
				target += "(" + *r.RuleContent + ")"
			}
			lines = append(lines, verb+" "+target+destinationNote(s.Destination))
		}
		return lines
	case "addDirectories":
		var lines []string
		for _, dir := range texts(s.Directories) {
			lines = append(lines, "允许访问 "+dir+destinationNote(s.Destination))
		}
		return lines
	case "setMode":
		return []string{modeLine(s.Mode)}
	default:
		return []string{s.Type}
	}
}

func modeLine(mode string) string {
	switch mode {
	case "acceptEdits":
		return "这个对话里自动接受改动"
	case "bypassPermissions":
		return "这个对话里完全放行"
	case "default":
		return "这个对话里逐项确认"
	case "plan":
		return "这个对话切到计划模式"
	default:
		return "这个对话切到 " + mode + " 模式"
	}
}

// destinationNote 说明规则记在哪。项目里只给自己用的（settings.local.json）是 TUI 的默认，不另外说明。
func destinationNote(destination string) string {
	switch destination {
	case "userSettings":
		return "（所有项目）"
	case "projectSettings":
		return "（这个项目，进 Git）"
	case "session":
		return "（只在这个对话里）"
	default:
		return ""
	}
}

func ruleVerb(behavior string) string {
	switch behavior {
	case "deny":
		return "一律拒绝"
	case "ask":
		return "每次都问"
	default:
		return "允许"
	}
}
