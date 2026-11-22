// Package hubtest 是测试用的假设备（hub.Devices）：不连 SSH，探测照预先给的结果答，会话列表由测试推。只给测试用。
package hubtest

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"io"
	"slices"
	"sync"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"golang.org/x/crypto/ssh"
)

// Devices 是假设备。Config 是 ~/.ssh/config 里的主机，KeyList 是 ~/.ssh 里的私钥，Probes 是各地址探测的结果（没给的照 Found 答成功）。
type Devices struct {
	Config    []string
	ConfigErr error
	KeyList   []string
	Probes    map[string]Probe
	// DirProblems 是各目录进不去的原因（CheckDir 答它，没列的进得去），ImageData 是 sessions.image 答的图。
	DirProblems map[string]string
	ImageData   []byte
	// Paths 是 CheckPath 答的各路径的种类（没列的是没有）；UploadErr、UploadGate 管 Upload 怎么答（见 Upload）。
	Paths      map[string]hostlink.PathKind
	UploadErr  error
	UploadGate chan struct{}
	// OnChat 在每起一条对话连接时调（比如先定好怎么应答）。
	OnChat func(*ChatLink)
	// SearchHits 是各台设备上对话内容里找得到的：设备编号 → 会话号 → 片段（sessions.search 照它答，片段里含关键词的算命中，
	// 不分大小写）；SearchErr 是没搜成的设备的原因，Partial 里的设备报只搜了一部分。
	SearchHits map[string]map[string]string
	SearchErr  map[string]error
	Partial    map[string]bool
	// SessionsErr 是各个 sessions.* 方法答的错（方法名 → 错误）；ErrBySession 是对各个会话的 sessions.* 答的错（会话号 → 错误，
	// 比如 Refuse 带的 code）。
	ErrBySession map[string]error
	// Handoffs 是 sessions.handoff 答的结果（会话号 → 结果 JSON）：没给的是交出来了。
	Handoffs map[string]string
	// OnSearch 给了时由它答 sessions.search（l 是那条连接，search 是客户端给这次搜索起的名字，命中用 PushMatch 推），不照 SearchHits 答。
	OnSearch    func(l *ChatLink, search any, query string) (json.RawMessage, error)
	SessionsErr map[string]error
	// OnSessions 给了时在答每个 sessions.* 请求之前调（h 是设备编号），可以在这里等着，扮慢的核心。
	OnSessions func(h string, c Call)

	mu       sync.Mutex
	hostKey  string
	watchers []*watcher
	opened   map[string]int
	retained []string
	probed   []string
	chats    []*ChatLink
	bulks    []*ChatLink
	checked  []string
	uploaded []host.Upload
	searched []string
	sessions []string
}

// Probe 是一次探测的结果。
type Probe struct {
	Found host.Found
	Err   error
}

// NewDevices 建一套假设备，各台的主机公钥都是同一把新生成的。
func NewDevices() *Devices {
	public, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		panic(err)
	}
	key, err := ssh.NewPublicKey(public)
	if err != nil {
		panic(err)
	}
	return &Devices{Probes: map[string]Probe{}, hostKey: sshlink.EncodeHostKey(key), opened: map[string]int{}}
}

// HostKey 是各台的主机公钥（base64）。
func (d *Devices) HostKey() string { return d.hostKey }

// Found 是名叫 name 的一台连上后查到的样子。
func (d *Devices) Found(name string) host.Found {
	return host.Found{Home: "/home/" + name, Claude: "2.1.330", System: "Linux x86_64", Hostname: name, HostKey: d.hostKey}
}

// Probe 见 hub.Devices。
func (d *Devices) Probe(target, _, _ string) (host.Found, error) {
	d.mu.Lock()
	d.probed = append(d.probed, target)
	p, ok := d.Probes[target]
	d.mu.Unlock()
	if !ok {
		return d.Found(target), nil
	}
	return p.Found, p.Err
}

// Probed 是探测过的地址，按先后。
func (d *Devices) Probed() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.probed)
}

// ConfigHosts 见 hub.Devices。
func (d *Devices) ConfigHosts() ([]string, error) { return slices.Clone(d.Config), d.ConfigErr }

// Keys 见 hub.Devices。
func (d *Devices) Keys() []string { return slices.Clone(d.KeyList) }

// Retain 见 hub.Devices。
func (d *Devices) Retain(ids []string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.retained = slices.Clone(ids)
}

// Retained 是上次 Retain 留下的。
func (d *Devices) Retained() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.retained
}

type watcher struct {
	id     string
	w      host.SessionWatch
	closed bool
}

func (w *watcher) Close() error { w.closed = true; return nil }

// WatchSessions 见 hub.Devices：记下回调，等测试推。
func (d *Devices) WatchSessions(h sshlink.Host, sw host.SessionWatch) io.Closer {
	d.mu.Lock()
	defer d.mu.Unlock()
	w := &watcher{id: h.ID, w: sw}
	d.watchers = append(d.watchers, w)
	d.opened[h.ID]++
	return w
}

// Watching 是正订阅着的设备，按订阅的先后；没有是 nil。
func (d *Devices) Watching() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	var ids []string
	for _, w := range d.watchers {
		if !w.closed {
			ids = append(ids, w.id)
		}
	}
	return ids
}

// Opened 是设备 id 一共订阅过几次。
func (d *Devices) Opened(id string) int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.opened[id]
}

// last 是设备 id 最近的订阅；open 时只找还开着的。
func (d *Devices) last(id string, open bool) *watcher {
	d.mu.Lock()
	defer d.mu.Unlock()
	for i := len(d.watchers) - 1; i >= 0; i-- {
		w := d.watchers[i]
		if w.id == id && (!open || !w.closed) {
			return w
		}
	}
	return nil
}

// Push 替设备 id 开着的订阅推一份列表（会话的 Backend 填上 id）。
func (d *Devices) Push(id string, u hostlink.SessionsUpdate) {
	if w := d.last(id, true); w != nil {
		w.w.Update(withBackend(u, id))
	}
}

// PushActivity 替设备 id 开着的订阅推一份各会话的忙闲（整份）。
func (d *Devices) PushActivity(id string, a map[string]hostlink.Activity) {
	if w := d.last(id, true); w != nil {
		w.w.Activity(a)
	}
}

// PushStale 替设备 id 已经关掉的订阅推一份（像断开前在路上的推送）。
func (d *Devices) PushStale(id string, u hostlink.SessionsUpdate) {
	if w := d.last(id, false); w != nil {
		w.w.Update(withBackend(u, id))
	}
}

// Greet 替设备 id 开着的订阅交来 serve 的招呼（像每次连上时那样），带主机名 hostname。
func (d *Devices) Greet(id, hostname string) {
	if w := d.last(id, true); w != nil && w.w.Hello != nil {
		w.w.Hello(hostlink.Hello{Hostname: hostname})
	}
}

// Fail 替设备 id 开着的订阅报连上（why 是空）或断开的原因。
func (d *Devices) Fail(id, why string) {
	if w := d.last(id, true); w != nil {
		w.w.Problem(why)
	}
}

func withBackend(u hostlink.SessionsUpdate, id string) hostlink.SessionsUpdate {
	u.Upsert = slices.Clone(u.Upsert)
	for i := range u.Upsert {
		u.Upsert[i].Backend = id
	}
	return u
}

// Searched 是搜过的「设备编号 关键词」，按先后。
func (d *Devices) Searched() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.searched)
}

// ClaudeSwitch 见 hub.Devices：假的 claude-switch，路径为空（serve 自己找），装的时候什么都不做。
func (d *Devices) ClaudeSwitch(sshlink.Host) accounts.Program { return program{} }

type program struct{}

func (program) Path() string                  { return "" }
func (program) Install(context.Context) error { return nil }

// Refuse 是假核心答出错时的错误：message 是给人看的原因，code 是应答里的 code（和真连接答的一样，hostlink.ReplyCode 认得出）。
func Refuse(message, code string) error { return refusal{message, code} }

type refusal struct{ message, code string }

func (r refusal) Error() string { return r.message }
func (r refusal) Code() string  { return r.code }
