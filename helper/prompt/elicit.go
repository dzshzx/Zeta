package prompt

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

// ElicitAction 是对 MCP 表单或网址的回应：接受、拒绝或取消。
type ElicitAction string

const (
	ElicitAccept  ElicitAction = "accept"
	ElicitDecline ElicitAction = "decline"
	ElicitCancel  ElicitAction = "cancel"
)

// ElicitResponse 是 elicitation 应答里的 response：接受表单时带 Content。
type ElicitResponse struct {
	Action  ElicitAction    `json:"action"`
	Content json.RawMessage `json:"content,omitempty"`
}

// Elicit 把用户对 MCP 表单或网址的回应拼成 response。values 是表单里填的，写法见 Content。
// 接受表单而有没填对的项时，response 为零值，problems 是每一项的错误（键 → 说明），这时不应答，把错误交回客户端。
// p 不是 elicitation、action 认不出时返回错误。
func (p *Prompt) Elicit(action ElicitAction, values map[string][]string) (resp ElicitResponse, problems map[string]string, err error) {
	if p.Kind != KindElicitation {
		return ElicitResponse{}, nil, errors.New("这个请求不是 MCP 表单，不能这样回应")
	}
	switch action {
	case ElicitDecline, ElicitCancel:
		return ElicitResponse{Action: action}, nil, nil
	case ElicitAccept:
		if p.URL != "" {
			return ElicitResponse{Action: action}, nil, nil
		}
		content, problems := Content(p.Fields, values)
		if len(problems) > 0 {
			return ElicitResponse{}, problems, nil
		}
		return ElicitResponse{Action: action, Content: content}, nil, nil
	default:
		return ElicitResponse{}, nil, fmt.Errorf("认不出的回应 %q", action)
	}
}

// Content 把表单里填的（键 → 输入框里的字；多选是选中的值，开关是 "true"/"false"）换成要交回去的 content，
// 顺带按 schema 校验。返回 content 和每一项的错误（键 → 说明）；有错误时 content 为 nil。没填的可选项不交。
func Content(fields []Field, values map[string][]string) (json.RawMessage, map[string]string) {
	var content object
	problems := map[string]string{}
	for _, f := range fields {
		value, problem := f.value(values[f.Key])
		if value != nil {
			content.set(f.Key, value)
		}
		if problem != "" {
			problems[f.Key] = problem
		}
	}
	if len(problems) > 0 {
		return nil, problems
	}
	// 各项的值都是 json.Marshal 写出来的，拼起来不会出错
	raw, _ := content.MarshalJSON()
	return raw, problems
}

// value 是这一项要交的值（没填的可选项是 nil）和校验没过的说明。
func (f *Field) value(raw []string) (json.RawMessage, string) {
	text := ""
	if len(raw) > 0 {
		text = strings.TrimSpace(raw[0])
	}
	switch f.Kind {
	case FieldToggle:
		// 开关没有「没填」：没动就是关
		return json.RawMessage(strconv.FormatBool(text == "true")), ""
	case FieldMulti:
		if len(raw) == 0 {
			return nil, f.missing()
		}
		return f.multiValue(raw)
	case FieldInteger, FieldNumber:
		if text == "" {
			return nil, f.missing()
		}
		return f.numberValue(text)
	default:
		if text == "" {
			return nil, f.missing()
		}
		return f.textValue(text)
	}
}

func (f *Field) missing() string {
	if f.Required {
		return "必填"
	}
	return ""
}

func (f *Field) textValue(text string) (json.RawMessage, string) {
	value, _ := json.Marshal(text)
	n := utf8.RuneCountInString(text)
	switch {
	case f.MinLength != nil && n < *f.MinLength:
		return value, fmt.Sprintf("至少 %d 个字", *f.MinLength)
	case f.MaxLength != nil && n > *f.MaxLength:
		return value, fmt.Sprintf("最多 %d 个字", *f.MaxLength)
	default:
		return value, ""
	}
}

func (f *Field) numberValue(text string) (json.RawMessage, string) {
	integer := f.Kind == FieldInteger
	n, err := strconv.ParseFloat(text, 64)
	if err != nil || math.IsNaN(n) || math.IsInf(n, 0) || integer && n != math.Trunc(n) {
		if integer {
			return nil, "要填整数"
		}
		return nil, "要填数字"
	}
	var value json.RawMessage
	if integer {
		value = json.RawMessage(plain(n))
	} else {
		value, _ = json.Marshal(n)
	}
	switch {
	case f.Min != nil && n < *f.Min:
		return value, "不能小于 " + plain(*f.Min)
	case f.Max != nil && n > *f.Max:
		return value, "不能大于 " + plain(*f.Max)
	default:
		return value, ""
	}
}

func (f *Field) multiValue(raw []string) (json.RawMessage, string) {
	value, _ := json.Marshal(raw)
	n := float64(len(raw))
	switch {
	case f.Min != nil && n < *f.Min:
		return value, "至少选 " + plain(*f.Min) + " 项"
	case f.Max != nil && n > *f.Max:
		return value, "最多选 " + plain(*f.Max) + " 项"
	default:
		return value, ""
	}
}

// plain 是数字的平常写法：整数不带小数点，别的照最短的小数写。
func plain(d float64) string {
	if d == math.Trunc(d) && math.Abs(d) < 1<<63 {
		return strconv.FormatInt(int64(d), 10)
	}
	return strconv.FormatFloat(d, 'f', -1, 64)
}
