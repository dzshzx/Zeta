package host

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/remote"
)

// Protocol 和 helper/serve.go 的 serveProtocol 一致。
const Protocol = 1

// PingEvery 是多久发一次 ping：比那边的 90 秒短得多，丢一两次也不至于被当成走了。
const PingEvery = 30 * time.Second

// ServeOptions 是一条到 zeta-helper serve 的连接要的东西。
type ServeOptions struct {
	// Open 起 serve 进程；起不来时的错误是给人看的原因。
	Open func() (*remote.Process, error)
	// Subscriptions 是连上、收到招呼后各发一次的方法（重连后也再发一遍，所以先收到的总是整份）。
	Subscriptions []string
	// Message 收到推来的每一行（type 不是 zeta_reply 的），在读的 goroutine 上调用。
	Message func(kind string, line []byte)
	// Problem 每次连上（空）或断开、连不上（给人看的原因）时调用一次，在读的 goroutine 上。
	Problem func(why string)
	// Backoff 是第 attempt 次重连前等多久；nil 时照 ReconnectDelay。
	Backoff func(attempt int) time.Duration
	// Ping 是多久发一次 ping；0 时是 PingEvery。
	Ping time.Duration
}

// Serve 是到一台设备上 zeta-helper serve 的常驻连接（协议见 helper/serve.go），不为每个操作各起一次命令。
// 新建后就在自己的 goroutine 上连：连上过以后断了（网络、那边的进程没了）隔一会儿重连，重连后照样再订阅一遍；
// 第一次就没连上的不重试，报了原因就停（同 Kotlin 的 HelperServe）。Close 以后不再重连、不再回调。
type Serve struct {
	opts ServeOptions

	mu      sync.Mutex
	closed  bool
	current *remote.Process
	nextID  int64
	writing sync.Mutex // 写请求时拿着（不和 mu 合用：写卡住时 Close 不跟着卡）
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
	s := &Serve{opts: opts, stop: make(chan struct{})}
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
	return nil
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
	if code == remote.ConnectionFailed {
		why = "连接断了：" + why
	}
	return connected, why
}

// read 一行行读推来的消息：先等招呼，对上了就订阅、开始 ping，之后的交给 Message。对不上协议时返回原因。
func (s *Serve) read(p *remote.Process, done <-chan struct{}, connected *bool) (refused string) {
	sc := bufio.NewScanner(p.Stdout)
	sc.Buffer(make([]byte, 64*1024), 64*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		var head struct {
			Type     string `json:"type"`
			Protocol int    `json:"protocol"`
			OK       *bool  `json:"ok"`
			Error    string `json:"error"`
		}
		if json.Unmarshal(line, &head) != nil {
			continue
		}
		switch {
		case *connected && head.Type == "zeta_reply":
			// 只有订阅这类请求才会答 false：那边不认这个方法，是两边的版本对不上
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
			s.opts.Problem("")
		default:
			return clip("zeta-helper serve 的协议对不上："+string(line), 300)
		}
	}
	return ""
}

// send 发一个请求；写不出去时不管，读那边会发现断了。
func (s *Serve) send(p *remote.Process, method string) {
	s.writing.Lock()
	defer s.writing.Unlock()
	s.nextID++
	line, _ := json.Marshal(map[string]any{"id": s.nextID, "method": method})
	_, _ = p.Stdin.Write(append(line, '\n'))
}

// pings 每隔一会儿发一次 ping，直到这次连接结束。
func (s *Serve) pings(p *remote.Process, done <-chan struct{}) {
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
func OpenServe(shell Shell, program *Program) (*remote.Process, error) {
	r, err := program.Ensure(shell.plainExec(time.Minute))
	if err != nil {
		return nil, errors.New("连不上「" + shell.Host.Name() + "」：" + err.Error())
	}
	if problem := shell.LinkProblem(r); problem != "" {
		return nil, errors.New(problem)
	}
	if r.Code != 0 {
		return nil, errors.New(orDefault(tail(r.Stderr, 300), program.Name+" 没装上"))
	}
	p, err := shell.StartPlain(program.Command("serve"))
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
