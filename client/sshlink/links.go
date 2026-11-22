package sshlink

import (
	"bytes"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// ConnectionFailed 是连不上或连接断了时命令的退出码，和 ssh 一样；远端命令的退出码原样带回。
const ConnectionFailed = 255

const (
	dialTimeout    = 15 * time.Second
	keepalivePause = 15 * time.Second // 有通道开着时隔这么久发一次保活包
	keepaliveWait  = 30 * time.Second // 保活包这么久没回音就当连接断了（加上间隔约 45 秒发现，同 Kotlin 版）
)

// HostKeyChangedError：对方的主机公钥和记下的不一样。Seen 是对方现在的（base64）。
type HostKeyChangedError struct{ Seen string }

func (e *HostKeyChangedError) Error() string {
	return "主机指纹和加设备时记下的不一样（设备重装过，或者连到了别的机器上）"
}

// Links 是 Zeta 直连各台设备的 SSH：每台一条连接，每个命令开一个通道，省得每次握手。连接断了（保活包约 45 秒没回音、
// 对方关了），上面的命令都以 ConnectionFailed 结束，下一个命令重新连。最后一个通道关掉 Idle 之后断开，闲着时不发保活包。
// 主机指纹在加设备时记下（Probe），以后连接只认这一个，变了就拒绝。哪个 goroutine 上用都行。
type Links struct {
	auth Resolver
	idle time.Duration

	mu    sync.Mutex
	links map[string]*link // 设备编号 → 它的连接
}

// NewLinks 用 auth 决定连到哪、用哪些私钥；最后一个通道关掉 idle 后断开连接。
func NewLinks(auth Resolver, idle time.Duration) *Links {
	return &Links{auth: auth, idle: idle, links: map[string]*link{}}
}

// Auth 是决定连到哪、用哪些私钥的那个。
func (l *Links) Auth() Resolver { return l.auth }

// Start 在设备 h 上开一个通道跑 command（原样交给那边用户的 shell）。连接没有就先连（阻塞到连上或失败）；复用的连接刚好断了时换一条再试一次。
// 连不上时返回的错误是一句给人看的话（Describe）。
func (l *Links) Start(h Host, command string) (*Process, error) {
	if h.Via != ViaDirect {
		return nil, Problem("「" + h.Name() + "」要经手机的 Debian 连，电脑上连不了")
	}
	key := ParseHostKey(h.HostKey)
	if key == nil {
		return nil, Problem("「" + h.Name() + "」没有记下主机指纹")
	}
	lk := l.link(h, key.Marshal())
	client, reused, err := lk.client()
	if err != nil {
		return nil, err
	}
	p, err := lk.open(client, command)
	if err != nil && reused {
		lk.drop(client)
		if client, _, err = lk.client(); err != nil {
			return nil, err
		}
		p, err = lk.open(client, command)
	}
	if err != nil {
		return nil, Problem(Describe(err))
	}
	return p, nil
}

// Probe 连一次 target 跑 command（加设备、检查连接时用），连接用完就断，不进连接池：只认主机公钥 expected（base64，空时谁都认），
// 返回对方的主机公钥（base64）和命令的结果。指纹不对时是 *HostKeyChangedError，别的连不上是 Problem。阻塞。
func (l *Links) Probe(target, key, expected, command string, timeout time.Duration) (seen string, r Result, err error) {
	var want []byte
	if expected != "" {
		k := ParseHostKey(expected)
		if k == nil {
			return "", Result{}, Problem("记下的主机指纹坏了")
		}
		want = k.Marshal()
	}
	client, blob, err := dial(l.auth, target, key, want)
	if blob != nil {
		seen = base64.StdEncoding.EncodeToString(blob)
	}
	if err != nil {
		return seen, Result{}, err
	}
	defer client.Close()
	session, err := client.NewSession()
	if err != nil {
		return seen, Result{}, Problem(Describe(err))
	}
	p, err := newProcess(session, command, func() {})
	if err != nil {
		return seen, Result{}, Problem(Describe(err))
	}
	return seen, p.Capture(nil, timeout), nil
}

// Retain 只留下 ids 这几台的连接，别的断开（设备删了），上面的命令都以 ConnectionFailed 结束。
func (l *Links) Retain(ids []string) {
	keep := map[string]bool{}
	for _, id := range ids {
		keep[id] = true
	}
	l.mu.Lock()
	var gone []*link
	for id, lk := range l.links {
		if !keep[id] {
			gone = append(gone, lk)
			delete(l.links, id)
		}
	}
	l.mu.Unlock()
	for _, lk := range gone {
		lk.close()
	}
}

// DropAll 断开所有连接（换了网络：旧连接多半已经死了，不等保活超时）。
func (l *Links) DropAll() {
	l.mu.Lock()
	all := make([]*link, 0, len(l.links))
	for _, lk := range l.links {
		all = append(all, lk)
	}
	l.mu.Unlock()
	for _, lk := range all {
		lk.close()
	}
}

// link 是设备 h 的连接；地址、私钥或指纹变了就换一条新的。
func (l *Links) link(h Host, hostKey []byte) *link {
	l.mu.Lock()
	defer l.mu.Unlock()
	old := l.links[h.ID]
	if old != nil && old.target == h.Target && old.key == h.Key && bytes.Equal(old.hostKey, hostKey) {
		return old
	}
	if old != nil {
		go old.close()
	}
	lk := &link{owner: l, target: h.Target, key: h.Key, hostKey: hostKey}
	l.links[h.ID] = lk
	return lk
}

// link 是一台设备的连接：没连上或断了时，下一个要用的去连（同一时间只连一次）。
type link struct {
	owner   *Links
	target  string
	key     string
	hostKey []byte

	dialing sync.Mutex // 连的时候拿着：几个命令同时要连接时只连一次

	mu     sync.Mutex
	conn   *ssh.Client
	active int         // 开着的通道
	idle   *time.Timer // 最后一个通道关掉后断开的定时器
}

// client 是连着的连接，没有就连上。reused 是不是早就连着的（那样它可能刚好断了还没发现）。
func (lk *link) client() (c *ssh.Client, reused bool, err error) {
	lk.dialing.Lock()
	defer lk.dialing.Unlock()
	lk.mu.Lock()
	c = lk.conn
	lk.mu.Unlock()
	if c != nil {
		return c, true, nil
	}
	c, _, err = dial(lk.owner.auth, lk.target, lk.key, lk.hostKey)
	if err != nil {
		return nil, false, err
	}
	lk.mu.Lock()
	lk.conn = c
	lk.mu.Unlock()
	stop := make(chan struct{})
	go func() {
		_ = c.Wait() // 连接断了、被关掉
		close(stop)
		lk.forget(c)
	}()
	go lk.keepalive(c, stop)
	lk.idleIfUnused(c)
	return c, false, nil
}

// open 在连接 c 上开一个通道跑 command。
func (lk *link) open(c *ssh.Client, command string) (*Process, error) {
	session, err := c.NewSession()
	if err != nil {
		return nil, err
	}
	lk.mu.Lock()
	lk.active++
	if lk.idle != nil {
		lk.idle.Stop()
		lk.idle = nil
	}
	lk.mu.Unlock()
	p, err := newProcess(session, command, func() { lk.release(c) })
	if err != nil {
		return nil, err
	}
	return p, nil
}

// release：一个通道关了；最后一个关掉后过 Idle 断开连接。
func (lk *link) release(c *ssh.Client) {
	lk.mu.Lock()
	lk.active--
	lk.mu.Unlock()
	lk.idleIfUnused(c)
}

// idleIfUnused：c 上没有通道开着时，过 Idle 断开它。
func (lk *link) idleIfUnused(c *ssh.Client) {
	lk.mu.Lock()
	defer lk.mu.Unlock()
	if lk.active > 0 || lk.conn != c {
		return
	}
	if lk.idle != nil {
		lk.idle.Stop()
	}
	lk.idle = time.AfterFunc(lk.owner.idle, func() {
		lk.mu.Lock()
		unused := lk.active == 0 && lk.conn == c
		lk.mu.Unlock()
		if unused {
			lk.drop(c)
		}
	})
}

// keepalive：有通道开着时隔一会儿发一次保活包，等不到回音就断开（上面的命令随即以 ConnectionFailed 结束）。
func (lk *link) keepalive(c *ssh.Client, stop <-chan struct{}) {
	tick := time.NewTicker(keepalivePause)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case <-tick.C:
		}
		lk.mu.Lock()
		busy := lk.active > 0
		lk.mu.Unlock()
		if !busy {
			continue
		}
		reply := make(chan error, 1)
		go func() {
			_, _, err := c.SendRequest("keepalive@openssh.com", true, nil)
			reply <- err
		}()
		select {
		case <-stop:
			return
		case err := <-reply:
			if err != nil {
				lk.drop(c)
				return
			}
		case <-time.After(keepaliveWait):
			lk.drop(c)
			return
		}
	}
}

// forget：连接 c 已经断了，下一个命令重新连。
func (lk *link) forget(c *ssh.Client) {
	lk.mu.Lock()
	defer lk.mu.Unlock()
	if lk.conn == c {
		lk.conn = nil
		if lk.idle != nil {
			lk.idle.Stop()
			lk.idle = nil
		}
	}
}

// drop 断开连接 c。
func (lk *link) drop(c *ssh.Client) {
	lk.forget(c)
	_ = c.Close()
}

// close 断开现在的连接（有的话）。
func (lk *link) close() {
	lk.mu.Lock()
	c := lk.conn
	lk.mu.Unlock()
	if c != nil {
		lk.drop(c)
	}
}

// dial 连上地址 target：实际连到哪、用哪些私钥问 auth。认 expected 这一个主机公钥（nil 时谁都认），seen 是对方的主机公钥（握手到那一步才有）。
func dial(auth Resolver, target, key string, expected []byte) (c *ssh.Client, seen []byte, err error) {
	route, err := auth.Resolve(target, key)
	if err != nil {
		return nil, nil, err
	}
	config := &ssh.ClientConfig{
		User: route.Target.User,
		Auth: []ssh.AuthMethod{ssh.PublicKeys(route.Signers...)},
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			seen = key.Marshal()
			if expected != nil && !bytes.Equal(seen, expected) {
				return &HostKeyChangedError{Seen: base64.StdEncoding.EncodeToString(seen)}
			}
			return nil
		},
		HostKeyAlgorithms: hostKeyAlgorithms(expected),
		Timeout:           dialTimeout,
	}
	conn, err := net.DialTimeout("tcp", route.Target.Addr(), dialTimeout)
	if err != nil {
		return nil, nil, Problem(Describe(err))
	}
	// 握手也限时：对方接了 TCP 却不说话时不一直等
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	sc, chans, reqs, err := ssh.NewClientConn(conn, route.Target.Addr(), config)
	if err != nil {
		_ = conn.Close()
		var changed *HostKeyChangedError
		switch {
		case errors.As(err, &changed):
			return nil, seen, changed
		case strings.Contains(err.Error(), "unable to authenticate"):
			return nil, seen, Problem(auth.Rejected(route))
		}
		return nil, seen, Problem(Describe(err))
	}
	_ = conn.SetDeadline(time.Time{})
	return ssh.NewClient(sc, chans, reqs), seen, nil
}

// hostKeyAlgorithms：先谈记下的那种主机公钥，对方有好几种时，不会因为换了一种而当成指纹变了。没记下时照默认的。
func hostKeyAlgorithms(expected []byte) []string {
	if expected == nil {
		return nil
	}
	key, err := ssh.ParsePublicKey(expected)
	if err != nil {
		return nil
	}
	if key.Type() == ssh.KeyAlgoRSA {
		return []string{ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256, ssh.KeyAlgoRSA}
	}
	return []string{key.Type()}
}

// Process 是一个通道上的远程命令。Stdout、Stderr 都要读（不读的话对方写满窗口就停下）。
type Process struct {
	Stdin  io.WriteCloser
	Stdout io.Reader
	Stderr io.Reader

	session *ssh.Session
	release func()
	once    sync.Once
}

func newProcess(session *ssh.Session, command string, release func()) (*Process, error) {
	p := &Process{session: session, release: release}
	var err error
	if p.Stdin, err = session.StdinPipe(); err == nil {
		if p.Stdout, err = session.StdoutPipe(); err == nil {
			if p.Stderr, err = session.StderrPipe(); err == nil {
				err = session.Start(command)
			}
		}
	}
	if err != nil {
		p.Close()
		return nil, err
	}
	return p, nil
}

// Wait 等命令结束，返回它的退出码；连接断了、通道被关掉而没有退出码的是 ConnectionFailed。
func (p *Process) Wait() int {
	err := p.session.Wait()
	p.done()
	var exit *ssh.ExitError
	switch {
	case err == nil:
		return 0
	case errors.As(err, &exit):
		return exit.ExitStatus()
	}
	return ConnectionFailed
}

// Close 关掉通道：那边的命令读到输入结束、写不出去就会退出（托管的 Claude Code 进程不受影响）。
func (p *Process) Close() {
	_ = p.session.Close()
	p.done()
}

func (p *Process) done() { p.once.Do(p.release) }

// Result 是跑完一个命令收齐的输出。
type Result struct {
	Code     int
	Stdout   string
	Stderr   string
	TimedOut bool // 到时候还没结束，已经关掉了
}

// Capture 把 stdin 写进标准输入后关掉，收齐输出等它结束；到 timeout 还没结束就关掉通道，结果标成超时。
func (p *Process) Capture(stdin []byte, timeout time.Duration) Result {
	var stdout, stderr bytes.Buffer
	var wg sync.WaitGroup
	wg.Add(3)
	go func() { defer wg.Done(); _, _ = io.Copy(&stdout, p.Stdout) }()
	go func() { defer wg.Done(); _, _ = io.Copy(&stderr, p.Stderr) }()
	go func() {
		defer wg.Done()
		if len(stdin) > 0 {
			_, _ = p.Stdin.Write(stdin)
		}
		_ = p.Stdin.Close()
	}()
	code := make(chan int, 1)
	go func() { wg.Wait(); code <- p.Wait() }()
	select {
	case c := <-code:
		return Result{Code: c, Stdout: stdout.String(), Stderr: stderr.String()}
	case <-time.After(timeout):
		p.Close()
		<-code
		return Result{Code: ConnectionFailed, Stdout: stdout.String(), Stderr: stderr.String(), TimedOut: true}
	}
}
