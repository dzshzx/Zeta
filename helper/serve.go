package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"
)

// serve：常驻连接。客户端（Zeta 的手机版、桌面版）起一个 zeta-helper serve，经它的标准输入输出说话，
// 不用为每个操作各起一次命令；远程主机上是一条 SSH 通道，手机本机是进 Debian 的那条管道，不开端口。
//
// 协议一行一个 JSON。连上先收到 {"type":"zeta_hello","protocol":<版本>}。客户端发
// {"id":<任意 JSON 值>,"method":"<方法>"}，每个请求都回一行 {"type":"zeta_reply","id":<原样>,"ok":true}
// 或 {"type":"zeta_reply","id":<原样>,"ok":false,"error":"<原因>"}。方法：
//
//	ping            什么也不做：客户端隔一会儿发一次，证明自己还在
//	sessions.watch  订阅会话列表：先发一行整份的 zeta_sessions（full 为真），之后会话目录有变化时只发变了的几项
//	                （见 sessionWatch）；再发一次就再给一份整份的
//
// 进程活多久由客户端定：标准输入结束（客户端关掉、SSH 断开）就退出；网络断了而输入没结束时，
// 客户端不再发 ping，idle 这么久没收到任何一行也退出，不在主机上留下没人用的进程。
// 每个客户端各起一个，彼此不共享状态，所以手机正式版、测试版和桌面版各用各的版本，互不影响。

// serveProtocol 是协议的版本：改了已有消息的含义时加一，只加新方法、新字段不加。
const serveProtocol = 1

type serveOptions struct {
	idle     time.Duration // 这么久没收到客户端的任何一行就退出
	debounce time.Duration // 会话目录变了以后等这么久再重读，其间的变化合成一次
	poll     time.Duration // 盯不了目录（inotify 用不了）、或要盯的目录还没有时，隔这么久重读一次
}

var defaultServeOptions = serveOptions{idle: 90 * time.Second, debounce: 2 * time.Second, poll: 10 * time.Second}

// serveRequest 是客户端发来的一行。
type serveRequest struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
}

// serveOut 把消息一行行写给客户端：几个 goroutine 都会写，每行写完马上送出。写不出去（客户端没了）时关掉 broken。
type serveOut struct {
	mu     sync.Mutex
	w      *bufio.Writer
	enc    *json.Encoder
	broken chan struct{}
	failed bool
}

func newServeOut(w io.Writer) *serveOut {
	b := bufio.NewWriter(w)
	enc := json.NewEncoder(b)
	enc.SetEscapeHTML(false)
	return &serveOut{w: b, enc: enc, broken: make(chan struct{})}
}

func (o *serveOut) send(v any) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.failed {
		return errServeBroken
	}
	err := o.enc.Encode(v)
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

// cmdServe 跑到客户端走了（输入结束、写不出去、idle 内没有消息）为止，那时返回 nil。
func cmdServe(e *env, opts serveOptions, in io.Reader, out io.Writer) error {
	o := newServeOut(out)
	if err := o.send(obj{"type": "zeta_hello", "protocol": serveProtocol}); err != nil {
		return nil
	}
	lines := make(chan []byte)
	go readRequests(in, lines)
	var watch *sessionWatch
	defer func() {
		if watch != nil {
			watch.close()
		}
	}()
	idle := time.NewTimer(opts.idle)
	defer idle.Stop()
	for {
		select {
		case line, ok := <-lines:
			if !ok {
				return nil
			}
			idle.Reset(opts.idle)
			watch = handleRequest(e, opts, o, watch, line)
		case <-idle.C:
			return nil
		case <-o.broken:
			return nil
		}
	}
}

// readRequests 把输入一行行交给 lines，输入结束（或出错）时关掉它。
func readRequests(in io.Reader, lines chan<- []byte) {
	defer close(lines)
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		lines <- append([]byte(nil), sc.Bytes()...)
	}
}

// handleRequest 处理一个请求，返回（可能刚开始的）会话列表订阅。
func handleRequest(e *env, opts serveOptions, o *serveOut, watch *sessionWatch, line []byte) *sessionWatch {
	var req serveRequest
	if err := json.Unmarshal(line, &req); err != nil {
		o.reply(nil, errors.New("not a JSON request"))
		return watch
	}
	switch req.Method {
	case "ping":
		o.reply(req.ID, nil)
	case "sessions.watch":
		if watch == nil {
			watch = startSessionWatch(e, opts, o.send)
		} else {
			watch.resend()
		}
		o.reply(req.ID, nil)
	default:
		o.reply(req.ID, errors.New("unknown method: "+req.Method))
	}
	return watch
}
