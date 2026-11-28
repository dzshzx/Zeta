package hub

import (
	"context"
	"encoding/json"
	"log"
	"strconv"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub/accounts"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/claudecfg"
	"github.com/dzshzx/Zeta/hub/settings"
)

// Remote 是设置里一台设备上经它的 serve 做的几样：核心的请求（Claude Code / Antigravity 配置 config.*、闲置超时 system.* 等）、
// 账号与额度（accounts.*、quota.*）。请求走这台给对话用的那条 serve 连接（chatConn），不另开。
type Remote struct {
	Core        *coreapi.Client
	Accounts    *accounts.Service
	Quota       *accounts.Quota
	AgyCore     *coreapi.Client
	AgyAccounts *accounts.Service
	AgyQuota    *accounts.Quota

	conn *chatConn
}

// CoreFor 返回这台设备上对应 harness（"claude" 或 "agy"）的 coreapi.Client。
func (r *Remote) CoreFor(harness string) *coreapi.Client {
	if sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy {
		return r.AgyCore
	}
	return r.Core
}

// AccountsFor 返回这台设备上对应 harness 的账号服务。
func (r *Remote) AccountsFor(harness string) *accounts.Service {
	if sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy {
		return r.AgyAccounts
	}
	return r.Accounts
}

// QuotaFor 返回这台设备上对应 harness 的额度状态。
func (r *Remote) QuotaFor(harness string) *accounts.Quota {
	if sessionproto.NormalizeHarness(harness) == sessionproto.HarnessAgy {
		return r.AgyQuota
	}
	return r.Quota
}

// Remote 是设备 id 的那几样；没有这台、或它的「连接」关着时是 nil。连接换过（改了连法、第一次就没连上被丢掉）时按新的连接重建。主线程上调用。
func (h *Hub) Remote(id string) *Remote {
	x, ok := h.Host(id)
	if !ok || x.Off {
		return nil
	}
	if r := h.remotes[id]; r != nil && h.conns[id] == r.conn {
		return r
	}
	conn := h.chatConn(id)
	caller := &remoteCaller{conn: conn, dead: func() { h.post(func() { h.dropDeadConn(id, conn) }) }}
	core := coreapi.New(caller)
	agyCore := core.WithHarness(sessionproto.HarnessAgy)
	loop := accounts.Loop{Run: h.run, Post: h.post}
	q := accounts.NewQuota(core, usageSource{conn: conn, harness: sessionproto.HarnessClaude}, loop, nil)
	aq := accounts.NewQuota(agyCore, usageSource{conn: conn, harness: sessionproto.HarnessAgy}, loop, nil)
	r := &Remote{
		Core:        core,
		Accounts:    accounts.NewService(core, h.devices.ClaudeSwitch(x), deviceConvs{conn, h.run, sessionproto.HarnessClaude}, q, loop),
		Quota:       q,
		AgyCore:     agyCore,
		AgyAccounts: accounts.NewAgyService(agyCore, deviceConvs{conn, h.run, sessionproto.HarnessAgy}, aq, loop),
		AgyQuota:    aq,
		conn:        conn,
	}
	h.remotes[id] = r
	return r
}

// remoteCaller 把设置的请求交给对话连接（coreapi.Caller）。不是 serve 答的出错（连不上）时，
// 一次都没连上的连接丢掉，下次打开设置页时重新连。
type remoteCaller struct {
	conn *chatConn
	dead func()
}

func (c *remoteCaller) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	raw, err := c.conn.Call(ctx, method, params)
	if _, replied := hostlink.ReplyCode(err); err != nil && !replied {
		c.dead()
	}
	return raw, err
}

// usageSource 借这台上本客户端开着进程的对话问额度（conv.quota）；agy 无活跃进程时交由核心的 quota.usage 直接查。
type usageSource struct {
	conn    *chatConn
	harness string
}

func (s usageSource) Usage(ctx context.Context) (json.RawMessage, error) {
	target := sessionproto.NormalizeHarness(s.harness)
	for _, conv := range s.conn.client.Conversations() {
		st := conv.State()
		if st.Process && sessionproto.NormalizeHarness(st.Harness) == target {
			return conv.Quota(ctx)
		}
	}
	if target == sessionproto.HarnessAgy {
		return nil, nil
	}
	return nil, accounts.ErrNoProcess
}

// deviceConvs 是本客户端在这台上对应 harness 的对话（accounts.Conversations）：换号、记下账号前看忙不忙，闲着的进程收掉。
type deviceConvs struct {
	conn    *chatConn
	run     func(func())
	harness string
}

func (d deviceConvs) matches(st chat.State) bool {
	if d.harness == "" {
		return true
	}
	return sessionproto.NormalizeHarness(st.Harness) == sessionproto.NormalizeHarness(d.harness)
}

func (d deviceConvs) Busy() bool {
	for _, conv := range d.conn.client.Conversations() {
		st := conv.State()
		if d.matches(st) && (st.Busy || len(st.Prompts) > 0 || st.RunningTasks() > 0) {
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
		st := conv.State()
		if !d.matches(st) || !st.Process {
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

// DefaultHarness 是设备 backend 上新对话默认用的 CLI 工具（"claude" 或 "agy"）。
func (h *Hub) DefaultHarness(backend string) string {
	return claudecfg.DeviceSettings(h.settings.Store(), backend).DefaultHarness()
}

// SetDefaultHarness 设设备 backend 上新对话默认用的 CLI 工具（"claude" 或 "agy"）。
func (h *Hub) SetDefaultHarness(backend, harness string) error {
	return claudecfg.DeviceSettings(h.settings.Store(), backend).SetDefaultHarness(harness)
}

// options 是在设备 backend 上起 Claude Code 进程用的设置：那台的自动标题、自动压缩和压缩时机（设置里各台各设各的，
// 环境变量的写法同 Kotlin 版的 AutoCompact.env）。桌面版只连远程主机，都在那边的 tmux 里开窗口（Desk）。
func (h *Hub) options(backend string) coreapi.Options {
	d := claudecfg.DeviceSettings(h.settings.Store(), backend)
	// 条目里实时收到的图只要 key，画到时经 conv.image 取（InlineImageLoader）：带图的对话整份推一次就是几十 MB
	o := coreapi.Options{AutoTitle: d.AutoTitle(), Desk: true, ImageRefs: true}
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
