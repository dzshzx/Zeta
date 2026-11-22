package main

import (
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/core"
)

// 核心的 sessions.* 方法（订阅 sessions.watch、sessions.unwatch 之外的）：侧栏对会话的操作，几个客户端读写同一份，
// 逻辑和同名的 zeta-helper 子命令是同一份。参数、结果、错误代号见 docs/core.md「会话」。

// sessionCall 是一个 sessions.* 请求：参数和 id、method 平级。
type sessionCall struct {
	Session  string   `json:"session"`
	Title    string   `json:"title"`
	Archived bool     `json:"archived"`
	Sessions []string `json:"sessions"`
	Query    string   `json:"query"`
	File     string   `json:"file"`
	Ref      string   `json:"ref"`

	client *core.Client
	id     json.RawMessage // 请求的 id：sessions.search 的推送带着它
}

// sessionMethod 是一个方法怎么做：slow 的在自己的 goroutine 里做、做完再答，不挡同一个客户端后面的请求；
// session 为真的要带样子对的会话号。
type sessionMethod struct {
	run     func(*sessionsHandler, *sessionCall) (any, error)
	slow    bool
	session bool
}

var sessionMethods = map[string]sessionMethod{
	"sessions.rename":     {run: (*sessionsHandler).rename, session: true},
	"sessions.occupant":   {run: (*sessionsHandler).occupant, session: true},
	"sessions.archive":    {run: (*sessionsHandler).archive, slow: true, session: true}, // 归档时关 tmux 窗口
	"sessions.delete":     {run: (*sessionsHandler).deleteSession, slow: true, session: true},
	"sessions.export":     {run: (*sessionsHandler).export, slow: true, session: true},
	"sessions.handoff":    {run: (*sessionsHandler).handoff, slow: true, session: true},
	"sessions.desk_open":  {run: (*sessionsHandler).deskOpen, slow: true, session: true},
	"sessions.desk_close": {run: (*sessionsHandler).deskClose, slow: true, session: true},
	"sessions.lookup":     {run: (*sessionsHandler).lookup, slow: true},
	"sessions.search":     {run: (*sessionsHandler).search, slow: true},
	"sessions.image":      {run: (*sessionsHandler).image, slow: true},
}

// errNoSession：这个会话号没有会话记录（或给的记录已经不在）。
var errNoSession = codedError{code: "no_session", err: errors.New("这个会话的记录已经不在了")}

// call 做一个 sessions.* 请求。
func (h *sessionsHandler) call(c *core.Client, r core.Request) (any, error) {
	m, ok := sessionMethods[r.Method]
	if !ok {
		return nil, errors.New("unknown method: " + r.Method)
	}
	q := &sessionCall{client: c, id: r.ID}
	if err := json.Unmarshal(r.Raw, q); err != nil {
		return nil, errors.New("bad params: " + err.Error())
	}
	if m.session && !isSessionID(q.Session) {
		return nil, errors.New("bad session id")
	}
	if !m.slow {
		return m.run(h, q)
	}
	go func() {
		v, err := m.run(h, q)
		c.Reply(r.ID, v, err)
	}()
	return core.Later, nil
}

// record 是会话的记录文件（同一个会话号在几个项目目录里都有时是最新的那份）；没有时是 errNoSession。
func (h *sessionsHandler) record(session string) (string, error) {
	if path := sessionFile(h.e, session); path != "" {
		return path, nil
	}
	return "", errNoSession
}

func (h *sessionsHandler) rename(q *sessionCall) (any, error) {
	path, err := h.record(q.Session)
	if err != nil {
		return nil, err
	}
	title, err := renameSession(path, q.Title)
	if err != nil {
		return nil, err
	}
	return obj{"title": title}, nil
}

func (h *sessionsHandler) archive(q *sessionCall) (any, error) {
	// 归档文件是读一遍、改了整份换上，几个客户端同时改时一个一个来
	h.archiveMu.Lock()
	err := archiveSessions(h.e, []string{q.Session}, q.Archived)
	h.archiveMu.Unlock()
	if err != nil {
		return nil, err
	}
	if q.Archived {
		deskClose([]string{q.Session})
	}
	return obj{"archived": q.Archived}, nil
}

func (h *sessionsHandler) deleteSession(q *sessionCall) (any, error) {
	deleted := []deletedRecord{}
	for _, path := range h.e.sessionFilesNamed(q.Session) {
		if !h.e.insideRoot(path) {
			continue
		}
		d, err := deleteRecord(h.e, path)
		if err != nil {
			return nil, err
		}
		deleted = append(deleted, d)
	}
	return obj{"deleted": deleted, "closed": deskClose([]string{q.Session})}, nil
}

func (h *sessionsHandler) export(q *sessionCall) (any, error) {
	path, err := h.record(q.Session)
	if err != nil {
		return nil, err
	}
	markdown, title, err := exportSession(path)
	if err != nil {
		if isNotExist(err) {
			return nil, errNoSession
		}
		return nil, err
	}
	return obj{"title": title, "markdown": markdown, "file_name": exportFileName(title, time.Now())}, nil
}

// fileNameUnsafe 是文件名里不能用的字符（Windows 的保留字符和控制字符）。
var fileNameUnsafe = regexp.MustCompile(`[\\/:*?"<>|\p{C}]`)

// exportFileName 是导出文件的建议名字，照 Eta：Zeta-标题-年月日-时分.md；标题去掉不能用的字符、最多 40 字，空了是「对话」。
func exportFileName(title string, now time.Time) string {
	clean := strings.TrimSpace(fileNameUnsafe.ReplaceAllString(squash(title), ""))
	clean = strings.TrimSpace(runePrefix(clean, 40))
	if clean == "" {
		clean = "对话"
	}
	return "Zeta-" + clean + "-" + now.Format("20060102-1504") + ".md"
}

func (h *sessionsHandler) lookup(q *sessionCall) (any, error) {
	if !allOf(q.Sessions, isSessionID) {
		return nil, errors.New("bad session id")
	}
	return lookupSessions(h.e, q.Sessions), nil
}

func (h *sessionsHandler) search(q *sessionCall) (any, error) {
	matches := []searchMatch{}
	done, err := searchSessions(h.e, q.Query, func(m searchMatch) bool {
		matches = append(matches, m)
		// 客户端走了就不再找
		return q.client.Send(obj{"type": "sessions.match", "search": q.id, "id": m.ID, "role": m.Role, "snippet": m.Snippet}) == nil
	})
	if err != nil {
		return nil, err
	}
	return struct {
		Matches []searchMatch `json:"matches"`
		searchDone
	}{matches, done}, nil
}

func (h *sessionsHandler) image(q *sessionCall) (any, error) {
	if q.File == "" {
		return nil, errors.New("bad params: no file")
	}
	if !lexists(q.File) {
		return nil, errNoSession
	}
	if !h.e.insideRoot(q.File) {
		return nil, errors.New("not a session record")
	}
	return sessionImage(q.File, q.Ref)
}

func (h *sessionsHandler) occupant(q *sessionCall) (any, error) {
	return h.e.occupancy(q.Session), nil
}

func (h *sessionsHandler) handoff(q *sessionCall) (any, error) {
	return h.e.handOff(q.Session), nil
}

func (h *sessionsHandler) deskOpen(q *sessionCall) (any, error) {
	// 窗口的工作目录照会话记录里的；还没有记录（新会话第一条消息之前）时是家目录
	cwd := h.e.tilde()
	if path := sessionFile(h.e, q.Session); path != "" {
		if s, ok, err := summarize(path); err == nil && ok {
			if dir, _ := s.cwd.(string); dir != "" {
				cwd = dir
			}
		}
	}
	window, err := deskOpen(h.e, q.Session, cwd)
	if err != nil {
		return nil, err
	}
	return obj{"window": window}, nil
}

func (h *sessionsHandler) deskClose(q *sessionCall) (any, error) {
	return obj{"closed": deskClose([]string{q.Session})}, nil
}
