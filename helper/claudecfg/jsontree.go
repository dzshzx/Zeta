package claudecfg

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"strings"
)

// object 是保留键顺序的 JSON 对象。settings.json、~/.claude.json 只改要改的键，别的键和顺序原样留着，
// 所以不解码成 map。值是 nil、bool、json.Number、string、[]any 或 *object。
type object struct {
	keys []string
	vals map[string]any
}

func newObject() *object { return &object{vals: map[string]any{}} }

func (o *object) get(key string) (any, bool) {
	v, ok := o.vals[key]
	return v, ok
}

// set 把 key 设成 v：已有的键留在原位，新键放在最后。
func (o *object) set(key string, v any) {
	if _, ok := o.vals[key]; !ok {
		o.keys = append(o.keys, key)
	}
	o.vals[key] = v
}

func (o *object) del(key string) {
	if _, ok := o.vals[key]; !ok {
		return
	}
	delete(o.vals, key)
	for i, k := range o.keys {
		if k == key {
			o.keys = append(o.keys[:i:i], o.keys[i+1:]...)
			break
		}
	}
}

func (o *object) len() int { return len(o.keys) }

// str 取 key 处的字符串；不是字符串时 ok 为 false。
func (o *object) str(key string) (string, bool) {
	s, ok := o.vals[key].(string)
	return s, ok
}

// obj 取 key 处的对象；没有或不是对象时为 nil。
func (o *object) obj(key string) *object {
	v, _ := o.vals[key].(*object)
	return v
}

// parseJSON 按顺序解出一个 JSON 值；后面还有别的东西时报错。
func parseJSON(data []byte) (any, error) {
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	v, err := decodeValue(d)
	if err != nil {
		return nil, err
	}
	if _, err := d.Token(); err != io.EOF {
		return nil, errors.New("JSON 后面还有内容")
	}
	return v, nil
}

// parseObject 解出一个 JSON 对象；不是对象时报错。
func parseObject(data []byte) (*object, error) {
	v, err := parseJSON(data)
	if err != nil {
		return nil, err
	}
	o, ok := v.(*object)
	if !ok {
		return nil, errors.New("不是 JSON 对象")
	}
	return o, nil
}

func decodeValue(d *json.Decoder) (any, error) {
	t, err := d.Token()
	if err != nil {
		return nil, err
	}
	switch t := t.(type) {
	case json.Delim:
		switch t {
		case '{':
			return decodeObject(d)
		case '[':
			return decodeArray(d)
		}
		return nil, errors.New("JSON 格式不对")
	default:
		return t, nil
	}
}

func decodeObject(d *json.Decoder) (*object, error) {
	o := newObject()
	for d.More() {
		t, err := d.Token()
		if err != nil {
			return nil, err
		}
		key, ok := t.(string)
		if !ok {
			return nil, errors.New("JSON 格式不对")
		}
		v, err := decodeValue(d)
		if err != nil {
			return nil, err
		}
		o.set(key, v)
	}
	_, err := d.Token()
	return o, err
}

func decodeArray(d *json.Decoder) ([]any, error) {
	list := []any{}
	for d.More() {
		v, err := decodeValue(d)
		if err != nil {
			return nil, err
		}
		list = append(list, v)
	}
	_, err := d.Token()
	return list, err
}

// renderJSON 和 JSON.stringify(v, null, 2) + "\n" 一样的写法（Claude Code 自己写 settings.json 就是这样）。
func renderJSON(v any) string {
	var b strings.Builder
	writeJSON(&b, v, 0, true)
	b.WriteByte('\n')
	return b.String()
}

// compactJSON 是不带空白的一行。
func compactJSON(v any) string {
	var b strings.Builder
	writeJSON(&b, v, 0, false)
	return b.String()
}

func writeJSON(b *strings.Builder, v any, depth int, pretty bool) {
	switch v := v.(type) {
	case nil:
		b.WriteString("null")
	case bool:
		b.WriteString(strconv.FormatBool(v))
	case json.Number:
		b.WriteString(v.String())
	case string:
		writeString(b, v)
	case []any:
		writeList(b, len(v), depth, pretty, '[', ']', func(i int) { writeJSON(b, v[i], depth+1, pretty) })
	case *object:
		writeList(b, len(v.keys), depth, pretty, '{', '}', func(i int) {
			writeString(b, v.keys[i])
			b.WriteByte(':')
			if pretty {
				b.WriteByte(' ')
			}
			writeJSON(b, v.vals[v.keys[i]], depth+1, pretty)
		})
	}
}

func writeList(b *strings.Builder, n, depth int, pretty bool, open, close byte, item func(int)) {
	b.WriteByte(open)
	if n == 0 {
		b.WriteByte(close)
		return
	}
	for i := range n {
		if i > 0 {
			b.WriteByte(',')
		}
		if pretty {
			b.WriteByte('\n')
			b.WriteString(strings.Repeat("  ", depth+1))
		}
		item(i)
	}
	if pretty {
		b.WriteByte('\n')
		b.WriteString(strings.Repeat("  ", depth))
	}
	b.WriteByte(close)
}

// writeString 照 JSON.stringify 转义：只转引号、反斜杠和控制字符，中文和 <>& 原样。
func writeString(b *strings.Builder, s string) {
	b.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"':
			b.WriteString(`\"`)
		case r == '\\':
			b.WriteString(`\\`)
		case r == '\n':
			b.WriteString(`\n`)
		case r == '\r':
			b.WriteString(`\r`)
		case r == '\t':
			b.WriteString(`\t`)
		case r == '\b':
			b.WriteString(`\b`)
		case r == '\f':
			b.WriteString(`\f`)
		case r < 0x20:
			b.WriteString(`\u00`)
			b.WriteByte("0123456789abcdef"[r>>4])
			b.WriteByte("0123456789abcdef"[r&0xF])
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
}

// strings 取数组里的字符串，别的值跳过。
func stringItems(v any) []string {
	list, _ := v.([]any)
	var out []string
	for _, item := range list {
		if s, ok := item.(string); ok {
			out = append(out, s)
		}
	}
	return out
}
