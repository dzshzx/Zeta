package host

import (
	"encoding/json"
	"strings"
)

// Session 是会话列表里的一项：zeta-helper list、lookup 的一行，serve 推来的 zeta_sessions 里的一项（字段见 helper/sessions.go）。
type Session struct {
	ID       string `json:"id"`
	File     string `json:"file"`
	Cwd      string `json:"cwd"`
	Title    string `json:"title"`
	Preview  string `json:"preview"`
	Updated  int64  `json:"updated"`  // 毫秒
	External bool   `json:"external"` // 正被 Zeta 之外的 Claude Code 进程占着（比如终端里开着）
	Archived bool   `json:"archived"`
	Backend  string `json:"-"` // 在哪台设备上，读的一方填
}

// Place 是工作目录的最后一级。
func (s Session) Place() string {
	place := strings.TrimRight(s.Cwd, "/")
	if i := strings.LastIndex(place, "/"); i >= 0 {
		place = place[i+1:]
	}
	if place == "" {
		return "/"
	}
	return place
}

// Label 是列表里显示的标题：没有标题时是第一句话。
func (s Session) Label() string {
	if strings.TrimSpace(s.Title) != "" {
		return s.Title
	}
	return s.Preview
}

// UnmarshalJSON：cwd 是 helper 照记录原样给的，可能是字符串、null 或别的值：字符串照用，null 是空，别的值用它的 JSON 写法。
func (s *Session) UnmarshalJSON(data []byte) error {
	type plain Session
	var v struct {
		plain
		Cwd json.RawMessage `json:"cwd"`
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return err
	}
	*s = Session(v.plain)
	if json.Unmarshal(v.Cwd, &s.Cwd) != nil && len(v.Cwd) > 0 && string(v.Cwd) != "null" {
		s.Cwd = string(v.Cwd)
	}
	return nil
}

// SessionsUpdate 是 serve 推来的一行 zeta_sessions（见 helper/watch.go）：Full 时 Upsert 是那台的整份、按时间从新到旧；
// 否则 Upsert 是新出现或变了的项，Remove 是不再列出的会话号。
type SessionsUpdate struct {
	Full   bool
	Upsert []Session
	Remove []string
}

// parseSessions 解一行 zeta_sessions；缺会话号或文件的项跳过，backend 填进每一项。
func parseSessions(line []byte, backend string) (SessionsUpdate, error) {
	var msg struct {
		Full   bool              `json:"full"`
		Upsert []json.RawMessage `json:"upsert"`
		Remove []string          `json:"remove"`
	}
	if err := json.Unmarshal(line, &msg); err != nil {
		return SessionsUpdate{}, err
	}
	u := SessionsUpdate{Full: msg.Full, Remove: msg.Remove}
	for _, raw := range msg.Upsert {
		var s Session
		if json.Unmarshal(raw, &s) != nil || s.ID == "" || s.File == "" {
			continue
		}
		s.Backend = backend
		u.Upsert = append(u.Upsert, s)
	}
	return u, nil
}
