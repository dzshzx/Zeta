package transcript

import (
	"encoding/json"
	"strconv"
	"strings"
	"unicode"
)

// 读 JSON 值的小工具，语义照 Kotlin 一侧（kotlinx.serialization 的 contentOrNull、intOrNull、booleanOrNull，
// Kotlin 的 trim、isBlank、lines），两边对同样的输入得出同样的条目。输入按 json.Decoder.UseNumber 解出来。

type obj = map[string]any

// str 是 JsonPrimitive.contentOrNull：字符串、数字（原样的文字）、真假值都有内容，null、对象、数组和没有这个键都没有。
func str(o obj, key string) (string, bool) {
	switch v := o[key].(type) {
	case string:
		return v, true
	case json.Number:
		return string(v), true
	case bool:
		return strconv.FormatBool(v), true
	}
	return "", false
}

func strOr(o obj, key string) string {
	s, _ := str(o, key)
	return s
}

// integer 是 JsonPrimitive.intOrNull：内容能当 32 位整数读的才算。
func integer(o obj, key string) (int, bool) {
	s, ok := str(o, key)
	if !ok {
		return 0, false
	}
	n, err := strconv.ParseInt(s, 10, 32)
	return int(n), err == nil
}

// boolean 是 JsonPrimitive.booleanOrNull：内容恰好是 true 或 false。
func boolean(o obj, key string) (bool, bool) {
	s, ok := str(o, key)
	if !ok || (s != "true" && s != "false") {
		return false, false
	}
	return s == "true", true
}

func object(o obj, key string) obj {
	m, _ := o[key].(map[string]any)
	return m
}

func array(o obj, key string) []any {
	a, _ := o[key].([]any)
	return a
}

// flattenText 是工具结果 content 的文字：字符串（或别的原始值）本身，或若干块里各块的 text 用换行连起来。
func flattenText(v any) string {
	switch x := v.(type) {
	case []any:
		var parts []string
		for _, e := range x {
			if b, ok := e.(map[string]any); ok {
				if t, ok := str(b, "text"); ok {
					parts = append(parts, t)
				}
			}
		}
		return strings.Join(parts, "\n")
	case map[string]any, nil:
		return ""
	default:
		s, _ := str(obj{"v": x}, "v")
		return s
	}
}

// isSpace 是 Kotlin 的 Char.isWhitespace：Unicode 的各种空格、行段分隔符，加上 \t 到 \r 和 U+001C 到 U+001F。
func isSpace(r rune) bool {
	return (r >= '\t' && r <= '\r') || (r >= 0x1c && r <= 0x1f) ||
		unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

func trim(s string) string    { return strings.TrimFunc(s, isSpace) }
func trimEnd(s string) string { return strings.TrimRightFunc(s, isSpace) }
func isBlank(s string) bool {
	return strings.IndexFunc(s, func(r rune) bool { return !isSpace(r) }) < 0
}
func intToString(n int) string { return strconv.Itoa(n) }
func ptr[T any](v T) *T        { return &v }

// lines 是 Kotlin 的 String.lines()：按 \r\n、\n、\r 断行，结尾的换行后面还算一行（空的）。
func lines(s string) []string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return strings.Split(s, "\n")
}

// firstLine 是 lineSequence().firstOrNull().orEmpty()。
func firstLine(s string) string { return lines(s)[0] }
