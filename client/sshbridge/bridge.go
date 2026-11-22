// Package sshbridge 是 Android 版直连远程主机的那一层：应用（Kotlin）起一个 zeta-ssh 子进程（client/cmd/zeta-ssh），
// 经它的标准输入输出说话，连接、通道、闲置断开、主机指纹的核对、出错的说法都在这里（用 sshlink、hostlink），
// 应用只做 Android 原生的几件事：用 Keystore 里的私钥签名、经系统解析域名，以及把进程的输入输出换成 Java 的 Process。
// 协议见 docs/core.md「Android 直连（zeta-ssh）」。
package sshbridge

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// Protocol 是协议的版本，招呼里带着：改了已有消息的含义时加一。程序和应用在同一个 APK 里，版本总是对得上，这只是给人看的。
const Protocol = 1

const (
	// Window 是每个进程的每路输出（stdout、stderr）最多发出去、还没被应用确认读掉的字节数：应用不读时这边不再读通道，
	// SSH 的窗口满了，那边的命令就停下来等，不会在应用里越堆越多，也不挡别的进程。
	Window = 256 * 1024
	// chunk 是一条 out 消息最多带多少字节。
	chunk = 32 * 1024
	// callWait 是等应用答一次回调（取公钥、签名、解析域名）最多多久。
	callWait = 30 * time.Second
	// stdinQueue 是一个进程排着没写的输入最多几条：应用每写一条等到 wrote 才写下一条，多了是用错了协议。
	stdinQueue = 8
)

// Options 是 Run 的设置。
type Options struct {
	// Idle：一台主机的最后一个通道关掉后过这么久断开连接；0 时一分钟。
	Idle time.Duration
}

// Run 读 in 里应用发来的请求、往 out 写消息，直到 in 结束（应用关掉了管道或退出了），这时断开所有连接再返回。
func Run(in io.Reader, out io.Writer, opts Options) error {
	if opts.Idle == 0 {
		opts.Idle = time.Minute
	}
	s := &server{out: out, procs: map[int64]*proc{}, calls: map[int64]chan answer{}}
	s.links = sshlink.NewLinks(appAuth{s}, opts.Idle)
	s.links.SetDial(s.dial)
	defer s.links.DropAll()
	if err := s.send(hello{Type: "hello", Protocol: Protocol, Window: Window}); err != nil {
		return err
	}
	scanner := bufio.NewScanner(in)
	// 一行最大：stdin 一条带 chunk 字节的 base64，再加上 start 里的命令；留足余量
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
	for scanner.Scan() {
		var r request
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			continue // 坏的一行跳过，不因此断开
		}
		s.handle(r)
	}
	s.failCalls()
	return scanner.Err()
}

// request 是应用发来的一行：method 决定用哪些字段，见 docs/core.md。
type request struct {
	Method string `json:"method"`
	ID     int64  `json:"id"`
	P      int64  `json:"p"`

	// start
	Host    hostSpec `json:"host"`
	Command string   `json:"command"`
	Login   bool     `json:"login"`
	Name    string   `json:"name"`
	TZ      string   `json:"tz"`

	// stdin、answer（data 是 base64）
	Data []byte `json:"data"`
	EOF  bool   `json:"eof"`

	// ack
	Stream string `json:"stream"`
	N      int    `json:"n"`

	// probe
	Target    string `json:"target"`
	HostKey   string `json:"host_key"`
	TimeoutMS int64  `json:"timeout_ms"`

	// retain
	IDs []string `json:"ids"`

	// answer
	Addrs []string `json:"addrs"`
	Error string   `json:"error"`
}

// hostSpec 是 start 里的那台主机。
type hostSpec struct {
	ID      string `json:"id"`
	Target  string `json:"target"`
	HostKey string `json:"host_key"`
}

type hello struct {
	Type     string `json:"type"`
	Protocol int    `json:"protocol"`
	Window   int    `json:"window"`
}

type outMsg struct {
	Type   string `json:"type"`
	P      int64  `json:"p"`
	Stream string `json:"stream"`
	Data   []byte `json:"data"`
}

type wroteMsg struct {
	Type  string `json:"type"`
	P     int64  `json:"p"`
	Error string `json:"error,omitempty"`
}

type exitMsg struct {
	Type    string `json:"type"`
	P       int64  `json:"p"`
	Code    int    `json:"code"`
	Problem string `json:"problem,omitempty"`
}

type probeReply struct {
	Type     string `json:"type"`
	ID       int64  `json:"id"`
	HostKey  string `json:"host_key,omitempty"`
	Changed  bool   `json:"changed,omitempty"`
	Problem  string `json:"problem,omitempty"`
	Code     int    `json:"code"`
	Stdout   string `json:"stdout"`
	Stderr   string `json:"stderr"`
	TimedOut bool   `json:"timed_out,omitempty"`
}

type server struct {
	links *sshlink.Links

	writing sync.Mutex
	out     io.Writer

	mu     sync.Mutex
	procs  map[int64]*proc
	calls  map[int64]chan answer
	nextID int64
	key    *appSigner // 取到过的公钥（每个进程只取一次）
}

// send 把 v 编成一行写给应用，几个 goroutine 都会写，整行写。
func (s *server) send(v any) error {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return err
	}
	s.writing.Lock()
	defer s.writing.Unlock()
	_, err := s.out.Write(b.Bytes())
	return err
}

// handle 分派一行请求。都不在这里等：要等的（连接、跑命令、写输入）各在自己的 goroutine 上做，读请求的这一路不停。
func (s *server) handle(r request) {
	switch r.Method {
	case "start":
		s.start(r)
	case "stdin":
		s.stdin(r)
	case "ack":
		if p := s.proc(r.P); p != nil {
			p.ack(r.Stream, r.N)
		}
	case "close":
		if p := s.proc(r.P); p != nil {
			p.close()
		}
	case "probe":
		go s.probe(r)
	case "retain":
		go s.links.Retain(r.IDs)
	case "drop_all":
		go s.links.DropAll()
	case "answer":
		s.answer(r)
	}
}

// probe 连一次跑命令（加设备、检查连接），答 reply。命令在那边的登录 shell 里跑。
func (s *server) probe(r request) {
	timeout := time.Duration(r.TimeoutMS) * time.Millisecond
	if timeout <= 0 {
		timeout = 45 * time.Second
	}
	seen, res, err := s.links.Probe(r.Target, "", r.HostKey, remoteCommand(r.Command, r.Name, r.TZ, true), timeout)
	reply := probeReply{Type: "reply", ID: r.ID, HostKey: seen}
	var changed *sshlink.HostKeyChangedError
	switch {
	case errors.As(err, &changed):
		reply.Changed, reply.HostKey, reply.Problem = true, changed.Seen, changed.Error()
	case err != nil:
		reply.Problem = sshlink.Describe(err)
	default:
		reply.Code, reply.Stdout, reply.Stderr, reply.TimedOut = res.Code, res.Stdout, res.Stderr, res.TimedOut
	}
	_ = s.send(reply)
}
