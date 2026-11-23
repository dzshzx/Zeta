package prompt

import (
	"encoding/json"

	"github.com/dzshzx/Zeta/helper/locales"
)

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

// describeSuggestions 把「以后不再问」会做的几件事逐条写成大白话（核心文字表里的话，客户端按自己的语言出字）。
// 不是对象的项跳过，认不出的类型照原样写类型名。
//
// 规则怎么处理（behavior）、记在哪（destination）、切到什么模式（mode）都原样放进参数，由文字表里的 select 分情况说：
// 认不出的值落到 other。
func describeSuggestions(list []json.RawMessage) []locales.Msg {
	var lines []locales.Msg
	for _, raw := range list {
		var s suggestion
		if isObject(raw) && decode(raw, &s) {
			lines = append(lines, s.describe()...)
		}
	}
	return lines
}

func (s *suggestion) describe() []locales.Msg {
	switch s.Type {
	case "addRules":
		var lines []locales.Msg
		for _, r := range s.Rules {
			if r.ToolName == nil {
				continue
			}
			target := *r.ToolName
			if r.RuleContent != nil {
				target += "(" + *r.RuleContent + ")"
			}
			lines = append(lines, locales.M("suggestRule", "behavior", s.Behavior, "target", target, "where", s.Destination))
		}
		return lines
	case "addDirectories":
		var lines []locales.Msg
		for _, dir := range texts(s.Directories) {
			lines = append(lines, locales.M("suggestDirectory", "dir", dir, "where", s.Destination))
		}
		return lines
	case "setMode":
		return []locales.Msg{locales.M("suggestMode", "mode", s.Mode)}
	default:
		return []locales.Msg{locales.M("suggestOther", "type", s.Type)}
	}
}
