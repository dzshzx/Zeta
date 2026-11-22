// Package hubtest 是测试用的假设备（hub.Devices）：不连 SSH，探测照预先给的结果答，会话列表由测试推。只给测试用。
package hubtest

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"io"
	"maps"
	"slices"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"golang.org/x/crypto/ssh"
)

// Devices 是假设备。Config 是 ~/.ssh/config 里的主机，KeyList 是 ~/.ssh 里的私钥，Probes 是各地址探测的结果（没给的照 Found 答成功）。
type Devices struct {
	Config    []string
	ConfigErr error
	KeyList   []string
	Probes    map[string]Probe
	// DirProblems 是各目录进不去的原因（CheckDir 答它，没列的进得去），ImageData 是 Image 答的图。
	DirProblems map[string]string
	ImageData   []byte
	// Paths 是 CheckPath 答的各路径的种类（没列的是没有）；UploadErr、UploadGate 管 Upload 怎么答（见 Upload）。
	Paths      map[string]hostlink.PathKind
	UploadErr  error
	UploadGate chan struct{}
	// OnChat 在每起一条对话连接时调（比如先定好怎么应答）。
	OnChat func(*ChatLink)
	// Found 是各台设备上对话内容里找得到的：设备编号 → 会话号 → 片段（Search 照它答，片段里含关键词的算命中，不分大小写）；
	// SearchErr 是没搜成的设备的原因，Partial 里的设备报只搜了一部分。
	SearchHits map[string]map[string]string
	SearchErr  map[string]error
	Partial    map[string]bool

	mu       sync.Mutex
	hostKey  string
	watchers []*watcher
	opened   map[string]int
	retained []string
	probed   []string
	chats    []*ChatLink
	checked  []string
	uploaded []host.Upload
	searched []string
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

// Search 见 hub.Devices：照 SearchHits、SearchErr、Partial 答，记下搜过的「设备编号 关键词」。
func (d *Devices) Search(_ context.Context, h sshlink.Host, query string, hit func(hostlink.SearchHit)) (bool, error) {
	d.mu.Lock()
	d.searched = append(d.searched, h.ID+" "+query)
	hits, err, partial := d.SearchHits[h.ID], d.SearchErr[h.ID], d.Partial[h.ID]
	d.mu.Unlock()
	if err != nil {
		return false, err
	}
	ids := slices.Sorted(maps.Keys(hits))
	for _, id := range ids {
		if strings.Contains(strings.ToLower(hits[id]), strings.ToLower(query)) {
			hit(hostlink.SearchHit{ID: id, Snippet: hits[id]})
		}
	}
	return !partial, nil
}

// Searched 是搜过的「设备编号 关键词」，按先后。
func (d *Devices) Searched() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.searched)
}
