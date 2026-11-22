package host

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"
)

// Event 是连接交给调用方的事件：Attached、Line 或 Exit。
type Event interface{ event() }

// Attached：脚本说接上了（zeta_attached）。连接上的第一个事件，之后才有 Line。
type Attached struct {
	// From 是接下来第一行在输出日志里的行号（从 1 起）。
	From int
	// Key 是接上的托管目录的标识。
	Key string
	// Attacher 是脚本里接着的这一方的进程号，Detach 时交回去。
	Attacher int
	// Joined：start 接上的是本包已在跑这个会话的进程，Key 是那个进程的、不是给的那个；
	// StartOptions.OnJoin 已排在别的还没写的消息前面。
	Joined bool
}

// Line 是输出日志里的一行（Claude Code 的 stream-json，一个 JSON 对象，不带换行）。
type Line struct {
	// N 是它在输出日志里的行号（从 1 起）。不是 JSON 对象的行不交出来，行号照样算上。
	N    int
	Data []byte
}

// Exit 是连接的结局，最后一个事件。脚本报了 zeta_exit 时照它，没报就是连接进程自己的退出码和标准错误输出。
type Exit struct {
	// Code 是 Claude Code 的退出码；没有 zeta_exit 时是连接进程的退出码（0 换成 -1：连接没说结局就断了）。
	Code int
	// Stderr 是标准错误输出的最后一段：zeta_exit 时是 Claude Code 的最后约 2000 字节，否则是连接进程的最后约 4000 字。
	Stderr string
	// Reason 是会话去了别处的原因：ReasonReleased 或 ReasonHeldElsewhere，别的结局是空的。
	Reason string
}

func (Attached) event() {}
func (Line) event()     {}
func (Exit) event()     {}

// zeta_exit 的 reason。
const (
	// ReasonReleased：终端里接回去了，zeta-helper 让出会话时结束了这边的进程。
	ReasonReleased = "released"
	// ReasonHeldElsewhere：要接着的会话正被 Zeta 之外的 Claude Code（比如终端里的）占着，脚本没起进程。
	ReasonHeldElsewhere = "held_elsewhere"
)

// stderrKeep 是没有 zeta_exit 时结局里留标准错误输出的最后多少个字。
const stderrKeep = 4000

// Link 是和一个托管进程之间的连接（一个 zeta_host.sh start 或 attach 进程）。
//
// 事件按到达顺序从 Events 出来，调用方在一个 goroutine 里收；连接自己结束时最后一个是 Exit，随后通道关上。
// Send 的消息在接上之后按序整行写进去，接上之前的先存着。Stop、Detach 之后不再收新消息（已经排着的在接上了时照样写完）、
// 不再交出事件，通道在连接进程退出后关上。
type Link struct {
	cmd    *exec.Cmd
	onJoin [][]byte
	stderr tail
	events chan Event

	mu       sync.Mutex
	wake     *sync.Cond // mu 上的：有东西要写、接上了、关了或连接结束了
	key      string
	attacher int
	queue    [][]byte // 还没写的行，各带换行
	attached bool
	closed   bool // 调过 Stop 或 Detach
	ended    bool // 连接进程的标准输出读完了

	// deliverMu 管着 silenced：Stop、Detach 返回之后一定不再交出事件
	deliverMu sync.Mutex
	silenced  bool
	done      chan struct{} // Stop、Detach 时关上，正等着交出的事件随即落空
	closeOnce sync.Once
}

// open 起连接进程 cmd，开读输出、写输入两个 goroutine。
func open(cmd *exec.Cmd, key string, onJoin [][]byte) (*Link, error) {
	l := &Link{cmd: cmd, onJoin: onJoin, events: make(chan Event), key: key, done: make(chan struct{})}
	l.wake = sync.NewCond(&l.mu)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = &l.stderr
	// 脚本留在后台的进程都不接着这两条管道；万一有，不为它一直等
	cmd.WaitDelay = 5 * time.Second
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	go l.write(stdin)
	go l.read(stdout)
	return l, nil
}

// Events 是这个连接的事件。
func (l *Link) Events() <-chan Event { return l.events }

// Key 是现在的托管标识：起的时候是给的那个，start 接上本包现成的进程后是那个进程的。
func (l *Link) Key() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.key
}

// Send 排一行消息（一个 JSON 对象，不带换行），接上后按序整行写进托管进程的输入管道。Stop、Detach 之后、连接结束后不再收。
func (l *Link) Send(line []byte) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed || l.ended {
		return
	}
	l.queue = append(l.queue, withNewline(line))
	l.wake.Signal()
}

// Stop 结束会话并清掉托管目录（zeta_host.sh stop：发 end_session，由 Claude Code 自己退出），别的接着的一方收到正常的 zeta_exit。
// 命令在后台跑，返回的通道在它跑完时关上；脚本自己立即返回，结束进程的等待在它另起的会话里做。
func (l *Link) Stop() <-chan struct{} {
	l.close()
	return runScript("stop", l.Key())
}

// Detach 只断开这边（zeta_host.sh detach）：还有别的接着的一方时进程照常跑，没有时照 Stop 结束。返回的通道同 Stop。
func (l *Link) Detach() <-chan struct{} {
	l.close()
	l.mu.Lock()
	args := []string{"detach", l.key}
	if l.attacher > 0 {
		args = append(args, strconv.Itoa(l.attacher))
	}
	l.mu.Unlock()
	return runScript(args...)
}

// Leave 只关掉这条连接：不跑脚本的 detach，进程照跑（交班时新核心接回去；没人接回时看门狗过这台主机设的闲置时长后收掉它，默认十分钟）。
func (l *Link) Leave() { l.close() }

// close 让连接不再写新消息、不再交出事件。已经排着的消息在接上了时照样写完，再关上输入，连接进程随之离开。
func (l *Link) close() {
	l.mu.Lock()
	l.closed = true
	l.wake.Broadcast()
	l.mu.Unlock()
	// 先关 done：正在交出的那个事件随即落空（除非别的 goroutine 正好在收），放开 deliverMu
	l.closeOnce.Do(func() { close(l.done) })
	l.deliverMu.Lock()
	l.silenced = true
	l.deliverMu.Unlock()
}

// deliver 交出一个事件；Stop、Detach 之后不交，返回 false。
func (l *Link) deliver(ev Event) bool {
	l.deliverMu.Lock()
	defer l.deliverMu.Unlock()
	if l.silenced {
		return false
	}
	select {
	case l.events <- ev:
		return true
	case <-l.done:
		return false
	}
}

// write 把排着的消息一行行写进连接进程的标准输入，直到关了或连接结束；最后关上标准输入。
func (l *Link) write(stdin io.WriteCloser) {
	defer stdin.Close()
	for {
		line, ok := l.nextOutgoing()
		if !ok {
			return
		}
		if _, err := stdin.Write(line); err != nil {
			return
		}
	}
}

// nextOutgoing 等下一行要写的：接上之后才写；关了以后写完已排着的就停（没接上的不写了），连接结束了也停。
func (l *Link) nextOutgoing() ([]byte, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for {
		switch {
		case l.ended:
			return nil, false
		case l.attached && len(l.queue) > 0:
			line := l.queue[0]
			l.queue = l.queue[1:]
			return line, true
		case l.closed:
			return nil, false
		}
		l.wake.Wait()
	}
}

// attachedReport 是脚本接上时打的那一行（zeta_attached）。
type attachedReport struct {
	From     int    `json:"from"`
	Key      string `json:"key"`
	Attacher int    `json:"attacher"`
}

// exitReport 是脚本报结局的那一行（zeta_exit）。
type exitReport struct {
	Code         *int   `json:"code"`
	Reason       string `json:"reason"`
	StderrBase64 string `json:"stderr_base64"`
}

// lineType 只取一行里的 type。
type lineType struct {
	Type string `json:"type"`
}

// read 读连接进程的标准输出直到结束，事件按序交出，最后交出结局、关上事件通道。
// 接上（zeta_attached）之前的行是登录 shell 打的别的东西，丢掉；断在一行中间的半行不算。
func (l *Link) read(stdout io.Reader) {
	var report *exitReport
	next := 0 // 下一行的行号；0 是还没接上
	in := bufio.NewReaderSize(stdout, 64<<10)
	for {
		line, err := in.ReadBytes('\n')
		if err != nil {
			break
		}
		line = line[:len(line)-1]
		var head lineType
		valid := len(line) > 0 && line[0] == '{' && json.Unmarshal(line, &head) == nil
		switch {
		case valid && head.Type == "zeta_attached":
			next = l.handleAttached(line)
		case valid && head.Type == "zeta_exit":
			report = parseExit(line)
		case next > 0:
			n := next
			next++
			if valid {
				l.deliver(Line{N: n, Data: line})
			}
		}
	}
	waitErr := l.cmd.Wait()
	l.mu.Lock()
	l.ended = true
	l.wake.Broadcast()
	l.mu.Unlock()
	l.deliver(l.outcome(report, waitErr))
	close(l.events)
}

// handleAttached 处理 zeta_attached：记下标识和进程号，放开写输入，交出 Attached；返回接下来一行的行号。
// 接上的是本包现成的进程（key 和给的不一样）时换成它的标识，OnJoin 排到还没写的消息前面（这时还一行没写，顺序不会乱）。
func (l *Link) handleAttached(line []byte) int {
	var rep attachedReport
	_ = json.Unmarshal(line, &rep)
	if rep.From < 1 {
		rep.From = 1
	}
	l.mu.Lock()
	joined := rep.Key != "" && rep.Key != l.key
	if joined {
		first := make([][]byte, 0, len(l.onJoin)+len(l.queue))
		for _, j := range l.onJoin {
			first = append(first, withNewline(j))
		}
		l.queue = append(first, l.queue...)
		l.key = rep.Key
	}
	l.attacher = rep.Attacher
	l.attached = true
	ev := Attached{From: rep.From, Key: l.key, Attacher: rep.Attacher, Joined: joined}
	l.wake.Broadcast()
	l.mu.Unlock()
	l.deliver(ev)
	return rep.From
}

// outcome 是连接的结局：脚本报了 zeta_exit 就照它，否则照连接进程的退出码和标准错误输出。
func (l *Link) outcome(report *exitReport, waitErr error) Exit {
	if report != nil {
		code := 1
		if report.Code != nil {
			code = *report.Code
		}
		return Exit{Code: code, Stderr: decodeStderr(report.StderrBase64), Reason: report.Reason}
	}
	code := exitCode(waitErr)
	if code == 0 {
		code = -1
	}
	return Exit{Code: code, Stderr: l.stderr.String()}
}

// parseExit 解 zeta_exit 那一行。
func parseExit(line []byte) *exitReport {
	var rep exitReport
	_ = json.Unmarshal(line, &rep)
	return &rep
}

// decodeStderr 解 zeta_exit 里的标准错误输出（最后约 2000 字节的 base64）：截在半个字上的开头几个字节去掉，
// 别的不合法的字节换成替换字符。解不开时是空的。
func decodeStderr(b64 string) string {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return ""
	}
	return utf8Text(raw)
}

// utf8Text 把 b 当 UTF-8 文字：开头不是一个字的起头的字节去掉，别的不合法的字节换成替换字符。
func utf8Text(b []byte) string {
	start := 0
	for start < len(b) && !utf8.RuneStart(b[start]) {
		start++
	}
	return strings.ToValidUTF8(string(b[start:]), "�")
}

// exitCode 是连接进程的退出码；被信号结束时照 shell 的写法是 128 加信号值，没等到退出码时是 -1。
func exitCode(err error) int {
	if err == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		return -1
	}
	if status, ok := exitErr.Sys().(syscall.WaitStatus); ok && status.Signaled() {
		return 128 + int(status.Signal())
	}
	return exitErr.ExitCode()
}

func withNewline(line []byte) []byte {
	out := make([]byte, 0, len(line)+1)
	return append(append(out, line...), '\n')
}

// tail 收标准错误输出，只留最后一段。
type tail struct {
	mu  sync.Mutex
	buf []byte
}

func (t *tail) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.buf = append(t.buf, p...)
	// 按字节留够 stderrKeep 个字（一个字最多 4 字节），多出的扔掉
	if keep := stderrKeep * utf8.UTFMax; len(t.buf) > keep {
		t.buf = append(t.buf[:0], t.buf[len(t.buf)-keep:]...)
	}
	return len(p), nil
}

// String 是最后 stderrKeep 个字。
func (t *tail) String() string {
	t.mu.Lock()
	text := []rune(utf8Text(t.buf))
	t.mu.Unlock()
	if len(text) > stderrKeep {
		text = text[len(text)-stderrKeep:]
	}
	return string(text)
}

var _ io.Writer = (*tail)(nil)
