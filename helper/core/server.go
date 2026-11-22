// Package core 是常驻核心进程 zeta-helper core 的服务端框架：在 unix 套接字上听，每条连接是一个客户端
// （各客户端起的 zeta-helper serve），一行一个 JSON 的请求交给 Handler，应答和推送整行写回。对话逻辑在 Handler 里，不在这个包。
//
// 每台主机上每个 Linux 用户、每个包（ZETA_NAME）、每个 helper 版本只有一个核心：套接字是
// ~/.cache/<ZETA_NAME>/core/<版本>.sock（见 Locate），同目录的 <版本>.lock 由正在跑的核心持着（flock）。
//
// 请求：{"id":<任意 JSON 值>,"method":"<方法>","conv":"<对话>",<其余参数平级>}。
// 应答：每个请求一行 {"type":"zeta_reply","id":<原样>,"ok":true,"result":<结果，可省>}，
// 出错时 {"type":"zeta_reply","id":<原样>,"ok":false,"error":"<原因>"}。推送由 Handler 经 Client.Send、SendAll 随时发。
// 核心连上时不打招呼：serve 已经给客户端发过 zeta_hello，核心发来的每一行都原样转给客户端。
package core

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"sync"
	"time"
)

// MaxLine 是一行请求最长多少字节（发消息时带的图片也在这一行里）。
const MaxLine = 64 << 20

// writeTimeout：往一个客户端写一行这么久还没写完，就当它走了，免得一个不读的客户端卡住别人。
const writeTimeout = 30 * time.Second

// ErrGone 是客户端已经走了（断开、写不出去）时 Send 的结果。
var ErrGone = errors.New("client gone")

// Request 是客户端发来的一行。
type Request struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Conv   string          `json:"conv"`
	// Raw 是原始的那一行，处理器自己从里面解这个方法的参数。
	Raw json.RawMessage `json:"-"`
}

// Handler 是挂在核心上的处理器（对话逻辑）。
//
// 同一个客户端的请求按到达顺序一个个交给 Request，上一个返回了才交下一个；不同客户端的请求会同时来，处理器自己加锁。
// Request 返回的结果写成应答的 result（nil 时省掉），返回错误时应答 ok:false。Request 里推给这个客户端的消息排在应答前面。
// 客户端断开后调一次 Gone，那时它的 Request 都已返回，往它 Send 只会得到 ErrGone。
// Idle 隔一会儿被调一次（没有客户端连着时），说处理器是不是闲着（比如没有接着的对话）：没有客户端、又闲着满空闲时长，核心就退出。
type Handler interface {
	Request(c *Client, r Request) (any, error)
	Gone(c *Client)
	Idle() bool
}

// Client 是连着核心的一个客户端（一条连接）。可以当 map 的键，几个 goroutine 同时 Send 也是一行一行整行写出。
type Client struct {
	id   uint64
	conn net.Conn

	mu     sync.Mutex
	closed bool
}

// ID 是这个核心里客户端的序号，从 1 起，写日志用。
func (c *Client) ID() uint64 { return c.id }

// Send 把 v 编成一行 JSON 写给这个客户端。客户端已经走了或写不出去时返回 ErrGone（写不出去时顺手断开它，随后 Gone）；
// v 编不成 JSON 时返回编码的错误。
func (c *Client) Send(v any) error {
	line, err := encodeLine(v)
	if err != nil {
		return err
	}
	return c.write(line)
}

// SendAll 把 v 编一次，发给这几个客户端；写不出去的照 Send 的规矩断开。只在 v 编不成 JSON 时返回错误。
func SendAll(clients []*Client, v any) error {
	line, err := encodeLine(v)
	if err != nil {
		return err
	}
	for _, c := range clients {
		_ = c.write(line)
	}
	return nil
}

func (c *Client) write(line []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return ErrGone
	}
	_ = c.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
	if _, err := c.conn.Write(line); err != nil {
		c.closed = true
		_ = c.conn.Close()
		return ErrGone
	}
	return nil
}

// hangUp 断开连接；先关连接，正卡在写上的 Send 随即出错返回，再记下已断开。
func (c *Client) hangUp() {
	_ = c.conn.Close()
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
}

// encodeLine 编成一行 JSON（结尾带换行），不转义 HTML 字符。
func encodeLine(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

type reply struct {
	Type   string          `json:"type"`
	ID     json.RawMessage `json:"id"`
	OK     bool            `json:"ok"`
	Result any             `json:"result,omitempty"`
	Error  string          `json:"error,omitempty"`
}

func (c *Client) reply(id json.RawMessage, result any, err error) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	r := reply{Type: "zeta_reply", ID: id, OK: err == nil, Result: result}
	if err != nil {
		r.Error = err.Error()
	}
	line, err := encodeLine(r)
	if err != nil { // 处理器给的 result 编不成 JSON
		line, _ = encodeLine(reply{Type: "zeta_reply", ID: id, Error: "cannot encode the result: " + err.Error()})
	}
	_ = c.write(line)
}

// Server 是持着锁、在套接字上听着的核心。由 Listen 得到，Serve 跑到退出。
type Server struct {
	paths Paths
	lock  *os.File
	ln    *net.UnixListener

	mu      sync.Mutex
	clients map[*Client]struct{}
	nextID  uint64
	quietAt time.Time // 第一次看到没有客户端、处理器也闲着的时刻；不是这样时为零
	closing bool
	wg      sync.WaitGroup
}

// Listen 建好目录（只有本人能进）、持锁、清掉上一个核心崩溃时留下的套接字文件，在 p.Socket 上听。
// 这个版本已经有核心在听时返回 ErrRunning，调用方直接退出就好。
func Listen(p Paths) (*Server, error) {
	if err := p.makeDir(); err != nil {
		return nil, err
	}
	lock, err := p.acquire()
	if err != nil {
		return nil, err
	}
	// 持着锁时这里的套接字文件不会是别人正用着的
	if err := os.Remove(p.Socket); err != nil && !errors.Is(err, os.ErrNotExist) {
		_ = lock.Close()
		return nil, err
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: p.Socket, Net: "unix"})
	if err != nil {
		_ = lock.Close()
		return nil, err
	}
	return &Server{paths: p, lock: lock, ln: ln, clients: map[*Client]struct{}{}}, nil
}

// Serve 接客户端、把请求交给 h，跑到 ctx 结束（比如收到 SIGTERM），或者没有客户端、h 也闲着满 idle 为止，返回 nil。
// 返回前关掉所有连接（各调一次 Gone）、删掉套接字文件、放开锁。接不了连接时返回那个错误。
func (s *Server) Serve(ctx context.Context, h Handler, idle time.Duration) error {
	defer s.shutdown()
	acceptErr := make(chan error, 1)
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		acceptErr <- s.accept(h)
	}()
	tick := time.NewTicker(min(max(idle/10, 10*time.Millisecond), time.Second))
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-acceptErr:
			return err
		case now := <-tick.C:
			if s.idleSince(h, now) >= idle {
				return nil
			}
		}
	}
}

// idleSince 是没有客户端、处理器也闲着已经多久了：从第一次看到这样算起，有客户端或处理器不闲时清零、返回 0。
// 所以退出总在真正闲下来之后满 idle，不会因为查得稀早退一个间隔。问处理器时不持着 s.mu，免得和处理器自己的锁绕在一起。
func (s *Server) idleSince(h Handler, now time.Time) time.Duration {
	s.mu.Lock()
	connected := len(s.clients) > 0
	s.mu.Unlock()
	idle := !connected && h.Idle()
	s.mu.Lock()
	defer s.mu.Unlock()
	if !idle || len(s.clients) > 0 { // 问的时候刚连上一个也算不闲
		s.quietAt = time.Time{}
		return 0
	}
	if s.quietAt.IsZero() {
		s.quietAt = now
	}
	return now.Sub(s.quietAt)
}

func (s *Server) accept(h Handler) error {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			s.mu.Lock()
			closing := s.closing
			s.mu.Unlock()
			if closing {
				return nil
			}
			return err
		}
		s.mu.Lock()
		if s.closing {
			s.mu.Unlock()
			_ = conn.Close()
			return nil
		}
		s.nextID++
		c := &Client{id: s.nextID, conn: conn}
		s.clients[c] = struct{}{}
		s.wg.Add(1)
		s.mu.Unlock()
		go s.serveClient(h, c)
	}
}

// serveClient 读一个客户端的请求，直到它断开（或核心要退出），然后告诉处理器它走了。
func (s *Server) serveClient(h Handler, c *Client) {
	defer s.wg.Done()
	sc := bufio.NewScanner(c.conn)
	sc.Buffer(make([]byte, 64<<10), MaxLine)
	for sc.Scan() {
		if line := sc.Bytes(); len(bytes.TrimSpace(line)) > 0 {
			handle(h, c, bytes.Clone(line))
		}
	}
	c.hangUp()
	s.mu.Lock()
	delete(s.clients, c)
	s.mu.Unlock()
	h.Gone(c)
}

func handle(h Handler, c *Client, line []byte) {
	var r Request
	if err := json.Unmarshal(line, &r); err != nil {
		c.reply(r.ID, nil, errors.New("not a JSON request: "+err.Error()))
		return
	}
	r.Raw = line
	result, err := h.Request(c, r)
	c.reply(r.ID, result, err)
}

// shutdown 不再接新连接、删掉套接字文件，断开所有客户端并等它们的 Gone 调完，最后放开锁。
func (s *Server) shutdown() {
	s.mu.Lock()
	s.closing = true
	for c := range s.clients {
		_ = c.conn.Close()
	}
	s.mu.Unlock()
	_ = s.ln.Close() // 自己建的套接字文件，关的时候一起删掉
	s.wg.Wait()
	_ = os.Remove(s.paths.Socket)
	_ = s.lock.Close()
}
