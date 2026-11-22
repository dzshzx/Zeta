package main

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/core"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 核心的 sessions.* 方法里订阅（sessions.watch、sessions.unwatch，见 corehandlers.go）以外的：侧栏对会话的操作，
// 几个客户端读写同一份，逻辑和同名的 zeta-helper 子命令是同一份。参数、结果、错误代号见 docs/core.md「会话」。

// sessionOps 做 sessions.search 以外的会话操作。挂在 HandleAsync 上：各在自己的 goroutine 里做、做完再答，
// 不挡同一个客户端后面的请求（要读写会话记录、关 tmux 窗口、等别处的进程退出）。
// 改名、接管、删除先看核心里开着这个会话没有（convs），开着就走对话自己的那条路。
// 改名、归档、置顶、删除写完叫会话列表的订阅（lists）马上推这一项。
type sessionOps struct {
	e     *env
	convs *conv.Manager
	lists *sessionsHandler
}

// sessionParams 是 sessions.* 请求的参数：和 id、method 平级。
type sessionParams struct {
	Session  string   `json:"session"`
	Title    string   `json:"title"`
	Archived bool     `json:"archived"`
	Pinned   bool     `json:"pinned"`
	Sessions []string `json:"sessions"`
	File     string   `json:"file"`
	Ref      string   `json:"ref"`
	// UTCOffset 是客户端所在时区比 UTC 快多少分钟（sessions.export 起文件名用）；没带时按主机的本地时间
	UTCOffset *int `json:"utc_offset"`
}

// sessionMethod 是一个方法怎么做；session 为真的要带样子对的会话号，push 为真的做成以后马上把这个会话推给订阅的客户端。
type sessionMethod struct {
	run     func(sessionOps, sessionParams) (any, error)
	session bool
	push    bool
}

var sessionMethods = map[string]sessionMethod{
	"sessions.rename":     {run: sessionOps.rename, session: true, push: true},
	"sessions.occupant":   {run: sessionOps.occupant, session: true},
	"sessions.archive":    {run: sessionOps.archive, session: true, push: true},
	"sessions.pin":        {run: sessionOps.pin, session: true, push: true},
	"sessions.delete":     {run: sessionOps.deleteSession, session: true, push: true},
	"sessions.export":     {run: sessionOps.export},
	"sessions.handoff":    {run: sessionOps.handoff, session: true},
	"sessions.desk_open":  {run: sessionOps.deskOpen, session: true},
	"sessions.desk_close": {run: sessionOps.deskClose, session: true},
	"sessions.lookup":     {run: sessionOps.lookup},
	"sessions.image":      {run: sessionOps.image},
}

// errNoSession：这个会话号没有会话记录（或给的记录已经不在）。
var errNoSession = codedError{code: sessionproto.CodeNoSession, err: errors.New("这个会话的记录已经不在了")}

// errActive：sessions.delete 要删的会话正在进行（activeSession），没删。
var errActive = codedError{code: sessionproto.CodeActive, err: errors.New("对话正在进行，结束后再删")}

// errBadSession：会话号的样子不对。
var errBadSession = errors.New("bad session id")

func (h sessionOps) Request(_ *core.Client, r core.Request) (any, error) {
	m, ok := sessionMethods[r.Method]
	if !ok {
		return nil, errors.New("unknown method: " + r.Method)
	}
	var p sessionParams
	if err := json.Unmarshal(r.Raw, &p); err != nil {
		return nil, errors.New("bad params: " + err.Error())
	}
	if m.session && !isSessionID(p.Session) {
		return nil, errBadSession
	}
	result, err := m.run(h, p)
	if err == nil && m.push {
		h.lists.push(p.Session)
	}
	return result, err
}

func (sessionOps) Gone(*core.Client) {}
func (sessionOps) Idle() bool        { return true }

// sessionSearches 做 sessions.search：边找边推 sessions.match，找完答汇总（searchDone）。按客户端记着正在跑的搜索：
// 同一个客户端发来新的就取消它上一个，客户端断开时也取消。
//
// 登记要在请求的顺序里做（Request 里），找在自己的 goroutine 里：放进 HandleAsync 起的 goroutine 的话，
// 先后不定，新的搜索可能反被旧的取消。
type sessionSearches struct {
	e *env

	mu      sync.Mutex
	running map[*core.Client]*runningSearch
}

type runningSearch struct{ cancel context.CancelFunc }

func newSessionSearches(e *env) *sessionSearches {
	return &sessionSearches{e: e, running: map[*core.Client]*runningSearch{}}
}

func (s *sessionSearches) Request(c *core.Client, r core.Request) (any, error) {
	var p struct {
		Query string `json:"query"`
		// Search 是客户端给这次搜索起的名字，推送的 sessions.match 原样带回
		Search json.RawMessage `json:"search"`
	}
	if err := json.Unmarshal(r.Raw, &p); err != nil {
		return nil, errors.New("bad params: " + err.Error())
	}
	name := searchName(p.Search, r.ID)
	ctx, run := s.start(c)
	go func() {
		done, err := searchSessions(ctx, s.e, p.Query, func(m searchMatch) {
			_ = c.Send(obj{"type": "sessions.match", "search": name, "id": m.ID, "role": m.Role, "snippet": m.Snippet})
		})
		s.finish(c, run)
		if err != nil {
			c.Reply(r.ID, nil, err)
			return
		}
		c.Reply(r.ID, done, nil)
	}()
	return core.Later, nil
}

// searchName 是推送的 sessions.match 带的 search：客户端起的名字 named；没给时是请求的 id。
//
// 没给名字时用 id 是给还没改的客户端留的退路：各客户端都带 search 以后删掉它，连同 docs/core.md「会话」里那一句。
func searchName(named, id json.RawMessage) json.RawMessage {
	if len(named) == 0 || string(named) == "null" {
		return id
	}
	return named
}

// start 取消客户端 c 上一个还在跑的搜索，登记新的这个。
func (s *sessionSearches) start(c *core.Client) (context.Context, *runningSearch) {
	ctx, cancel := context.WithCancel(context.Background())
	run := &runningSearch{cancel}
	s.mu.Lock()
	previous := s.running[c]
	s.running[c] = run
	s.mu.Unlock()
	if previous != nil {
		previous.cancel()
	}
	return ctx, run
}

// finish：搜索 run 做完了，还登记着的话去掉。
func (s *sessionSearches) finish(c *core.Client, run *runningSearch) {
	s.mu.Lock()
	if s.running[c] == run {
		delete(s.running, c)
	}
	s.mu.Unlock()
	run.cancel()
}

func (s *sessionSearches) Gone(c *core.Client) {
	s.mu.Lock()
	run := s.running[c]
	delete(s.running, c)
	s.mu.Unlock()
	if run != nil {
		run.cancel()
	}
}

func (*sessionSearches) Idle() bool { return true }

// record 是会话的记录文件（同一个会话号在几个项目目录里都有时是最新的那份）；没有时是 errNoSession。
func (h sessionOps) record(session string) (string, error) {
	if path := sessionFile(h.e, session); path != "" {
		return path, nil
	}
	return "", errNoSession
}

func (h sessionOps) rename(q sessionParams) (any, error) {
	path, err := h.record(q.Session)
	if err != nil {
		return nil, err
	}
	// 开着时对话的标题跟着换，开着的 Claude Code 也改名，不然它之后会把旧名字写回去
	if title, open, err := h.convs.RenameSession(q.Session, q.Title); open {
		if err != nil {
			return nil, err
		}
		return obj{"title": title}, nil
	}
	title, err := renameSession(path, q.Title)
	if err != nil {
		return nil, err
	}
	return obj{"title": title}, nil
}

func (h sessionOps) archive(q sessionParams) (any, error) {
	if err := archiveSessions(h.e, []string{q.Session}, q.Archived); err != nil {
		return nil, err
	}
	if q.Archived {
		deskClose([]string{q.Session})
	}
	return obj{"archived": q.Archived}, nil
}

func (h sessionOps) pin(q sessionParams) (any, error) {
	if err := pinSessions(h.e, []string{q.Session}, q.Pinned); err != nil {
		return nil, err
	}
	return obj{"pinned": q.Pinned}, nil
}

func (h sessionOps) deleteSession(q sessionParams) (any, error) {
	if h.activeSession(q.Session) {
		return nil, errActive
	}
	// 开着的进程先结束、等它退出：不然 Claude Code 还会把删掉的记录写出来
	if h.convs.EndSession(q.Session) {
		h.e.waitOwnHostGone(q.Session, releaseGrace+6*time.Second)
	}
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

// activeSession：会话 session 正在进行，sessions.delete 不删它。正在进行指：核心里开着它的对话在回答、等确认或有后台任务；
// Zeta 之外的进程占着它（列表里的 external）；本包托管、核心没接着的进程登记着它，正忙（busy、shell）或在等确认。
func (h sessionOps) activeSession(session string) bool {
	if state := h.convs.SessionActivity(session).State; state != "" && state != "idle" {
		return true
	}
	for _, r := range h.e.registeredSessions() {
		if r.session == session && (!h.e.ownHost(r) || busyStatus(r.status)) {
			return true
		}
	}
	return false
}

// busyStatus：Claude Code 在 ~/.claude/sessions 登记的这个忙闲算正在进行。
func busyStatus(status string) bool {
	return status == sessionproto.StatusBusy || status == sessionproto.StatusShell || status == sessionproto.StatusWaiting
}

func (h sessionOps) export(q sessionParams) (any, error) {
	at := time.Now()
	if q.UTCOffset != nil {
		at = sessionproto.AtOffset(at, *q.UTCOffset)
	}
	if len(q.Sessions) > 0 {
		if !allOf(q.Sessions, isSessionID) {
			return nil, errBadSession
		}
		return h.exportMany(q.Sessions, at)
	}
	if !isSessionID(q.Session) {
		return nil, errBadSession
	}
	ex, err := h.exportOne(q.Session)
	if err != nil {
		return nil, err
	}
	return obj{"title": ex.Title, "markdown": ex.Markdown, "file_name": sessionproto.ExportFileName(ex.Title, at)}, nil
}

// exportOne 导出一个会话；记录不在时是 errNoSession。
func (h sessionOps) exportOne(session string) (sessionproto.ExportPart, error) {
	path, err := h.record(session)
	if err != nil {
		return sessionproto.ExportPart{}, err
	}
	markdown, title, err := exportSession(path)
	if err != nil {
		if isNotExist(err) {
			return sessionproto.ExportPart{}, errNoSession
		}
		return sessionproto.ExportPart{}, err
	}
	return sessionproto.ExportPart{Session: session, Title: title, Markdown: markdown}, nil
}

// exportMany 把这几个会话（同一台主机上的）按给的先后导出、合成一份（sessionproto.CombineExports），同一个会话号只导一次，
// 导不出来的跳过。各自的导出另放在 parts 里：客户端合成几台设备的时按用户选的先后重排。
// 一个都没导出成时出错：都是因为记录不在时是 errNoSession，否则是别的原因里的第一个。
func (h sessionOps) exportMany(sessions []string, at time.Time) (any, error) {
	var parts []sessionproto.ExportPart
	done, failed := []string{}, []string{}
	var problem error
	seen := map[string]bool{}
	for _, id := range sessions {
		if seen[id] {
			continue
		}
		seen[id] = true
		ex, err := h.exportOne(id)
		if err != nil {
			failed = append(failed, id)
			if problem == nil && !errors.Is(err, errNoSession) {
				problem = err
			}
			continue
		}
		parts = append(parts, ex)
		done = append(done, id)
	}
	if len(parts) == 0 {
		if problem != nil {
			return nil, problem
		}
		return nil, errNoSession
	}
	title, markdown := sessionproto.CombineExports(parts)
	return obj{"title": title, "markdown": markdown, "file_name": sessionproto.ExportFileName(title, at),
		"exported": done, "failed": failed, "parts": parts}, nil
}

func (h sessionOps) lookup(q sessionParams) (any, error) {
	if !allOf(q.Sessions, isSessionID) {
		return nil, errBadSession
	}
	return lookupSessions(h.e, q.Sessions), nil
}

func (h sessionOps) image(q sessionParams) (any, error) {
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

func (h sessionOps) occupant(q sessionParams) (any, error) {
	return h.e.occupancy(q.Session), nil
}

func (h sessionOps) handoff(q sessionParams) (any, error) {
	return h.convs.HandOffSession(q.Session)
}

func (h sessionOps) deskOpen(q sessionParams) (any, error) {
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

func (h sessionOps) deskClose(q sessionParams) (any, error) {
	return obj{"closed": deskClose([]string{q.Session})}, nil
}
