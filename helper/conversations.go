package main

import (
	"bytes"
	"encoding/json"
	"log"
	"os"
	"sync"

	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/core"
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
		Start:       startHost(e),
		History:     func(args ...string) ([]byte, error) { return historyOutput(e, args) },
		DefaultMode: func() string { return defaultPermissionMode(e) },
		Windows:     windows,
	}, h.send)
	return h
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

// historyOutput 是 zeta-helper history 的输出，在核心进程里直接跑，不另起进程。
func historyOutput(e *env, args []string) ([]byte, error) {
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := dispatchHistory(e, o, args)
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
