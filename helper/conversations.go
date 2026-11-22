package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"os"
	"sync"

	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/core"
	"github.com/dzshzx/Zeta/helper/host"
	ctxusage "github.com/dzshzx/Zeta/helper/usage"
)

// conversations 把对话（helper/conv）挂到核心进程上：conv.* 请求交给对话表，对话的推送按客户端编号找到那条连接写出去。
type conversations struct {
	m *conv.Manager

	mu      sync.Mutex
	clients map[int]*core.Client
}

func newConversations(e *env) *conversations {
	h := &conversations{clients: map[int]*core.Client{}}
	// 学到的各模型上下文窗口：文件坏了也照用（当空的），原因记进日志
	windows, err := ctxusage.LoadWindows(e.home + "/.config/" + e.name + "/context-windows.json")
	if err != nil {
		log.Print(err)
	}
	h.m = conv.NewManager(conv.Deps{
		Start:         startHost(e),
		Attach:        attachHost(e),
		SessionFile:   func(session string) string { return sessionFile(e, session) },
		Subagent:      func(args ...string) ([]byte, error) { return subagentOutput(e, args) },
		History:       func(args ...string) ([]byte, error) { return historyOutput(e, args) },
		DefaultMode:   func() string { return defaultPermissionMode(e) },
		Windows:       windows,
		SessionTitles: func(cwd string) []string { return sessionTitles(e, cwd) },
		Desk: func(session, cwd string) {
			if err := cmdDeskOpen(e, newEmitter(io.Discard), session, cwd); err != nil {
				log.Print("开 tmux 窗口：", err)
			}
		},
	}, h.send)
	go adoptHosts(e, h.m)
	return h
}

// adoptHosts：核心起来时扫一遍托管进程（zeta-helper hosts，在进程里直接跑），在干活的接上，闲着的断开（见 conv.Manager.Adopt）。
func adoptHosts(e *env, m *conv.Manager) {
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := cmdHosts(e, o)
	o.flush()
	if err != nil {
		log.Print("扫托管进程：", err)
		return
	}
	var hosted []conv.Hosted
	for line := range bytes.Lines(buf.Bytes()) {
		var h conv.Hosted
		if json.Unmarshal(line, &h) != nil || h.Key == "" {
			continue
		}
		if h.Cwd == "" {
			h.Cwd = e.home
		}
		hosted = append(hosted, h)
	}
	m.Adopt(hosted, func(key string) { <-host.Release(key) })
}

func (h *conversations) Request(c *core.Client, r core.Request) (any, error) {
	id := int(c.ID())
	h.mu.Lock()
	h.clients[id] = c
	h.mu.Unlock()
	return h.m.Request(id, r.Method, r.Raw)
}

func (h *conversations) Gone(c *core.Client) {
	id := int(c.ID())
	h.mu.Lock()
	delete(h.clients, id)
	h.mu.Unlock()
	h.m.Gone(id)
}

func (h *conversations) Idle() bool { return h.m.Idle() }

func (h *conversations) send(client int, msg any) {
	h.mu.Lock()
	c := h.clients[client]
	h.mu.Unlock()
	if c != nil {
		_ = c.Send(msg)
	}
}

// sessionTitles 是这台主机上工作目录 cwd 里各会话的标题（和会话列表一样只看最新的 listLimit 个），分支起标题时避开重名。
func sessionTitles(e *env, cwd string) []string {
	files := e.sessionFiles()
	sortNewestFirst(files)
	var titles []string
	for _, path := range files[:min(len(files), listLimit)] {
		if s, ok, err := summarize(path); err == nil && ok && s.cwd == any(cwd) && s.title != "" {
			titles = append(titles, s.title)
		}
	}
	return titles
}

// historyOutput 是 zeta-helper history 的输出，在核心进程里直接跑，不另起进程。
func historyOutput(e *env, args []string) ([]byte, error) {
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := dispatchHistory(e, o, args)
	o.flush()
	return buf.Bytes(), err
}

// subagentOutput 是 zeta-helper subagent 的输出（子代理查看页增量读记录），同样在核心进程里直接跑。
func subagentOutput(e *env, args []string) ([]byte, error) {
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := dispatchSubagent(e, o, args)
	o.flush()
	return buf.Bytes(), err
}

// defaultPermissionMode 是这台主机上 Claude Code 设置里的默认权限模式（~/.claude/settings.json 的 permissions.defaultMode），没设时为空。
func defaultPermissionMode(e *env) string {
	data, err := os.ReadFile(e.claude + "/settings.json")
	if err != nil {
		return ""
	}
	var s struct {
		Permissions struct {
			DefaultMode string `json:"defaultMode"`
		} `json:"permissions"`
	}
	_ = json.Unmarshal(data, &s)
	return s.Permissions.DefaultMode
}

// Release：交班时断开全部托管进程（进程照跑），新核心起来后接回去（adoptHosts）。
func (h *conversations) Release() { h.m.Release() }

// sessionFile 是会话号 session 的会话记录：各项目目录里找，几份时取最新的；没有时为空。
func sessionFile(e *env, session string) string {
	if !sessionIDPattern.MatchString(session) {
		return ""
	}
	files := e.sessionFilesNamed(session)
	sortNewestFirst(files)
	if len(files) == 0 {
		return ""
	}
	return files[0]
}
