package hub

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// chatFixture 是一台假设备上的中枢：对话连接是 hubtest 的假 serve，conv.open 时推来录好的整份（recorded/open）。
type chatFixture struct {
	t       *testing.T
	devices *hubtest.Devices
	hub     *Hub
	id      string
	// shown 是像界面那样把 Take 交来的变化依次套上拼出来的条目，turns 是收到的一轮的起止。
	shown []items.Item
	turns []chat.Turn
	state chat.State
}

func newChatFixture(t *testing.T) *chatFixture {
	t.Helper()
	d := hubtest.NewDevices()
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			if c.Method == "conv.open" {
				pushAll(l, hubtest.Recorded("open", c.Conv()))
			}
			return nil, nil
		})
	}
	h, _ := newHub(t, d)
	x, err := h.AddHost("box", "", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	return &chatFixture{t: t, devices: d, hub: h, id: x.ID}
}

func pushAll(l *hubtest.ChatLink, lines []string) {
	for _, line := range lines {
		l.Push(line)
	}
}

func (f *chatFixture) link() *hubtest.ChatLink { return f.devices.Chat(f.id) }

// replay 推一段录好的消息，再像界面那样取走变化。
func (f *chatFixture) replay(name string) {
	f.t.Helper()
	pushAll(f.link(), hubtest.Recorded(name, f.hub.Chat().Conv.Name()))
	f.take()
}

func (f *chatFixture) take() {
	f.t.Helper()
	u := f.hub.Chat().Conv.Take()
	f.state = u.State
	for _, ch := range u.Items {
		if ch.Full {
			f.shown = ch.Items
			continue
		}
		next, err := items.Splice(f.shown, ch.At, ch.Remove, ch.Items)
		if err != nil {
			f.t.Fatal(err)
		}
		f.shown = next
	}
	f.turns = append(f.turns, u.Turns...)
}

func (f *chatFixture) texts() string {
	var out []string
	for _, it := range f.shown {
		out = append(out, string(it.Kind)+":"+it.Text)
	}
	return strings.Join(out, "|")
}

func (f *chatFixture) run(do func(conv *chat.Conversation) error) {
	f.t.Helper()
	var failed error
	f.hub.RunChat(func(_ context.Context, conv *chat.Conversation) error { return do(conv) }, func(err error) { failed = err })
	if failed != nil {
		f.t.Fatal(failed)
	}
}

func TestOpeningASessionAndSwitching(t *testing.T) {
	f := newChatFixture(t)
	s := hostlink.Session{ID: "s1", File: "/home/zeta/.claude/projects/-w/s1.jsonl", Cwd: "/w", Title: "修 bug", Backend: f.id}
	f.hub.OpenSession(s)
	c := f.hub.Chat()
	if c == nil || c.Opening || c.Conv == nil || c.Problem != "" {
		t.Fatalf("打开：%+v", c)
	}
	calls := f.link().Took()
	if len(calls) != 1 || calls[0].Method != "conv.open" ||
		calls[0].JSON() != `{"conv":"w1","cwd":"/w","file":"/home/zeta/.claude/projects/-w/s1.jsonl","options":{"auto_title":true,"desk":true},"session":"s1","title":"修 bug"}` {
		t.Fatalf("打开的请求：%+v", calls)
	}
	if len(f.devices.Checked()) != 0 {
		t.Error("接着旧会话也去查了工作目录")
	}
	// 再点同一个不动
	f.hub.OpenSession(s)
	if m := f.link().Methods(); m != nil {
		t.Fatalf("再点同一个发了 %v", m)
	}
	// 换一个：先关掉原来的，同一条连接上打开新的
	f.hub.OpenSession(hostlink.Session{ID: "s2", File: "/f2", Cwd: "/w", Backend: f.id})
	calls = f.link().Took()
	if len(calls) != 2 || calls[0].Method != "conv.close" || calls[0].Conv() != "w1" || calls[1].Method != "conv.open" || calls[1].Str("session") != "s2" {
		t.Fatalf("换会话：%+v", calls)
	}
	if f.devices.Chats() != 1 {
		t.Errorf("起了 %d 条对话连接", f.devices.Chats())
	}
	f.hub.CloseChat()
	if f.hub.Chat() != nil || !slices.Equal(f.link().Methods(), []string{"conv.close"}) {
		t.Error("关掉对话")
	}
}

func TestNewChatChecksTheWorkingDirectory(t *testing.T) {
	f := newChatFixture(t)
	x, _ := f.hub.Host(f.id)
	f.hub.NewChat()
	c := f.hub.Chat()
	if c == nil || !c.Fresh() || c.Conv == nil || c.CwdProblem != "" || !slices.Equal(f.devices.Checked(), []string{x.Home}) {
		t.Fatalf("新对话：%+v，查过 %v", c, f.devices.Checked())
	}
	if calls := f.link().Took(); len(calls) != 1 || calls[0].JSON() != `{"conv":"w1","cwd":"`+x.Home+`","options":{"auto_title":true,"desk":true}}` {
		t.Fatalf("新对话的打开：%+v", calls)
	}
	// 进不去时照样打开，原因留给输入栏上方
	x.DefaultCwd = "/srv/gone"
	if err := f.hub.UpdateHost(x); err != nil {
		t.Fatal(err)
	}
	f.devices.DirProblems = map[string]string{"/srv/gone": "「box」上没有 /srv/gone 这个目录"}
	f.hub.NewChat()
	if c := f.hub.Chat(); c.CwdProblem != "「box」上没有 /srv/gone 这个目录" || c.Session.Cwd != "/srv/gone" {
		t.Fatalf("进不去：%+v", c)
	}
}

// 一轮流式回答：录下的增量依次套上，和协议层的整份一样；新对话第一轮以后有了会话号，侧栏点它不会再开一个。
func TestStreamingTurn(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	f.take()
	f.replay("turn")
	if got := f.texts(); got != "user:你好，介绍一下自己|work:|answer:你好，我是按剧本回话的假 Claude" {
		t.Fatalf("条目：%s", got)
	}
	if f.state.Title != "假 Claude 起的标题" || f.state.Busy || f.state.Usage.Model != "claude-fake" {
		t.Fatalf("状态：%+v", f.state)
	}
	if len(f.turns) != 2 || f.turns[1].Outcome != chat.OutcomeDone {
		t.Fatalf("一轮的起止：%+v", f.turns)
	}
	f.link().Took()
	f.hub.OpenSession(hostlink.Session{ID: f.state.Session, Backend: f.id})
	if m := f.link().Methods(); m != nil {
		t.Fatalf("点开正开着的新会话发了 %v", m)
	}
}

func TestPermissionQueueSteerAndStop(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	f.take()
	f.replay("turn")
	f.replay("permission")
	if len(f.state.Prompts) != 1 || f.state.Prompts[0].ToolName != "Bash" {
		t.Fatalf("确认：%+v", f.state.Prompts)
	}
	id := f.state.Prompts[0].RequestID
	f.link().Took()
	f.run(func(conv *chat.Conversation) error {
		return conv.Answer(t.Context(), id, prompt.Reply{Kind: prompt.ReplyAllowAlways})
	})
	if calls := f.link().Took(); len(calls) != 1 || calls[0].JSON() != `{"conv":"w1","reply":{"kind":"allow_always"},"request_id":"`+id+`"}` {
		t.Fatalf("回应：%+v", calls)
	}
	f.replay("permission-answered")
	if len(f.state.Prompts) != 0 || !strings.HasSuffix(f.texts(), "answer:有一个 a.txt") {
		t.Fatalf("回应以后：%+v %s", f.state.Prompts, f.texts())
	}

	f.replay("queued")
	if len(f.state.Queued) != 1 || f.state.Queued[0].Text != "先说重点" || !f.state.Busy {
		t.Fatalf("排队：%+v", f.state.Queued)
	}
	f.run(func(conv *chat.Conversation) error { return conv.Steer(t.Context()) })
	f.replay("steered")
	if len(f.state.Queued) != 0 || !strings.HasSuffix(f.texts(), "notice:已打断，先处理插话|user:先说重点|answer:收到插话") {
		t.Fatalf("插话以后：%+v %s", f.state.Queued, f.texts())
	}
	f.run(func(conv *chat.Conversation) error { return conv.Interrupt(t.Context()) })
	if m := f.link().Methods(); !slices.Equal(m, []string{"conv.steer", "conv.interrupt"}) {
		t.Fatalf("请求：%v", m)
	}
	f.replay("stopped")
	if last := f.turns[len(f.turns)-1]; last.Outcome != chat.OutcomeStopped || !strings.HasSuffix(f.texts(), "notice:已停止") {
		t.Fatalf("停下：%+v %s", last, f.texts())
	}
}

// 断线：连接的问题给界面看，请求不发；重连后接着这个会话号重新打开，核心推来整份。
func TestReconnectReopensTheConversation(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	f.take()
	f.replay("turn")
	session := f.state.Session
	f.link().Took()
	f.link().Drop("连接断了：EOF")
	if got := f.hub.ChatLinkProblem(); got != "连接断了：EOF" {
		t.Fatalf("连接的问题 %q", got)
	}
	var failed error
	f.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, chat.Outgoing{Text: "在吗"})
	},
		func(err error) { failed = err })
	if failed == nil || f.link().Methods() != nil {
		t.Fatalf("断线时发消息：%v", failed)
	}
	f.link().OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		if c.Method == "conv.open" {
			pushAll(f.link(), hubtest.Recorded("reopened", c.Conv()))
		}
		return nil, nil
	})
	f.link().Connect()
	calls := f.link().Took()
	if len(calls) != 1 || calls[0].Method != "conv.open" || calls[0].Str("session") != session || calls[0].Conv() != "w1" {
		t.Fatalf("重连后：%+v", calls)
	}
	f.take()
	if f.hub.ChatLinkProblem() != "" || !f.hub.Chat().Conv.Take().Connected || !strings.HasPrefix(f.texts(), "user:你好，介绍一下自己") {
		t.Fatalf("重新打开后：%q %s", f.hub.ChatLinkProblem(), f.texts())
	}
}

// 第一次就没连上：打开失败、说原因，这条连接丢掉，下次打开重新连。
func TestOpenFailsWhenTheDeviceIsUnreachable(t *testing.T) {
	d := hubtest.NewDevices()
	d.OnChat = func(l *hubtest.ChatLink) {
		l.OnCall(func(hubtest.Call) (json.RawMessage, error) {
			return nil, errors.New("连不上「box」：connection refused")
		})
	}
	h, _ := newHub(t, d)
	x, _ := h.AddHost("box", "", d.Found("box"))
	h.OpenSession(hostlink.Session{ID: "s1", Backend: x.ID})
	if c := h.Chat(); c.Opening || c.Conv != nil || c.Problem != "打不开这个对话：连不上「box」：connection refused" {
		t.Fatalf("打不开：%+v", c)
	}
	first := d.Chat(x.ID)
	if !first.Closed() {
		t.Error("没连上的连接还留着")
	}
	h.OpenSession(hostlink.Session{ID: "s2", Backend: x.ID})
	if d.Chats() != 2 {
		t.Errorf("再打开时没重新连：%d 条", d.Chats())
	}
}

func TestRemovingTheDeviceClosesItsChat(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	if err := f.hub.RemoveHost(f.id); err != nil {
		t.Fatal(err)
	}
	if f.hub.Chat() != nil || !f.link().Closed() {
		t.Fatal("删了设备，对话和连接还在")
	}
}
