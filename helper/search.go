package main

import (
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	searchTime  = 10 * time.Second
	searchLimit = 80
)

// spokenText 是一条记录里用户或 Claude 亲口说的话和说话的角色；工具调用、工具结果、系统注入的内容的文字是空串。
func spokenText(o obj) (role, text string) {
	if truthy(o["isSidechain"]) || truthy(o["isMeta"]) {
		return "", ""
	}
	content := asObj(o["message"])["content"]
	switch o["type"] {
	case "attachment":
		attachment := asObj(o["attachment"])
		if attachment["type"] != "queued_command" || !truthy(attachment["prompt"]) {
			return "", ""
		}
		return "user", userText(attachment["prompt"])
	case "user":
		if truthy(o["isCompactSummary"]) || hasToolResult(content) {
			return "", ""
		}
		return "user", userText(content)
	case "assistant":
		list, ok := content.([]any)
		if !ok {
			return "", ""
		}
		var parts []string
		for _, b := range list {
			if m, ok := b.(obj); ok && m["type"] == "text" {
				parts = append(parts, asString(m["text"]))
			}
		}
		return "assistant", strings.TrimSpace(strings.Join(parts, "\n"))
	}
	return "", ""
}

// snippet 是命中处的摘要：前面留一点、后面多留些，折成一行，侧栏里两行放得下。at、length 按字符数。
func snippet(text string, at, length int) string {
	r := []rune(text)
	start := max(0, at-20)
	end := min(len(r), at+length+70)
	body := squash(string(r[start:end]))
	if start > 0 {
		body = "…" + body
	}
	if end < len(r) {
		body += "…"
	}
	return body
}

// searcher 在一份份记录里找关键词。
type searcher struct {
	needle  string // 小写的关键词
	escaped string // 关键词在 JSON 字符串里的样子：记录里的字符串是转义过的，按行粗筛时用它比
}

// cmdSearch 只看用户和 Claude 说的话。逐行先按子串粗筛（工具结果、请求快照这类大行不解析），每个会话取第一处命中就换下一个；
// 限时限量，免得几百 MB 的记录把手机拖住。
func cmdSearch(e *env, o *emitter, query string) error {
	needle := strings.ToLower(strings.TrimSpace(query))
	if needle == "" {
		return errors.New("empty query")
	}
	quoted := appendQuoted(nil, needle)
	s := searcher{needle: needle, escaped: string(quoted[1 : len(quoted)-1])}
	files := e.sessionFiles()
	sortNewestFirst(files)
	files = files[:min(len(files), listLimit)]
	deadline := time.Now().Add(searchTime)
	scanned, matched := 0, 0
	complete := true
	for _, path := range files {
		if time.Now().After(deadline) || matched >= searchLimit {
			complete = false
			break
		}
		scanned++
		if role, snip, ok := s.file(path); ok {
			matched++
			o.out(obj{"type": "zeta_match", "id": dropRunes(baseName(path), 6), "role": role, "snippet": snip})
			o.flush()
		}
	}
	o.out(obj{"type": "zeta_search_done", "scanned": scanned, "total": len(files), "matched": matched, "complete": complete})
	return nil
}

// file 在一份记录里找第一处命中。
func (s searcher) file(path string) (role, snip string, found bool) {
	f, err := openFile(path)
	if err != nil {
		return "", "", false
	}
	defer f.Close()
	lr := newLineReader(f)
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		if role, snip, found := s.line(line); found {
			return role, snip, true
		}
	}
	return "", "", false
}

func (s searcher) line(line []byte) (role, snip string, found bool) {
	switch {
	case has(line, `"type":"user"`):
		if has(line, `"tool_result"`) {
			return "", "", false
		}
	case has(line, `"type":"assistant"`):
		if !has(line, `"type":"text"`) {
			return "", "", false
		}
	case !has(line, `"queued_command"`):
		return "", "", false
	}
	if !strings.Contains(strings.ToLower(decodeString(line)), s.escaped) {
		return "", "", false
	}
	o, ok := parseObject(line)
	if !ok {
		return "", "", false
	}
	role, text := spokenText(o)
	if strings.Contains(text, "[Attached ") {
		// 附件行只留文件名，摘要里不出现一长串路径
		lines := strings.Split(text, "\n")
		for i, l := range lines {
			if name, ok := attachedName(l); ok {
				lines[i] = "附件 " + name
			}
		}
		text = strings.Join(lines, "\n")
	}
	if text == "" {
		return "", "", false
	}
	// strings.ToLower 逐字符一对一地换，小写后的第几个字符就是原文的第几个字符
	lower := strings.ToLower(text)
	at := strings.Index(lower, s.needle)
	if at < 0 {
		return "", "", false // 只是命中了路径、id 之类的字段
	}
	return role, snippet(text, utf8.RuneCountInString(lower[:at]), runeLen(s.needle)), true
}
