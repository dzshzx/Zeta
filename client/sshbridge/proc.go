package sshbridge

import (
	"context"
	"errors"
	"io"
	"sync"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
)

// remoteCommand 是交给那边用户 shell 的一行（登录 shell 与否、Zeta 的目录名、时区），和 Windows 版同一份。
var remoteCommand = hostlink.RemoteCommand

// proc 是应用起的一个远程命令（编号 id 由应用给）：先连接、开通道，再把输出按额度（Window）送给应用，输入按到达的先后写进通道。
type proc struct {
	s      *server
	id     int64
	ctx    context.Context
	cancel context.CancelFunc

	ready   chan struct{} // 开出通道或没开成时关掉
	started *sshlink.Process
	failed  error

	stdin chan request // 排着要写的输入（stdin 请求）
	out   [2]*credit   // stdout、stderr 的额度

	mu     sync.Mutex
	closed bool // 应用要结束它了：输出不再送，读掉扔了
}

var streams = [2]string{"stdout", "stderr"}

// start 起一个进程：在自己的 goroutine 上连接、开通道、送输出，结束时发 exit。
func (s *server) start(r request) {
	ctx, cancel := context.WithCancel(context.Background())
	p := &proc{s: s, id: r.P, ctx: ctx, cancel: cancel, ready: make(chan struct{}), stdin: make(chan request, stdinQueue)}
	for i := range p.out {
		p.out[i] = newCredit(Window)
	}
	s.mu.Lock()
	if s.procs[r.P] != nil {
		s.mu.Unlock()
		cancel()
		_ = s.send(exitMsg{Type: "exit", P: r.P, Code: sshlink.ConnectionFailed, Problem: "进程编号重复了"})
		return
	}
	s.procs[r.P] = p
	s.mu.Unlock()
	host := sshlink.Host{ID: r.Host.ID, Target: r.Host.Target, Via: sshlink.ViaDirect, HostKey: r.Host.HostKey}
	go p.writeInput()
	go p.run(host, remoteCommand(r.Command, r.Name, r.TZ, r.Login))
}

func (s *server) proc(id int64) *proc {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.procs[id]
}

// stdin 把一条输入交给那个进程排队；进程已经结束的答出错。
func (s *server) stdin(r request) {
	p := s.proc(r.P)
	if p == nil {
		if !r.EOF {
			_ = s.send(wroteMsg{Type: "wrote", P: r.P, Error: "进程已经结束"})
		}
		return
	}
	select {
	case p.stdin <- r:
	default:
		if !r.EOF {
			_ = s.send(wroteMsg{Type: "wrote", P: r.P, Error: "输入排得太多（要等 wrote 再写下一条）"})
		}
	}
}

// run 连接、开通道，送完输出、等到退出码后发 exit。连不上的退出码是 255，原因在 problem 里。
func (p *proc) run(h sshlink.Host, command string) {
	started, err := p.s.links.StartContext(p.ctx, h, command)
	p.started, p.failed = started, err
	close(p.ready)
	if err != nil {
		p.finish(sshlink.ConnectionFailed, sshlink.Describe(err))
		return
	}
	// 开通道的同时被要求结束了：马上关掉
	p.mu.Lock()
	closed := p.closed
	p.mu.Unlock()
	if closed {
		started.Close()
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); p.pump(0, started.Stdout) }()
	go func() { defer wg.Done(); p.pump(1, started.Stderr) }()
	wg.Wait()
	p.finish(started.Wait(), "")
}

// finish 发 exit，从表里拿掉。
func (p *proc) finish(code int, problem string) {
	p.cancel()
	p.s.mu.Lock()
	delete(p.s.procs, p.id)
	p.s.mu.Unlock()
	if errors.Is(p.failed, context.Canceled) {
		problem = "已经结束"
	}
	_ = p.s.send(exitMsg{Type: "exit", P: p.id, Code: code, Problem: problem})
}

// pump 把一路输出送给应用：有额度才读，读到多少送多少；应用要结束它以后读掉扔了。
func (p *proc) pump(i int, r io.Reader) {
	buf := make([]byte, chunk)
	for {
		n := p.out[i].wait(len(buf))
		if n == 0 { // 要结束了：剩下的不送
			_, _ = io.Copy(io.Discard, r)
			return
		}
		k, err := r.Read(buf[:n])
		if k > 0 {
			p.out[i].use(k)
			_ = p.s.send(outMsg{Type: "out", P: p.id, Stream: streams[i], Data: buf[:k]})
		}
		if err != nil {
			return
		}
	}
}

// ack：应用读掉了 n 字节，那一路可以再送这么多。
func (p *proc) ack(stream string, n int) {
	for i, name := range streams {
		if name == stream && n > 0 {
			p.out[i].give(n)
		}
	}
}

// close：应用要结束它。还在连的不连了；开着的通道关掉（那边的命令读到输入结束、写不出去就会退出），之后发 exit。
func (p *proc) close() {
	p.mu.Lock()
	p.closed = true
	p.mu.Unlock()
	p.cancel()
	for _, c := range p.out {
		c.stop()
	}
	select {
	case <-p.ready:
		if p.started != nil {
			p.started.Close()
		}
	default: // 还在连：run 连上后看到 closed 自己关
	}
}

// writeInput 按先后把输入写进通道，每条写完（或写不进）答一条 wrote；输入结束（eof）时关掉通道的输入，不答。
func (p *proc) writeInput() {
	for {
		var r request
		select {
		case r = <-p.stdin:
		case <-p.ctx.Done():
			p.rejectQueued()
			return
		}
		<-p.ready
		if p.started == nil {
			p.reply(r, errors.New("没有连上"))
			continue
		}
		if r.EOF {
			_ = p.started.Stdin.Close()
			continue
		}
		_, err := p.started.Stdin.Write(r.Data)
		p.reply(r, err)
	}
}

// rejectQueued：进程结束了，排着的输入都答出错。
func (p *proc) rejectQueued() {
	for {
		select {
		case r := <-p.stdin:
			p.reply(r, errors.New("进程已经结束"))
		default:
			return
		}
	}
}

func (p *proc) reply(r request, err error) {
	if r.EOF {
		return
	}
	msg := wroteMsg{Type: "wrote", P: p.id}
	if err != nil {
		msg.Error = "写不进去：" + err.Error()
	}
	_ = p.s.send(msg)
}

// credit 是一路输出还能送多少字节。
type credit struct {
	mu      sync.Mutex
	cond    *sync.Cond
	left    int
	stopped bool
}

func newCredit(n int) *credit {
	c := &credit{left: n}
	c.cond = sync.NewCond(&c.mu)
	return c
}

// wait 等到有额度，返回这次最多读多少（不超过 max）；stop 以后返回 0。
func (c *credit) wait(max int) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	for c.left == 0 && !c.stopped {
		c.cond.Wait()
	}
	if c.stopped {
		return 0
	}
	return min(c.left, max)
}

func (c *credit) use(n int) {
	c.mu.Lock()
	c.left -= n
	c.mu.Unlock()
}

func (c *credit) give(n int) {
	c.mu.Lock()
	c.left += n
	c.mu.Unlock()
	c.cond.Broadcast()
}

func (c *credit) stop() {
	c.mu.Lock()
	c.stopped = true
	c.mu.Unlock()
	c.cond.Broadcast()
}
