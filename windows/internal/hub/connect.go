package hub

import (
	"context"
	"encoding/json"
	"errors"
	"slices"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/locales"
)

// SetConnected 是设备页的「连接」开关：设备 id 连不连（sshlink.Host.Off）。
//
// 关掉时断开这台的会话列表订阅、给对话用的和取大块数据的 serve 连接、SSH 连接，之后不再自动重连：要连它的请求都答已断开
// （offline）。聊天窗格里这台的对话 Zeta 放手（连接一断核心就当这个客户端离开了，进程在那边照样跑），窗格说已断开。
// 打开时马上连回去：会话列表重新订阅，聊天窗格里这台的对话按会话号重新打开（接上还在跑的进程）。
// 存不上时内存里照样改（这次运行里有效），把原因交回去。主线程上调用。
func (h *Hub) SetConnected(id string, on bool) error {
	i := slices.IndexFunc(h.hosts, func(x sshlink.Host) bool { return x.ID == id })
	if i < 0 || h.hosts[i].Off == !on {
		return nil
	}
	hosts := slices.Clone(h.hosts)
	hosts[i].Off = !on
	err := h.setHosts(hosts)
	c := h.chat
	here := c != nil && c.Backend == id
	if here && !on {
		h.chat = released(c) // 正在打开的那次回来时对话已经换走，打开的随即关掉
	}
	// 两个方向都换掉连接：开着时连着的要断开，关着时留下的是答已断开的假连接
	h.dropLinks(id)
	h.Refresh()
	if here && on {
		h.reopen(c)
	}
	return err
}

// dropLinks 关掉设备 id 上给对话用的和取大块数据的 serve 连接，丢掉它的设置用的那几样，SSH 连接只留「连接」开着的设备的：
// 删设备、拨「连接」开关时。
func (h *Hub) dropLinks(id string) {
	h.closeConn(id)
	h.closeBulk(id)
	delete(h.remotes, id)
	h.devices.Retain(h.connectedIDs())
}

// released 是断开设备后聊天窗格里留着的对话：还是那个会话（新对话已经有了会话号的接着它），没有打开着的对话。
func released(c *Chat) *Chat {
	s := c.Session
	if c.Conv != nil && c.Conv.Session() != "" {
		s.ID = c.Conv.Session()
	}
	return &Chat{Backend: c.Backend, Session: s}
}

// Working：设备 id 上本客户端开着的对话有在进行的（在回答、等确认、有后台任务），断开前要问一声。
func (h *Hub) Working(id string) bool {
	c := h.conns[id]
	return c != nil && deviceConvs{conn: c}.Busy()
}

// Off：设备 id 的「连接」关着。
func (h *Hub) Off(id string) bool {
	x, ok := h.Host(id)
	return ok && x.Off
}

// connectedIDs 是「连接」开着的各台设备（SSH 连接只留它们的）。
func (h *Hub) connectedIDs() []string {
	var ids []string
	for _, x := range h.hosts {
		if !x.Off {
			ids = append(ids, x.ID)
		}
	}
	return ids
}

// offline 是「连接」关着的设备 x 上要连设备的请求得到的错误。
func offline(x sshlink.Host) error { return errors.New(locales.HubOffline(x.Name())) }

// gated 是中枢实际用的 Devices：「连接」关着的设备（sshlink.Host.Off）不去连，要连它的都答已断开。
// 要连设备的入口都带着那台的 Host，在这里拦一次，中枢里取连接、传附件、查路径的地方不用各自判断。
type gated struct{ Devices }

func (g gated) OpenChat(x sshlink.Host, push func(line []byte), hello func(hostlink.Hello), problem func(why string)) host.ChatLink {
	if x.Off {
		return offLink{offline(x)}
	}
	return g.Devices.OpenChat(x, push, hello, problem)
}

func (g gated) OpenBulk(x sshlink.Host) host.ChatLink {
	if x.Off {
		return offLink{offline(x)}
	}
	return g.Devices.OpenBulk(x)
}

func (g gated) CheckDir(x sshlink.Host, path string) (string, error) {
	if x.Off {
		return "", offline(x)
	}
	return g.Devices.CheckDir(x, path)
}

func (g gated) Upload(ctx context.Context, x sshlink.Host, u host.Upload, progress func(int64)) (string, error) {
	if x.Off {
		return "", offline(x)
	}
	return g.Devices.Upload(ctx, x, u, progress)
}

func (g gated) CheckPath(x sshlink.Host, path string) (hostlink.PathKind, error) {
	if x.Off {
		return 0, offline(x)
	}
	return g.Devices.CheckPath(x, path)
}

// offLink 是「连接」关着的设备上的假连接：不连设备，每个请求都答已断开。
type offLink struct{ err error }

func (l offLink) Call(context.Context, string, map[string]any) (json.RawMessage, error) {
	return nil, l.err
}

func (offLink) Close() error { return nil }
