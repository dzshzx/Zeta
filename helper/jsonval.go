package main

import (
	"bytes"
	"encoding/json"
	"encoding/json/jsontext"
	jsonv2 "encoding/json/v2"
	"errors"
	"io"
	"reflect"
	"strconv"
	"strings"
	"unicode/utf8"
)

// jsonOptions：数字保留原文（json.Number），原样转出时不会被改写成 1e+06 之类；重名的键后一个整个替换前一个
// （同 Python 的 json 模块，v2 默认会把对象合并）；孤立的 \ud800 这类转义换成替代字符。
// 用 v2 一遍解完：json.Decoder 的 UseNumber 要先把整个值读一遍再解一遍，大记录慢近三倍。
var jsonOptions = jsonv2.JoinOptions(
	jsonv2.WithUnmarshalers(jsonv2.UnmarshalFromFunc(numberText)),
	json.MergeWithLegacySemantics(true),
	jsontext.AllowDuplicateNames(true),
	jsontext.AllowInvalidUTF8(true),
)

func numberText(dec *jsontext.Decoder, v *any) error {
	if dec.PeekKind() != '0' {
		return errors.ErrUnsupported // 不是数字：照常解
	}
	tok, err := dec.ReadToken()
	if err != nil {
		return err
	}
	*v = json.Number(tok.String())
	return nil
}

// parseJSON 解析一行（或一个文件）里的一个 JSON 值，前后只许有空白。
// 不是合法 UTF-8 的字节先按 Unicode 的通行做法换成替代字符。
func parseJSON(data []byte) (any, bool) {
	if !utf8.Valid(data) {
		data = fixUTF8(data)
	}
	var v any
	if jsonv2.Unmarshal(data, &v, jsonOptions) != nil {
		return nil, false
	}
	return v, true
}

func parseObject(data []byte) (obj, bool) {
	v, ok := parseJSON(data)
	m, isObj := v.(obj)
	return m, ok && isObj
}

// truthy：JSON 值算不算“有”：null、false、0、空串、空数组、空对象都不算。
func truthy(v any) bool {
	switch t := v.(type) {
	case nil:
		return false
	case bool:
		return t
	case string:
		return t != ""
	case json.Number:
		f, err := strconv.ParseFloat(string(t), 64)
		return err != nil || f != 0
	case []any:
		return len(t) > 0
	case obj:
		return len(t) > 0
	}
	return true
}

// asObj：是对象就返回它，否则是空的（读字段都得到 nil）。
func asObj(v any) obj {
	m, _ := v.(obj)
	return m
}

func asList(v any) []any {
	l, _ := v.([]any)
	return l
}

// asString：是字符串就返回它，否则是空串。
func asString(v any) string {
	s, _ := v.(string)
	return s
}

// orValue：v 算“有”时返回它，否则返回 fallback。
func orValue(v any, fallback any) any {
	if truthy(v) {
		return v
	}
	return fallback
}

// sizeOf 是数组、对象的项数或字符串的字符数，别的算 0。
func sizeOf(v any) int {
	switch t := v.(type) {
	case []any:
		return len(t)
	case obj:
		return len(t)
	case string:
		return utf8.RuneCountInString(t)
	}
	return 0
}

// sameValue：两个 JSON 值相等（数字按数值比）。
func sameValue(a, b any) bool {
	switch x := a.(type) {
	case nil:
		return b == nil
	case string:
		y, ok := b.(string)
		return ok && x == y
	case bool:
		y, ok := b.(bool)
		return ok && x == y
	case json.Number:
		y, ok := b.(json.Number)
		return ok && numberKey(x) == numberKey(y)
	}
	return reflect.DeepEqual(a, b)
}

// valueKey 把 JSON 值变成能当 map 键的字符串，相等的值（数字按数值）得到同一个键。
func valueKey(v any) string {
	switch t := v.(type) {
	case nil:
		return "z"
	case string:
		return "s" + t
	case bool:
		return "b" + strconv.FormatBool(t)
	case json.Number:
		return "n" + numberKey(t)
	}
	return "j" + compactJSON(v)
}

func numberKey(n json.Number) string {
	if i, err := n.Int64(); err == nil {
		return strconv.FormatInt(i, 10)
	}
	if f, err := n.Float64(); err == nil {
		if f == float64(int64(f)) && f < 1<<63 && f >= -(1<<63) {
			return strconv.FormatInt(int64(f), 10)
		}
		return strconv.FormatFloat(f, 'g', -1, 64)
	}
	return string(n)
}

// isIntegerLiteral：JSON 数字写成整数（没有小数点和指数）。
func isIntegerLiteral(n json.Number) bool { return !strings.ContainsAny(string(n), ".eE") }

const (
	maxText   = 6000
	truncMark = "\n…（已截断）" // 应用的 FileDiff.kt 认这个尾巴
)

// clip 把值里过长的字符串（按字符数）截到 maxText，数组、对象逐项处理，返回新的值。
func clip(v any) any {
	switch t := v.(type) {
	case string:
		return clipText(t)
	case []any:
		out := make([]any, len(t))
		for i, x := range t {
			out[i] = clip(x)
		}
		return out
	case obj:
		out := make(obj, len(t))
		for k, x := range t {
			out[k] = clip(x)
		}
		return out
	}
	return v
}

func clipText(s string) string {
	if len(s) <= maxText {
		return s
	}
	n := 0
	for i := range s {
		if n == maxText {
			return s[:i] + truncMark
		}
		n++
	}
	return s
}

// appendQuoted 把字符串写成 JSON 字符串：只转义引号、反斜杠和控制字符（\b \f \n \r \t，其余写成 \u00xx），
// 别的字符原样。Claude Code（JavaScript 的 JSON.stringify）写会话记录也是这样，所以拿它在记录行里按子串找，
// 和追加 custom-title 行都用它。
func appendQuoted(b []byte, s string) []byte {
	const hex = "0123456789abcdef"
	b = append(b, '"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '"', '\\':
			b = append(b, '\\', c)
		case '\b':
			b = append(b, '\\', 'b')
		case '\f':
			b = append(b, '\\', 'f')
		case '\n':
			b = append(b, '\\', 'n')
		case '\r':
			b = append(b, '\\', 'r')
		case '\t':
			b = append(b, '\\', 't')
		default:
			if c < 0x20 {
				b = append(b, '\\', 'u', '0', '0', hex[c>>4], hex[c&0xf])
			} else {
				b = append(b, c)
			}
		}
	}
	return append(b, '"')
}

// spacedJSON 把一段 JSON 按原来的键序重写成一行，项之间用 ", "、键值之间用 ": "（导出里工具参数的摘要）。
func spacedJSON(raw []byte) (string, bool) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var b []byte
	var stack []jsonLevel
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			return string(b), len(stack) == 0 && len(b) > 0
		}
		if err != nil {
			return "", false
		}
		if d, ok := tok.(json.Delim); ok && (d == '}' || d == ']') {
			b = append(b, byte(d))
			stack = stack[:len(stack)-1]
			continue
		}
		if n := len(stack); n > 0 {
			b = stack[n-1].separate(b)
		}
		b = appendToken(b, tok)
		if d, ok := tok.(json.Delim); ok {
			stack = append(stack, jsonLevel{object: d == '{'})
		}
	}
}

// jsonLevel 是 spacedJSON 里的一层数组或对象：已经写了几项（对象里键和值各算一项）。
type jsonLevel struct {
	object bool
	items  int
}

// separate 在这一层的下一项前写分隔符。
func (l *jsonLevel) separate(b []byte) []byte {
	switch {
	case l.object && l.items%2 == 1:
		b = append(b, ": "...)
	case l.items > 0:
		b = append(b, ", "...)
	}
	l.items++
	return b
}

func appendToken(b []byte, tok json.Token) []byte {
	switch t := tok.(type) {
	case json.Delim:
		return append(b, byte(t))
	case string:
		return appendQuoted(b, t)
	case json.Number:
		return append(b, t...)
	case bool:
		return strconv.AppendBool(b, t)
	}
	return append(b, "null"...)
}

// plainText 把 JSON 值写成一行文字：字符串原样，数字照原样，别的写成紧凑的 JSON。
func plainText(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case json.Number:
		return string(t)
	}
	return compactJSON(v)
}

// compactJSON 把值写成紧凑的 JSON（不转义 HTML 字符）。
func compactJSON(v any) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
	return strings.TrimSuffix(buf.String(), "\n")
}
