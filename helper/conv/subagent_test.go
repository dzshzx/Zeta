package conv

import (
	"encoding/json"
	"errors"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// fakeSubagent 扮 zeta-helper subagent：记下每次的参数，按 reply 给输出。
type fakeSubagent struct {
	mu    sync.Mutex
	calls [][]string
	reply func(n int, args []string) ([]byte, error) // n 是第几次（从 0 起）
}

func (f *fakeSubagent) run(args ...string) ([]byte, error) {
	f.mu.Lock()
	n := len(f.calls)
	f.calls = append(f.calls, args)
	reply := f.reply
	f.mu.Unlock()
	return reply(n, args)
}

func (f *fakeSubagent) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.calls)
}

func (f *fakeSubagent) call(n int) []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[n]
}

// subagentRead 是一次 zeta-helper subagent 的输出：概要一行，之后一行一个事件。
func subagentRead(head map[string]any, events ...string) []byte {
	head["type"] = "zeta_subagent"
	b, _ := json.Marshal(head)
	return []byte(string(b) + "\n" + strings.Join(append(events, ""), "\n"))
}

const testFile = "/home/u/.claude/projects/p/s1/subagents/agent-a1.jsonl"

func foundHead(offset, line int, more bool, idleMs int) map[string]any {
	return map[string]any{"file": testFile, "agent_id": "a1", "offset": offset, "line": line, "more": more,
		"skipped": 0, "reset": false, "idle_ms": idleMs,
		"meta": map[string]any{"agentType": "Explore", "description": "查代码", "requestShape": "background"}}
}

const (
	promptEvent   = `{"type":"user","prompt":true,"message":{"content":[{"type":"text","text":"去查一下"}]}}`
	toolUseEvent  = `{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/a"}}]}}`
	toolDoneEvent = `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"内容"}]}}`
	reportEvent   = `{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"查完了"}]}}`
)

// fastPace 让对话 c 读记录的节奏快到测试里不用等。
func fastPace(c *Conversation) {
	_, _ = c.Call(func() (any, error) {
		c.subagents.pace = subagentPace{poll: 5 * time.Millisecond, slow: 10 * time.Millisecond,
			settle: 5 * time.Millisecond, slowAfter: 4}
		return nil, nil
	})
}

// subagentHarness 是开着一个对话（会话 s1、进程在回答）、子代理记录由 fake 给的 harness。
func subagentHarness(t *testing.T, fake *fakeSubagent) (*harness, *Conversation, *fakeLink) {
	h := newHarness(t)
	h.m.deps.Subagent = fake.run
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "session": "s1"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "派个子代理"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	fastPace(c)
	return h, c, l
}

// subagentPushes 是客户端 client 收到的、给 toolUse 的 conv.subagent。
func (h *harness) subagentPushes(client int, toolUse string) []map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []map[string]any
	for _, m := range h.client(client).other {
		if m["type"] == "conv.subagent" && m["tool_use_id"] == toolUse {
			out = append(out, m)
		}
	}
	return out
}

// subagentView 把推送拼成客户端手里的条目和最后一份概要；增量越界时测试失败。
func (h *harness) subagentView(client int, toolUse string) ([]any, map[string]any) {
	h.t.Helper()
	var items []any
	var last map[string]any
	for _, m := range h.subagentPushes(client, toolUse) {
		got, _ := m["items"].([]any)
		if m["full"] == true {
			items = got
		} else {
			at, remove := int(m["at"].(float64)), int(m["remove"].(float64))
			if at+remove > len(items) {
				h.t.Fatalf("增量越界：at %d remove %d，手里 %d 项", at, remove, len(items))
			}
			items = append(append(append([]any{}, items[:at]...), got...), items[at+remove:]...)
		}
		last = m
	}
	return items, last
}

// coreSubagentItems 是核心里这个子代理现在的条目（和客户端拼出来的比）。
func coreSubagentItems(c *Conversation, toolUse string) []any {
	v, _ := c.Call(func() (any, error) {
		l := c.subagents.logs[toolUse]
		if l == nil {
			return []byte("[]"), nil
		}
		return json.Marshal(l.items)
	})
	var out []any
	_ = json.Unmarshal(v.([]byte), &out)
	if out == nil {
		out = []any{}
	}
	return out
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("等不到：%s", what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// kinds 是条目的种类和要点（工作组写出各步的名字和状态）。
func kinds(items []any) []string {
	var out []string
	for _, it := range items {
		m := it.(map[string]any)
		s := m["kind"].(string)
		if steps, ok := m["steps"].([]any); ok {
			for _, st := range steps {
				step := st.(map[string]any)
				s += " " + step["name"].(string) + "/" + step["status"].(string)
			}
		} else {
			s += ":" + m["text"].(string)
		}
		out = append(out, s)
	}
	return out
}

func TestSubagentWatchReadsIncrementally(t *testing.T) {
	fake := &fakeSubagent{reply: func(n int, _ []string) ([]byte, error) {
		switch n {
		case 0: // 第一段：只读了一部分，后面还有
			return subagentRead(foundHead(100, 2, true, 0), promptEvent, toolUseEvent), nil
		case 1:
			return subagentRead(foundHead(200, 4, false, 0), toolDoneEvent, reportEvent), nil
		}
		return subagentRead(foundHead(200, 4, false, 0)), nil
	}}
	h, c, _ := subagentHarness(t, fake)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "读到第二段并推出去", func() bool {
		items, _ := h.subagentView(1, "toolu_1")
		return len(items) == 2
	})
	// 第一次带 offset 0、还不知道子代理 id；后面还有时马上接着从上次读到的地方读，用记录里的子代理 id
	if got := fake.call(0); !reflect.DeepEqual(got, []string{"0", "0", "toolu_1", "-", "s1"}) {
		t.Fatalf("第一次读的参数：%v", got)
	}
	if got := fake.call(1); !reflect.DeepEqual(got, []string{"100", "2", "toolu_1", "a1", "s1"}) {
		t.Fatalf("接着读的参数：%v", got)
	}
	pushes := h.subagentPushes(1, "toolu_1")
	if pushes[0]["full"] != true || pushes[0]["conv"] != "a" || pushes[0]["loaded"] != false || pushes[0]["state"] != "running" {
		t.Fatalf("开始看时先推整份（还没读到）：%v", pushes[0])
	}
	for _, p := range pushes[1:] {
		if p["full"] == true {
			t.Fatalf("之后应只推增量：%v", p)
		}
	}
	items, last := h.subagentView(1, "toolu_1")
	if want := coreSubagentItems(c, "toolu_1"); !reflect.DeepEqual(items, want) {
		t.Fatalf("客户端拼出来的条目和核心里的不一样\n客户端：%v\n核心：%v", items, want)
	}
	if got := kinds(items); !reflect.DeepEqual(got, []string{"work Read/success", "answer:查完了"}) {
		t.Fatalf("条目：%v", got)
	}
	meta := last["meta"].(map[string]any)
	if meta["agent_id"] != "a1" || meta["agent_type"] != "Explore" || meta["description"] != "查代码" ||
		meta["background"] != true || meta["finished"] != false {
		t.Fatalf("meta：%v", meta)
	}
	if last["prompt"] != "去查一下" || last["tool_uses"] != 1.0 || last["running"] != true || last["loaded"] != true ||
		last["missing"] != false || last["file"] != testFile || last["skipped"] != 0.0 || last["state"] != "running" {
		t.Fatalf("概要：%v", last)
	}
	if _, ok := last["failure"]; ok {
		t.Fatalf("读成了不该带 failure：%v", last)
	}
	// 在跑、记录没新内容：照节奏一直读，内容没变时不推
	n, before := fake.count(), len(pushes)
	waitFor(t, "按节奏接着读", func() bool { return fake.count() > n+3 })
	if after := len(h.subagentPushes(1, "toolu_1")); after != before {
		t.Fatalf("没变化也推了：%d → %d", before, after)
	}
}

func TestSubagentLiveEventsAndDedup(t *testing.T) {
	var mu sync.Mutex
	file := subagentRead(foundHead(50, 1, false, 0), promptEvent)
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		mu.Lock()
		defer mu.Unlock()
		return file, nil
	}}
	h, c, l := subagentHarness(t, fake)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "读到记录", func() bool {
		_, last := h.subagentView(1, "toolu_1")
		return last["loaded"] == true
	})
	// 输出流里子代理的事件比记录先到：先补上
	h.feed(c, l,
		`{"type":"assistant","parent_tool_use_id":"toolu_1","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}`,
		`{"type":"user","parent_tool_use_id":"toolu_1","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"内容"}]}}`,
		// 别的子代理的不算
		`{"type":"assistant","parent_tool_use_id":"toolu_2","message":{"id":"m9","content":[{"type":"tool_use","id":"t9","name":"Bash","input":{}}]}}`)
	items, last := h.subagentView(1, "toolu_1")
	if got := kinds(items); !reflect.DeepEqual(got, []string{"work Read/success"}) || last["tool_uses"] != 1.0 {
		t.Fatalf("输出流补上的：%v，工具调用 %v", got, last["tool_uses"])
	}
	// 主列表不收子代理的事件
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:派个子代理"}) {
		t.Fatalf("主列表：%v", got)
	}
	// 记录追上了：输出流那份丢掉，工具调用不重复算
	mu.Lock()
	file = subagentRead(foundHead(150, 3, false, 0), toolUseEvent, toolDoneEvent)
	mu.Unlock()
	waitFor(t, "记录追上输出流", func() bool {
		v, _ := c.Call(func() (any, error) { return len(c.subagents.logs["toolu_1"].t.liveEvents), nil })
		return v == 0
	})
	h.settle(c)
	items, last = h.subagentView(1, "toolu_1")
	if got := kinds(items); !reflect.DeepEqual(got, []string{"work Read/success"}) || last["tool_uses"] != 1.0 {
		t.Fatalf("去重后：%v，工具调用 %v", got, last["tool_uses"])
	}
	if want := coreSubagentItems(c, "toolu_1"); !reflect.DeepEqual(items, want) {
		t.Fatalf("客户端拼出来的条目和核心里的不一样\n客户端：%v\n核心：%v", items, want)
	}
}

func TestSubagentOutcomeAndSettle(t *testing.T) {
	var mu sync.Mutex
	idle := 0
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		mu.Lock()
		defer mu.Unlock()
		head := foundHead(50, 2, false, idle)
		head["agent_id"], head["meta"] = "", map[string]any{}
		return subagentRead(head, promptEvent, toolUseEvent), nil
	}}
	h, c, l := subagentHarness(t, fake)
	// task_started 记下 Agent 调用对应的子代理 id，读记录时交给 zeta-helper 免得逐个翻 meta
	h.feed(c, l, `{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_1","task_type":"local_agent"}`)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "读到记录", func() bool { return fake.count() > 0 })
	if got := fake.call(0)[3]; got != "a1" {
		t.Fatalf("子代理 id：%v", got)
	}
	waitFor(t, "在跑", func() bool {
		_, last := h.subagentView(1, "toolu_1")
		return last["running"] == true && last["loaded"] == true
	})
	// 结局以输出流的为准：报了结局就不在跑，最后那个没结果的工具调用收口成已中断
	h.feed(c, l, `{"type":"system","subtype":"task_notification","task_id":"a1","status":"completed"}`)
	items, last := h.subagentView(1, "toolu_1")
	if last["running"] != false || last["outcome"] != "completed" {
		t.Fatalf("报了结局还在跑：%v", last)
	}
	if got := kinds(items); !reflect.DeepEqual(got, []string{"work Read/failed"}) {
		t.Fatalf("条目：%v", got)
	}
	// 记录还在写（idle 小）时接着读；停下来以后不再读
	n := fake.count()
	waitFor(t, "结束后接着读", func() bool { return fake.count() > n+2 })
	mu.Lock()
	idle = subagentSettleMs
	mu.Unlock()
	waitFor(t, "记录停下后不再读", func() bool {
		n := fake.count()
		time.Sleep(50 * time.Millisecond)
		return fake.count() == n
	})
}

func TestSubagentMetaEndsRunningAndProcessEnd(t *testing.T) {
	fake := &fakeSubagent{reply: func(n int, _ []string) ([]byte, error) {
		return subagentRead(foundHead(50, 2, false, 0), promptEvent, toolUseEvent), nil
	}}
	h, _, _ := subagentHarness(t, fake)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "在跑", func() bool {
		_, last := h.subagentView(1, "toolu_1")
		return last["running"] == true && last["loaded"] == true
	})
	// 进程没了：没有结局的子代理也不算在跑了
	h.call(1, "conv.end", map[string]any{"conv": "a"})
	_, last := h.subagentView(1, "toolu_1")
	if last["running"] != false {
		t.Fatalf("进程没了还在跑：%v", last)
	}
}

func TestSubagentMissingAndFailure(t *testing.T) {
	var mu sync.Mutex
	var reply func() ([]byte, error)
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		mu.Lock()
		defer mu.Unlock()
		return reply()
	}}
	reply = func() ([]byte, error) { return nil, errors.New("读不了") }
	h, c, _ := subagentHarness(t, fake)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "推出读失败的原因", func() bool {
		_, last := h.subagentView(1, "toolu_1")
		return last["failure"] == "读不了"
	})
	// 在跑时读不了也接着试；读成了 failure 就省掉
	mu.Lock()
	reply = func() ([]byte, error) { return subagentRead(map[string]any{"missing": true}), nil }
	mu.Unlock()
	waitFor(t, "没找到记录", func() bool {
		_, last := h.subagentView(1, "toolu_1")
		_, failed := last["failure"]
		return last["missing"] == true && last["loaded"] == true && !failed
	})
	// 不在跑又没找到：不再读
	h.feed(c, h.link(0), resultOK)
	waitFor(t, "没找到又不在跑时停下", func() bool {
		n := fake.count()
		time.Sleep(50 * time.Millisecond)
		return fake.count() == n
	})
}

func TestSubagentUnwatchAndRelease(t *testing.T) {
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		return subagentRead(foundHead(50, 2, false, 0), promptEvent), nil
	}}
	h, c, l := subagentHarness(t, fake)
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1"})
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	h.call(2, "conv.subagent", map[string]any{"conv": "b", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "两边都读到", func() bool {
		_, a := h.subagentView(1, "toolu_1")
		_, b := h.subagentView(2, "toolu_1")
		return a["loaded"] == true && b["loaded"] == true
	})
	if p := h.subagentPushes(2, "toolu_1"); p[0]["conv"] != "b" || p[0]["full"] != true {
		t.Fatalf("推给第二个客户端的用它起的名字、先给整份：%v", p[0])
	}
	// 客户端 1 停止看，客户端 2 放开对话：之后都不再收到，也不再读
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": false})
	h.call(2, "conv.close", map[string]any{"conv": "b"})
	a, b := len(h.subagentPushes(1, "toolu_1")), len(h.subagentPushes(2, "toolu_1"))
	h.feed(c, l, `{"type":"assistant","parent_tool_use_id":"toolu_1","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}`)
	waitFor(t, "没人看时不读", func() bool {
		n := fake.count()
		time.Sleep(50 * time.Millisecond)
		return fake.count() == n
	})
	if len(h.subagentPushes(1, "toolu_1")) != a || len(h.subagentPushes(2, "toolu_1")) != b {
		t.Fatal("停止看以后还在推")
	}
	// 再看时从整份重来，从上次读到的地方接着读；没人看时输出流的事件不收
	n := fake.count()
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "再看时接着读", func() bool { return fake.count() > n })
	if got := fake.call(n)[:2]; !reflect.DeepEqual(got, []string{"50", "2"}) {
		t.Fatalf("接着读的位置：%v", got)
	}
	pushes := h.subagentPushes(1, "toolu_1")
	if pushes[a]["full"] != true {
		t.Fatalf("再看时应先推整份：%v", pushes[a])
	}
	if items, _ := h.subagentView(1, "toolu_1"); len(items) != 0 {
		t.Fatalf("没人看时收了输出流的事件：%v", items)
	}
}

func TestSubagentSessionsAfterClear(t *testing.T) {
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		return subagentRead(map[string]any{"missing": true}), nil
	}}
	h, c, l := subagentHarness(t, fake)
	// /clear 换了新会话：之前的子代理记录在原来的会话目录里，两个都找
	h.feed(c, l, `{"type":"conversation_reset","new_conversation_id":"s2"}`)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "toolu_1", "watch": true})
	waitFor(t, "读一次", func() bool { return fake.count() > 0 })
	if got := fake.call(0)[4:]; !reflect.DeepEqual(got, []string{"s2", "s1"}) {
		t.Fatalf("找记录的会话：%v", got)
	}
}

func TestSubagentKeepsRecentEvents(t *testing.T) {
	var events []string
	for i := range subagentKeep + 5 {
		id := "t" + strconv.Itoa(i)
		events = append(events, `{"type":"assistant","message":{"id":"m`+id+`","content":[{"type":"tool_use","id":"`+id+`","name":"Read","input":{}}]}}`)
	}
	var tr subagentTranscript
	head := foundHead(1, 1, false, 0)
	head["skipped"] = 3
	tr.applyRead(subagentRead(head, events...))
	if len(tr.fileEvents) != subagentKeep || tr.skipped != 3+5 || tr.toolUses() != subagentKeep+5 {
		t.Fatalf("留下 %d 条，跳过 %d，工具调用 %d", len(tr.fileEvents), tr.skipped, tr.toolUses())
	}
	// 换了记录文件：从头来
	head = foundHead(1, 1, false, 0)
	head["file"] = "/other.jsonl"
	tr.applyRead(subagentRead(head, toolUseEvent))
	if len(tr.fileEvents) != 1 || tr.skipped != 0 || tr.prompt != "" || tr.toolUses() != 1 {
		t.Fatalf("换文件后：%d 条，跳过 %d，指令 %q", len(tr.fileEvents), tr.skipped, tr.prompt)
	}
}

func TestSubagentPace(t *testing.T) {
	p := defaultSubagentPace
	cases := []struct {
		running bool
		idle    int
		want    time.Duration
	}{{false, 0, 700 * time.Millisecond}, {true, 3, 1500 * time.Millisecond}, {true, 4, 4 * time.Second}}
	for _, tc := range cases {
		if got := p.delay(tc.running, tc.idle); got != tc.want {
			t.Errorf("在跑 %v、连着 %d 次没变：%v，应为 %v", tc.running, tc.idle, got, tc.want)
		}
	}
}

func TestRawDelta(t *testing.T) {
	enc := func(s ...string) []json.RawMessage {
		out := make([]json.RawMessage, len(s))
		for i, x := range s {
			out[i] = json.RawMessage(`"` + x + `"`)
		}
		return out
	}
	cases := []struct {
		old, now          []json.RawMessage
		at, remove, items int
		changed           bool
	}{
		{enc("a", "b"), enc("a", "b"), 0, 0, 0, false},
		{enc("a", "b"), enc("a", "b", "c"), 2, 0, 1, true},
		{enc("a", "b"), enc("a", "x"), 1, 1, 1, true},
		{enc("a", "b", "c"), enc("b", "c"), 0, 1, 0, true},
		{nil, enc("a"), 0, 0, 1, true},
	}
	for i, tc := range cases {
		at, remove, items, changed := rawDelta(tc.old, tc.now)
		if at != tc.at || remove != tc.remove || len(items) != tc.items || changed != tc.changed {
			t.Errorf("第 %d 个：at %d remove %d items %d changed %v", i, at, remove, len(items), changed)
		}
	}
}

func TestSubagentTrackerKeepsRecentLogs(t *testing.T) {
	var s subagentTracker
	for i := range subagentMaxLogs + 1 {
		s.log("u" + strconv.Itoa(i))
	}
	if _, ok := s.logs["u0"]; ok || len(s.logs) != subagentMaxLogs {
		t.Fatalf("应收掉最早看的那个：%d 个", len(s.logs))
	}
	// 有人看着的不收；都有人看着时新的那个也留着
	for _, l := range s.logs {
		l.watchers[1] = &subagentWatcher{}
	}
	if l := s.log("new"); s.logs["new"] != l || len(s.logs) != subagentMaxLogs+1 {
		t.Fatalf("有人看着的被收掉了：%d 个", len(s.logs))
	}
}

// 查看页的状态由核心按先后定：在跑优先，没读到记录时正在读取或读失败，记录文件没有，结局，最后是没做完。
func TestSubagentState(t *testing.T) {
	for _, c := range []struct {
		s    subagentStatus
		want string
	}{
		{subagentStatus{Running: true, Missing: true, Outcome: "completed"}, "running"},
		{subagentStatus{}, "loading"},
		{subagentStatus{Failure: "读不了", Outcome: "completed"}, "read_failed"},
		{subagentStatus{Loaded: true, Missing: true, Outcome: "completed"}, "missing"},
		{subagentStatus{Loaded: true, Outcome: "completed"}, "completed"},
		{subagentStatus{Loaded: true, Outcome: "failed"}, "failed"},
		{subagentStatus{Loaded: true, Outcome: "stopped"}, "stopped"},
		{subagentStatus{Loaded: true, Failure: "上次读不了"}, "unfinished"},
		{subagentStatus{Loaded: true, Outcome: "别的"}, "unfinished"},
	} {
		if got := subagentState(c.s); got != c.want {
			t.Errorf("%+v：%q，要 %q", c.s, got, c.want)
		}
	}
}

// 查看页的模型和思考强度：记录里有回答时用回答实际用的；还没有时用 meta 里、再是 Agent 调用参数里指定的；
// 连 meta 都没读到时整份用调用的参数。
func TestSubagentShowsTheModelAndEffortItActuallyUsed(t *testing.T) {
	c := &Conversation{r: transcript.New("t")}
	l := &subagentLog{toolUseID: "toolu_1", call: &agentCall{Model: "opus", Effort: "high"}}
	meta := func() *subagentMeta {
		var s subagentStatus
		c.fillSubagentStatus(l, &s)
		return s.Meta
	}
	if m := meta(); m == nil || m.Model != "opus" || m.Effort != "high" {
		t.Fatalf("还没读到记录：%+v", m)
	}
	head := foundHead(10, 1, false, 0)
	head["meta"] = map[string]any{"model": "sonnet"}
	l.t.applyRead(subagentRead(head, promptEvent))
	if m := meta(); m.Model != "sonnet" || m.Effort != "high" {
		t.Fatalf("只有 meta：%+v", m)
	}
	l.t.applyRead(subagentRead(foundHead(20, 2, false, 0),
		`{"type":"assistant","effort":"medium","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"text","text":"x"}]}}`,
		reportEvent))
	if m := meta(); m.Model != "claude-sonnet-5-5" || m.Effort != "medium" {
		t.Fatalf("有了回答：%+v", m)
	}
}
