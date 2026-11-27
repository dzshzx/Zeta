// Package mobile 是 iOS 版的核心：把桌面版的中枢（hub）原样跑在手机上，只换掉认证（Zeta 自己的一把 Ed25519 密钥或用户导入的私钥，
// 不读 ~/.ssh），对 Swift 界面只露两样：一个按方法名调用、参数和结果都是 JSON 的 Call，和一个「有变化了」的回调。
// cgo 的导出在 cmd/zeta-ios，协议写在 docs/ios.md「核心桥」。
//
// 中枢的方法要在同一个线程上调（见 hub 的包说明）。这里自己起一个 goroutine 当那个线程：中枢的 post 和界面的每次 Call
// 都排进它，逐个跑完后通知界面一次。会阻塞的（探测）不进这个 goroutine，在调用方的线程上跑。
package mobile

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"slices"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/appversion"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/host"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/runlog"
	"github.com/dzshzx/Zeta/hub/settings"
	"golang.org/x/crypto/ssh"
)

// Config 是起核心要的几样。
type Config struct {
	// Dir 是设置目录（应用的 Application Support 下），设置文件的格式同桌面版。
	Dir string
	// Seed 是 Zeta 登录用的 Ed25519 私钥的 32 字节种子，界面存在钥匙串里，每次起核心时交进来。
	Seed []byte
	// Device 是这台手机的名字，对话连接连上时报给核心（core.client），别的客户端看到会话被占着时说在哪台设备上。空是不报。
	Device string
	// Changed 在状态有了变化时调，在核心的 goroutine 上；界面收到后自己挑时候调 Call("state")。
	Changed func()
	// Log 是 Zeta 自己的运行日志（入口打开，标准库 log 也接到它），设置里的诊断页看它；nil 时诊断页没有这一组。
	Log *runlog.Log
}

// Core 见包的说明。
type Core struct {
	hub     *hub.Hub
	changed func()
	auth    *appAuth    // Start 起的才有；测试里 New 建的没有，不调 key、importkey、resetkey
	dir     string      // 设置目录，诊断包带上它下面 diag/ 里的文件
	log     *runlog.Log // Zeta 自己的运行日志，见 Config.Log

	// 排给核心 goroutine 的事，按排进来的先后跑。不设上限：排的一方可能就是核心 goroutine 自己（中枢在主线程上也会 post），
	// 有上限的通道满了会卡死。wake 有事可做时有一个信号。
	queueMu sync.Mutex
	queue   []job
	wake    chan struct{}

	mu     sync.Mutex
	probes map[string]probed // 探测过、还没加的设备：凭据 → 探测结果

	// 下面只在核心的 goroutine 上读写。
	shown    *chat.Conversation // 上次 state 交给界面的对话：换了对话时条目整份重发
	problem  string             // 最近一次操作没成的原因，界面显示一次就清掉（state 的 failed）
	sidebar  sidebar            // 侧栏的提醒和没成的操作（sessions.go）
	composer *composer          // 输入栏（composer.go），comp 建
	relay    *relayDraft        // 账号页上正在添加或编辑的中转（accounts.go）
}

// job 是排给核心 goroutine 的一件事；quiet 的（界面自己来取状态）跑完不算有变化，不然界面每取一次就又被叫来取一次。
type job struct {
	run   func()
	quiet bool
}

type probed struct {
	target string
	found  host.Found
}

// devices 是中枢要的 hub.Devices：iOS 上没有 ~/.ssh，导入配置和选私钥都答没有。
type devices struct{ *host.Devices }

func (devices) ConfigHosts() ([]string, error) { return nil, nil }
func (devices) Keys() []string                 { return nil }

// appAuth 用 Zeta 的密钥登录，地址要写明用户：导入了私钥时用导入的，否则用 Zeta 自己生成的那把。
// 换了以后新起的连接用新的，已经连着的不受影响。
type appAuth struct {
	mu       sync.Mutex
	own      ssh.Signer
	imported ssh.Signer
}

func (a *appAuth) signer() (ssh.Signer, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.imported != nil {
		return a.imported, true
	}
	return a.own, false
}

func (a *appAuth) set(imported ssh.Signer) {
	a.mu.Lock()
	a.imported = imported
	a.mu.Unlock()
}

// public 是在用的那把的公钥（authorized_keys 的一行），和在用的是不是导入的。
func (a *appAuth) public() (string, bool) {
	s, imported := a.signer()
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(s.PublicKey())))
	if !imported {
		line += " zeta-ios"
	}
	return line, imported
}

// Resolve 见 sshlink.Resolver。
func (a *appAuth) Resolve(target, key string) (sshlink.Route, error) {
	s, _ := a.signer()
	return sshlink.KeyAuth{Signer: s}.Resolve(target, key)
}

// Rejected 见 sshlink.Resolver。
// 按这次连接用的那把说，不看现在在用哪把（中途换了钥匙时两者不同）。
func (a *appAuth) Rejected(route sshlink.Route) string {
	a.mu.Lock()
	own := a.own
	a.mu.Unlock()
	if len(route.Signers) == 1 && !bytes.Equal(route.Signers[0].PublicKey().Marshal(), own.PublicKey().Marshal()) {
		return locales.MobileImportedKeyRejected()
	}
	return locales.MobileKeyRejected()
}

// parseKey 读导入的私钥（OpenSSH 或 PEM 格式，可以有密码），答签名用的和去掉密码后的 OpenSSH 格式（界面存进钥匙串，
// 以后起核心时原样交回来，不用再问密码）。
func parseKey(text, passphrase string) (ssh.Signer, string, error) {
	raw, err := ssh.ParseRawPrivateKey([]byte(text))
	if _, missing := err.(*ssh.PassphraseMissingError); missing {
		if passphrase == "" {
			return nil, "", locales.Err(locales.MobileKeyNeedsPassphrase)
		}
		raw, err = ssh.ParseRawPrivateKeyWithPassphrase([]byte(text), []byte(passphrase))
	}
	var signer ssh.Signer
	var block *pem.Block
	if err == nil {
		signer, err = ssh.NewSignerFromKey(raw)
	}
	if err == nil {
		block, err = ssh.MarshalPrivateKey(raw, "")
	}
	if err != nil {
		return nil, "", locales.Err(locales.MobileKeyUnreadable)
	}
	return signer, string(pem.EncodeToMemory(block)), nil
}

// Start 起核心：读设置、取编进来的 zeta-helper 和 claude-switch，订阅列着的设备的会话列表。
func Start(c Config) (*Core, error) {
	if len(c.Seed) != ed25519.SeedSize {
		return nil, fmt.Errorf("key seed must be %d bytes, got %d", ed25519.SeedSize, len(c.Seed))
	}
	signer, err := ssh.NewSignerFromKey(ed25519.NewKeyFromSeed(c.Seed))
	if err != nil {
		return nil, err
	}
	prefs, err := settings.Load(c.Dir)
	if err != nil {
		log.Printf("zeta: %v", err)
	}
	auth := &appAuth{own: signer}
	set, err := host.NewDevices(auth)
	if err != nil {
		return nil, err
	}
	set.Device = c.Device
	core := New(prefs, devices{set}, c.Changed)
	core.auth = auth
	core.dir, core.log = c.Dir, c.Log
	core.Do(core.hub.Start)
	return core, nil
}

// New 用设置 s、设备 d 建核心（测试里 d 是假设备），还没订阅设备：Start 建的已经订阅了，测试自己 Do(c.hub.Start)。
func New(s *settings.Settings, d hub.Devices, changed func()) *Core {
	if changed == nil {
		changed = func() {}
	}
	c := &Core{changed: changed, probes: map[string]probed{}, wake: make(chan struct{}, 1)}
	run := func(f func()) { go f() }
	c.hub = hub.New(s, d, run, c.post)
	go c.loop()
	return c
}

// post 把 f 排给核心 goroutine，不等它跑。
func (c *Core) post(f func()) { c.enqueue(job{run: f}) }

func (c *Core) enqueue(j job) {
	c.queueMu.Lock()
	c.queue = append(c.queue, j)
	c.queueMu.Unlock()
	select {
	case c.wake <- struct{}{}:
	default:
	}
}

// loop 是核心 goroutine：把排着的逐个跑完，一批里有不是 quiet 的就通知界面一次。
func (c *Core) loop() {
	for range c.wake {
		for {
			c.queueMu.Lock()
			batch := c.queue
			c.queue = nil
			c.queueMu.Unlock()
			if len(batch) == 0 {
				break
			}
			changed := false
			for _, j := range batch {
				j.run()
				changed = changed || !j.quiet
			}
			if changed {
				c.changed()
			}
		}
	}
}

// Do 在核心的 goroutine 上跑 f，等它跑完。不能在核心 goroutine 上调（会等自己）。
func (c *Core) Do(f func()) { c.wait(f, false) }

func (c *Core) wait(f func(), quiet bool) {
	done := make(chan struct{})
	c.enqueue(job{run: func() { defer close(done); f() }, quiet: quiet})
	<-done
}

// Call 调一个方法：params 是参数的 JSON（没有参数时可以是空），结果是结果的 JSON；出错时结果是 {"error":"原因"}。
// 方法见 docs/ios.md「核心桥」。可以在任意线程上调；probe 会阻塞到探测完（最多约 45 秒），别的很快返回。
func (c *Core) Call(method string, params []byte) []byte {
	if len(params) == 0 {
		params = []byte("{}")
	}
	result, err := c.call(method, params)
	if err != nil {
		out, _ := json.Marshal(map[string]string{"error": locales.ErrorText(err)})
		return out
	}
	out, err := json.Marshal(result)
	if err != nil {
		out, _ = json.Marshal(map[string]string{"error": err.Error()})
	}
	return out
}

// ok 是没有别的结果的方法答的。
var ok = map[string]bool{"ok": true}

var errUnknown = errors.New("unknown method")

func (c *Core) call(method string, raw []byte) (any, error) {
	if !json.Valid(raw) {
		return nil, errors.New("params are not JSON")
	}
	return c.dispatch(method, raw)
}

// 聊天页和设备的方法（见 docs/ios.md「核心桥」）。别的领域在各自的文件里登记。
type (
	targetParams struct {
		Target string `json:"target"`
	}
	tokenParams struct {
		Token string `json:"token"`
	}
	idParams struct {
		ID string `json:"id"`
		On bool   `json:"on"`
	}
	openParams struct {
		Backend string `json:"backend"`
		ID      string `json:"id"`
	}
	textParams struct {
		Text string `json:"text"`
	}
	answerParams struct {
		RequestID string          `json:"request_id"`
		Reply     json.RawMessage `json:"reply"`
	}
	keyParams struct {
		Key        string `json:"key"`
		Passphrase string `json:"passphrase"`
	}
)

func init() {
	blocking("probe", func(c *Core, p targetParams) (any, error) { return c.probe(p.Target) })
	blocking("key", func(c *Core, _ none) (any, error) { return c.key("") })
	blocking("importkey", func(c *Core, p keyParams) (any, error) {
		signer, text, err := parseKey(p.Key, p.Passphrase)
		if err != nil {
			return nil, err
		}
		c.auth.set(signer)
		return c.key(text)
	})
	blocking("resetkey", func(c *Core, _ none) (any, error) {
		c.auth.set(nil)
		return c.key("")
	})
	reading("state", func(c *Core, _ none) (any, error) { return c.state(), nil })
	onLoop("resync", func(c *Core, _ none) (any, error) {
		c.shown = nil // 下一次 state 整份交条目
		return ok, nil
	})
	onLoop("add", func(c *Core, p tokenParams) (any, error) { return c.add(p.Token) })
	onLoop("remove", func(c *Core, p idParams) (any, error) { return ok, c.hub.RemoveHost(p.ID) })
	onLoop("connect", func(c *Core, p idParams) (any, error) { return ok, c.hub.SetConnected(p.ID, p.On) })
	onLoop("list", func(c *Core, p idParams) (any, error) {
		if p.ID == "" {
			c.hub.ListAll()
		} else {
			c.hub.ListOnly(p.ID)
		}
		return ok, nil
	})
	onLoop("refresh", func(c *Core, _ none) (any, error) {
		c.hub.Refresh()
		return ok, nil
	})
	onLoop("open", func(c *Core, p openParams) (any, error) {
		// 列表里有它时用列表里的那份（带记录文件、标题）；没有时（比如点通知打开另一台上的）只带设备和会话号，同桌面版 openListed
		s, found := c.session(p.Backend, p.ID)
		if !found {
			if _, known := c.hub.Host(p.Backend); !known || p.ID == "" {
				return nil, locales.Err(locales.MobileSessionGone)
			}
			s = hostlink.Session{Backend: p.Backend, ID: p.ID}
		}
		c.hub.OpenSession(s)
		return ok, nil
	})
	onLoop("new", func(c *Core, p openParams) (any, error) {
		c.hub.NewChatOn(p.Backend)
		return ok, nil
	})
	onLoop("close", func(c *Core, _ none) (any, error) {
		c.hub.CloseChat()
		return ok, nil
	})
	inChat("interrupt", func(none) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Interrupt(ctx) }, nil
	})
	inChat("answer", func(p answerParams) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		var r prompt.Reply
		if err := json.Unmarshal(p.Reply, &r); err != nil {
			return nil, fmt.Errorf("bad reply: %w", err)
		}
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Answer(ctx, p.RequestID, r) }, nil
	})
	inChat("earlier", func(none) (func(ctx context.Context, conv *chat.Conversation) error, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.LoadEarlier(ctx, false) }, nil
	})
}

// session 找列着的会话。
func (c *Core) session(backend, id string) (hostlink.Session, bool) {
	list := c.hub.Sessions()
	i := slices.IndexFunc(list, func(s hostlink.Session) bool { return s.Backend == backend && s.ID == id })
	if i < 0 {
		return hostlink.Session{}, false
	}
	return list[i], true
}

// Probed 是探测的结果：凭据 token 交给 add 加上这台，别的给人看。
type Probed struct {
	Token       string `json:"token"`
	Summary     string `json:"summary"`
	Hostname    string `json:"hostname"`
	Fingerprint string `json:"fingerprint"`
}

func (c *Core) probe(target string) (Probed, error) {
	target = strings.TrimSpace(target)
	f, err := c.hub.Probe(target, "", "")
	if err != nil {
		return Probed{}, err
	}
	token := sshlink.NewID()
	c.mu.Lock()
	c.probes[token] = probed{target: target, found: f}
	c.mu.Unlock()
	return Probed{Token: token, Summary: f.Summary(), Hostname: f.Hostname, Fingerprint: sshlink.Fingerprint(f.HostKey)}, nil
}

func (c *Core) add(token string) (any, error) {
	c.mu.Lock()
	p, found := c.probes[token]
	delete(c.probes, token)
	c.mu.Unlock()
	if !found {
		return nil, locales.Err(locales.MobileProbeFirst)
	}
	_, err := c.hub.AddHost(p.target, "", p.found)
	return ok, err
}

// Key 是 key、importkey、resetkey 答的：在用的那把的公钥、是不是导入的、版本名；importkey 另答去掉密码的私钥 PEM，
// 界面存进钥匙串，下次起核心后用 importkey 原样交回来。
type Key struct {
	Public   string `json:"public"`
	Imported bool   `json:"imported"`
	Version  string `json:"version"`
	PEM      string `json:"pem,omitempty"`
}

// key 答在用的那把；pemText 是刚导入的私钥（importkey 才有）。换了钥匙以后新起的连接用新的，已经连着的不断开。
func (c *Core) key(pemText string) (Key, error) {
	public, imported := c.auth.public()
	return Key{Public: public, Imported: imported, Version: appversion.Current().Name, PEM: pemText}, nil
}
