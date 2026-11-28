package conv

import (
	"encoding/json"
	"fmt"
	"reflect"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeLink 是假的托管连接：记下写进去的行，测试经 events 扮 Claude Code 往回送。
type fakeLink struct {
	mu      sync.Mutex
	sent    [][]byte
	stopped bool
	left    bool
	key     string
	events  Events
}

func (l *fakeLink) Send(line []byte) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sent = append(l.sent, line)
}

func (l *fakeLink) Stop()       { l.mu.Lock(); l.stopped = true; l.mu.Unlock() }
func (l *fakeLink) Leave()      { l.mu.Lock(); l.left = true; l.mu.Unlock() }
func (l *fakeLink) Key() string { return l.key }

// lines 是写进去的、type 为 kind 的各行（control_request 按 subtype 认：kind 写成 control_request/<subtype>）。
func (l *fakeLink) lines(kind string) []map[string]any {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []map[string]any
	for _, b := range l.sent {
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		t, _ := m["type"].(string)
		if req, ok := m["request"].(map[string]any); ok {
			t += "/" + fmt.Sprint(req["subtype"])
		}
		if t == kind {
			out = append(out, m)
		}
	}
	return out
}

// fakeClient 扮一个客户端：收推送，把整份和增量拼起来。
type fakeClient struct {
	msgs  []map[string]any
	state map[string]any
	items []any
	turns []map[string]any
	other []map[string]any
}

type harness struct {
	t           *testing.T
	m           *Manager
	mu          sync.Mutex
	clients     map[int]*fakeClient
	links       []*fakeLink
	starts      []StartOptions
	history     []byte
	historyArgs [][]string
	desks       chan string // 开过的 tmux 窗口：会话号 工作目录
	attaches    []string    // 接上过的托管进程：标识@从第几行读起
}

func newHarness(t *testing.T) *harness {
	h := &harness{t: t, clients: map[int]*fakeClient{}, desks: make(chan string, 16)}
	deps := Deps{
		Desk: func(session, cwd string) { h.desks <- session + " " + cwd },
		Attach: func(key string, from int, ev Events) (Link, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			l := &fakeLink{key: key, events: ev}
			h.links = append(h.links, l)
			h.attaches = append(h.attaches, key+"@"+strconv.Itoa(from))
			return l, nil
		},
		Start: func(o StartOptions, ev Events) (Link, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			l := &fakeLink{key: o.Key, events: ev}
			h.links = append(h.links, l)
			h.starts = append(h.starts, o)
			return l, nil
		},
		History: func(args ...string) ([]byte, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.historyArgs = append(h.historyArgs, args)
			return h.history, nil
		},
	}
	h.m = NewManager(deps, func(client int, msg any) {
		b, err := json.Marshal(msg)
		if err != nil {
			t.Errorf("推送编不成 JSON：%v", err)
			return
		}
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		h.mu.Lock()
		defer h.mu.Unlock()
		h.client(client).receive(t, m)
	})
	t.Cleanup(func() {
		h.m.mu.Lock()
		all := make([]*Conversation, 0, len(h.m.all))
		for c := range h.m.all {
			all = append(all, c)
		}
		h.m.mu.Unlock()
		for _, c := range all {
			c.close()
			c.bg.Wait()
		}
	})
	return h
}

func (h *harness) client(id int) *fakeClient {
	c := h.clients[id]
	if c == nil {
		c = &fakeClient{}
		h.clients[id] = c
	}
	return c
}

func (c *fakeClient) receive(t *testing.T, m map[string]any) {
	c.msgs = append(c.msgs, m)
	switch m["type"] {
	case "conv.state":
		if m["full"] == true || c.state == nil {
			c.state = map[string]any{}
		}
		for k, v := range m {
			c.state[k] = v
		}
	case "conv.items":
		items, _ := m["items"].([]any)
		if m["full"] == true {
			c.items = items
			return
		}
		at, remove := int(m["at"].(float64)), int(m["remove"].(float64))
		if at+remove > len(c.items) {
			t.Errorf("增量越界：at %d remove %d，手里 %d 项", at, remove, len(c.items))
			return
		}
		c.items = append(append(append([]any{}, c.items[:at]...), items...), c.items[at+remove:]...)
	case "conv.turn":
		c.turns = append(c.turns, m)
	default:
		c.other = append(c.other, m)
	}
}

// call 发一个请求，出错就让测试失败。
func (h *harness) call(client int, method string, params map[string]any) any {
	h.t.Helper()
	params["method"] = method
	b, _ := json.Marshal(params)
	v, err := h.m.Request(client, method, b)
	if err != nil {
		h.t.Fatalf("%s：%v", method, err)
	}
	return v
}

func (h *harness) conv(client int, name string) *Conversation { return h.m.find(client, name) }

func (h *harness) link(i int) *fakeLink {
	h.mu.Lock()
	defer h.mu.Unlock()
	if i >= len(h.links) {
		h.t.Fatalf("只起过 %d 个进程", len(h.links))
	}
	return h.links[i]
}

// feed 扮 Claude Code 送来几行，等对话处理完并把攒着的变化推出去，再核对各客户端拼出来的条目和核心里的一样。
func (h *harness) feed(c *Conversation, l *fakeLink, lines ...string) {
	h.t.Helper()
	for _, line := range lines {
		l.events.Line([]byte(line))
	}
	h.settle(c)
}

func (h *harness) settle(c *Conversation) {
	h.t.Helper()
	// 各个拿着的客户端该有的条目（声明了 image_refs 的是去掉图片原文的那份）
	wants := map[int][]byte{}
	// 先把攒着的推出去：推之前核心才给各轮算好能做什么（markRounds）
	_, _ = c.Call(func() (any, error) {
		c.flush()
		for id := range c.holders {
			wants[id], _ = json.Marshal(itemsFor(c.options[id], c.r.Items))
		}
		return nil, nil
	})
	h.mu.Lock()
	defer h.mu.Unlock()
	for id, cl := range h.clients {
		b, ok := wants[id]
		if !ok { // 只核对拿着这个对话的客户端
			continue
		}
		var want []any
		_ = json.Unmarshal(b, &want)
		if want == nil {
			want = []any{}
		}
		got := cl.items
		if got == nil {
			got = []any{}
		}
		if !reflect.DeepEqual(got, want) {
			h.t.Fatalf("客户端 %d 拼出来的条目和核心里的不一样\n客户端：%v\n核心：%v", id, got, want)
		}
	}
}

func (h *harness) state(client int, key string) any {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.client(client).state[key]
}

func (h *harness) texts(client int) []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []string
	for _, it := range h.client(client).items {
		m := it.(map[string]any)
		out = append(out, fmt.Sprintf("%v:%v", m["kind"], m["text"]))
	}
	return out
}

// 一轮的几行 Claude Code 输出。
func initLine(session string, caps ...string) string {
	b, _ := json.Marshal(map[string]any{"type": "system", "subtype": "init", "session_id": session, "model": "claude-fake",
		"permissionMode": "default", "capabilities": caps})
	return string(b)
}

func answerLines(msg, text string) []string {
	return []string{
		`{"type":"stream_event","event":{"type":"message_start","message":{"id":"` + msg + `"}}}`,
		`{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}`,
		`{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"` + text[:1] + `"}}}`,
		`{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"` + text[1:] + `"}}}`,
		`{"type":"assistant","message":{"id":"` + msg + `","content":[{"type":"text","text":"` + text + `"}]}}`,
		`{"type":"stream_event","event":{"type":"content_block_stop","index":0}}`,
	}
}

const resultOK = `{"type":"result","subtype":"success","is_error":false,"result":"ok"}`

func lifecycle(uuid, state string) string {
	return `{"type":"command_lifecycle","command_uuid":"` + uuid + `","state":"` + state + `"}`
}

func TestOneTurn(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/work", "options": map[string]any{"system_prompt": "hint"}})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "  你好  "})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	if args := strings.Join(h.starts[0].Args, " "); strings.Contains(args, "--resume") || !strings.Contains(args, "--append-system-prompt "+imagesHint+" hint") {
		t.Fatalf("新对话的参数不对：%s", args)
	}
	users := l.lines("user")
	if len(users) != 1 || h.state(1, "busy") != true || h.state(1, "title") != "你好" {
		t.Fatalf("发出去的：%v，busy %v，标题 %v", users, h.state(1, "busy"), h.state(1, "title"))
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:你好"}) {
		t.Fatalf("条目：%v", got)
	}
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m1", "回答")...)
	h.feed(c, l, resultOK)
	if h.state(1, "session") != "s1" || h.state(1, "busy") != false {
		t.Fatalf("会话 %v，busy %v", h.state(1, "session"), h.state(1, "busy"))
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:你好", "answer:回答"}) {
		t.Fatalf("条目：%v", got)
	}
	turns := h.client(1).turns
	if len(turns) != 2 || turns[0]["phase"] != "started" || turns[1]["outcome"] != "done" {
		t.Fatalf("一轮的起止：%v", turns)
	}
}

func TestQueueInterruptAndRecall(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	h.settle(c)
	queued, _ := h.state(1, "queued").([]any)
	if len(queued) != 2 {
		t.Fatalf("排队的：%v", queued)
	}
	second := queued[0].(map[string]any)["uuid"].(string)
	third := queued[1].(map[string]any)["uuid"].(string)
	// 第二条在工具边界并进这一轮
	h.feed(c, l, lifecycle(second, "started"))
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "user:二"}) {
		t.Fatalf("条目：%v", got)
	}
	// 停止：第三条撤回放回输入框
	h.call(1, "conv.interrupt", map[string]any{"conv": "a"})
	if n := len(l.lines("control_request/cancel_async_message")); n != 1 {
		t.Fatalf("撤回请求 %d 个", n)
	}
	h.feed(c, l, lifecycle(third, "cancelled"), resultOK)
	recalled, _ := h.state(1, "recalled").(map[string]any)
	if recalled["text"] != "三" || h.state(1, "busy") != false {
		t.Fatalf("撤回的 %v，busy %v", recalled, h.state(1, "busy"))
	}
	if got := h.texts(1); got[len(got)-1] != "notice:已停止" {
		t.Fatalf("条目：%v", got)
	}
	if v := h.call(1, "conv.take_recalled", map[string]any{"conv": "a"}); v.(*messageView).Text != "三" {
		t.Fatalf("取走的：%v", v)
	}
	h.settle(c)
	if h.state(1, "recalled") != nil {
		t.Fatalf("取走后还在：%v", h.state(1, "recalled"))
	}
}

func TestSteerWithSendNow(t *testing.T) {
	for _, answer := range []string{"stopped", "delivering"} {
		t.Run(answer, func(t *testing.T) {
			h := newHarness(t)
			h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
			h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
			c, l := h.conv(1, "a"), h.link(0)
			h.feed(c, l, initLine("s1", "interrupt_send_now_v1"))
			h.call(1, "conv.send", map[string]any{"conv": "a", "text": "插话"})
			h.call(1, "conv.steer", map[string]any{"conv": "a"})
			reqs := l.lines("control_request/interrupt")
			req := reqs[len(reqs)-1]
			body := req["request"].(map[string]any)
			if body["send_now"] != true || body["message_uuid"] == nil {
				t.Fatalf("插话的请求：%v", req)
			}
			h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+req["request_id"].(string)+
				`","response":{"send_now":"`+answer+`"}}}`)
			// 停下这一轮时先以 result 收尾、排队的消息再另起一轮；挪到后台时消息先并进这一轮
			if answer == "stopped" {
				h.feed(c, l, resultOK, lifecycle(body["message_uuid"].(string), "started"))
			} else {
				h.feed(c, l, lifecycle(body["message_uuid"].(string), "started"), resultOK)
			}
			got := h.texts(1)
			stopped := slices.Contains(got, "notice:已打断，先处理插话")
			if stopped != (answer == "stopped") {
				t.Fatalf("应答 %s 后的条目：%v", answer, got)
			}
		})
	}
}

func TestPermissionPrompt(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "跑个命令"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"),
		`{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"tool_use_id":"t1"}}`,
		`{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"pwd"},"tool_use_id":"t2"}}`)
	if prompts, _ := h.state(1, "prompts").([]any); len(prompts) != 2 {
		t.Fatalf("等回应的：%v", prompts)
	}
	h.call(1, "conv.answer", map[string]any{"conv": "a", "request_id": "r1", "reply": map[string]any{"kind": "allow"}})
	resp := l.lines("control_response")
	if len(resp) != 1 || !strings.Contains(fmt.Sprint(resp[0]), "updatedInput:map[command:ls]") {
		t.Fatalf("应答：%v", resp)
	}
	// 别的客户端答了 r2：看到 t2 的结果就收起
	h.feed(c, l, `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"/"}]}}`)
	if prompts, _ := h.state(1, "prompts").([]any); len(prompts) != 0 {
		t.Fatalf("答过的还在：%v", prompts)
	}
}

func TestProcessExitWhileBusy(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	l.events.Exit(Exit{Code: 137})
	h.settle(c)
	turns := h.client(1).turns
	if last := turns[len(turns)-1]; last["outcome"] != "interrupted" || h.state(1, "process") != false {
		t.Fatalf("一轮的结局：%v，process %v", last, h.state(1, "process"))
	}
	if got := h.texts(1); got[len(got)-1] != "notice:Claude Code 进程已被终止" {
		t.Fatalf("条目：%v", got)
	}
	// 再发一条：起新进程，--resume 接上
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	if args := strings.Join(h.starts[1].Args, " "); !strings.Contains(args, "--resume s1") {
		t.Fatalf("接着会话的参数：%s", args)
	}
}

func TestHistoryAndSharing(t *testing.T) {
	h := newHarness(t)
	h.history = []byte(`{"type":"zeta_history","skipped":12,"anchor":"u0","model":"claude-x","effort":"high"}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"旧问题"}]}}
{"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"旧回答"}]}}
`)
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	c := h.conv(1, "a")
	deadline := time.Now().Add(5 * time.Second)
	for h.state(1, "loading_history") != false {
		if time.Now().After(deadline) {
			t.Fatal("历史没载完")
		}
		h.settle(c)
		time.Sleep(10 * time.Millisecond)
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:旧问题", "answer:旧回答"}) || h.state(1, "earlier") != float64(12) {
		t.Fatalf("条目 %v，earlier %v", got, h.state(1, "earlier"))
	}
	if h.state(1, "effort") != "high" || h.state(1, "reported_model") != "claude-x" {
		t.Fatalf("思考强度 %v，模型 %v", h.state(1, "effort"), h.state(1, "reported_model"))
	}
	// 第二个客户端打开同一个会话：拿到的是同一个对话，名字是它自己起的
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	if h.conv(2, "b") != c {
		t.Fatal("同一个会话开出了两个对话")
	}
	h.call(2, "conv.send", map[string]any{"conv": "b", "text": "新问题"})
	h.settle(c)
	if h.client(1).state["conv"] != "a" || h.client(2).state["conv"] != "b" {
		t.Fatalf("推送里的名字：%v、%v", h.client(1).state["conv"], h.client(2).state["conv"])
	}
	if args := strings.Join(h.starts[0].Args, " "); !strings.Contains(args, "--resume s1") || !strings.Contains(args, "--effort high") {
		t.Fatalf("参数：%s", args)
	}
}

func TestReleaseWhenIdle(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.m.Gone(1)
	start := time.Now()
	releasable := func(at time.Time) bool {
		v, _ := c.Call(func() (any, error) { return c.releasable(at), nil })
		return v == true
	}
	if releasable(start.Add(time.Hour)) {
		t.Fatal("还在回答就收掉了")
	}
	h.feed(c, l, resultOK)
	if releasable(start) || releasable(start.Add(releaseAfter-time.Second)) {
		t.Fatal("没等够就收掉了")
	}
	if !releasable(start.Add(releaseAfter)) || !l.left || l.stopped {
		t.Fatalf("闲满了没断开：left %v stopped %v", l.left, l.stopped)
	}
}
