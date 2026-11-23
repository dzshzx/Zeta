package hostlink

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
	Pinned   bool   `json:"pinned"` // 置顶了（标记在那台的核心里，sessions.pin）
	// Ended：没有活着的 Claude Code 进程登记着它（核心给的 live 为假）。核心比客户端旧、没给 live 时，
	// 和客户端自己拼的会话一样是零值，当作还在运行。
	Ended   bool   `json:"-"`
	Backend string `json:"-"` // 在哪台设备上，读的一方填
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
// live 明说为假时 Ended 为真，没给时不算结束。
func (s *Session) UnmarshalJSON(data []byte) error {
	type plain Session
	var v struct {
		plain
		Cwd  json.RawMessage `json:"cwd"`
		Live *bool           `json:"live"`
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return err
	}
	*s = Session(v.plain)
	s.Ended = v.Live != nil && !*v.Live
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

// ParseSessions 解一行 zeta_sessions；缺会话号或文件的项跳过，backend 填进每一项。
func ParseSessions(line []byte, backend string) (SessionsUpdate, error) {
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

// Activity 是核心报的一个会话眼下的样子（conv.activity 推送里的一项，字段见 helper/conv/activity.go）。
type Activity struct {
	// State：waiting（等用户回应）、answering（这一轮在回答）、background（答完了，后台任务还在跑）、idle（进程开着、没在干活）；
	// 进程没了时为空。
	State string `json:"activity"`
	// Tasks 是还在跑的后台任务数。
	Tasks int `json:"tasks"`
	// TurnEnd 是最后一轮结束的时刻（Unix 毫秒），这个核心里还没结束过一轮时为 0。
	TurnEnd int64 `json:"turn_end"`
}

// ParseActivity 解一行 conv.activity：整份的会话号 → 样子。
func ParseActivity(line []byte) (map[string]Activity, error) {
	var msg struct {
		Sessions map[string]Activity `json:"sessions"`
	}
	if err := json.Unmarshal(line, &msg); err != nil {
		return nil, err
	}
	if msg.Sessions == nil {
		msg.Sessions = map[string]Activity{}
	}
	return msg.Sessions, nil
}
