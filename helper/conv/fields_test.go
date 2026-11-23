package conv

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// conv.state 里替客户端算好的几项（rewinding、last_activity、mode.shown、model_from_session、确认的 agent_label），
// 和 conv.title、带新消息的 conv.restore_code、接回进程时恢复用户的权限模式。

// 别的客户端发起的剪会话也看得到 rewinding；剪完变回假。
func TestRewindingSeenByEveryHolder(t *testing.T) {
	h, c, l := twoTurns(t)
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1", "cwd": "/w"})
	if h.conv(2, "b") != c || h.state(2, "rewinding") != false {
		t.Fatalf("第二个客户端：同一个对话 %v，rewinding %v", h.conv(2, "b") == c, h.state(2, "rewinding"))
	}
	second := h.item(1, "user", "二")
	h.call(1, "conv.drop", map[string]any{"conv": "a", "target": second["id"]})
	h.settle(c)
	if h.state(2, "rewinding") != true || h.state(1, "rewinding") != true {
		t.Fatalf("剪的期间：客户端 2 %v，客户端 1 %v", h.state(2, "rewinding"), h.state(1, "rewinding"))
	}
	_, id := lastRequest(t, l, "rewind_conversation")
	h.respond(c, l, id, `{"rewound":true}`, "")
	if h.state(2, "rewinding") != false {
		t.Fatal("剪完了 rewinding 还是真")
	}
}

// fakeClock 是测试里的时钟，Deps.Now 用。
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func (k *fakeClock) Now() time.Time { k.mu.Lock(); defer k.mu.Unlock(); return k.now }

func (k *fakeClock) set(ms int64) { k.mu.Lock(); k.now = time.UnixMilli(ms); k.mu.Unlock() }

func (h *harness) msgCount(client int) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.client(client).msgs)
}

// msgsSince 是客户端 client 收到的第 n 条之后的推送。
func (h *harness) msgsSince(client, n int) []map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	return slices.Clone(h.client(client).msgs[n:])
}

// last_activity：发消息、输出改了列表、重试、压缩时更新，取整到秒；同一秒里的几批输出不再推它。
func TestLastActivity(t *testing.T) {
	h := newHarness(t)
	clock := &fakeClock{}
	h.m.deps.Now = clock.Now
	clock.set(10_500)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	if got := h.state(1, "last_activity"); got != float64(10_000) {
		t.Fatalf("发出消息后：%v", got)
	}
	// 没改列表的输出不算
	clock.set(13_100)
	h.feed(c, l, initLine("s1"), `{"type":"control_cancel_request","request_id":"nobody"}`)
	if got := h.state(1, "last_activity"); got != float64(10_000) {
		t.Fatalf("没改列表的输出：%v", got)
	}
	clock.set(14_200)
	lines := answerLines("m1", "回答")
	h.feed(c, l, lines[:3]...)
	if got := h.state(1, "last_activity"); got != float64(14_000) {
		t.Fatalf("流式输出：%v", got)
	}
	// 同一秒里又来一批：列表变了，但 last_activity 推出去的值没变，不另推
	clock.set(14_900)
	before := h.msgCount(1)
	h.feed(c, l, lines[3])
	for _, m := range h.msgsSince(1, before) {
		if m["type"] == "conv.state" && m["last_activity"] != nil {
			t.Fatalf("同一秒里又推了 last_activity：%v", m)
		}
	}
	clock.set(31_700)
	h.feed(c, l, `{"type":"system","subtype":"api_retry","attempt":2}`)
	if got := h.state(1, "last_activity"); got != float64(31_000) || h.state(1, "status") != "请求出错，正在重试（第 2 次）" {
		t.Fatalf("重试：%v，%v", got, h.state(1, "status"))
	}
	// 状态行另带种类码和参数，客户端按自己的语言出字
	if code, args := h.state(1, "status_code"), h.state(1, "status_args"); code != "statusRetrying" ||
		(locales.Msg{Key: code.(string), Args: args.(map[string]any)}).In("en") != "Request failed, retrying (attempt 2)" {
		t.Fatalf("重试的种类码：%v %v", code, args)
	}
	clock.set(40_000)
	h.feed(c, l, `{"type":"system","subtype":"status","status":"compacting"}`)
	if got := h.state(1, "last_activity"); got != float64(40_000) {
		t.Fatalf("压缩：%v", got)
	}
}

// 后台子代理的确认带 agent_label：can_use_tool 的 agent_id 就是 task_started 的 task_id，经它找回发起的 Agent 调用、取说明。
func TestPromptAgentLabel(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "查一下"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"),
		`{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_a","name":"Agent","input":{"description":"查资料","prompt":"去查","run_in_background":true}}]}}`,
		`{"type":"system","subtype":"task_started","task_id":"ag1","tool_use_id":"toolu_a","task_type":"local_agent"}`,
		`{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"tool_use_id":"s1","agent_id":"ag1"}}`,
		`{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"pwd"},"tool_use_id":"s2","agent_id":"ag-unknown"}}`,
		`{"type":"control_request","request_id":"r3","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"id"},"tool_use_id":"m2"}}`)
	prompts, _ := h.state(1, "prompts").([]any)
	var labels []any
	for _, p := range prompts {
		labels = append(labels, p.(map[string]any)["agent_label"])
	}
	if want := []any{"查资料", "后台子代理", nil}; !reflect.DeepEqual(labels, want) {
		t.Fatalf("叫法：%v", labels)
	}
}

// mode.shown 是用户选的，没选时是 Claude Code 的默认；model_from_session 是接着已有会话、又没在这个对话里指定模型。
func TestShownModeAndModelFromSession(t *testing.T) {
	h := newHarness(t)
	h.m.deps.DefaultMode = func() string { return "acceptEdits" }
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	mode := func(client int) map[string]any { return h.state(client, "mode").(map[string]any) }
	if m := mode(1); m["shown"] != "acceptEdits" || m["user"] != "" {
		t.Fatalf("没选过：%v", m)
	}
	h.call(1, "conv.mode", map[string]any{"conv": "a", "value": "plan"})
	h.settle(h.conv(1, "a"))
	if m := mode(1); m["shown"] != "plan" || m["user"] != "plan" {
		t.Fatalf("选了以后：%v", m)
	}
	if h.state(1, "model_from_session") != false {
		t.Fatal("新对话的模型不是会话记录定的")
	}

	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s9", "cwd": "/w"})
	b := h.conv(2, "b")
	if h.state(2, "model_from_session") != true {
		t.Fatal("接着已有会话、没指定模型时应由会话记录定")
	}
	h.call(2, "conv.model", map[string]any{"conv": "b", "value": "claude-x"})
	h.settle(b)
	if h.state(2, "model_from_session") != false {
		t.Fatal("指定了模型还算会话记录定的")
	}
	// 接着已有会话时选默认要明说 default，也不再由会话记录定
	h.call(2, "conv.model", map[string]any{"conv": "b", "value": nil})
	h.settle(b)
	if h.state(2, "model_from_session") != false || h.state(2, "model") != "default" {
		t.Fatalf("选回默认：%v，%v", h.state(2, "model_from_session"), h.state(2, "model"))
	}
}

// 还原代码时带新消息：还原成了接着改写重发；没还原成时新消息放回输入框，不剪、不算一轮。
func TestRestoreCodeThenRewrite(t *testing.T) {
	h, c, l := twoTurns(t)
	second := h.item(1, "user", "二")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"], "text": "改过的二"})
	_, id := lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":true}`, "")
	req, id := lastRequest(t, l, "rewind_conversation")
	if req["target_message_uuid"] != second["uuid"] || h.state(1, "rewinding") != true {
		t.Fatalf("接着剪的请求 %v，rewinding %v", req, h.state(1, "rewinding"))
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if got := sentTexts(l); !reflect.DeepEqual(got, []string{"一", "二", "改过的二"}) {
		t.Fatalf("发出去的：%v", got)
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:一", "answer:回答一", "user:改过的二"}) {
		t.Fatalf("条目：%v", got)
	}
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m3", "新回答")...)
	h.feed(c, l, resultOK)

	// 没还原成：不剪，新消息放回输入框，不推 conv.turn
	turns := len(h.client(1).turns)
	first := h.item(1, "user", "一")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": first["id"], "text": "再改一", "files": []string{"/w/a.txt"}})
	_, id = lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":false}`, "")
	recalled, _ := h.state(1, "recalled").(map[string]any)
	if recalled["text"] != "再改一" || !reflect.DeepEqual(recalled["files"], []any{"/w/a.txt"}) {
		t.Fatalf("放回的：%v", recalled)
	}
	if got := h.texts(1); got[len(got)-1] != "notice:没有还原代码：没有这条消息之前的快照" {
		t.Fatalf("条目：%v", got)
	}
	if n := len(l.lines("control_request/rewind_conversation")); n != 1 || len(h.client(1).turns) != turns {
		t.Fatalf("没还原成也剪了（%d 次）或推了一轮（%v）", n, h.client(1).turns[turns:])
	}

	// 还原成了、剪会话没成：照 rewrite 没剪成那样放回输入框
	h.call(1, "conv.take_recalled", map[string]any{"conv": "a"})
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": first["id"], "text": "第三次"})
	_, id = lastRequest(t, l, "rewind_files")
	h.respond(c, l, id, `{"canRewind":true}`, "")
	_, id = lastRequest(t, l, "rewind_conversation")
	h.respond(c, l, id, `{"rewound":false,"reason":"stale_target"}`, "")
	recalled, _ = h.state(1, "recalled").(map[string]any)
	if recalled["text"] != "第三次" || h.state(1, "busy") != false {
		t.Fatalf("放回的 %v，busy %v", recalled, h.state(1, "busy"))
	}
}

// renamer 记下 Deps.Rename 的调用；fail 不为空时改名失败。
type renamer struct {
	mu    sync.Mutex
	calls []string
	fail  error
}

func (r *renamer) rename(file, title string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.fail != nil {
		return "", r.fail
	}
	r.calls = append(r.calls, file+"="+title)
	return "写下的" + title, nil
}

// conv.title：写会话记录、换标题、请开着的 Claude Code 也改名；之后自动起标题不再覆盖它。还没有会话号时答出错。
func TestRenameKeepsUserTitle(t *testing.T) {
	h := newHarness(t)
	names := &renamer{}
	h.m.deps.Rename = names.rename
	h.m.deps.SessionFile = func(session string) string { return "/p/" + session + ".jsonl" }
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "options": map[string]any{"auto_title": true}})
	if _, err := h.m.Request(1, "conv.title", []byte(`{"conv":"a","title":"早了"}`)); !errors.Is(err, errRenameNoSession) {
		t.Fatalf("没有会话号时改名：%v", err)
	}
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "帮我写一个排序函数，要稳定排序"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m1", "好的，这是一个稳定的归并排序")...)
	h.feed(c, l, resultOK)
	_, titleID := lastRequest(t, l, "generate_session_title")

	h.call(1, "conv.title", map[string]any{"conv": "a", "title": "排序"})
	h.settle(c)
	if !reflect.DeepEqual(names.calls, []string{"/p/s1.jsonl=排序"}) || h.state(1, "title") != "写下的排序" {
		t.Fatalf("改名：%v，标题 %v", names.calls, h.state(1, "title"))
	}
	if req, _ := lastRequest(t, l, "rename_session"); req["title"] != "写下的排序" {
		t.Fatalf("请 Claude Code 改名：%v", req)
	}
	// 改名之前发出的起标题请求回来了：不覆盖；下一轮也不再请它起
	h.respond(c, l, titleID, `{"title":"自动起的"}`, "")
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "再加上测试用例，覆盖空数组"})
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m2", "测试用例如下，含空数组")...)
	h.feed(c, l, resultOK)
	if n := len(l.lines("control_request/generate_session_title")); n != 1 || h.state(1, "title") != "写下的排序" {
		t.Fatalf("起标题请求 %d 个，标题 %v", n, h.state(1, "title"))
	}

	// 写不成：标题不变，答出错
	names.fail = errors.New("写不进")
	if _, err := h.m.Request(1, "conv.title", []byte(`{"conv":"a","title":"另一个"}`)); err == nil {
		t.Fatal("写不成也答成了")
	}
	h.settle(c)
	if h.state(1, "title") != "写下的排序" {
		t.Fatalf("写不成标题却变了：%v", h.state(1, "title"))
	}
}

// 用户的权限模式记在托管进程上，变了才写，没写成（托管目录还没建好）的下次推送再写；后台运行切的模式不记。
func TestKeepUserMode(t *testing.T) {
	h := newHarness(t)
	var mu sync.Mutex
	var kept []string
	missing := true // 刚起进程时托管目录还没有
	h.m.deps.KeepMode = func(key, mode string) error {
		mu.Lock()
		defer mu.Unlock()
		if missing {
			return fs.ErrNotExist
		}
		kept = append(kept, key+"="+mode)
		return nil
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.mode", map[string]any{"conv": "a", "value": "plan"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	mu.Lock()
	missing = false
	mu.Unlock()
	h.feed(c, l, initLine("s1"), resultOK)
	// 后台运行的一条：进程切到它的模式，用户的不变
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "定时任务", "mode": "acceptEdits"})
	h.feed(c, l, initLine("s1"), resultOK)
	h.call(1, "conv.mode", map[string]any{"conv": "a", "value": "default"})
	h.settle(c)
	mu.Lock()
	defer mu.Unlock()
	if want := []string{l.key + "=plan", l.key + "=default"}; !reflect.DeepEqual(kept, want) {
		t.Fatalf("记下的：%v", kept)
	}
}

// 接回托管进程时恢复用户的模式（进程现在在后台运行的模式上）；后台子代理的确认照样带叫法。
func TestAdoptRestoresUserModeAndAgentLabel(t *testing.T) {
	h := newHarness(t)
	var mu sync.Mutex
	var kept []string
	h.m.deps.KeepMode = func(key, mode string) error { mu.Lock(); kept = append(kept, key+"="+mode); mu.Unlock(); return nil }
	h.m.deps.DefaultMode = func() string { return "default" }
	// 会话记录里有发起后台子代理的 Agent 调用；输出日志里摘出来的 task_started 说它的 id 是 ag1
	h.history = []byte(`{"type":"zeta_history","skipped":0}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"跑一下测试"}]}}
{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_a","name":"Agent","input":{"description":"跑测试","run_in_background":true}}]}}
`)
	started := json.RawMessage(`{"type":"system","subtype":"task_started","task_id":"ag1","tool_use_id":"toolu_a","task_type":"local_agent"}`)
	pending := json.RawMessage(`{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"tool_use_id":"s1","agent_id":"ag1"}}`)
	h.m.Adopt([]Hosted{{Key: "k1", Session: "s1", File: "/s1.jsonl", Cwd: "/w", Lines: 4, Mode: "acceptEdits", UserMode: "plan",
		Pending: []json.RawMessage{pending}, Tasks: 1, TaskEvents: []json.RawMessage{started}}})
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	c := h.conv(1, "a")
	deadline := time.Now().Add(5 * time.Second)
	for h.state(1, "process") != true {
		if time.Now().After(deadline) {
			t.Fatal("没接上")
		}
		time.Sleep(5 * time.Millisecond)
		h.settle(c)
	}
	mode := h.state(1, "mode").(map[string]any)
	if mode["user"] != "plan" || mode["process"] != "acceptEdits" || mode["shown"] != "plan" {
		t.Fatalf("权限模式：%v", mode)
	}
	prompts, _ := h.state(1, "prompts").([]any)
	if len(prompts) != 1 || prompts[0].(map[string]any)["agent_label"] != "跑测试" {
		t.Fatalf("确认：%v", prompts)
	}
	// 用户下一条按恢复的模式跑：进程切回 plan
	h.call(1, "conv.answer", map[string]any{"conv": "a", "request_id": "p1", "reply": map[string]any{"kind": "allow"}})
	l := linkOf(t, h, "k1")
	h.feed(c, l, resultOK)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "接着"})
	if req, _ := lastRequest(t, l, "set_permission_mode"); req["mode"] != "plan" {
		t.Fatalf("切的模式：%v", req)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(kept) != 0 {
		t.Fatalf("接回时又写了一遍：%v", kept)
	}
}

// 最近发消息的客户端报的设备名记在托管进程上：变了才写，没写成的下次推送再写；换一个客户端发消息就换成它的，没报过的记成空。
func TestKeepDeviceOfTheLastSender(t *testing.T) {
	h := newHarness(t)
	var mu sync.Mutex
	var kept []string
	missing := true
	h.m.deps.ClientDevice = func(client int) Device {
		return map[int]Device{1: {Name: "phone"}, 2: {Local: true}}[client]
	}
	h.m.deps.KeepDevice = func(key string, d Device) error {
		mu.Lock()
		defer mu.Unlock()
		if missing {
			return fs.ErrNotExist
		}
		kept = append(kept, fmt.Sprintf("%s=%s/%v", key, d.Name, d.Local))
		return nil
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	mu.Lock()
	missing = false
	mu.Unlock()
	h.feed(c, l, initLine("s1"), resultOK)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
	h.feed(c, l, initLine("s1"), resultOK)
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1", "cwd": "/w"})
	h.call(2, "conv.send", map[string]any{"conv": "b", "text": "三"})
	h.feed(c, l, initLine("s1"), resultOK)
	h.call(3, "conv.open", map[string]any{"conv": "c", "session": "s1", "cwd": "/w"})
	h.call(3, "conv.send", map[string]any{"conv": "c", "text": "四"})
	h.settle(c)
	mu.Lock()
	defer mu.Unlock()
	if want := []string{l.key + "=phone/false", l.key + "=/true", l.key + "=/false"}; !reflect.DeepEqual(kept, want) {
		t.Fatalf("记下的：%v", kept)
	}
}
