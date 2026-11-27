package hubtest

import (
	"encoding/json"
	"errors"
	"maps"
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 假核心的 sessions.* 方法（docs/core.md「会话」）和取主机上图片文件的 images.file：照 Devices 上预先给的答，结果的样子和真的一样。
// 置顶、手动排序、改名、归档、删除、结束只答成功，不改列表（真核心改完经 sessions.watch 推来，测试要的话自己 Push）。

// answerSessions 答连接 l 上的 sessions.* 和 images.file 请求，记下「设备编号 方法 参数 JSON」。
func (d *Devices) answerSessions(l *ChatLink, c Call) (json.RawMessage, error) {
	host := l.Host
	d.mu.Lock()
	d.sessions = append(d.sessions, host+" "+c.Method+" "+c.JSON())
	err, hook := d.SessionsErr[c.Method], d.OnSessions
	if err == nil {
		err = d.ErrBySession[c.Str("session")]
	}
	d.mu.Unlock()
	if hook != nil {
		hook(host, c)
	}
	if err != nil {
		return nil, err
	}
	var result any
	switch c.Method {
	case "sessions.order":
		result = map[string]any{"rank": c.Params["rank"]}
	case "sessions.pin":
		result = map[string]any{"pinned": c.Params["pinned"]}
	case "sessions.rename":
		result = map[string]any{"title": sessionproto.CleanTitle(c.Str("title"))}
	case "sessions.archive":
		result = map[string]any{"archived": c.Params["archived"]}
	case "sessions.delete":
		result = map[string]any{"deleted": []any{}, "closed": 0}
	case "sessions.end", "sessions.end_elsewhere":
		result = map[string]any{"ended": 1}
	case "sessions.export":
		return d.export(c)
	case "sessions.search":
		d.mu.Lock()
		onSearch := d.OnSearch
		d.mu.Unlock()
		if onSearch != nil {
			return onSearch(l, c.Params["search"], c.Str("query"))
		}
		return d.search(l, c.Params["search"], c.Str("query"))
	case "sessions.handoff":
		return d.answer(d.Handoffs, c.Str("session"), `{"handed":true,"status":null}`)
	case "sessions.image":
		if d.ImageData == nil {
			return nil, errors.New("没有这张图")
		}
		result = map[string]any{"media_type": "image/png", "data": d.ImageData}
	case "images.file":
		d.mu.Lock()
		data := d.FileImages[c.Str("path")]
		d.mu.Unlock()
		if data == nil {
			return nil, errors.New("不是图片文件或读不到")
		}
		result = map[string]any{"media_type": "image/png", "data": data}
	default:
		return nil, errors.New("假核心没有 " + c.Method)
	}
	return json.Marshal(result)
}

// export 照核心的格式答 sessions.export（带 sessions）：每个会话的导出是「# 对话 <会话号>」加一行正文，标题是「对话 <会话号>」；
// 按给的顺序合成一份（sessionproto.CombineExports），各自的放在 parts 里。文件名的时刻是 utc_offset 那个时区里的
// 2026-11-22 15:00（不管偏多少都是 1500）。
func (d *Devices) export(c Call) (json.RawMessage, error) {
	ids := c.Strs("sessions")
	parts := make([]sessionproto.ExportPart, len(ids))
	for i, id := range ids {
		parts[i] = sessionproto.ExportPart{Session: id, Title: "对话 " + id, Markdown: "# 对话 " + id + "\n\n正文\n"}
	}
	title, markdown := sessionproto.CombineExports(parts, "")
	offset, _ := c.Params["utc_offset"].(int)
	at := time.Date(2026, 11, 22, 15, 0, 0, 0, time.FixedZone("", offset*60))
	return json.Marshal(map[string]any{"title": title, "markdown": markdown,
		"file_name": sessionproto.ExportFileName(title, at, ""), "exported": ids, "failed": []string{}, "parts": parts})
}

// answer 是 results 里给会话 session 的结果，没给时是 fallback。
func (d *Devices) answer(results map[string]string, session, fallback string) (json.RawMessage, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if r, ok := results[session]; ok {
		return json.RawMessage(r), nil
	}
	return json.RawMessage(fallback), nil
}

// search 照 SearchHits、SearchErr、Partial 答连接 l 上名叫 name 的 sessions.search：命中一个个推 sessions.match，
// 再答个数和搜没搜完。记下「设备编号 关键词」。
func (d *Devices) search(l *ChatLink, name any, query string) (json.RawMessage, error) {
	host := l.Host
	d.mu.Lock()
	d.searched = append(d.searched, host+" "+query)
	hits, err, partial := d.SearchHits[host], d.SearchErr[host], d.Partial[host]
	d.mu.Unlock()
	if err != nil {
		return nil, err
	}
	matched := 0
	for _, session := range slices.Sorted(maps.Keys(hits)) {
		if strings.Contains(strings.ToLower(hits[session]), strings.ToLower(query)) {
			l.PushMatch(name, session, hits[session])
			matched++
		}
	}
	return json.Marshal(map[string]any{"scanned": len(hits), "total": len(hits), "matched": matched, "complete": !partial})
}

// PushMatch 推一行名叫 search 的搜索的命中（sessions.match）。
func (l *ChatLink) PushMatch(search any, session, snippet string) {
	line, _ := json.Marshal(map[string]any{"type": "sessions.match", "search": search, "id": session, "role": "user", "snippet": snippet})
	l.Push(string(line))
}

// SessionCalls 取走到现在发来的 sessions.* 请求（sessions.search、sessions.image 和 images.file 不算），每个是「设备编号 方法 参数 JSON」。
func (d *Devices) SessionCalls() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := slices.DeleteFunc(d.sessions, func(s string) bool {
		_, rest, _ := strings.Cut(s, " ")
		return strings.HasPrefix(rest, "sessions.search ") || strings.HasPrefix(rest, "sessions.image ") || strings.HasPrefix(rest, "images.file ")
	})
	d.sessions = nil
	return out
}
