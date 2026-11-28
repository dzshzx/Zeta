package main

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/locales"
)

// searchTime、searchLimit 是一次搜索最多找多久、最多几个命中；测试里改。
var (
	searchTime  = 10 * time.Second
	searchLimit = 80
)

// searchCheckEvery：一份记录里每读这么多行看一次搜索是不是该停了。
const searchCheckEvery = 256

// spokenText 是一条记录里用户或 Claude 亲口说的话和说话的角色；工具调用、工具结果、系统注入的内容的文字是空串。
func spokenText(o obj, lang string) (role, text string) {
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
		if name, body, ok := peerMessage(attachment); ok {
			return "user", locales.M("searchPeerMessage", "name", name, "body", body).In(lang) // 片段里也认得出是别的会话发来的
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
	ctx     context.Context // 取消或到了时限就不再往下读
	needle  string          // 小写的关键词
	escaped string          // 关键词在 JSON 字符串里的样子：记录里的字符串是转义过的，按行粗筛时用它比
	lang    string          // 片段里核心自己加的字（来自会话、附件）用这个语言写，是发起搜索的客户端的语言
}

// cmdSearch 边找边输出 zeta_match，最后一行是汇总 zeta_search_done（见 searchSessions）。
func cmdSearch(e *env, o *emitter, query string) error {
	done, err := searchSessions(context.Background(), e, query, "", func(m searchMatch) {
		o.out(obj{"type": "zeta_match", "id": m.ID, "role": m.Role, "snippet": m.Snippet})
		o.flush()
	})
	if err != nil {
		return err
	}
	o.out(obj{"type": "zeta_search_done", "scanned": done.Scanned, "total": done.Total, "matched": done.Matched, "complete": done.Complete})
	return nil
}

// searchMatch 是一个会话里的第一处命中。
type searchMatch struct {
	ID      string `json:"id"`
	Role    string `json:"role"`
	Snippet string `json:"snippet"`
}

// searchDone 是一次搜索的汇总：看了几份、一共几份、命中几个；Complete 为假是到了时限、个数，或被取消。
type searchDone struct {
	Scanned  int  `json:"scanned"`
	Total    int  `json:"total"`
	Matched  int  `json:"matched"`
	Complete bool `json:"complete"`
}

// searchSessions 在最新的 listLimit 个会话里找关键词，只看用户和 Claude 说的话。逐行先按子串粗筛（工具结果、请求快照这类大行不解析），
// 每个会话取第一处命中就换下一个，命中一个就交给 found。限时（searchTime）限量，免得几百 MB 的记录把手机拖住；
// ctx 取消时也停，每读完一份记录、一份里每读 searchCheckEvery 行看一次。停下时照样给出汇总，Complete 为假。
// zeta-helper search 和核心的 sessions.search 都用它。
func searchSessions(ctx context.Context, e *env, query, lang string, found func(searchMatch)) (searchDone, error) {
	needle := strings.ToLower(strings.TrimSpace(query))
	if needle == "" {
		return searchDone{}, errors.New("empty query")
	}
	ctx, cancel := context.WithTimeout(ctx, searchTime)
	defer cancel()
	quoted := appendQuoted(nil, needle)
	s := searcher{ctx: ctx, needle: needle, escaped: string(quoted[1 : len(quoted)-1]), lang: lang}
	files := e.sessionFiles()
	sortNewestFirst(files)
	files = files[:min(len(files), listLimit)]
	done := searchDone{Total: len(files), Complete: true}
	for _, path := range files {
		if ctx.Err() != nil || done.Matched >= searchLimit {
			done.Complete = false
			break
		}
		done.Scanned++
		if role, snip, ok := s.file(path); ok {
			done.Matched++
			found(searchMatch{ID: sessionIDOfPath(path), Role: role, Snippet: snip})
		}
	}
	if ctx.Err() != nil {
		// 最后一份读到一半停下的
		done.Complete = false
	}
	return done, nil
}

// file 在一份记录里找第一处命中；搜索该停时不再往下读，当没找到。
func (s searcher) file(path string) (role, snip string, found bool) {
	if _, _, ok := agyDirFromTranscript(path); ok {
		return s.agyFile(path)
	}
	f, err := openFile(path)
	if err != nil {
		return "", "", false
	}
	defer f.Close()
	lr := newLineReader(f)
	n := 0
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		if n++; n%searchCheckEvery == 0 && s.ctx.Err() != nil {
			return "", "", false
		}
		if role, snip, found := s.line(line); found {
			return role, snip, true
		}
	}
	return "", "", false
}

func (s searcher) agyFile(path string) (role, snip string, found bool) {
	role, text, ok := agystore.SearchTranscript(path, s.needle)
	if !ok || text == "" {
		return "", "", false
	}
	lower := strings.ToLower(text)
	at := max(0, strings.Index(lower, s.needle))
	return role, snippet(text, utf8.RuneCountInString(lower[:at]), runeLen(s.needle)), true
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
	role, text := spokenText(o, s.lang)
	if strings.Contains(text, "[Attached ") {
		// 附件行只留文件名，摘要里不出现一长串路径
		lines := strings.Split(text, "\n")
		for i, l := range lines {
			if name, ok := attachedName(l); ok {
				lines[i] = locales.M("searchAttachment", "name", name).In(s.lang)
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
