package hostlink

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// Protocol 和 helper/serve.go 的 serveProtocol 一致。
const Protocol = 1

// PingEvery 是多久发一次 ping：比那边的 90 秒短得多，丢一两次也不至于被当成走了。
const PingEvery = 30 * time.Second

// ServeOptions 是一条到 zeta-helper serve 的连接要的东西。
type ServeOptions struct {
	// Open 起 serve 进程；起不来时的错误是给人看的原因。
	Open func() (*sshlink.Process, error)
	// Subscriptions 是连上、收到招呼后各发一次的方法（重连后也再发一遍，所以先收到的总是整份）。
	Subscriptions []string
	// Hello 每次连上（收到对得上协议的 zeta_hello）时调，在读的 goroutine 上、Problem 报连上之前；可以是 nil。
	Hello func(Hello)
	// Message 收到推来的每一行（type 不是 zeta_reply 的），在读的 goroutine 上调用。
	Message func(kind string, line []byte)
	// Problem 每次连上（空）或断开、连不上（给人看的原因）时调用一次，在读的 goroutine 上。
	Problem func(why string)
	// Backoff 是第 attempt 次重连前等多久；nil 时照 ReconnectDelay。
	Backoff func(attempt int) time.Duration
	// Ping 是多久发一次 ping；0 时是 PingEvery。
	Ping time.Duration
}

// Hello 是 serve 连上时先发来的招呼（zeta_hello）里客户端要用的。
type Hello struct {
	// Hostname 是那台的主机名（同 uname -n）；查不到时是空。
	Hostname string `json:"hostname"`
}

// Serve 是到一台设备上 zeta-helper serve 的常驻连接（协议见 helper/serve.go），不为每个操作各起一次命令。
// 新建后就在自己的 goroutine 上连：连上过以后断了（网络、那边的进程没了）隔一会儿重连，重连后照样再订阅一遍；
// 第一次就没连上的不重试，报了原因就停（同 Kotlin 的 HelperServe）。Close 以后不再重连、不再回调。
type Serve struct {
	opts ServeOptions

	mu      sync.Mutex
	closed  bool
	current *sshlink.Process
	// conn 是收到招呼、能发请求的那个进程（current 连上以后）；changed 在 conn、ended 变了时关掉、换一个新的。
	conn    *sshlink.Process
	changed chan struct{}
	// ended：不再重连了（Close 了，或第一次就没连上），why 是最后的原因。
	ended   bool
	why     string
	nextID  int64
	pending map[int64]chan reply // 发出去还没收到应答的 Call
	writing sync.Mutex           // 写请求时拿着（不和 mu 合用：写卡住时 Close 不跟着卡）
	stop    chan struct{}
}

// StartServe 开始连。
func StartServe(opts ServeOptions) *Serve {
	if opts.Backoff == nil {
		opts.Backoff = ReconnectDelay
	}
	if opts.Ping == 0 {
		opts.Ping = PingEvery
	}
	s := &Serve{opts: opts, stop: make(chan struct{}), changed: make(chan struct{}), pending: map[int64]chan reply{}}
	go s.loop()
	return s
}

// ReconnectDelay 是第 attempt 次重连前等多久：1、2、4、8 秒，15 秒，之后 30 秒，二十几次以后一分钟（同 Kotlin 版）。
func ReconnectDelay(attempt int) time.Duration {
	switch {
	case attempt <= 0:
		return 0
	case attempt <= 4:
		return time.Second << (attempt - 1)
	case attempt == 5:
		return 15 * time.Second
	case attempt <= 24:
		return 30 * time.Second
	}
	return time.Minute
}

// Close 不再要这条连接：关掉通道（那边的 serve 读到输入结束就退出），之后不再回调。
func (s *Serve) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	close(s.stop)
	p := s.current
	s.current = nil
	s.mu.Unlock()
	if p != nil {
		p.Close()
	}
	s.end("和 zeta-helper serve 的连接已关闭")
	return nil
}

// end：不再重连了，等着连上的 Call 返回 why。
func (s *Serve) end(why string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ended {
		return
	}
	s.ended, s.why = true, why
	s.broadcast()
}

// broadcast 叫醒等连接的 Call。拿着 mu 时调。
func (s *Serve) broadcast() {
	close(s.changed)
	s.changed = make(chan struct{})
}

// reply 是一次 Call 的结果。
type reply struct {
	result json.RawMessage
	err    error
}

// ReplyError 是 serve 答了 ok 为假时 Call 返回的错误（用 errors.As 取）：Error() 是应答里的 error（给人看的原因，没有时是
// 默认的一句），Code() 是应答里的 code，认得出的出错才带（比如 conv.earlier 的 history_changed、账号的 claude_switch_missing，
// 见 docs/core.md），没带时是空。
type ReplyError struct {
	Message string
	code    string
}

func (e *ReplyError) Error() string { return e.Message }

// Code 是应答里的 code，没带时是空。
func (e *ReplyError) Code() string { return e.code }

// Call 发一个请求（params 的各项和 id、method 平级写出）并等它的 zeta_reply：ok 时返回 result（没有时为 nil），
// 否则返回 *ReplyError（serve 给的原因和 code）。还在连（第一次或重连）时等连上；连接在应答之前断了返回错误，不重发（请求可能已经生效）。
// 不再重连了（Close 了，或第一次就没连上）时直接返回最后的原因。不能在 Message、Problem 回调里调：应答要靠那个 goroutine 读。
func (s *Serve) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	p, err := s.connection(ctx)
	if err != nil {
		return nil, err
	}
	ch := make(chan reply, 1)
	id := s.write(p, method, params, ch)
	select {
	case r := <-ch:
		return r.result, r.err
	case <-ctx.Done():
		s.mu.Lock()
		delete(s.pending, id)
		s.mu.Unlock()
		return nil, ctx.Err()
	}
}

// errDropped：请求发出去以后、应答之前连接断了。
var errDropped = errors.New("和 zeta-helper serve 的连接断了，没收到应答")

// connection 等到能发请求的那个进程。
func (s *Serve) connection(ctx context.Context) (*sshlink.Process, error) {
	for {
		s.mu.Lock()
		p, changed, ended, why := s.conn, s.changed, s.ended, s.why
		s.mu.Unlock()
		switch {
		case ended:
			return nil, errors.New(orDefault(why, "和 zeta-helper serve 的连接已关闭"))
		case p != nil:
			return p, nil
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

// connect、disconnect 记下 p 能不能发请求。断开时等着应答的 Call 都返回 errDropped。
func (s *Serve) connect(p *sshlink.Process) {
	s.mu.Lock()
	s.conn = p
	s.broadcast()
	s.mu.Unlock()
}

func (s *Serve) disconnect(p *sshlink.Process) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conn != p {
		return
	}
	s.conn = nil
	for id, ch := range s.pending {
		ch <- reply{err: errDropped}
		delete(s.pending, id)
	}
	s.broadcast()
}

func (s *Serve) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

func (s *Serve) loop() {
	attempt := 0
	everConnected := false
	for {
		if attempt > 0 {
			select {
			case <-s.stop:
				return
			case <-time.After(s.opts.Backoff(attempt)):
			}
		}
		connected, why := s.connectOnce()
		if s.isClosed() {
			return
		}
		s.opts.Problem(why)
		everConnected = everConnected || connected
		// 一次都没连上（地址不对、设备关着）：报了原因就停，等调用方下次要时再新建
		if !everConnected {
			s.end(why)
			return
		}
		if connected {
			attempt = 1
		} else {
			attempt++
		}
	}
}

// connectOnce 连一次，读到断开为止，返回连上过没有和断开的原因。
func (s *Serve) connectOnce() (connected bool, why string) {
	p, err := s.opts.Open()
	if err != nil {
		return false, err.Error()
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		p.Close()
		return false, ""
	}
	s.current = p
	s.mu.Unlock()
	stderr := &tailBuffer{max: 2000}
	errDone := make(chan struct{})
	go func() { _, _ = io.Copy(stderr, p.Stderr); close(errDone) }()
	done := make(chan struct{})
	refused := s.read(p, done, &connected)
	close(done)
	s.disconnect(p)
	s.mu.Lock()
	if s.current == p {
		s.current = nil
	}
	s.mu.Unlock()
	p.Close()
	code := p.Wait()
	<-errDone
	switch {
	case refused != "":
		return connected, refused
	case strings.TrimSpace(stderr.String()) != "":
		why = tail(stderr.String(), 300)
	default:
		why = fmt.Sprintf("和 zeta-helper serve 的连接断了（退出码 %d）", code)
	}
	if code == sshlink.ConnectionFailed {
		why = "连接断了：" + why
	}
	return connected, why
}

// read 一行行读推来的消息：先等招呼，对上了就订阅、开始 ping，之后的交给 Message。对不上协议时返回原因。
func (s *Serve) read(p *sshlink.Process, done <-chan struct{}, connected *bool) (refused string) {
	sc := bufio.NewScanner(p.Stdout)
	sc.Buffer(make([]byte, 64*1024), 64*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		var head struct {
			Type     string          `json:"type"`
			Protocol int             `json:"protocol"`
			ID       json.RawMessage `json:"id"`
			OK       *bool           `json:"ok"`
			Error    string          `json:"error"`
			Code     string          `json:"code"`
			Result   json.RawMessage `json:"result"`
			Hello                    // zeta_hello 里客户端要用的，招呼行只解这一次
		}
		if json.Unmarshal(line, &head) != nil {
			continue
		}
		switch {
		case *connected && head.Type == "zeta_reply":
			if s.answer(head.ID, head.OK != nil && *head.OK, head.Result, head.Error, head.Code) {
				continue
			}
			// 订阅、ping 不等应答：答 false 是那边不认这个方法，两边的版本对不上
			if head.OK != nil && !*head.OK {
				s.opts.Problem(orDefault(head.Error, "zeta-helper serve 拒绝了请求"))
			}
		case *connected:
			s.opts.Message(head.Type, line)
		case head.Type == "zeta_hello" && head.Protocol == Protocol:
			*connected = true
			for _, method := range s.opts.Subscriptions {
				s.send(p, method)
			}
			go s.pings(p, done)
			s.connect(p)
			if s.opts.Hello != nil {
				s.opts.Hello(head.Hello)
			}
			s.opts.Problem("")
		default:
			return clip("zeta-helper serve 的协议对不上："+string(line), 300)
		}
	}
	return ""
}

// answer 把应答交给等着它的 Call；不是 Call 发的（订阅、ping）返回 false。
func (s *Serve) answer(rawID json.RawMessage, ok bool, result json.RawMessage, why, code string) bool {
	id, err := strconv.ParseInt(string(rawID), 10, 64)
	if err != nil {
		return false
	}
	s.mu.Lock()
	ch, waiting := s.pending[id]
	delete(s.pending, id)
	s.mu.Unlock()
	if !waiting {
		return false
	}
	if !ok {
		ch <- reply{err: &ReplyError{Message: orDefault(why, "zeta-helper serve 拒绝了请求"), code: code}}
		return true
	}
	if string(result) == "null" {
		result = nil
	}
	ch <- reply{result: result}
	return true
}

// send 发一个不等应答的请求（订阅、ping）。
func (s *Serve) send(p *sshlink.Process, method string) { s.write(p, method, nil, nil) }

// write 发一个请求，返回它的 id；ch 不为空时应答交给它（p 已经断了时马上交 errDropped）。
// 写不出去时不管，读那边会发现断了，到时 ch 收到 errDropped。
func (s *Serve) write(p *sshlink.Process, method string, params map[string]any, ch chan reply) int64 {
	s.writing.Lock()
	defer s.writing.Unlock()
	s.mu.Lock()
	s.nextID++
	id := s.nextID
	if ch != nil {
		if s.conn != p {
			s.mu.Unlock()
			ch <- reply{err: errDropped}
			return id
		}
		s.pending[id] = ch
	}
	s.mu.Unlock()
	msg := make(map[string]any, len(params)+2)
	maps.Copy(msg, params)
	msg["id"], msg["method"] = id, method
	line, err := json.Marshal(msg)
	if err != nil {
		s.answer(json.RawMessage(strconv.FormatInt(id, 10)), false, nil, "请求写不成 JSON："+err.Error(), "")
		return id
	}
	_, _ = p.Stdin.Write(append(line, '\n'))
	return id
}

// pings 每隔一会儿发一次 ping，直到这次连接结束。
func (s *Serve) pings(p *sshlink.Process, done <-chan struct{}) {
	tick := time.NewTicker(s.opts.Ping)
	defer tick.Stop()
	for {
		select {
		case <-done:
			return
		case <-s.stop:
			return
		case <-tick.C:
			s.send(p, "ping")
		}
	}
}

// OpenServe 在 shell 那台上起 serve：没装 zeta-helper 就先装上。装不上时的错误是给人看的原因。
func OpenServe(shell Shell, program *Program) (*sshlink.Process, error) {
	r, err := program.Ensure(shell.plainExec(time.Minute), shell.Name)
	if err != nil {
		return nil, errors.New("连不上「" + shell.Host.Name() + "」：" + err.Error())
	}
	if problem := shell.LinkProblem(r); problem != "" {
		return nil, errors.New(problem)
	}
	if r.Code != 0 {
		return nil, errors.New(orDefault(tail(r.Stderr, 300), program.Name+" 没装上"))
	}
	p, err := shell.StartPlain(program.Command(shell.Name, "serve"))
	if err != nil {
		return nil, errors.New("连不上「" + shell.Host.Name() + "」：" + err.Error())
	}
	return p, nil
}

// tailBuffer 只留最后 max 个字节。
type tailBuffer struct {
	mu  sync.Mutex
	max int
	b   []byte
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.b = append(t.b, p...)
	if len(t.b) > t.max {
		t.b = t.b[len(t.b)-t.max:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.b)
}

func orDefault(s, fallback string) string {
	if strings.TrimSpace(s) == "" {
		return fallback
	}
	return s
}

func clip(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// ReplyCode 是 serve 答出错时带的 code：err 的链上有带 Code() string 的错误（Call 返回的 *ReplyError 就是）时 ok 为真，
// code 是它的 code（没带时为空）；ok 为假时 err 不是 serve 答的出错（连不上、断了、超时）。
func ReplyCode(err error) (code string, ok bool) {
	var reply interface{ Code() string }
	if errors.As(err, &reply) {
		return reply.Code(), true
	}
	return "", false
}
