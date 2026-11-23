package hub

import (
	"bytes"
	"context"
	"fmt"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// 整条链路：中枢经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve，serve 拉起核心，核心经 zeta_host.sh
// 起按剧本回话的假 Claude（tools/fake-claude，放成 ~/.local/bin/claude）。走一遍：新对话、流式回答、工具确认、
// 回答中排队再插话、停止、断线重连后重新打开。设了 ZETA_RECORD_DIR 时把各段收到的推送写成 hubtest/recorded 的样子。
func TestConversationThroughCore(t *testing.T) {
	for _, tool := range []string{"bash", "jq"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("假 Claude 要 %s：%v", tool, err)
		}
	}
	e := newE2E(t, `{"title":"假 Claude 起的标题","steps":[{"thinking":"想一想"},{"text":"你好，我是按剧本回话的假 Claude","chunk":4,"delay":5}]}
{"steps":[{"tool":"Bash","input":{"command":"ls","description":"列出文件"},"result":"a.txt","ask":true},{"text":"有一个 a.txt"}]}
{"steps":[{"text":"慢慢说","chunk":2,"delay":200},{"pause":20000}]}
{"steps":[{"text":"收到插话"}]}
{"steps":[{"pause":20000}]}
`)
	h := e.hub

	h.NewChat()
	e.until(t, "打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening })
	c := h.Chat()
	if c.Conv == nil || c.Problem != "" || c.CwdProblem != "" {
		t.Fatalf("打开：%+v", c)
	}
	e.record("open")

	// 一轮：流式回答，答完起标题
	e.send(t, "你好，介绍一下自己")
	e.until(t, "第一轮答完", func() bool { return e.finished(chat.OutcomeDone) })
	e.until(t, "起好标题", func() bool { return e.state.Title == "假 Claude 起的标题" })
	if got := e.texts(); !slices.Equal(got, []string{"user:你好，介绍一下自己", "work:", "answer:你好，我是按剧本回话的假 Claude"}) {
		t.Fatalf("条目：%v", got)
	}
	if e.state.Busy || !e.state.Process || e.state.Session == "" || e.state.Usage.Model == "" {
		t.Fatalf("状态：%+v", e.state)
	}
	e.record("turn")

	// 工具确认：允许以后工具跑完、接着回答
	e.send(t, "看看有什么文件")
	e.until(t, "工具确认", func() bool { return len(e.state.Prompts) == 1 })
	p := e.state.Prompts[0]
	if p.Kind != prompt.KindPermission || p.ToolName != "Bash" || len(p.SuggestionLines) == 0 {
		t.Fatalf("确认：%+v", p)
	}
	e.record("permission")
	e.do(t, func(conv *chat.Conversation) error {
		return conv.Answer(e.ctx(), p.RequestID, prompt.Reply{Kind: prompt.ReplyAllow})
	})
	e.until(t, "确认以后答完", func() bool { return e.finished(chat.OutcomeDone) })
	if got := e.texts(); got[len(got)-1] != "answer:有一个 a.txt" {
		t.Fatalf("确认以后的条目：%v", got)
	}
	e.record("permission-answered")

	// 回答中再发一条：排队；立即插话以后这一轮收尾，排队的那条接着开始
	e.send(t, "说慢点")
	e.until(t, "开始回答", func() bool { return e.state.Busy && strings.HasPrefix(e.last(), "answer:慢") })
	e.send(t, "先说重点")
	e.until(t, "排队", func() bool { return len(e.state.Queued) == 1 })
	if e.state.Queued[0].Text != "先说重点" {
		t.Fatalf("排队的：%+v", e.state.Queued)
	}
	e.record("queued")
	e.do(t, func(conv *chat.Conversation) error { return conv.Steer(e.ctx()) })
	e.until(t, "插话的那条答完", func() bool { return e.last() == "answer:收到插话" && !e.state.Busy })
	if len(e.state.Queued) != 0 {
		t.Fatalf("插话以后还排着：%+v", e.state.Queued)
	}
	e.record("steered")

	// 停止
	e.send(t, "等一下")
	e.until(t, "开始", func() bool { return e.state.Busy })
	e.turns = nil
	e.do(t, func(conv *chat.Conversation) error { return conv.Interrupt(e.ctx()) })
	e.until(t, "停下", func() bool { return e.finished(chat.OutcomeStopped) })
	e.record("stopped")

	// 断线：重连以后接着这个会话重新打开，条目整份再来一份
	session, before := e.state.Session, e.texts()
	e.server.DropAll()
	e.until(t, "看到断线", func() bool { return h.ChatLinkProblem() != "" })
	e.until(t, "重连以后重新打开", func() bool { return h.ChatLinkProblem() == "" && e.connected && e.fulls == 2 })
	if e.state.Session != session || !slices.Equal(e.texts(), before) {
		t.Fatalf("重新打开后：会话 %s（原来 %s），条目 %v", e.state.Session, session, e.texts())
	}
	e.record("reopened")

	// 结束进程、关掉对话
	e.do(t, func(conv *chat.Conversation) error { return conv.End(e.ctx()) })
	e.until(t, "进程结束", func() bool { return !e.state.Process })
	h.CloseChat()
	e.flush(t)
}

// e2e 是一次端到端测试：真的 serve 和核心，中枢的 run 在后台跑，post 排进 posts 由测试的 goroutine 执行（像窗口的主线程）。
type e2e struct {
	hub    *Hub
	server *sshtest.Server
	posts  chan func()
	// another 起同一台设备的另一个客户端（自己的设置、自己的 SSH 连接），推来的同样排进 posts
	another func(t *testing.T) *Hub

	// 下面只在测试的 goroutine 上动：像界面那样 Take 下来、套上增量拼出来的样子
	state     chat.State
	connected bool
	shown     []items.Item
	turns     []chat.Turn
	fulls     int // 收到过几次整份的条目：打开一次，重连后重新打开再一次

	mu       sync.Mutex
	lines    []string // 收到的推送，还没写出去的
	recorded map[string][]string
}

func newE2E(t *testing.T, scenario string) *e2e {
	t.Helper()
	program, err := host.LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		t.Fatal(err)
	}
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	// 经 windows/testdata 里指向 tools/fake-claude 的符号链接读：模块目录外的路径 go test 的缓存不认
	fake, err := os.ReadFile("../../testdata/fake-claude/fake-claude.sh")
	if err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(server.Home, ".local/bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	// 登录 shell 的 PATH 补上了 ~/.local/bin，托管脚本起的 claude 就是它；剧本放在它旁边（fake-claude 默认在自己的目录里找）
	if err := os.WriteFile(filepath.Join(bin, "claude"), fake, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "scenario.jsonl"), []byte(scenario), 0o644); err != nil {
		t.Fatal(err)
	}
	x := sshlink.Host{ID: "r-e2e", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetHosts([]sshlink.Host{x}); err != nil {
		t.Fatal(err)
	}
	e := &e2e{server: server, posts: make(chan func(), 1000), recorded: map[string][]string{}}
	devices := &teeDevices{Devices: &host.Devices{Links: sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute), Helper: program},
		tee: e.received}
	run, post := func(f func()) { go f() }, func(f func()) { e.posts <- f }
	e.hub = New(s, devices, run, post)
	e.another = func(t *testing.T) *Hub {
		s, err := settings.Load(t.TempDir())
		if err == nil {
			err = s.SetHosts([]sshlink.Host{x})
		}
		if err != nil {
			t.Fatal(err)
		}
		return New(s, &host.Devices{Links: sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute), Helper: program}, run, post)
	}
	t.Cleanup(func() { stopCore(t, server.Home, program) })
	return e
}

// teeDevices 在真设备之外记下对话连接上收到的每一行推送。
type teeDevices struct {
	*host.Devices
	tee func(line []byte)
}

func (d *teeDevices) OpenChat(h sshlink.Host, push func([]byte), hello func(hostlink.Hello), problem func(string)) host.ChatLink {
	return d.Devices.OpenChat(h, func(line []byte) { d.tee(line); push(line) }, hello, problem)
}

func (e *e2e) received(line []byte) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lines = append(e.lines, string(line))
}

// until 执行交回主线程的事、像界面那样取走对话的变化，直到 done 为真；限时 30 秒。
func (e *e2e) until(t *testing.T, what string, done func() bool) {
	t.Helper()
	deadline := time.After(30 * time.Second)
	for {
		e.take(t)
		if done() {
			return
		}
		select {
		case f := <-e.posts:
			f()
		case <-time.After(20 * time.Millisecond):
		case <-deadline:
			t.Fatalf("30 秒内没等到%s；条目 %v，状态 busy %v queued %d prompts %d，连接 %q", what, e.texts(), e.state.Busy,
				len(e.state.Queued), len(e.state.Prompts), e.hub.ChatLinkProblem())
		}
	}
}

func (e *e2e) take(t *testing.T) {
	t.Helper()
	c := e.hub.Chat()
	if c == nil || c.Conv == nil {
		return
	}
	u := c.Conv.Take()
	e.state, e.connected = u.State, u.Connected
	for _, ch := range u.Items {
		if ch.Full {
			e.shown = ch.Items
			e.fulls++
			continue
		}
		next, err := items.Splice(e.shown, ch.At, ch.Remove, ch.Items)
		if err != nil {
			t.Fatal(err)
		}
		e.shown = next
	}
	e.turns = append(e.turns, u.Turns...)
}

// finished：收到过以 outcome 结束的一轮（取走它，下一轮重新等）。
func (e *e2e) finished(outcome string) bool {
	for i, turn := range e.turns {
		if turn.Phase == chat.TurnFinished {
			e.turns = slices.Delete(e.turns, 0, i+1)
			if turn.Outcome != outcome {
				panic("一轮以 " + turn.Outcome + " 结束：" + turn.Text)
			}
			return true
		}
	}
	return false
}

func (e *e2e) texts() []string {
	var out []string
	for _, it := range e.shown {
		out = append(out, string(it.Kind)+":"+it.Text)
	}
	return out
}

func (e *e2e) last() string {
	texts := e.texts()
	if len(texts) == 0 {
		return ""
	}
	return texts[len(texts)-1]
}

// ctx 是一个请求的限时（到点自己释放）。
func (e *e2e) ctx() context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	go func() { <-ctx.Done(); cancel() }()
	return ctx
}

// do 在测试的 goroutine 上对对话做一件事（真界面里经 RunChat 放到后台）。
func (e *e2e) do(t *testing.T, f func(conv *chat.Conversation) error) {
	t.Helper()
	done := make(chan error, 1)
	conv := e.hub.Chat().Conv
	go func() { done <- f(conv) }()
	for {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
			return
		case f := <-e.posts:
			f()
		case <-time.After(30 * time.Second):
			t.Fatal("请求 30 秒没有应答")
		}
	}
}

func (e *e2e) send(t *testing.T, text string) {
	t.Helper()
	e.do(t, func(conv *chat.Conversation) error { return conv.Send(e.ctx(), coreapi.Outgoing{Text: text}) })
}

// sessionLike 是录下的推送里每次不同的会话号、消息号这类 UUID。
var sessionLike = regexp.MustCompile(`[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)

// record 把上次以后收到的推送记成一段。
func (e *e2e) record(name string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.recorded[name] = e.lines
	e.lines = nil
}

// flush：设了 ZETA_RECORD_DIR 时把录下的各段写出去，家目录换成 /home/zeta，各个会话号、消息号按出现的先后换成固定的。
func (e *e2e) flush(t *testing.T) {
	dir := os.Getenv("ZETA_RECORD_DIR")
	if dir == "" {
		return
	}
	ids := map[string]string{}
	for _, name := range slices.Sorted(maps.Keys(e.recorded)) {
		lines := e.recorded[name]
		var b bytes.Buffer
		for _, line := range lines {
			line = strings.ReplaceAll(line, e.server.Home, "/home/zeta")
			line = sessionLike.ReplaceAllStringFunc(line, func(id string) string {
				if _, ok := ids[id]; !ok {
					ids[id] = fmt.Sprintf("00000000-0000-4000-8000-%012d", len(ids)+1)
				}
				return ids[id]
			})
			b.WriteString(line + "\n")
		}
		if err := os.WriteFile(filepath.Join(dir, name+".jsonl"), b.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

// stopCore 结束这台上的核心：它的进程号写在锁文件里（见 docs/core.md「对话」）。serve 跟着 SSH 关掉就退出。
func stopCore(t *testing.T, home string, program *hostlink.Program) {
	lock := filepath.Join(home, ".cache", host.LinuxName, "core", program.Version+".lock")
	data, err := os.ReadFile(lock)
	if err != nil {
		return
	}
	if pid, _ := strconv.Atoi(strings.TrimSpace(string(data))); pid > 0 {
		if p, err := os.FindProcess(pid); err == nil {
			_ = p.Signal(syscall.SIGTERM)
		}
	}
}
