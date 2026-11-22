package conv

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/transcript"
)

// 核心替客户端算好的对话规则：任务清单、原样重发、各轮能做什么、别处占用、斜杠命令、后台命令输出、子代理概要。

// toolCall 是 Claude Code 调一次工具的两行：调用和结果（result 为空时只有调用，结果还没来）。
func toolCall(msg, id, name, input, result string, failed bool) []string {
	lines := []string{`{"type":"assistant","message":{"id":"` + msg + `","content":[{"type":"tool_use","id":"` + id +
		`","name":"` + name + `","input":` + input + `}]}}`}
	if result != "" {
		b, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"content": []any{
			map[string]any{"type": "tool_result", "tool_use_id": id, "content": result, "is_error": failed}}}})
		lines = append(lines, string(b))
	}
	return lines
}

// todos 是客户端 client 手里的任务清单，写成「编号 状态 内容/进行中的说法」。
func (h *harness) todos(client int) []string {
	list, _ := h.state(client, "todos").([]any)
	out := []string{}
	for _, x := range list {
		m := x.(map[string]any)
		out = append(out, m["key"].(string)+" "+m["status"].(string)+" "+m["content"].(string)+"/"+m["active_form"].(string))
	}
	return out
}

// todoPushes 是客户端 client 收到的带 todos 的 conv.state 有几条。
func (h *harness) todoPushes(client int) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	n := 0
	for _, m := range h.client(client).msgs {
		if _, ok := m["todos"]; ok && m["type"] == "conv.state" {
			n++
		}
	}
	return n
}

func TestTodosFollowTaskTools(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "开工"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	if got := h.todos(1); len(got) != 0 {
		t.Fatalf("还没有清单：%v", got)
	}
	h.feed(c, l, toolCall("m1", "t1", "TaskCreate", `{"subject":"写代码","activeForm":"正在写代码"}`, "Task #1 created successfully: 写代码", false)...)
	// 结果还没来的那项先按调用的 id 列着
	h.feed(c, l, toolCall("m2", "t2", "TaskCreate", `{"subject":"跑测试"}`, "", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"1 pending 写代码/正在写代码", "t2 pending 跑测试/跑测试"}) {
		t.Fatalf("新建中的清单：%v", got)
	}
	h.feed(c, l, `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"Task #2 created successfully: 跑测试"}]}}`)
	h.feed(c, l, toolCall("m3", "t3", "TaskCreate", `{"subject":"没建成"}`, "出错了", true)...)
	h.feed(c, l, toolCall("m4", "t4", "TaskUpdate", `{"taskId":"1","status":"in_progress"}`, "ok", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"1 in_progress 写代码/正在写代码", "2 pending 跑测试/跑测试"}) {
		t.Fatalf("改过状态的清单：%v", got)
	}
	// 回答文字不动清单：不推 todos
	pushes := h.todoPushes(1)
	h.feed(c, l, answerLines("m5", "做着呢")...)
	if h.todoPushes(1) != pushes {
		t.Fatal("清单没变也推了 todos")
	}
	h.feed(c, l, toolCall("m6", "t6", "TaskUpdate", `{"taskId":"2","status":"deleted"}`, "ok", false)...)
	h.feed(c, l, toolCall("m7", "t7", "TaskUpdate", `{"taskId":"1","status":"completed","subject":"写完代码"}`, "ok", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"1 completed 写完代码/正在写代码"}) {
		t.Fatalf("删掉一项、做完一项：%v", got)
	}
	// 旧清单全做完了又新建：算一份新清单
	h.feed(c, l, toolCall("m8", "t8", "TaskCreate", `{"subject":"下一件"}`, "Task #3 created successfully: 下一件", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"3 pending 下一件/下一件"}) {
		t.Fatalf("新的一份清单：%v", got)
	}
}

func TestTodosFromSeedAndTodoWrite(t *testing.T) {
	h := newHarness(t)
	seed := []any{
		map[string]any{"type": "assistant", "message": map[string]any{"id": "m0", "content": []any{map[string]any{
			"type": "tool_use", "id": "w0", "name": "TodoWrite", "input": map[string]any{"todos": []any{
				map[string]any{"content": "读需求", "status": "completed", "activeForm": "正在读需求"},
				map[string]any{"content": "动手", "status": "in_progress", "activeForm": "正在动手"},
				map[string]any{"content": " ", "status": "pending"},
			}}}}}},
		map[string]any{"type": "user", "message": map[string]any{"content": []any{map[string]any{
			"type": "tool_result", "tool_use_id": "w0", "content": "ok"}}}},
	}
	header, _ := json.Marshal(map[string]any{"type": "zeta_history", "skipped": 40, "anchor": "u1", "todo_seed": seed})
	h.history = []byte(string(header) + "\n" +
		`{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"接着做"}]}}` + "\n")
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.settle(c)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"todo-0 completed 读需求/正在读需求", "todo-1 in_progress 动手/正在动手"}) {
		t.Fatalf("从没载入的历史还原的清单：%v", got)
	}
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "继续"})
	l := h.link(0)
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, toolCall("m1", "w1", "TodoWrite", `{"todos":[{"content":"收尾","status":"pending"}]}`, "ok", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"todo-0 pending 收尾/收尾"}) {
		t.Fatalf("后一次 TodoWrite 换掉整份：%v", got)
	}
	// 任务工具的清单不空就用它
	h.feed(c, l, toolCall("m2", "t1", "TaskCreate", `{"subject":"任务"}`, "Task #1 created successfully: 任务", false)...)
	if got := h.todos(1); !reflect.DeepEqual(got, []string{"1 pending 任务/任务"}) {
		t.Fatalf("任务工具优先：%v", got)
	}
}

// 各轮能做什么和执行时的核对一致：rewindable 同 rewindTarget，forkable 同 branchEnd 加上有会话号。
func TestRoundsMatchTheChecks(t *testing.T) {
	r := transcript.New("")
	u1 := r.AddUser("一", nil, nil, "uuid-1")
	work := &transcript.Item{Kind: transcript.KindWork, ID: "w-1"}
	r.Items = append(r.Items, work)
	r.AddNotice("已停止", false)
	u2 := r.AddUser("二", nil, nil, "") // Claude Code 不认得：不能改，前一轮也剪不准
	r.AddNotice("已停止", false)
	u3 := r.AddUser("三", nil, nil, "uuid-3")
	failed := r.AddNotice("出错了", true)
	r.AddNotice("已停止", false)
	for _, session := range []string{"s1", ""} {
		c := &Conversation{r: r, session: session}
		c.markRounds()
		want := map[*transcript.Item]transcript.Round{
			u1: {Rewindable: true, Forkable: false, Last: work.ID},
			u2: {Rewindable: false, Forkable: session != "", Last: ""},
			u3: {Rewindable: true, Forkable: session != "", Last: failed.ID},
		}
		for it, w := range want {
			got := transcript.Round{Rewindable: it.Rewindable, Forkable: it.Forkable, Last: it.RoundLast}
			if got != w {
				t.Errorf("会话号 %q，%s：%+v，要 %+v", session, it.Text, got, w)
			}
			_, _, ok := branchEnd(r.Items, it.ID)
			if it.Forkable != (ok && session != "") {
				t.Errorf("会话号 %q，%s：forkable %v，branchEnd %v", session, it.Text, it.Forkable, ok)
			}
		}
	}
	// 没变时不算改过，不重推
	c := &Conversation{r: r, session: "s1"}
	c.markRounds()
	rev := u1.Rev()
	c.markRounds()
	if u1.Rev() != rev {
		t.Fatal("没变也改了 rev")
	}
}

func TestRoundsArePushed(t *testing.T) {
	h, c, l := twoTurns(t)
	first, second := h.item(1, "user", "一"), h.item(1, "user", "二")
	answer1, answer2 := h.item(1, "answer", "回答一"), h.item(1, "answer", "回答二")
	if first["rewindable"] != true || first["forkable"] != true || first["round_last"] != answer1["id"] ||
		second["round_last"] != answer2["id"] || second["forkable"] != true {
		t.Fatalf("各轮：%v %v", first, second)
	}
	// 新的一轮开始、操作行跟着挪
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	h.settle(c)
	if third := h.item(1, "user", "三"); third["round_last"] != nil || third["rewindable"] != true {
		t.Fatalf("刚发出的一轮：%v", third)
	}
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m3", "回答三")...)
	if third := h.item(1, "user", "三"); third["round_last"] != h.item(1, "answer", "回答三")["id"] {
		t.Fatalf("答出来以后：%v", third)
	}
}

// pngData 是一张 1×1 的 PNG（base64）。
const pngData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

// sentUser 是写进 l 的最后一条用户消息的内容块。
func sentUser(t *testing.T, l *fakeLink) []map[string]any {
	t.Helper()
	users := l.lines("user")
	if len(users) == 0 {
		t.Fatal("没发用户消息")
	}
	var blocks []map[string]any
	b, _ := json.Marshal(users[len(users)-1]["message"].(map[string]any)["content"])
	_ = json.Unmarshal(b, &blocks)
	return blocks
}

func TestResendRewritesTheSameMessage(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "看这张图",
		"images": []any{map[string]any{"media_type": "image/png", "data": pngData}}, "files": []any{"/w/a.txt"}})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, answerLines("m1", "看到了")...)
	h.feed(c, l, resultOK)
	first := sentUser(t, l)
	target := h.item(1, "user", "看这张图")

	// 客户端只给 target 和 resend，新消息的字和图都由核心照原样拼
	h.call(1, "conv.rewrite", map[string]any{"conv": "a", "target": target["id"], "resend": true, "text": "不看这句"})
	req, id := waitRequest(t, l, "rewind_conversation", 1)
	if req["target_message_uuid"] != target["uuid"] {
		t.Fatalf("剪的位置：%v", req)
	}
	h.respond(c, l, id, `{"rewound":true}`, "")
	if len(l.lines("user")) != 2 {
		t.Fatalf("剪完没重发：%v", l.lines("user"))
	}
	again := sentUser(t, l)
	if !reflect.DeepEqual(again, first) {
		t.Fatalf("重发的和原来的不一样\n原来：%v\n重发：%v", first, again)
	}
	if got := h.texts(1); !reflect.DeepEqual(got, []string{"user:看这张图"}) {
		t.Fatalf("剪完重发后的条目：%v", got)
	}
}

// 会话记录里的图由核心经 SessionImage 取回再发；取不回时什么也不剪。
func TestResendFetchesImagesFromTheRecord(t *testing.T) {
	h := newHarness(t)
	var fetched atomic.Int32
	fail := atomic.Bool{}
	h.m.deps.SessionImage = func(file, ref string) (Image, error) {
		fetched.Add(1)
		if file != "/f.jsonl" || ref != "0.1" || fail.Load() {
			return Image{}, errors.New("no such image")
		}
		return Image{MediaType: "image/png", Data: pngData}, nil
	}
	h.history = []byte(`{"type":"zeta_history","skipped":0}
{"type":"user","uuid":"u1","message":{"content":[{"type":"text","text":"[Attached file: /w/b.txt]\n\n记录里的图"},{"type":"image","ref":"0.1"}]}}
{"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"旧回答"}]}}
`)
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "file": "/f.jsonl", "cwd": "/w"})
	c := h.conv(1, "a")
	waitLoaded(t, h, c)
	h.settle(c)
	target := h.item(1, "user", "记录里的图")

	fail.Store(true)
	b, _ := json.Marshal(map[string]any{"conv": "a", "target": target["id"], "resend": true})
	if _, err := h.m.Request(1, "conv.rewrite", b); err == nil || !strings.Contains(err.Error(), "图片") {
		t.Fatalf("取不回图时：%v", err)
	}
	if len(h.links) != 0 {
		t.Fatal("取不回图还起了进程去剪")
	}

	fail.Store(false)
	h.call(1, "conv.rewrite", map[string]any{"conv": "a", "target": target["id"], "resend": true})
	l := h.link(0)
	_, id := waitRequest(t, l, "rewind_conversation", 1)
	h.respond(c, l, id, `{"rewound":true}`, "")
	blocks := sentUser(t, l)
	if len(blocks) != 2 || blocks[0]["type"] != "image" || blocks[1]["text"] != "[Attached file: /w/b.txt]\n\n记录里的图" {
		t.Fatalf("重发的内容：%v", blocks)
	}
	if src := blocks[0]["source"].(map[string]any); src["media_type"] != "image/png" || src["data"] != pngData {
		t.Fatalf("重发的图：%v", src)
	}
	if fetched.Load() != 2 {
		t.Fatalf("取了 %d 次图", fetched.Load())
	}
}

// 还原代码带 resend：还原成了再照原样重发。
func TestRestoreCodeThenResend(t *testing.T) {
	h, c, l := twoTurns(t)
	second := h.item(1, "user", "二")
	h.call(1, "conv.restore_code", map[string]any{"conv": "a", "target": second["id"], "resend": true})
	_, id := waitRequest(t, l, "rewind_files", 1)
	h.respond(c, l, id, `{"canRewind":true}`, "")
	_, id = waitRequest(t, l, "rewind_conversation", 1)
	h.respond(c, l, id, `{"rewound":true}`, "")
	if got := sentTexts(l); !reflect.DeepEqual(got, []string{"一", "二", "二"}) {
		t.Fatalf("发出去的：%v", got)
	}
}

func TestOccupantIsWatchedAndReleases(t *testing.T) {
	old := occupantPoll
	occupantPoll = 10 * time.Millisecond
	t.Cleanup(func() { occupantPoll = old })
	h := newHarness(t)
	var mu sync.Mutex
	status, waiting := "waiting", "Bash"
	held := true
	calls := 0
	h.m.deps.Occupant = func(session string) sessionproto.Occupancy {
		mu.Lock()
		defer mu.Unlock()
		calls++
		if !held || session != "s1" {
			return sessionproto.Occupancy{HandsOver: true}
		}
		s, w := status, waiting
		return sessionproto.Occupancy{Status: &s, WaitingFor: &w, HandsOver: s != "busy"}
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1", "cwd": "/w"})
	occupant := func(client int) map[string]any { m, _ := h.state(client, "occupant").(map[string]any); return m }
	waitFor(t, "打开时就带着占用", func() bool {
		o := occupant(1)
		return h.state(1, "blocked") == true && o["status"] == "waiting" && o["waiting_for"] == "Bash" && o["hands_over"] == true
	})
	mu.Lock()
	status = "busy"
	mu.Unlock()
	waitFor(t, "那边忙起来了", func() bool { o := occupant(2); return o["status"] == "busy" && o["hands_over"] == false })
	// 两个客户端拿着同一个对话，也只盯一份：每轮只查一次
	mu.Lock()
	before := calls
	mu.Unlock()
	time.Sleep(55 * time.Millisecond)
	mu.Lock()
	polled := calls - before
	held = false
	mu.Unlock()
	if polled > 7 {
		t.Fatalf("50 毫秒里查了 %d 次：盯了不止一份", polled)
	}
	waitFor(t, "那边退出后自动解开", func() bool {
		return h.state(1, "blocked") == false && h.state(1, "occupant") == nil && h.state(2, "blocked") == false
	})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "接着"})
	mu.Lock()
	after := calls
	mu.Unlock()
	time.Sleep(40 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if calls != after {
		t.Fatalf("解开以后还在查：%d → %d", after, calls)
	}
}

// 起进程时才发现被占着（held_elsewhere）：没查过那边，马上查一次，之后照样盯着。
func TestOccupantAfterHeldElsewhere(t *testing.T) {
	old := occupantPoll
	occupantPoll = 10 * time.Millisecond
	t.Cleanup(func() { occupantPoll = old })
	h := newHarness(t)
	var held atomic.Bool
	idle := "idle"
	h.m.deps.Occupant = func(session string) sessionproto.Occupancy {
		if held.Load() && session == "s1" {
			return sessionproto.Occupancy{Status: &idle, HandsOver: true}
		}
		return sessionproto.Occupancy{HandsOver: true}
	}
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	held.Store(true)
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	l.events.Exit(Exit{Reason: reasonHeldElsewhere})
	waitFor(t, "锁上并带着占用", func() bool {
		o, _ := h.state(1, "occupant").(map[string]any)
		return h.state(1, "blocked") == true && o["status"] == "idle"
	})
	held.Store(false)
	waitFor(t, "那边退出后解开", func() bool { return h.state(1, "blocked") == false })
	h.settle(c)
}

func TestCommandsAreRememberedPerCwd(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core", "commands.json")
	h := newHarness(t)
	h.m.deps.Commands = NewCommandStore(path)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	if h.state(1, "commands") != nil {
		t.Fatalf("还没拿到过就有命令：%v", h.state(1, "commands"))
	}
	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	c, l := h.conv(1, "a"), h.link(0)
	_, id := waitRequest(t, l, "initialize", 1)
	h.respond(c, l, id, `{"commands":[{"name":"review","description":"审查"},{"name":"__internal"},{"name":""},{"description":"没名字"},{"name":"compact"}]}`, "")
	if got := commandNames(h, 1); !reflect.DeepEqual(got, []string{"review", "compact"}) {
		t.Fatalf("内部命令、没有名字的没滤掉：%v", got)
	}

	// 换一个核心（新的一份存储读同一个文件）：还没起进程，打开同一目录、别的目录都直接给
	h2 := newHarness(t)
	h2.m.deps.Commands = NewCommandStore(path)
	h2.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h2.call(2, "conv.open", map[string]any{"conv": "b", "cwd": "/elsewhere"})
	waitFor(t, "记着的命令", func() bool {
		return reflect.DeepEqual(commandNames(h2, 1), []string{"review", "compact"}) && reflect.DeepEqual(commandNames(h2, 2), []string{"review", "compact"})
	})
	if len(h2.links) != 0 {
		t.Fatal("给命令还起了进程")
	}
	// 同一目录的优先于别处最近拿到的
	store := NewCommandStore(path)
	store.Remember("/other", json.RawMessage(`[{"name":"other"}]`))
	if got := string(store.Recall("/w")); !strings.Contains(got, "review") {
		t.Fatalf("/w 的命令：%s", got)
	}
	if got := string(store.Recall("/new")); !strings.Contains(got, "other") {
		t.Fatalf("没记过的目录用最近的：%s", got)
	}
	// 文件坏了只是没有
	if err := os.WriteFile(path, []byte("{坏"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := NewCommandStore(path).Recall("/w"); got != nil {
		t.Fatalf("坏文件读出了：%s", got)
	}
}

// commandNames 是客户端 client 手里的斜杠命令的名字。
func commandNames(h *harness, client int) []string {
	var out []string
	list, _ := h.state(client, "commands").([]any)
	for _, x := range list {
		out = append(out, x.(map[string]any)["name"].(string))
	}
	return out
}

// taskOutputs 是客户端 client 收到的给任务 task 的 conv.task_output 的文字。
func (h *harness) taskOutputs(client int, task string) []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []string
	for _, m := range h.client(client).other {
		if m["type"] == "conv.task_output" && m["task"] == task {
			out = append(out, m["text"].(string))
		}
	}
	return out
}

// tickTaskOutput 让任务 task 排着的下一次读马上发生（不等 taskOutputPoll），返回排着没有。
func tickTaskOutput(c *Conversation, task string) bool {
	v, _ := c.Call(func() (any, error) {
		w := c.taskOutputs[task]
		if w == nil || w.timer == nil {
			return false, nil
		}
		w.timer.Stop()
		w.timer = nil
		c.readTaskOutput(task, w)
		return true, nil
	})
	return v == true
}

func TestTaskOutputIsPushedWhileWatched(t *testing.T) {
	old := taskOutputPoll
	taskOutputPoll = time.Hour // 下一次读由测试用 tickTaskOutput 叫
	t.Cleanup(func() { taskOutputPoll = old })
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "后台跑个命令"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.feed(c, l, `{"type":"system","subtype":"task_started","task_id":"b1","task_type":"local_bash","description":"构建"}`)
	h.feed(c, l, `{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"构建"}]}`)

	if v := h.call(1, "conv.task_output", map[string]any{"conv": "a", "task": "b1", "watch": true}); v != nil {
		t.Fatalf("带 watch 的应答：%v", v)
	}
	answer := func(n int, text string) {
		t.Helper()
		_, id := waitRequest(t, l, "get_task_output", n)
		b, _ := json.Marshal(map[string]any{"output": text, "total_bytes": len(text), "truncated": false})
		h.respond(c, l, id, string(b), "")
	}
	next := func() {
		t.Helper()
		if !tickTaskOutput(c, "b1") {
			t.Fatal("在跑的任务没排下一次读")
		}
	}
	answer(1, "第一行\n")
	next()
	answer(2, "第一行\n") // 没变：不推
	next()
	answer(3, "第一行\n第二行\n")
	if got := h.taskOutputs(1, "b1"); !reflect.DeepEqual(got, []string{"第一行\n", "第一行\n第二行\n"}) {
		t.Fatalf("推来的输出：%q", got)
	}
	// 任务结束：再读一次就不再问
	h.feed(c, l, `{"type":"system","subtype":"task_notification","task_id":"b1","status":"completed","summary":"退出码 0"}`)
	next()
	answer(4, "第一行\n第二行\n完成\n")
	if tickTaskOutput(c, "b1") {
		t.Fatal("结束后还排着下一次")
	}
	if n := len(l.lines("control_request/get_task_output")); n != 4 {
		t.Fatalf("结束后还在问：%d 次", n)
	}
	if got := h.taskOutputs(1, "b1"); len(got) != 3 || got[2] != "第一行\n第二行\n完成\n" {
		t.Fatalf("推来的输出：%q", got)
	}
	// 再看一次先给读到的那份；不看了就不再记着
	h.call(1, "conv.task_output", map[string]any{"conv": "a", "task": "b1", "watch": true})
	h.settle(c)
	if got := h.taskOutputs(1, "b1"); len(got) != 4 {
		t.Fatalf("再看时：%q", got)
	}
	h.call(1, "conv.task_output", map[string]any{"conv": "a", "task": "b1", "watch": false})
	if v, _ := c.Call(func() (any, error) { return len(c.taskOutputs), nil }); v != 0 {
		t.Fatalf("不看了还记着：%v", v)
	}
	// 不带 watch 的照旧读一次、在应答里给
	got := make(chan any, 1)
	go func() {
		v, err := h.m.Request(1, "conv.task_output", []byte(`{"conv":"a","task":"b1"}`))
		if err != nil {
			v = err
		}
		got <- v
	}()
	answer(5, "一次")
	if o := <-got; !strings.Contains(string(raw(o)), "一次") {
		t.Fatalf("读一次：%v", o)
	}
}

func TestSubagentSummaryFromTheAgentCall(t *testing.T) {
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		return subagentRead(map[string]any{"missing": true, "offset": 0, "line": 0}), nil
	}}
	h, c, l := subagentHarness(t, fake)
	h.feed(c, l, toolCall("m1", "agent-1", "Agent",
		`{"description":"查代码","subagent_type":"Explore","prompt":"去查一下登录","run_in_background":true}`, "", false)...)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "agent-1", "watch": true})
	waitFor(t, "先用调用的参数说话", func() bool {
		_, last := h.subagentView(1, "agent-1")
		meta, _ := last["meta"].(map[string]any)
		return meta["description"] == "查代码" && meta["agent_type"] == "Explore" && meta["agent_id"] == "" && last["prompt"] == "去查一下登录"
	})
	// 不在列表里的调用：照旧是 null
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "agent-x", "watch": true})
	waitFor(t, "找不到调用", func() bool {
		pushes := h.subagentPushes(1, "agent-x")
		return len(pushes) > 0 && pushes[len(pushes)-1]["meta"] == nil
	})
}

func TestSubagentMetaGapsFromTheAgentCall(t *testing.T) {
	fake := &fakeSubagent{reply: func(int, []string) ([]byte, error) {
		head := foundHead(50, 1, false, 0)
		head["meta"] = map[string]any{"requestShape": "background"} // 记录旁的 meta 读到了，说明和类型却是空的
		return subagentRead(head, promptEvent), nil
	}}
	h, c, l := subagentHarness(t, fake)
	h.feed(c, l, toolCall("m1", "agent-1", "Agent",
		`{"description":"查代码","subagent_type":"Explore","prompt":"去查一下","run_in_background":true}`, "", false)...)
	h.call(1, "conv.subagent", map[string]any{"conv": "a", "tool_use_id": "agent-1", "watch": true})
	waitFor(t, "空着的用调用的参数补上", func() bool {
		_, last := h.subagentView(1, "agent-1")
		meta, _ := last["meta"].(map[string]any)
		return meta["description"] == "查代码" && meta["agent_type"] == "Explore" && meta["agent_id"] == "a1"
	})
}
