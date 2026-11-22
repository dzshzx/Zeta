package hubtest

import (
	"encoding/json"
	"errors"
	"maps"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
)

// 假核心的 sessions.* 方法（docs/core.md「会话」）：照 Devices 上预先给的答，结果的样子和真的一样。
// 改名、归档、删除只答成功，不改列表（真核心改完经 sessions.watch 推来，测试要的话自己 Push）。

// answerSessions 答设备 host 上发来的 sessions.* 请求，记下「设备编号 方法 参数 JSON」。
func (d *Devices) answerSessions(host string, c Call) (json.RawMessage, error) {
	d.mu.Lock()
	d.sessions = append(d.sessions, host+" "+c.Method+" "+c.JSON())
	err := d.SessionsErr[c.Method]
	d.mu.Unlock()
	if err != nil {
		return nil, err
	}
	var result any
	switch c.Method {
	case "sessions.rename":
		result = map[string]any{"title": c.Str("title")}
	case "sessions.archive":
		result = map[string]any{"archived": c.Params["archived"]}
	case "sessions.delete":
		result = map[string]any{"deleted": []any{}, "closed": 0}
	case "sessions.export":
		id := c.Str("session")
		result = map[string]any{"title": "对话 " + id, "markdown": "# 对话 " + id + "\n\n正文\n", "file_name": "Zeta-对话 " + id + "-20261122-1500.md"}
	case "sessions.lookup":
		result = d.lookup(host, c.Params["sessions"])
	case "sessions.search":
		return d.search(host, c.Str("query"))
	case "sessions.image":
		if d.ImageData == nil {
			return nil, errors.New("没有这张图")
		}
		result = map[string]any{"media_type": "image/png", "data": d.ImageData}
	default:
		return nil, errors.New("假核心没有 " + c.Method)
	}
	return json.Marshal(result)
}

// lookup 照 Far 答：请求的会话号里找得到的，按请求的顺序。
func (d *Devices) lookup(host string, ids any) []hostlink.Session {
	d.mu.Lock()
	defer d.mu.Unlock()
	list, _ := ids.([]string)
	if list == nil {
		for _, v := range asSlice(ids) {
			s, _ := v.(string)
			list = append(list, s)
		}
	}
	found := []hostlink.Session{}
	for _, id := range list {
		if i := slices.IndexFunc(d.Far[host], func(s hostlink.Session) bool { return s.ID == id }); i >= 0 {
			found = append(found, d.Far[host][i])
		}
	}
	return found
}

func asSlice(v any) []any {
	s, _ := v.([]any)
	return s
}

// search 照 SearchHits、SearchErr、Partial 答一次 sessions.search，记下「设备编号 关键词」。
func (d *Devices) search(host, query string) (json.RawMessage, error) {
	d.mu.Lock()
	d.searched = append(d.searched, host+" "+query)
	hits, err, partial := d.SearchHits[host], d.SearchErr[host], d.Partial[host]
	d.mu.Unlock()
	if err != nil {
		return nil, err
	}
	type match struct {
		ID      string `json:"id"`
		Role    string `json:"role"`
		Snippet string `json:"snippet"`
	}
	matches := []match{}
	for _, id := range slices.Sorted(maps.Keys(hits)) {
		if strings.Contains(strings.ToLower(hits[id]), strings.ToLower(query)) {
			matches = append(matches, match{id, "user", hits[id]})
		}
	}
	return json.Marshal(map[string]any{"matches": matches, "complete": !partial, "matched": len(matches)})
}

// SessionCalls 取走到现在发来的 sessions.* 请求（除了 sessions.search、sessions.image），每个是「设备编号 方法 参数 JSON」。
func (d *Devices) SessionCalls() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := slices.DeleteFunc(d.sessions, func(s string) bool {
		_, rest, _ := strings.Cut(s, " ")
		return strings.HasPrefix(rest, "sessions.search ") || strings.HasPrefix(rest, "sessions.image ")
	})
	d.sessions = nil
	return out
}

// Hostname 见 hub.Devices：照 Hostnames 答。
func (d *Devices) Hostname(h sshlink.Host) (string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if name, ok := d.Hostnames[h.ID]; ok {
		return name, nil
	}
	return "", errors.New("「" + h.Name() + "」没有报主机名")
}
