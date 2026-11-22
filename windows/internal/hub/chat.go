package hub

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/host"
)

// Chat 是聊天窗格里的对话：侧栏点开的会话，或新对话。界面只读它的字段；条目、状态从 Conv 的 Take 取。
type Chat struct {
	// Backend 是对话在哪台设备上。
	Backend string
	// Session 是点开的会话（新对话时只有 Cwd 和 Backend）。
	Session hostlink.Session
	// Conv 是打开成以后的对话；还在打开、没打开成时为 nil。
	Conv *chat.Conversation
	// Opening：正在打开（连设备、核心载入历史）。
	Opening bool
	// Problem 是没打开成的原因。
	Problem string
	// CwdProblem：新对话的工作目录在设备上进不去，起不了 Claude Code；给人看的原因。
	CwdProblem string
	// Handoff 是会话正在别处运行时「接管」的状态（handoff.go）。
	Handoff Handoff
}

// Fresh：新对话（不是接着已有的会话打开的）。
func (c *Chat) Fresh() bool { return c.Session.ID == "" }

// chatConn 是一台设备上给对话用的 serve 连接，和它上面的对话协议层。
type chatConn struct {
	link   host.ChatLink
	client *chat.Client
	// problem 是连接现在的问题（断了、连不上），空是连着；ever：连上过。只在主线程上读写。
	problem string
	ever    bool
	// 这条连接上最近一次搜索（sessions.search）：searchName 是给它起的名字，推来的 sessions.match 里 search 等于它的交给 searchHit；
	// 搜完了 searchHit 为 nil（search.go）。
	searchMu   sync.Mutex
	searchName int64
	searchHit  func(session, snippet string)
}

// Call 把请求交给连接：Client 建在连接之前，经它转一手。
func (c *chatConn) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	return c.link.Call(ctx, method, params)
}

// Chat 是聊天窗格里的对话；没有时是 nil。
func (h *Hub) Chat() *Chat { return h.chat }

// ChatLinkProblem 是对话所在设备的连接现在的问题（断了、正在重连），连着时是空。
func (h *Hub) ChatLinkProblem() string {
	if h.chat == nil {
		return ""
	}
	if c := h.conns[h.chat.Backend]; c != nil {
		return c.problem
	}
	return ""
}

// OpenSession 在聊天窗格里打开会话 s，关掉原来的。已经开着它时什么也不做。
func (h *Hub) OpenSession(s hostlink.Session) {
	h.seen(s)
	if h.showing(s.Backend, s.ID) && s.ID != "" {
		return
	}
	h.open(&Chat{Backend: s.Backend, Session: s},
		chat.Spec{Session: s.ID, File: s.File, Cwd: s.Cwd, Title: s.Title, Options: h.options(s.Backend)}, false)
}

// NewChat 在侧栏正看着的设备上开一个新对话，工作目录是它的默认工作目录；先在设备上看进不进得去。
func (h *Hub) NewChat() {
	x, ok := h.Host(h.Backend())
	if !ok {
		return
	}
	cwd := x.DefaultCwd
	if cwd == "" {
		cwd = x.Home
	}
	h.open(&Chat{Backend: x.ID, Session: hostlink.Session{Cwd: cwd, Backend: x.ID}},
		chat.Spec{Cwd: cwd, Options: h.options(x.ID)}, true)
}

// open 换成对话 c：关掉原来的，在后台连设备、打开，结果回主线程填进 c。checkCwd 时先看工作目录进不进得去。
func (h *Hub) open(c *Chat, spec chat.Spec, checkCwd bool) {
	h.CloseChat()
	x, ok := h.Host(c.Backend)
	if !ok {
		return
	}
	c.Opening = true
	h.chat = c
	conn := h.chatConn(c.Backend)
	h.run(func() {
		var cwdProblem string
		if checkCwd {
			// 查不成（连不上等）时不拦：打开时会报连不上
			cwdProblem, _ = h.devices.CheckDir(x, spec.Cwd)
		}
		ctx, cancel := context.WithTimeout(context.Background(), chat.OpenTimeout)
		defer cancel()
		conv, err := conn.client.Open(ctx, spec)
		h.post(func() {
			if h.chat != c {
				h.closeConv(conv) // 打开的时候已经换走了
				return
			}
			c.Opening, c.Conv, c.CwdProblem = false, conv, cwdProblem
			if err != nil {
				c.Problem = "打不开这个对话：" + err.Error()
				h.dropDeadConn(c.Backend, conn)
			}
		})
	})
}

// CloseChat 关掉聊天窗格里的对话（conv.close：别的客户端没拿着、又闲着时核心会断开进程）。
func (h *Hub) CloseChat() {
	if h.chat == nil {
		return
	}
	h.closeConv(h.chat.Conv)
	h.chat = nil
}

// closeTimeout 是关掉一个对话最多等多久。
const closeTimeout = 10 * time.Second

func (h *Hub) closeConv(conv *chat.Conversation) {
	if conv == nil {
		return
	}
	h.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), closeTimeout)
		defer cancel()
		if err := conv.Close(ctx); err != nil {
			log.Printf("zeta: 关掉对话：%v", err)
		}
	})
}

// chatConn 是设备 id 上给对话用的连接，没有就起一条。推送、招呼和连接的变化在读的 goroutine 上来：推送交给协议层，招呼经 post 交给 greeted，
// 连上时经 run 在后台让打开过的对话重新打开（Call 的应答要靠读的那个 goroutine 送回来，不能就地等），
// 界面要重画的经 post 回主线程。
func (h *Hub) chatConn(id string) *chatConn {
	if c := h.conns[id]; c != nil {
		return c
	}
	x, _ := h.Host(id)
	c := &chatConn{}
	c.client = chat.NewClient(c, func() { h.post(func() {}) })
	c.link = h.devices.OpenChat(x,
		c.received,
		func(hello hostlink.Hello) {
			h.post(func() {
				if h.conns[id] == c {
					h.greeted(id, hello)
				}
			})
		},
		func(why string) {
			if why == "" {
				h.run(func() {
					ctx, cancel := context.WithTimeout(context.Background(), chat.OpenTimeout)
					defer cancel()
					if err := c.client.Reconnected(ctx); err != nil {
						log.Printf("zeta: 重连后重新打开对话：%v", err)
					}
				})
			} else {
				c.client.Disconnected()
			}
			h.post(func() {
				c.problem = why
				c.ever = c.ever || why == ""
			})
		})
	h.conns[id] = c
	return c
}

// dropDeadConn：一次都没连上的连接已经停了（见 hostlink.Serve），丢掉，下次打开时重新连。
func (h *Hub) dropDeadConn(id string, c *chatConn) {
	if h.conns[id] == c && !c.ever {
		h.closeConn(id)
	}
}

// closeConn 关掉设备 id 上给对话用的连接。
func (h *Hub) closeConn(id string) {
	if c := h.conns[id]; c != nil {
		delete(h.conns, id)
		_ = c.link.Close()
	}
}

// imageTimeout 是取一张图最多等多久（图可能有几十 MB）。
const imageTimeout = 2 * time.Minute

// ImageLoader 是聊天列表取会话记录里图片的办法（sessions.image，走取大块数据的连接，见 bulk.go；在后台 goroutine 上调用）。
func (h *Hub) ImageLoader(backend string) func(file, ref string) ([]byte, error) {
	call := h.bulkCall(backend)
	return func(file, ref string) ([]byte, error) {
		ctx, cancel := context.WithTimeout(context.Background(), imageTimeout)
		defer cancel()
		raw, err := call(ctx, "sessions.image", map[string]any{"file": file, "ref": ref})
		if err != nil {
			return nil, err
		}
		var im struct {
			Data []byte `json:"data"` // base64
		}
		if err := json.Unmarshal(raw, &im); err != nil {
			return nil, err
		}
		if len(im.Data) == 0 {
			return nil, errors.New("没取到图片")
		}
		return im.Data, nil
	}
}

// Uploader 是往设备 backend 上传附件的办法（在后台 goroutine 上调用，见 Devices.Upload）。
func (h *Hub) Uploader(backend string) func(ctx context.Context, u host.Upload, progress func(int64)) (string, error) {
	x, _ := h.Host(backend)
	devices := h.devices
	return func(ctx context.Context, u host.Upload, progress func(int64)) (string, error) {
		return devices.Upload(ctx, x, u, progress)
	}
}

// PathChecker 是查设备 backend 上路径的办法（在后台 goroutine 上调用）。
func (h *Hub) PathChecker(backend string) func(path string) (hostlink.PathKind, error) {
	x, _ := h.Host(backend)
	devices := h.devices
	return func(path string) (hostlink.PathKind, error) { return devices.CheckPath(x, path) }
}

// RunChat 在后台对聊天窗格里的对话做一件事（发消息、停止、回应确认……），出错时把原因交回主线程的 failed。
// 对话已经换走时什么也不做。
func (h *Hub) RunChat(do func(ctx context.Context, conv *chat.Conversation) error, failed func(err error)) {
	h.RunChatThen(func(ctx context.Context, conv *chat.Conversation) func() {
		if err := do(ctx, conv); err != nil && failed != nil {
			return func() { failed(err) }
		}
		return nil
	})
}

// RunChatThen 在后台对聊天窗格里的对话做一件事，do 返回的函数（可以是 nil）交回主线程跑；那时对话已经换走了就不跑，
// 结果不会落到别的对话上。对话已经换走时什么也不做。
func (h *Hub) RunChatThen(do func(ctx context.Context, conv *chat.Conversation) func()) {
	c := h.chat
	if c == nil || c.Conv == nil {
		return
	}
	conv := c.Conv
	h.background(chat.OpenTimeout, func(ctx context.Context) func() {
		apply := do(ctx, conv)
		if apply == nil {
			return nil
		}
		return func() {
			if h.chat == c && c.Conv == conv {
				apply()
			}
		}
	})
}

// reopen 在新的连接上重新打开对话 c（设备的连法改了）：接着它已经有的会话号。
func (h *Hub) reopen(c *Chat) {
	s := c.Session
	if c.Conv != nil {
		s.ID = c.Conv.Session()
	}
	h.chat = nil // 原来的连接已经关了，不再发 conv.close
	if s.ID == "" {
		h.open(&Chat{Backend: c.Backend, Session: s}, chat.Spec{Cwd: s.Cwd, Options: h.options(c.Backend)}, true)
		return
	}
	h.open(&Chat{Backend: c.Backend, Session: s}, chat.Spec{Session: s.ID, File: s.File, Cwd: s.Cwd, Title: s.Title, Options: h.options(c.Backend)}, false)
}
