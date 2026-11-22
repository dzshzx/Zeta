// Package hub 是桌面版的中枢：设备清单、侧栏看哪台设备（或全部设备）、各台的会话列表（订阅那边的 zeta-helper serve）和置顶。
// 界面只读它的状态、调它的方法，不自己连设备。
//
// 聊天窗格里的对话也在这里：开哪个会话、新对话、给对话用的各台 serve 连接（见 chat.go）。
//
// Hub 的方法都在主线程（界面线程）上调用；要等设备的事经 run 放到后台，拿到的结果经 post 交回主线程再改状态
// （界面上是窗口的 Update，改完重画）。例外是 Probe、ConfigHosts、Keys：它们不碰状态、会阻塞，在后台 goroutine 上调用。
package hub

import (
	"context"
	"io"
	"log"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// Devices 是中枢碰设备的几样事。真的是 host.Devices；界面测试里换成假的，不连设备。
type Devices interface {
	// Probe 直连 target 查一次（见 host.Probe）。阻塞。
	Probe(target, expected, key string) (host.Found, error)
	// WatchSessions 订阅设备 h 的会话列表和各会话的忙闲，回调在后台 goroutine 上；Close 后不再回调。
	WatchSessions(h sshlink.Host, w host.SessionWatch) io.Closer
	// Search 在设备 h 上的对话内容里找 query，边找边交给 hit；返回是不是全部搜完了。阻塞，ctx 取消时停（见 host.Devices.Search）。
	Search(ctx context.Context, h sshlink.Host, query string, hit func(hostlink.SearchHit)) (bool, error)
	// ConfigHosts 是这台电脑 ~/.ssh/config 里能选的主机。阻塞。
	ConfigHosts() ([]string, error)
	// Keys 是这台电脑 ~/.ssh 里能选的私钥。阻塞。
	Keys() []string
	// Retain 只留下 ids 这几台的连接。
	Retain(ids []string)
	// OpenChat 起一条到设备 h 的 serve 连接给对话用：推送、连接的变化在后台 goroutine 上交给 push、problem（见 host.Devices.OpenChat）。
	OpenChat(h sshlink.Host, push func(line []byte), problem func(why string)) host.ChatLink
	// CheckDir 看设备 h 上进不进得去目录 path：进不去时是原因，进得去是空，查不成时是错误。阻塞。
	CheckDir(h sshlink.Host, path string) (string, error)
	// Image 取设备 h 上会话记录 file 里位置 ref 的那张图。阻塞。
	Image(h sshlink.Host, file, ref string) ([]byte, error)
	// Upload 把本机的文件、文件夹或一段内容传到设备 h 上，返回那边的路径（见 host.Devices.Upload）。阻塞。
	Upload(ctx context.Context, h sshlink.Host, u host.Upload, progress func(int64)) (string, error)
	// CheckPath 查设备 h 上的路径是什么。阻塞。
	CheckPath(h sshlink.Host, path string) (hostlink.PathKind, error)
	// ClaudeSwitch 是带到设备 h 上的 claude-switch（账号与额度用，见 host.Devices.ClaudeSwitch）。
	ClaudeSwitch(h sshlink.Host) accounts.Program
}

// Hub 见包的说明。
type Hub struct {
	settings *settings.Settings
	devices  Devices
	run      func(func())
	post     func(func())

	hosts    []sshlink.Host
	pins     map[string]bool
	watches  map[string]io.Closer                    // 订阅着的设备
	sessions []hostlink.Session                      // 列着的各台的会话，合在一起
	problems map[string]string                       // 各台没读成会话列表的原因
	activity map[string]map[string]hostlink.Activity // 各台的核心报的各会话忙闲：设备 → 会话号 → 样子
	unread   map[string]bool                         // 不在眼前时答完了一轮、还没点开看的会话（按置顶键）
	search   search                                  // 侧栏的搜索（search.go）

	chat    *Chat                // 聊天窗格里的对话
	conns   map[string]*chatConn // 各台给对话用的 serve 连接
	remotes map[string]*Remote   // 设置里各台的 Claude Code 配置、账号与额度（remote.go）

	// SearchDelay 是关键词停下多久才去搜对话内容（打字时不必每个字都搜一次）；测试里设成 0。
	SearchDelay time.Duration
}

// New 建中枢：设置 s，设备 d；run 把要等设备的事放到后台，post 把一个函数交回主线程跑。还不连设备，界面起来后调 Start。
func New(s *settings.Settings, d Devices, run, post func(func())) *Hub {
	h := &Hub{settings: s, devices: d, run: run, post: post, hosts: s.Hosts(), pins: map[string]bool{},
		watches: map[string]io.Closer{}, problems: map[string]string{}, conns: map[string]*chatConn{}, remotes: map[string]*Remote{},
		activity: map[string]map[string]hostlink.Activity{}, unread: map[string]bool{}, SearchDelay: searchDelay}
	for _, pin := range s.Pins() {
		h.pins[pin] = true
	}
	return h
}

// Start 订阅侧栏列着的各台设备的会话列表。
func (h *Hub) Start() { h.Refresh() }

// Hosts 是设备清单。
func (h *Hub) Hosts() []sshlink.Host { return h.hosts }

// Host 是编号 id 的设备。
func (h *Hub) Host(id string) (sshlink.Host, bool) {
	i := slices.IndexFunc(h.hosts, func(x sshlink.Host) bool { return x.ID == id })
	if i < 0 {
		return sshlink.Host{}, false
	}
	return h.hosts[i], true
}

// Name 是设备 id 在界面上的名字。
func (h *Hub) Name(id string) string {
	if x, ok := h.Host(id); ok {
		return x.Name()
	}
	return "已删除的设备"
}

// Backend 是侧栏正看着的设备：存着的那台已经删了（或是手机本机）时是第一台；一台都没有时是空。
func (h *Hub) Backend() string {
	stored := h.settings.Backend()
	if _, ok := h.Host(stored); ok {
		return stored
	}
	if len(h.hosts) > 0 {
		return h.hosts[0].ID
	}
	return ""
}

// AllHosts：侧栏列全部设备的会话（设备菜单里选了「全部设备」）。
func (h *Hub) AllHosts() bool { return h.settings.PaneAll() && len(h.hosts) > 0 }

// Listed 是侧栏的会话列表列哪几台设备的。
func (h *Hub) Listed() []string {
	if h.AllHosts() {
		ids := make([]string, len(h.hosts))
		for i, x := range h.hosts {
			ids[i] = x.ID
		}
		return ids
	}
	if b := h.Backend(); b != "" {
		return []string{b}
	}
	return nil
}

// ListOnly 让侧栏只列设备 id 的会话。
func (h *Hub) ListOnly(id string) {
	if _, ok := h.Host(id); !ok {
		return
	}
	logSave(h.settings.SetPaneAll(false))
	logSave(h.settings.SetBackend(id))
	h.Refresh()
}

// ListAll 让侧栏列全部设备的会话（新对话仍开在 Backend 那台）。
func (h *Hub) ListAll() {
	if len(h.hosts) == 0 {
		return
	}
	logSave(h.settings.SetPaneAll(true))
	h.Refresh()
}

// Refresh 让列着的各台的会话列表跟上那边：还没订阅的订阅上，订阅着但连不上的马上重连，不再列着的断开、它的会话拿掉。
func (h *Hub) Refresh() {
	listed := h.Listed()
	for id := range h.watches {
		if !slices.Contains(listed, id) {
			h.unwatch(id)
		}
	}
	h.sessions = slices.DeleteFunc(h.sessions, func(s hostlink.Session) bool { return !slices.Contains(listed, s.Backend) })
	for id := range h.problems {
		if !slices.Contains(listed, id) {
			delete(h.problems, id)
		}
	}
	for id := range h.activity {
		if !slices.Contains(listed, id) {
			delete(h.activity, id)
		}
	}
	for _, id := range listed {
		if _, live := h.watches[id]; live && h.problems[id] == "" {
			continue
		}
		h.unwatch(id)
		h.watch(id)
	}
}

// watch 订阅设备 id 的会话列表。推来的经 post 回主线程，订阅已经换掉的不算。
func (h *Hub) watch(id string) {
	x, _ := h.Host(id)
	var w io.Closer
	current := func() bool { return w != nil && h.watches[id] == w }
	w = h.devices.WatchSessions(x, host.SessionWatch{
		Update: func(u hostlink.SessionsUpdate) {
			h.post(func() {
				if current() {
					h.received(id, u)
				}
			})
		},
		Activity: func(a map[string]hostlink.Activity) {
			h.post(func() {
				if current() {
					h.activityReceived(id, a)
				}
			})
		},
		Problem: func(why string) {
			h.post(func() {
				if !current() {
					return
				}
				if why == "" {
					delete(h.problems, id)
				} else {
					h.problems[id] = "没读到会话列表：" + why
				}
			})
		},
	})
	h.watches[id] = w
}

func (h *Hub) unwatch(id string) {
	if w, ok := h.watches[id]; ok {
		delete(h.watches, id)
		delete(h.activity, id) // 断开了就不留过时的忙闲
		_ = w.Close()
	}
}

// received：设备 id 推来了列表。整份时换掉它的那些；否则 Upsert 是新出现或变了的、Remove 是不再列出的，别的都留着。
func (h *Hub) received(id string, u hostlink.SessionsUpdate) {
	if u.Full {
		others := slices.DeleteFunc(slices.Clone(h.sessions), func(s hostlink.Session) bool { return s.Backend == id })
		if len(others) == 0 {
			h.sessions = u.Upsert // 只有这一台时照那边给的先后
			return
		}
		h.sessions = byTime(append(u.Upsert, others...))
		return
	}
	dropped := map[string]bool{}
	for _, sid := range u.Remove {
		dropped[sid] = true
	}
	for _, s := range u.Upsert {
		dropped[s.ID] = true
	}
	kept := slices.DeleteFunc(slices.Clone(h.sessions), func(s hostlink.Session) bool { return s.Backend == id && dropped[s.ID] })
	h.sessions = byTime(append(slices.Clone(u.Upsert), kept...))
}

func byTime(list []hostlink.Session) []hostlink.Session {
	sort.SliceStable(list, func(i, j int) bool { return list[i].Updated > list[j].Updated })
	return list
}

// Sessions 是列着的各台的会话，新的在前。
func (h *Hub) Sessions() []hostlink.Session { return h.sessions }

// Problem 是没读成会话列表的原因；都读成了是空。列着几台时每条前面注明是哪台。
func (h *Hub) Problem() string {
	listed := h.Listed()
	var lines []string
	for _, id := range listed {
		if why := h.problems[id]; why != "" {
			if len(listed) > 1 {
				why = h.Name(id) + "：" + why
			}
			lines = append(lines, why)
		}
	}
	return strings.Join(lines, "\n")
}

// Groups 是侧栏的分组：置顶、今天、更早（今天按 now 所在时区的零点算）。归档的不列。
type Groups struct {
	Pinned, Today, Earlier []hostlink.Session
}

// Groups 把列着的会话分组。current 是聊天窗格里开着的会话（没有会话号时不算）：列表里还没有它（新对话刚有了会话号、
// 那边的列表还没推来，或在最近 300 个之外）时也放进来，正开着的那个一直找得到。
func (h *Hub) Groups(now time.Time, current hostlink.Session) Groups {
	y, m, d := now.Date()
	today := time.Date(y, m, d, 0, 0, 0, 0, now.Location()).UnixMilli()
	var g Groups
	for _, s := range h.withCurrent(current, now) {
		switch {
		case s.Archived:
		case h.Pinned(s):
			g.Pinned = append(g.Pinned, s)
		case s.Updated >= today:
			g.Today = append(g.Today, s)
		default:
			g.Earlier = append(g.Earlier, s)
		}
	}
	return g
}

// withCurrent 是列着的会话，加上列表里还没有的 current（排在最前，时间算作 now）。current 不在列着的设备上时不加。
func (h *Hub) withCurrent(current hostlink.Session, now time.Time) []hostlink.Session {
	if current.ID == "" || !slices.Contains(h.Listed(), current.Backend) || slices.ContainsFunc(h.sessions, func(s hostlink.Session) bool {
		return s.Backend == current.Backend && s.ID == current.ID
	}) {
		return h.sessions
	}
	if current.Updated == 0 {
		current.Updated = now.UnixMilli()
	}
	return append([]hostlink.Session{current}, h.sessions...)
}

// Pinned：会话 s 置顶了。
func (h *Hub) Pinned(s hostlink.Session) bool { return h.pins[settings.PinKey(s.Backend, s.ID)] }

// TogglePin 置顶或取消置顶会话 s。只是 Zeta 这边的标记，会话记录不动。
func (h *Hub) TogglePin(s hostlink.Session) {
	key := settings.PinKey(s.Backend, s.ID)
	pins := h.settings.Pins()
	if h.pins[key] {
		delete(h.pins, key)
		pins = slices.DeleteFunc(pins, func(p string) bool { return p == key })
	} else {
		h.pins[key] = true
		pins = append(pins, key)
	}
	logSave(h.settings.SetPins(pins))
}

// Probe、ConfigHosts、Keys 见 Devices；在后台 goroutine 上调用。
func (h *Hub) Probe(target, expected, key string) (host.Found, error) {
	return h.devices.Probe(target, expected, key)
}

// ConfigHosts 是能从 SSH 配置导入的主机：已经加过的、localhost 不列。阻塞。
func (h *Hub) ConfigHosts(known []string) ([]string, error) {
	all, err := h.devices.ConfigHosts()
	return slices.DeleteFunc(all, func(a string) bool { return a == "localhost" || slices.Contains(known, a) }), err
}

// Keys 见 Devices。
func (h *Hub) Keys() []string { return h.devices.Keys() }

// AddHost 记下探测过的设备（地址 target、私钥 key、查到的 f），返回它。还没有设备时侧栏换到它。
func (h *Hub) AddHost(target, key string, f host.Found) (sshlink.Host, error) {
	x := sshlink.Host{ID: sshlink.NewID(), Target: target, Home: f.Home, DefaultCwd: f.Home, Via: sshlink.ViaDirect,
		HostKey: f.HostKey, Hostname: f.Hostname, Key: key}
	first := h.Backend() == ""
	if err := h.setHosts(append(slices.Clone(h.hosts), x)); err != nil {
		return x, err
	}
	if first {
		logSave(h.settings.SetBackend(x.ID))
	}
	h.Refresh()
	return x, nil
}

// UpdateHost 存改过的设备 x（改名、默认工作目录、私钥、认新的指纹）。连法相关的变了时会话列表按新的重连。
func (h *Hub) UpdateHost(x sshlink.Host) error {
	i := slices.IndexFunc(h.hosts, func(o sshlink.Host) bool { return o.ID == x.ID })
	if i < 0 {
		return nil
	}
	old := h.hosts[i]
	hosts := slices.Clone(h.hosts)
	hosts[i] = x
	if err := h.setHosts(hosts); err != nil {
		return err
	}
	if old.Target != x.Target || old.Key != x.Key || old.HostKey != x.HostKey {
		h.unwatch(x.ID)
		h.Refresh()
		// 对话也照新的连法重连：开着的那个在新连接上重新打开
		h.closeConn(x.ID)
		if c := h.chat; c != nil && c.Backend == x.ID {
			h.reopen(c)
		}
	}
	return nil
}

// RemoveHost 删掉设备 id：只是 Zeta 不再连它，那边的会话记录不动。正看着它时侧栏换到剩下的第一台。
func (h *Hub) RemoveHost(id string) error {
	hosts := slices.DeleteFunc(slices.Clone(h.hosts), func(x sshlink.Host) bool { return x.ID == id })
	if len(hosts) == len(h.hosts) {
		return nil
	}
	if err := h.setHosts(hosts); err != nil {
		return err
	}
	ids := make([]string, len(hosts))
	for i, x := range hosts {
		ids[i] = x.ID
	}
	h.devices.Retain(ids)
	h.unwatch(id)
	h.Refresh()
	if c := h.chat; c != nil && c.Backend == id {
		h.CloseChat()
	}
	h.closeConn(id)
	delete(h.remotes, id)
	return nil
}

// setHosts 换掉设备清单并存盘；存不上时内存里照样换（这次运行里能用），把原因交回去。
func (h *Hub) setHosts(hosts []sshlink.Host) error {
	h.hosts = hosts
	return h.settings.SetHosts(hosts)
}

// OtherNames 是除 id 之外各台设备的名字（起名字时查重）。
func (h *Hub) OtherNames(id string) []string {
	var names []string
	for _, x := range h.hosts {
		if x.ID != id {
			names = append(names, x.Name())
		}
	}
	return names
}

// logSave 记下没存上的设置：侧栏看哪台、置顶这类，没存上只是下次打开时不记得，不打扰用户。
func logSave(err error) {
	if err != nil {
		log.Printf("zeta: 设置没存上：%v", err)
	}
}

// Prompts 是常用指令（存在设置里，和 Compose 版共用）。
func (h *Hub) Prompts() []settings.SavedPrompt { return h.settings.Prompts() }

// SetPrompts 存常用指令。
func (h *Hub) SetPrompts(list []settings.SavedPrompt) error { return h.settings.SetPrompts(list) }
