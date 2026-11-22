package hub

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"strconv"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// Remote 是设置里一台设备上经它的 serve 做的几样：Claude Code 配置（config.*）、账号与额度（accounts.*、quota.*）。
// 请求走这台给对话用的那条 serve 连接（chatConn），不另开。账号和额度的状态留在这里，换页回来还在。
type Remote struct {
	Config   *claudecfg.Client
	Accounts *accounts.Service
	Quota    *accounts.Quota

	conn *chatConn
}

// Remote 是设备 id 的那几样；没有这台时是 nil。连接换过（改了连法、第一次就没连上被丢掉）时按新的连接重建。主线程上调用。
func (h *Hub) Remote(id string) *Remote {
	x, ok := h.Host(id)
	if !ok {
		return nil
	}
	if r := h.remotes[id]; r != nil && h.conns[id] == r.conn {
		return r
	}
	conn := h.chatConn(id)
	caller := &remoteCaller{conn: conn, dead: func() { h.post(func() { h.dropDeadConn(id, conn) }) }}
	q := accounts.NewQuota(caller, usageSource{conn}, h.post, nil)
	r := &Remote{
		Config:   claudecfg.New(caller, x.DefaultCwd),
		Accounts: accounts.NewService(caller, h.devices.ClaudeSwitch(x), deviceConvs{conn, h.run}, q, h.post),
		Quota:    q,
		conn:     conn,
	}
	h.remotes[id] = r
	return r
}

// remoteCaller 把设置的请求交给对话连接（claudecfg.Caller、accounts.Caller）。不是 serve 答的出错（连不上）时，
// 一次都没连上的连接丢掉，下次打开设置页时重新连。
type remoteCaller struct {
	conn *chatConn
	dead func()
}

func (c *remoteCaller) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	m, err := asMap(params)
	if err != nil {
		return nil, err
	}
	raw, err := c.conn.Call(ctx, method, m)
	var reply *hostlink.ReplyError
	if err != nil && !errors.As(err, &reply) {
		c.dead()
	}
	return raw, err
}

// asMap 把请求的参数换成连接要的 map（claudecfg、accounts 给的本来就是）。
func asMap(params any) (map[string]any, error) {
	switch p := params.(type) {
	case nil:
		return map[string]any{}, nil
	case map[string]any:
		return p, nil
	}
	data, err := json.Marshal(params)
	if err != nil {
		return nil, err
	}
	var m map[string]any
	return m, json.Unmarshal(data, &m)
}

// usageSource 借这台上本客户端开着进程的对话问额度（conv.quota）；都没开着进程时是 accounts.ErrNoProcess。
type usageSource struct{ conn *chatConn }

func (s usageSource) Usage(ctx context.Context) (json.RawMessage, error) {
	for _, conv := range s.conn.client.Conversations() {
		if conv.State().Process {
			return conv.Quota(ctx)
		}
	}
	return nil, accounts.ErrNoProcess
}

// deviceConvs 是本客户端在这台上的对话（accounts.Conversations）：换号、记下账号前看忙不忙，闲着的进程收掉。
type deviceConvs struct {
	conn *chatConn
	run  func(func())
}

func (d deviceConvs) Busy() bool {
	for _, conv := range d.conn.client.Conversations() {
		st := conv.State()
		if st.Busy || len(st.Prompts) > 0 || st.RunningTasks() > 0 {
			return true
		}
	}
	return false
}

func (d deviceConvs) CloseIdle() bool {
	if d.Busy() {
		return false
	}
	for _, conv := range d.conn.client.Conversations() {
		if !conv.State().Process {
			continue
		}
		d.run(func() {
			ctx, cancel := context.WithTimeout(context.Background(), closeTimeout)
			defer cancel()
			if err := conv.End(ctx); err != nil {
				log.Printf("zeta: 换号前结束对话进程：%v", err)
			}
		})
	}
	return true
}

// options 是在设备 backend 上起 Claude Code 进程用的设置：那台的自动标题、自动压缩和压缩时机（设置里各台各设各的，
// 环境变量的写法同 Kotlin 版的 AutoCompact.env）。
func (h *Hub) options(backend string) chat.Options {
	d := claudecfg.DeviceSettings(h.settings.Store(), backend)
	o := chat.Options{AutoTitle: d.AutoTitle()}
	switch p := d.CompactPercent(); {
	case !d.AutoCompact():
		o.Env = map[string]string{"DISABLE_AUTO_COMPACT": "1"}
	case p > 0:
		o.Env = map[string]string{"CLAUDE_AUTOCOMPACT_PCT_OVERRIDE": strconv.Itoa(p)}
		o.CompactPercent = p
	}
	return o
}

// ApplyOptions 把设备 backend 改过的自动标题、自动压缩交给开着的对话（conv.options），下次起进程时生效。离开设备页时调。
func (h *Hub) ApplyOptions(backend string) {
	c := h.chat
	if c == nil || c.Backend != backend {
		return
	}
	o := h.options(backend)
	h.RunChat(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetOptions(ctx, o) }, func(err error) {
		log.Printf("zeta: 对话的设置没改成：%v", err)
	})
}

// Settings 是设置（外观、更新这些不归中枢管的项，界面直接读写）。
func (h *Hub) Settings() *settings.Settings { return h.settings }
