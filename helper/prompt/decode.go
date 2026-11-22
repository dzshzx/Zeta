package prompt

import (
	"bytes"
	"encoding/json"
	"errors"
)

// controlRequest 是 Claude Code 发来的一行 control_request。
type controlRequest struct {
	Type      string       `json:"type"`
	RequestID *string      `json:"request_id"`
	Request   *requestBody `json:"request"`
}

// requestBody 是 control_request 里的 request。can_use_tool 和 elicitation 只用到各自那几项。
type requestBody struct {
	Subtype string `json:"subtype"`

	ToolName                string            `json:"tool_name"`
	Input                   json.RawMessage   `json:"input"`
	PermissionSuggestions   []json.RawMessage `json:"permission_suggestions"`
	SuppressAlwaysAllowRule bool              `json:"suppress_always_allow_rule"`
	DecisionReason          decisionReason    `json:"decision_reason"`
	AgentID                 string            `json:"agent_id"`
	ToolUseID               string            `json:"tool_use_id"`

	DisplayName     *string  `json:"display_name"`
	MCPServerName   string   `json:"mcp_server_name"`
	Message         string   `json:"message"`
	Mode            string   `json:"mode"`
	URL             string   `json:"url"`
	RequestedSchema *schema  `json:"requested_schema"`
	PropertyOrder   []scalar `json:"property_order"`
}

// decisionReason 是 Claude Code 说明为什么要问。它有时写成一个字符串，有时写成带 reason 的对象；只有空白时当作没有。
type decisionReason string

// UnmarshalJSON 认字符串和带 reason 的对象，别的当作没有。
func (r *decisionReason) UnmarshalJSON(data []byte) error {
	*r = ""
	var text string
	if json.Unmarshal(data, &text) == nil {
		if notBlank(text) {
			*r = decisionReason(text)
		}
		return nil
	}
	var obj struct {
		Reason string `json:"reason"`
	}
	if decode(data, &obj) && notBlank(obj.Reason) {
		*r = decisionReason(obj.Reason)
	}
	return nil
}

type wireQuestion struct {
	Question    string              `json:"question"`
	Header      string              `json:"header"`
	Options     objects[wireOption] `json:"options"`
	MultiSelect bool                `json:"multiSelect"`
}

type wireOption struct {
	Label       string  `json:"label"`
	Description string  `json:"description"`
	Preview     *string `json:"preview"`
}

// objects 是只收对象的数组：不是对象的元素丢掉，不连累别的。不是数组时为 nil，空数组时是长度为 0 的非 nil 切片。
type objects[T any] []T

// UnmarshalJSON 见 objects。
func (o *objects[T]) UnmarshalJSON(data []byte) error {
	*o = nil
	var list []json.RawMessage
	if !bytes.HasPrefix(data, []byte("[")) || json.Unmarshal(data, &list) != nil {
		return nil
	}
	*o = make(objects[T], 0, len(list))
	for _, raw := range list {
		var v T
		if isObject(raw) && decode(raw, &v) {
			*o = append(*o, v)
		}
	}
	return nil
}

// scalar 是 JSON 里的一个字符串、数字或布尔值，Text 是它的写法（字符串不带引号）。null、对象、数组时 OK 为假。
// JSON Schema 里的取值（enum、const、default）可以是数字，表单上都按字处理。
type scalar struct {
	Text string
	OK   bool
}

// UnmarshalJSON 见 scalar。
func (s *scalar) UnmarshalJSON(data []byte) error {
	*s = scalar{}
	switch {
	case len(data) == 0, data[0] == '{', data[0] == '[', bytes.Equal(data, []byte("null")):
	case data[0] == '"':
		s.OK = json.Unmarshal(data, &s.Text) == nil
	default:
		s.Text, s.OK = string(data), true
	}
	return nil
}

// texts 是 list 里那些有写法的值。
func texts(list []scalar) []string {
	var out []string
	for _, s := range list {
		if s.OK {
			out = append(out, s.Text)
		}
	}
	return out
}

// defaults 是 schema 的 default：一个值或一组值，换成输入框里的写法。
type defaults []string

// UnmarshalJSON 认数组和单个值，别的当作没有。
func (d *defaults) UnmarshalJSON(data []byte) error {
	*d = nil
	var list []scalar
	if json.Unmarshal(data, &list) == nil {
		*d = texts(list)
		return nil
	}
	var one scalar
	if json.Unmarshal(data, &one) == nil && one.OK {
		*d = defaults{one.Text}
	}
	return nil
}

// member 是 JSON 对象的一项。
type member struct {
	key   string
	value json.RawMessage
}

// object 是保留键的先后的 JSON 对象，值原样保留。重复的键以后一个为准、位置留在第一次出现处。
// 不是对象时为空。
type object []member

// UnmarshalJSON 见 object。
func (o *object) UnmarshalJSON(data []byte) error {
	*o = nil
	dec := json.NewDecoder(bytes.NewReader(data))
	if t, err := dec.Token(); err != nil || t != json.Delim('{') {
		return nil
	}
	for dec.More() {
		t, err := dec.Token()
		key, isKey := t.(string)
		var value json.RawMessage
		if err != nil || !isKey || dec.Decode(&value) != nil {
			*o = nil
			return nil
		}
		o.set(key, value)
	}
	return nil
}

// MarshalJSON 按原来的先后写出各项。
func (o object) MarshalJSON() ([]byte, error) {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, m := range o {
		if i > 0 {
			b.WriteByte(',')
		}
		key, err := json.Marshal(m.key)
		if err != nil {
			return nil, err
		}
		b.Write(key)
		b.WriteByte(':')
		b.Write(m.value)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

// set 设 key 的值：已有就原地换掉，没有就加在最后。
func (o *object) set(key string, value json.RawMessage) {
	for i := range *o {
		if (*o)[i].key == key {
			(*o)[i].value = value
			return
		}
	}
	*o = append(*o, member{key, value})
}

// get 是 key 的值，没有时为 nil。
func (o object) get(key string) json.RawMessage {
	for _, m := range o {
		if m.key == key {
			return m.value
		}
	}
	return nil
}

// decode 把 data 解进 v。字段类型对不上时只丢那一项、照样解别的；不是 JSON 时返回 false。
func decode(data []byte, v any) bool {
	err := json.Unmarshal(data, v)
	var typeErr *json.UnmarshalTypeError
	return err == nil || errors.As(err, &typeErr)
}

// isObject：raw 是一个 JSON 对象。
func isObject(raw json.RawMessage) bool {
	return bytes.HasPrefix(bytes.TrimSpace(raw), []byte("{"))
}
