package claudecfg

import (
	"encoding/json"
	"regexp"
	"strings"
)

// Markdown 开头 --- 包起来的 YAML 前言（SKILL.md、记忆文件、Subagent 文件都用它）。只认顶层的 key: value，够读这几种文件；
// 改写时按原文行保留不认识的键，不经过 YAML 库重排。

// frontValue 是前言里一个键的值：普通文字、列表（[a, b] 或逐行 - a），或下一层的 key: value。
type frontValue struct {
	text   string
	items  []string          // 列表
	fields map[string]string // 下一层的键值
	isList bool
}

// String 是给人看的文字：列表用逗号连起来，下一层的键值不算。
func (v frontValue) String() string {
	if v.isList {
		return strings.Join(v.items, ", ")
	}
	return v.text
}

// front 是解析好的前言：键按原文的顺序。
type front struct {
	keys   []string
	values map[string]frontValue
}

func (f front) get(key string) string { return f.values[key].String() }

func (f front) has(key string) bool {
	_, ok := f.values[key]
	return ok
}

// splitFront 把文件分成前言的原文（不含两条 ---）和正文；没有前言时 ok 为 false，body 是整个文件。
func splitFront(text string) (frontText, body string, ok bool) {
	if !strings.HasPrefix(text, "---") {
		return "", text, false
	}
	lines := strings.Split(text, "\n")
	if strings.TrimSpace(lines[0]) != "---" {
		return "", text, false
	}
	for i := 1; i < len(lines); i++ {
		if strings.TrimSpace(lines[i]) == "---" {
			return strings.Join(lines[1:i], "\n"), strings.Join(lines[i+1:], "\n"), true
		}
	}
	return "", text, false
}

// frontBlock 是前言里的一个顶层键和它占的原文行；key 为空是第一个键之前的行（注释之类）。
type frontBlock struct {
	key   string
	lines []string
}

var (
	keyLine         = regexp.MustCompile(`(?s)^([A-Za-z0-9_-]+)\s*:(.*)$`)
	nestedLine      = regexp.MustCompile(`(?s)^\s+([A-Za-z0-9_-]+)\s*:(.*)$`)
	trailingComment = regexp.MustCompile(`\s+#.*$`)
	numberish       = regexp.MustCompile(`^[-+]?(\d[\d_]*)?(\.\d*)?([eE][-+]?\d+)?$`)
)

func frontBlocks(text string) []frontBlock {
	var blocks []frontBlock
	for _, line := range strings.Split(text, "\n") {
		m := keyLine.FindStringSubmatch(line)
		switch {
		case m != nil && !startsWithSpace(line):
			blocks = append(blocks, frontBlock{key: m[1], lines: []string{line}})
		case len(blocks) > 0:
			blocks[len(blocks)-1].lines = append(blocks[len(blocks)-1].lines, line)
		default:
			blocks = append(blocks, frontBlock{lines: []string{line}})
		}
	}
	return blocks
}

func startsWithSpace(s string) bool { return s != "" && (s[0] == ' ' || s[0] == '\t') }

// parseFront 解析前言的原文。
func parseFront(text string) front {
	f := front{values: map[string]frontValue{}}
	for _, b := range frontBlocks(text) {
		if b.key == "" {
			continue
		}
		if !f.has(b.key) {
			f.keys = append(f.keys, b.key)
		}
		f.values[b.key] = blockValue(b)
	}
	return f
}

var blockIndicators = map[string]bool{"|": true, ">": true, "|-": true, ">-": true, "|+": true, ">+": true}

func blockValue(b frontBlock) frontValue {
	_, first, _ := strings.Cut(b.lines[0], ":")
	first = strings.TrimSpace(first)
	tail := b.lines[1:]
	var rest []string
	for _, l := range tail {
		if strings.TrimSpace(l) != "" {
			rest = append(rest, l)
		}
	}
	switch {
	case blockIndicators[first]:
		return blockScalar(first, tail, rest)
	case first == "" && len(rest) > 0 && allItems(rest):
		v := frontValue{isList: true}
		for _, l := range rest {
			v.items = append(v.items, scalar(strings.TrimLeft(l, " \t")[2:]).String())
		}
		return v
	case first == "" && len(rest) > 0:
		v := frontValue{fields: map[string]string{}}
		for _, l := range rest {
			if m := nestedLine.FindStringSubmatch(l); m != nil {
				v.fields[m[1]] = scalar(m[2]).String()
			}
		}
		return v
	case len(rest) > 0 && !strings.HasPrefix(first, "[") && !strings.HasPrefix(first, `"`) && !strings.HasPrefix(first, "'"):
		// 折行的普通标量
		parts := []string{first}
		for _, l := range rest {
			parts = append(parts, strings.TrimSpace(l))
		}
		return scalar(strings.Join(parts, " "))
	}
	return scalar(first)
}

func allItems(lines []string) bool {
	for _, l := range lines {
		if !strings.HasPrefix(strings.TrimLeft(l, " \t"), "- ") {
			return false
		}
	}
	return true
}

// blockScalar 是 | 或 > 开头的多行文字：去掉共同的缩进，| 保留换行，> 连成一行。
func blockScalar(indicator string, tail, rest []string) frontValue {
	indent := -1
	for _, l := range rest {
		n := len(l) - len(strings.TrimLeft(l, " \t"))
		if indent < 0 || n < indent {
			indent = n
		}
	}
	indent = max(indent, 0)
	body := make([]string, len(tail))
	for i, l := range tail {
		if len(l) >= indent {
			body[i] = l[indent:]
		}
	}
	sep := " "
	if indicator[0] == '|' {
		sep = "\n"
	}
	return frontValue{text: strings.TrimSpace(strings.Join(body, sep))}
}

func scalar(raw string) frontValue {
	v := strings.TrimSpace(raw)
	switch {
	case v == "":
		return frontValue{}
	case v[0] == '"':
		var s string
		if json.Unmarshal([]byte(v), &s) == nil {
			return frontValue{text: s}
		}
		return frontValue{text: strings.Trim(v, `"`)}
	case v[0] == '\'' && len(v) > 1 && strings.HasSuffix(v, "'"):
		return frontValue{text: strings.ReplaceAll(v[1:len(v)-1], "''", "'")}
	case v[0] == '[' && strings.HasSuffix(v, "]"):
		out := frontValue{isList: true}
		for _, part := range strings.Split(v[1:len(v)-1], ",") {
			if strings.TrimSpace(part) != "" {
				out.items = append(out.items, scalar(part).String())
			}
		}
		return out
	}
	return frontValue{text: trailingComment.ReplaceAllString(v, "")}
}

var plainWords = map[string]bool{"true": true, "false": true, "null": true, "yes": true, "no": true, "on": true, "off": true, "~": true}

// yamlValue：能写成普通标量就原样写，否则写成双引号字符串（JSON 的写法同时也是合法的 YAML）。
func yamlValue(text string) string {
	quoteIt := text == "" || text != strings.TrimSpace(text) || strings.ContainsAny(text, "\n\r\t") ||
		strings.Contains(text, ": ") || strings.Contains(text, " #") || strings.HasSuffix(text, ":") ||
		strings.ContainsRune("!&*[]{}|>'\"%@`#,?-:", rune(text[0])) || plainWords[strings.ToLower(text)] ||
		(strings.ContainsAny(text, "0123456789") && numberish.MatchString(text))
	if !quoteIt {
		return text
	}
	var b strings.Builder
	writeString(&b, text)
	return b.String()
}
