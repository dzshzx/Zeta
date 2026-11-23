package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

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

func newConversations(e *env, quota *quotaWatch) *conversations {
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
		DefaultMode:   (&defaultModeCache{path: e.claude + "/settings.json"}).get,
		Windows:       windows,
		SessionTitles: func(cwd string) []string { return sessionTitles(e, cwd) },
		Occupant:      e.occupancy,
		Handoff:       e.handOff,
		Quota:         newQuotaSink(e, quota.changed),
		Desk: func(session, cwd string) {
			if _, err := deskOpen(e, session, cwd); err != nil {
				log.Print("开 tmux 窗口：", err)
			}
		},
		KeepMode: func(key, mode string) error {
			err := e.keepHostMode(key, mode)
			if err != nil && !errors.Is(err, fs.ErrNotExist) {
				log.Print("记下用户的权限模式：", err)
			}
			return err
		},
		ClientDevice: h.device,
		ClientLang:   h.lang,
		KeepDevice:   e.keepHostClient,
		Rename:       renameSession,
		SessionImage: func(file, ref string) (conv.Image, error) { return convImage(e, file, ref) },
		Commands:     conv.NewCommandStore(commandsFile(e)),
	}, h.send)
	go adoptHosts(e, h.m)
	return h
}

// adoptHosts：核心起来时扫一遍托管进程，在干活的接上，闲着的留给看门狗（见 conv.Manager.Adopt）。
func adoptHosts(e *env, m *conv.Manager) {
	hosted, err := hostedProcesses(e)
	if err != nil {
		log.Print("扫托管进程：", err)
		return
	}
	m.Adopt(hosted)
}

// hostedProcesses 是本包的托管进程（zeta-helper hosts，在进程里直接跑）。
func hostedProcesses(e *env) ([]conv.Hosted, error) {
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := cmdHosts(e, o)
	o.flush()
	if err != nil {
		return nil, err
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
	return hosted, nil
}

// endIdleHosted 结束本包闲着的托管进程（不在回答、不在等确认、没有后台任务，同 conv.Hosted.Active），在忙的不动；
// 返回时结束的命令都已发出，进程各自退出（见 host.Stop）。闲着的进程平常留给看门狗按闲置超时收（docs/core.md「主机」），
// 这几处等不了：换账号、登录前（claude-switch 有 Claude Code 开着就不换，留着的进程以后被接回时还用着旧的登录），
// 换核心看新挂载时（留着的进程看不到新挂上的共享文件夹，以后被接回也看不到）。
func endIdleHosted(e *env) {
	hosted, err := hostedProcesses(e)
	if err != nil {
		log.Print("扫托管进程：", err)
		return
	}
	var stopping []<-chan struct{}
	for _, h := range hosted {
		if !h.Active() {
			stopping = append(stopping, host.Stop(h.Key))
		}
	}
	for _, done := range stopping {
		<-done
	}
}

func (h *conversations) Request(c *core.Client, r core.Request) (any, error) {
	id := int(c.ID())
	h.mu.Lock()
	h.clients[id] = c
	h.mu.Unlock()
	v, err := h.m.Start(id, r.Method, r.Raw)
	if p, ok := v.(conv.Pending); ok && err == nil {
		// 要等的在别的 goroutine 里等，这条连接接着处理后面的请求
		go func() {
			v, err := p()
			c.Reply(r.ID, v, err)
		}()
		return core.Later, nil
	}
	return v, err
}

// lang 是客户端 client 界面的语言；连接已经走了时为空（源语言）。
func (h *conversations) lang(client int) string {
	h.mu.Lock()
	c := h.clients[client]
	h.mu.Unlock()
	if c == nil {
		return ""
	}
	return c.Lang()
}

// device 是客户端 client 是哪台设备（clientDevice）；连接已经走了时不知道。
func (h *conversations) device(client int) conv.Device {
	h.mu.Lock()
	c := h.clients[client]
	h.mu.Unlock()
	if c == nil {
		return conv.Device{}
	}
	return clientDevice(c)
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

// defaultModeCache 读这台主机上 Claude Code 设置里的默认权限模式（~/.claude/settings.json 的 permissions.defaultMode），没设时为空。
// 每次推 conv.state 都要它（mode.shown），所以记着上次读到的，文件的大小和修改时刻没变就不重读。
type defaultModeCache struct {
	path string

	mu   sync.Mutex
	read bool
	size int64
	mod  time.Time
	mode string
}

func (c *defaultModeCache) get() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	fi, err := os.Stat(c.path)
	if err != nil {
		c.read = false
		return ""
	}
	if c.read && fi.Size() == c.size && fi.ModTime().Equal(c.mod) {
		return c.mode
	}
	c.read, c.size, c.mod, c.mode = true, fi.Size(), fi.ModTime(), ""
	data, err := os.ReadFile(c.path)
	if err != nil {
		c.read = false
		return ""
	}
	var s struct {
		Permissions struct {
			DefaultMode string `json:"defaultMode"`
		} `json:"permissions"`
	}
	_ = json.Unmarshal(data, &s)
	c.mode = s.Permissions.DefaultMode
	return c.mode
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

// convImage 取回会话记录 file 里位置 ref 的一张图（同 sessions.image），重新生成时原样重发用。
func convImage(e *env, file, ref string) (conv.Image, error) {
	if !e.insideRoot(file) {
		return conv.Image{}, errors.New("not a session record")
	}
	img, err := sessionImage(file, ref)
	if err != nil {
		return conv.Image{}, err
	}
	mediaType, _ := img.MediaType.(string)
	data, _ := img.Data.(string)
	if mediaType == "" || data == "" {
		return conv.Image{}, errors.New("not an image")
	}
	return conv.Image{MediaType: mediaType, Data: data}, nil
}

// commandsFile 是核心记各工作目录斜杠命令的文件（conv.CommandStore），在核心的目录里，丢了只是少一份提示。
func commandsFile(e *env) string {
	p, err := core.Locate(e.home, e.name)
	if err != nil {
		return e.home + "/.cache/" + e.name + "/core/commands.json"
	}
	return filepath.Join(filepath.Dir(p.Socket), "commands.json")
}
