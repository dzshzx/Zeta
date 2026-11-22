package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/core"
)

// serve：常驻连接。客户端（Zeta 的手机版、桌面版）起一个 zeta-helper serve，经它的标准输入输出说话，
// 不用为每个操作各起一次命令；远程主机上是一条 SSH 通道，手机本机是进 Debian 的那条管道，不开端口。
//
// 协议一行一个 JSON。连上先收到 {"type":"zeta_hello","protocol":<版本>}。客户端发
// {"id":<任意 JSON 值>,"method":"<方法>"}，每个请求都回一行 {"type":"zeta_reply","id":<原样>,"ok":true}
// 或 {"type":"zeta_reply","id":<原样>,"ok":false,"error":"<原因>"}。serve 自己处理的方法：
//
//	ping            什么也不做：客户端隔一会儿发一次，证明自己还在
//	sessions.watch  订阅会话列表：先发一行整份的 zeta_sessions（full 为真），之后会话目录有变化时只发变了的几项
//	                （见 sessionWatch）；再发一次就再给一份整份的
//
// accounts. 和 quota. 开头的方法（账号与额度，见 docs/core.md「账号与额度」、serve_accounts.go）在各自的 goroutine 里做，做完再答。
//
// conv. 开头的方法（对话，见 docs/core.md「对话」）转给这个版本的核心进程 zeta-helper core（helper/core）：
// 第一次收到时连上它的套接字（~/.cache/<ZETA_NAME>/core/<版本>.sock），没人在听就拉起一个、等它听上（约 5 秒为限），
// 连不上时这个请求答出错，下一个 conv. 请求再试。请求原样转过去，核心的应答（可能带 result）和推送原样写回客户端，
// 和 serve 自己的消息一样一行一行整行写。核心的连接断了（核心退出、崩溃）时 serve 跟着退出，客户端重连、重新打开对话。
//
// 进程活多久由客户端定：标准输入结束（客户端关掉、SSH 断开）就退出；网络断了而输入没结束时，
// 客户端不再发 ping，idle 这么久没收到任何一行也退出，不在主机上留下没人用的进程。
// 每个客户端各起一个 serve；对话的状态在核心里，同一个包、同一个 helper 版本的各个 serve 共用一个核心。

// serveProtocol 是协议的版本：改了已有消息的含义时加一，只加新方法、新字段不加。
const serveProtocol = 1

type serveOptions struct {
	idle     time.Duration // 这么久没收到客户端的任何一行就退出
	debounce time.Duration // 会话目录变了以后等这么久再重读，其间的变化合成一次
	poll     time.Duration // 盯不了目录（inotify 用不了）、或要盯的目录还没有时，隔这么久重读一次
}

var defaultServeOptions = serveOptions{idle: 90 * time.Second, debounce: 2 * time.Second, poll: 10 * time.Second}

// coreWriteTimeout：往核心写一行这么久还没写完，就当核心没了。
const coreWriteTimeout = 30 * time.Second

// serveRequest 是客户端发来的一行。
type serveRequest struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
}

// serveOut 把消息一行行写给客户端：几个 goroutine 都会写，每行写完马上送出。写不出去（客户端没了）时关掉 broken。
type serveOut struct {
	mu     sync.Mutex
	w      *bufio.Writer
	broken chan struct{}
	failed bool
}

func newServeOut(w io.Writer) *serveOut {
	return &serveOut{w: bufio.NewWriter(w), broken: make(chan struct{})}
}

// send 把 v 编成一行写出去，不转义 HTML 字符。
func (o *serveOut) send(v any) error {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return err
	}
	return o.sendLine(b.Bytes())
}

// sendLine 原样写出一整行（以换行结尾）。
func (o *serveOut) sendLine(line []byte) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.failed {
		return errServeBroken
	}
	_, err := o.w.Write(line)
	if err == nil {
		err = o.w.Flush()
	}
	if err != nil {
		o.failed = true
		close(o.broken)
	}
	return err
}

var errServeBroken = errors.New("client gone")

func (o *serveOut) reply(id json.RawMessage, err error) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	m := obj{"type": "zeta_reply", "id": id, "ok": err == nil}
	if err != nil {
		m["error"] = err.Error()
	}
	_ = o.send(m)
}

// serveSession 是一个客户端的这条连接：会话列表的订阅、到核心的连接都是用到时才开始。
type serveSession struct {
	e     *env
	opts  serveOptions
	o     *serveOut
	watch *sessionWatch
	core  *coreLink
}

// cmdServe 跑到客户端走了（输入结束、写不出去、idle 内没有消息）或核心的连接断了为止，那时返回 nil。
func cmdServe(e *env, opts serveOptions, in io.Reader, out io.Writer) error {
	s := &serveSession{e: e, opts: opts, o: newServeOut(out)}
	if err := s.o.send(obj{"type": "zeta_hello", "protocol": serveProtocol}); err != nil {
		return nil
	}
	defer s.close()
	lines := make(chan []byte)
	go readRequests(in, lines)
	idle := time.NewTimer(opts.idle)
	defer idle.Stop()
	for {
		select {
		case line, ok := <-lines:
			if !ok {
				return nil
			}
			idle.Reset(opts.idle)
			if !s.handle(line) {
				return nil
			}
		case <-s.coreGone():
			return nil
		case <-idle.C:
			return nil
		case <-s.o.broken:
			return nil
		}
	}
}

func (s *serveSession) close() {
	if s.watch != nil {
		s.watch.close()
	}
	if s.core != nil {
		s.core.close()
	}
}

// coreGone 在核心的连接断了时可读；还没连核心时是 nil，永远不可读。
func (s *serveSession) coreGone() <-chan struct{} {
	if s.core == nil {
		return nil
	}
	return s.core.gone
}

// readRequests 把输入一行行交给 lines，输入结束（或出错）时关掉它。一行最长 core.MaxLine（发消息时的图片也在行里）。
func readRequests(in io.Reader, lines chan<- []byte) {
	defer close(lines)
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64*1024), core.MaxLine)
	for sc.Scan() {
		lines <- append([]byte(nil), sc.Bytes()...)
	}
}

// handle 处理一个请求；返回 false 时 serve 该退出了（核心的连接断了）。
func (s *serveSession) handle(line []byte) bool {
	var req serveRequest
	if err := json.Unmarshal(line, &req); err != nil {
		s.o.reply(nil, errors.New("not a JSON request"))
		return true
	}
	switch {
	case req.Method == "ping":
		s.o.reply(req.ID, nil)
	case req.Method == "sessions.watch":
		if s.watch == nil {
			s.watch = startSessionWatch(s.e, s.opts, s.o.send)
		} else {
			s.watch.resend()
		}
		s.o.reply(req.ID, nil)
	case strings.HasPrefix(req.Method, "conv."):
		return s.forward(req.ID, line)
	case strings.HasPrefix(req.Method, "accounts."), strings.HasPrefix(req.Method, "quota."):
		go s.answerAccounts(req.ID, req.Method, line) // 慢（要跑 claude-switch），不挡后面的 ping
	default:
		s.o.reply(req.ID, errors.New("unknown method: "+req.Method))
	}
	return true
}

// forward 把一行原样转给核心，还没连就先连上（必要时拉起它）。连不上时答出错；写不进去说明核心没了，返回 false。
func (s *serveSession) forward(id json.RawMessage, line []byte) bool {
	if s.core == nil {
		link, err := dialCore(s.e, s.o)
		if err != nil {
			s.o.reply(id, errors.New("core unavailable: "+err.Error()))
			return true
		}
		s.core = link
	}
	return s.core.write(line) == nil
}

// coreLink 是 serve 到核心的连接：核心发来的每一行原样写给客户端。
type coreLink struct {
	conn net.Conn
	gone chan struct{} // 核心的连接断了（读不到了）或客户端写不进去时关掉
}

// dialCore 连上和本程序同一版本的核心（套接字路径见 core.Locate），没人在听就拉起一个。
func dialCore(e *env, o *serveOut) (*coreLink, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	p, err := core.Locate(e.home, e.name, exe)
	if err != nil {
		return nil, err
	}
	conn, err := core.Connect(p, exe)
	if err != nil {
		return nil, err
	}
	l := &coreLink{conn: conn, gone: make(chan struct{})}
	go l.relay(o)
	return l, nil
}

// relay 把核心发来的一行行原样写给客户端，直到核心的连接断了或客户端写不进去。
func (l *coreLink) relay(o *serveOut) {
	defer close(l.gone)
	r := bufio.NewReaderSize(l.conn, 64*1024)
	for {
		line, err := r.ReadBytes('\n')
		if err != nil { // 断了；没写完的半行丢掉
			return
		}
		if o.sendLine(line) != nil {
			return
		}
	}
}

func (l *coreLink) write(line []byte) error {
	_ = l.conn.SetWriteDeadline(time.Now().Add(coreWriteTimeout))
	_, err := l.conn.Write(append(line, '\n'))
	return err
}

func (l *coreLink) close() { _ = l.conn.Close() }
