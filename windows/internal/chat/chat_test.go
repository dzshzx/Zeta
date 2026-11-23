package chat

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// fakeLink 记下发出的请求，按方法给预先定好的应答；onCall 在应答之前跑，用来模拟核心在应答前推来的消息。
type fakeLink struct {
	mu      sync.Mutex
	calls   []call
	results map[string]string
	errs    map[string]error
	onCall  func(method string, params map[string]any)
}

type call struct {
	method string
	params string // params 的 JSON，键按字母排好
}

func (l *fakeLink) Call(_ context.Context, method string, params map[string]any) (json.RawMessage, error) {
	b, _ := json.Marshal(params)
	l.mu.Lock()
	l.calls = append(l.calls, call{method, string(b)})
	onCall := l.onCall
	result, err := l.results[method], l.errs[method]
	l.mu.Unlock()
	if onCall != nil {
		onCall(method, params)
	}
	if err != nil {
		return nil, err
	}
	if result == "" {
		return nil, nil
	}
	return json.RawMessage(result), nil
}

func (l *fakeLink) took() []call {
	l.mu.Lock()
	defer l.mu.Unlock()
	c := l.calls
	l.calls = nil
	return c
}

// opened 起一个 Client，打开一个对话：核心在应答之前推来整份的状态（会话 s1、两条条目）。
func opened(t *testing.T) (*fakeLink, *Client, *Conversation, *int) {
	t.Helper()
	link := &fakeLink{results: map[string]string{}, errs: map[string]error{}}
	notified := 0
	client := NewClient(link, func() { notified++ })
	link.onCall = func(method string, params map[string]any) {
		if method == "conv.open" {
			name := params["conv"].(string)
			client.Handle(line(`{"type":"conv.state","conv":"` + name + `","full":true,"session":"s1","title":"修 bug","busy":false,"mode":{"user":"default","process":"default"},"queued":[]}`))
			client.Handle(line(`{"type":"conv.items","conv":"` + name + `","full":true,"at":0,"remove":0,"items":[{"kind":"user","id":"a"},{"kind":"answer","id":"b"}]}`))
		}
	}
	conv, err := client.Open(context.Background(), Spec{Session: "s1", File: "/h/.claude/projects/p/s1.jsonl", Cwd: "/h/p",
		Options: Options{AutoTitle: true, CompactPercent: 80}})
	if err != nil {
		t.Fatal(err)
	}
	link.took()
	return link, client, conv, &notified
}

func line(s string) []byte { return []byte(s) }

func ids(t *testing.T, list []items.Item) string {
	t.Helper()
	out := make([]string, len(list))
	for i, it := range list {
		out[i] = it.ID
	}
	return strings.Join(out, ",")
}

func TestOpenSendsSpecAndKeepsPushesBeforeReply(t *testing.T) {
	link := &fakeLink{}
	client := NewClient(link, nil)
	link.onCall = func(_ string, params map[string]any) {
		client.Handle(line(`{"type":"conv.state","conv":"` + params["conv"].(string) + `","full":true,"session":"s9"}`))
	}
	conv, err := client.Open(context.Background(), Spec{Cwd: "/h/p", Options: Options{AutoTitle: true}})
	if err != nil {
		t.Fatal(err)
	}
	want := []call{{"conv.open", `{"conv":"w1","cwd":"/h/p","options":{"auto_title":true}}`}}
	if got := link.took(); !reflect.DeepEqual(got, want) {
		t.Fatalf("请求 %v，要 %v", got, want)
	}
	if v := conv.Take(); v.State.Session != "s9" || !v.Connected {
		t.Fatalf("应答前推来的状态丢了：%+v", v)
	}
}

func TestOpenFailureForgetsConversation(t *testing.T) {
	link := &fakeLink{errs: map[string]error{"conv.open": errors.New("这条连接已经有叫 w1 的对话")}}
	client := NewClient(link, nil)
	if _, err := client.Open(context.Background(), Spec{}); err == nil {
		t.Fatal("打开失败没报错")
	}
	if n := len(client.Conversations()); n != 0 {
		t.Fatalf("打开失败的对话还留着 %d 个", n)
	}
}

func TestStateMergesPartialAndReplacesFull(t *testing.T) {
	_, client, conv, notified := opened(t)
	before := *notified
	client.Handle(line(`{"type":"conv.state","conv":"w1","busy":true,"status":"正在重试","model":"opus"}`))
	s := conv.Take().State
	if !s.Busy || s.Status != "正在重试" || s.Title != "修 bug" || s.Session != "s1" || s.Model == nil || *s.Model != "opus" {
		t.Fatalf("部分更新没合进去或丢了别的字段：%+v", s)
	}
	if *notified != before+1 {
		t.Fatalf("合并后没通知界面")
	}
	client.Handle(line(`{"type":"conv.state","conv":"w1","model":null}`))
	if s := conv.Take().State; s.Model != nil {
		t.Fatalf("model 推成 null 后还是 %v", *s.Model)
	}
	client.Handle(line(`{"type":"conv.state","conv":"w1","full":true,"session":"s1"}`))
	if s := conv.Take().State; s.Busy || s.Title != "" {
		t.Fatalf("整份没替掉旧字段：%+v", s)
	}
}

func TestItemsAreACopy(t *testing.T) {
	_, client, conv, _ := opened(t)
	v := conv.Items()
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":0,"remove":1,"items":[{"id":"新"}]}`))
	if got := ids(t, v); got != "a,b" {
		t.Fatalf("拿着的条目被后来的推送改成了 %s", got)
	}
}

// 界面按 Take 交来的变化依次套上，和协议层的整份一样；整份的那次之前的变化丢掉。
func TestTakeHandsOverChangesInOrder(t *testing.T) {
	_, client, conv, _ := opened(t)
	first := conv.Take()
	if len(first.Items) != 1 || !first.Items[0].Full || ids(t, first.Items[0].Items) != "a,b" {
		t.Fatalf("打开后的第一份：%+v", first.Items)
	}
	shown := first.Items[0].Items
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":1,"remove":1,"items":[{"kind":"answer","id":"b2"}]}`))
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":2,"remove":0,"items":[{"kind":"work","id":"c"}]}`))
	u := conv.Take()
	if len(u.Items) != 2 {
		t.Fatalf("两次增量交来 %d 次", len(u.Items))
	}
	for _, ch := range u.Items {
		var err error
		if shown, err = items.Splice(shown, ch.At, ch.Remove, ch.Items); err != nil {
			t.Fatal(err)
		}
	}
	if ids(t, shown) != ids(t, conv.Items()) {
		t.Fatalf("套上增量后是 %s，协议层是 %s", ids(t, shown), ids(t, conv.Items()))
	}
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":0,"remove":0,"items":[{"id":"z"}]}`))
	client.Handle(line(`{"type":"conv.items","conv":"w1","full":true,"items":[{"id":"x"}]}`))
	if u := conv.Take(); len(u.Items) != 1 || !u.Items[0].Full {
		t.Fatalf("整份之前的增量没丢：%+v", u.Items)
	}
	if u := conv.Take(); u.Items != nil || u.State.Session != "s1" {
		t.Fatalf("取走后：%+v", u)
	}
}

func TestItemsSplice(t *testing.T) {
	_, client, conv, _ := opened(t)
	steps := []struct{ push, want string }{
		// 流式回答：换掉最后一项。
		{`{"at":1,"remove":1,"items":[{"kind":"answer","id":"b2"}]}`, "a,b2"},
		// 接着加一项。
		{`{"at":2,"remove":0,"items":[{"kind":"work","id":"c"}]}`, "a,b2,c"},
		// 往前载入更早的：在开头插入。
		{`{"at":0,"remove":0,"items":[{"kind":"user","id":"z"}]}`, "z,a,b2,c"},
		// 删除一轮：去掉中间两项。
		{`{"at":1,"remove":2,"items":[]}`, "z,c"},
		// 整份。
		{`{"full":true,"items":[{"kind":"user","id":"x"}]}`, "x"},
	}
	for _, s := range steps {
		client.Handle(line(`{"type":"conv.items","conv":"w1",` + s.push[1:]))
		if got := ids(t, conv.Items()); got != s.want {
			t.Fatalf("推 %s 后是 %s，要 %s", s.push, got, s.want)
		}
	}
}

func TestItemsOutOfRangeReopens(t *testing.T) {
	link, client, conv, _ := opened(t)
	done := make(chan struct{})
	link.onCall = func(method string, params map[string]any) {
		if method == "conv.open" {
			client.Handle(line(`{"type":"conv.items","conv":"w1","full":true,"items":[{"id":"fresh"}]}`))
			close(done)
		}
	}
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":1,"remove":5,"items":[]}`))
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("对不上以后没重新打开")
	}
	calls := link.took()
	if len(calls) != 2 || calls[0].method != "conv.close" || calls[1].method != "conv.open" ||
		!strings.Contains(calls[1].params, `"session":"s1"`) {
		t.Fatalf("重新打开的请求不对：%v", calls)
	}
	if got := ids(t, conv.Items()); got != "fresh" {
		t.Fatalf("重新打开后条目是 %s", got)
	}
}

func TestTurns(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.turn","conv":"w1","phase":"started"}`))
	client.Handle(line(`{"type":"conv.turn","conv":"w1","phase":"finished","outcome":"done","text":"已完成"}`))
	want := []Turn{{Phase: TurnStarted}, {Phase: TurnFinished, Outcome: OutcomeDone, Text: "已完成"}}
	if got := conv.Take().Turns; !reflect.DeepEqual(got, want) {
		t.Fatalf("一轮的起止 %+v，要 %+v", got, want)
	}
	if got := conv.Take().Turns; got != nil {
		t.Fatalf("取走后还有 %+v", got)
	}
}

func TestHandleLeavesOtherPushes(t *testing.T) {
	client := NewClient(&fakeLink{}, nil)
	for _, l := range []string{`{"type":"zeta_sessions","full":true}`, `{"type":"zeta_hello","protocol":2}`, `不是 JSON`} {
		if client.Handle(line(l)) {
			t.Fatalf("%s 被当成了对话的推送", l)
		}
	}
}

func TestDisconnectAndReconnect(t *testing.T) {
	link, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","session":"s2"}`)) // 比如压缩后换了会话号
	client.Disconnected()
	if conv.Take().Connected {
		t.Fatal("断线后还标着连上")
	}
	if err := conv.Interrupt(context.Background()); !errors.Is(err, errNotConnected) {
		t.Fatalf("断线时的请求：%v", err)
	}
	if len(link.took()) != 0 {
		t.Fatal("断线时还发了请求")
	}
	if err := client.Reconnected(context.Background()); err != nil {
		t.Fatal(err)
	}
	calls := link.took()
	if len(calls) != 1 || calls[0].method != "conv.open" || !strings.Contains(calls[0].params, `"session":"s2"`) ||
		!strings.Contains(calls[0].params, `"conv":"w1"`) || !strings.Contains(calls[0].params, `"compact_percent":80`) {
		t.Fatalf("重连后的打开：%v", calls)
	}
	if !conv.Take().Connected {
		t.Fatal("重连后没标回连上")
	}
}

func TestRequests(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()
	opus := "opus"
	run := []struct {
		do   func() error
		want call
	}{
		{func() error {
			return conv.Send(ctx, Outgoing{Text: "看图", Images: []coreapi.Image{{MediaType: "image/png", Data: []byte("hi")}}, Files: []string{"/h/a.go"}})
		}, call{"conv.send", `{"conv":"w1","files":["/h/a.go"],"images":[{"media_type":"image/png","data":"aGk="}],"text":"看图"}`}},
		{func() error { return conv.Send(ctx, Outgoing{Text: "好"}) }, call{"conv.send", `{"conv":"w1","text":"好"}`}},
		{func() error { return conv.Interrupt(ctx) }, call{"conv.interrupt", `{"conv":"w1"}`}},
		{func() error { return conv.Steer(ctx) }, call{"conv.steer", `{"conv":"w1"}`}},
		{func() error { return conv.Cancel(ctx, "q1", true) }, call{"conv.cancel", `{"conv":"w1","recall":true,"uuid":"q1"}`}},
		{func() error {
			return conv.Answer(ctx, "r1", prompt.Reply{Kind: prompt.ReplyDeny, Feedback: "换个做法"})
		}, call{"conv.answer", `{"conv":"w1","reply":{"kind":"deny","feedback":"换个做法"},"request_id":"r1"}`}},
		{func() error {
			return conv.Answer(ctx, "r2", prompt.Reply{Kind: prompt.ReplyAnswer, Answers: map[string]string{"用哪个？": "A"}})
		}, call{"conv.answer", `{"conv":"w1","reply":{"kind":"answer","answers":{"用哪个？":"A"}},"request_id":"r2"}`}},
		{func() error { return conv.SetModel(ctx, &opus) }, call{"conv.model", `{"conv":"w1","value":"opus"}`}},
		{func() error { return conv.SetMode(ctx, nil) }, call{"conv.mode", `{"conv":"w1","value":null}`}},
		{func() error { return conv.SetEffort(ctx, nil) }, call{"conv.effort", `{"conv":"w1","value":null}`}},
		{func() error { return conv.SetOptions(ctx, Options{AutoTitle: true}) }, call{"conv.options", `{"conv":"w1","options":{"auto_title":true}}`}},
		{func() error { return conv.Compact(ctx) }, call{"conv.compact", `{"conv":"w1"}`}},
		{func() error { return conv.StopTask(ctx, "t1") }, call{"conv.stop_task", `{"conv":"w1","task":"t1"}`}},
		{func() error { return conv.End(ctx) }, call{"conv.end", `{"conv":"w1"}`}},
	}
	for _, r := range run {
		if err := r.do(); err != nil {
			t.Fatal(err)
		}
		if got := link.took(); len(got) != 1 || got[0] != r.want {
			t.Fatalf("发出 %v，要 %v", got, r.want)
		}
	}
}

func TestRequestResults(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()

	if m, err := conv.TakeRecalled(ctx); err != nil || m != nil {
		t.Fatalf("没有撤回的消息时 %v、%v", m, err)
	}
	link.results["conv.take_recalled"] = `{"uuid":"q1","text":"再看看","images":null,"files":null}`
	if m, err := conv.TakeRecalled(ctx); err != nil || m == nil || m.Text != "再看看" {
		t.Fatalf("撤回的消息 %+v、%v", m, err)
	}

	link.results["conv.elicit"] = `{"errors":{"port":"要填整数","host":"必填"}}`
	err := conv.Elicit(ctx, "e1", prompt.ElicitAccept, map[string][]string{"port": {"x"}})
	var fe FormError
	if !errors.As(err, &fe) || fe["port"] != "要填整数" || err.Error() != "表单有几项不对：host：必填；port：要填整数" {
		t.Fatalf("表单的问题 %v", err)
	}
	if got := link.took(); got[len(got)-1] != (call{"conv.elicit", `{"action":"accept","conv":"w1","request_id":"e1","values":{"port":["x"]}}`}) {
		t.Fatalf("表单的请求 %v", got[len(got)-1])
	}
	link.results["conv.elicit"] = ""
	if err := conv.Elicit(ctx, "e1", prompt.ElicitDecline, nil); err != nil {
		t.Fatal(err)
	}

	link.errs["conv.compact"] = errors.New("没有打开这个对话")
	if err := conv.Compact(ctx); err == nil || err.Error() != "没有打开这个对话" {
		t.Fatalf("核心拒绝的原因没带回来：%v", err)
	}
}

func TestCloseForgetsAndSends(t *testing.T) {
	link, client, conv, _ := opened(t)
	if err := conv.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 1 || got[0] != (call{"conv.close", `{"conv":"w1"}`}) {
		t.Fatalf("关掉时发出 %v", got)
	}
	if len(client.Conversations()) != 0 {
		t.Fatal("关掉后还在")
	}
	if err := conv.Send(context.Background(), Outgoing{Text: "x"}); !errors.Is(err, errNotConnected) {
		t.Fatalf("关掉后还能发：%v", err)
	}
}

func TestStateDecodesHelperTypes(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1",` +
		`"prompts":[{"kind":"permission","request_id":"r1","tool_name":"Bash","input":{"command":"ls"},"notice":"要运行命令"}],` +
		`"tasks":[{"id":"t1","type":"local_bash","kind":"bash","description":"跑测试","started_at":1,"state":"running","running":true,"readable":true}],` +
		`"usage":{"context_tokens":1000,"context_window":200000,"total":{"input":1,"output":2,"cache_read":0,"cache_write":0,"total":3},"level":"normal"}}`))
	s := conv.Take().State
	if len(s.Prompts) != 1 || s.Prompts[0].Kind != prompt.KindPermission || s.Prompts[0].ToolName != "Bash" {
		t.Fatalf("确认没解对：%+v", s.Prompts)
	}
	if len(s.Tasks) != 1 || s.Tasks[0].ID != "t1" || !s.Tasks[0].Running() {
		t.Fatalf("后台任务没解对：%+v", s.Tasks)
	}
	if s.Usage.ContextTokens == nil || *s.Usage.ContextTokens != 1000 || s.Usage.Total.Total() != 3 || s.Usage.Total.CacheRead != 0 {
		t.Fatalf("用量没解对：%+v", s.Usage)
	}
}

// 连接第一次连上时，还在等 conv.open 应答的对话不能被 Reconnected 再开一次（核心会说名字重了）；
// 关掉的对话重连后也不再打开，断线时关掉不发请求。
func TestReconnectOnlyReopensOpenedConversations(t *testing.T) {
	link := &fakeLink{}
	client := NewClient(link, nil)
	reopened := make(chan error, 1)
	link.onCall = func(method string, _ map[string]any) {
		if method == "conv.open" && len(client.Conversations()) == 1 {
			// 应答之前连接报了连上：Reconnected 不能再开这一个
			reopened <- client.Reconnected(context.Background())
		}
	}
	first, err := client.Open(context.Background(), Spec{Cwd: "/h"})
	if err != nil || <-reopened != nil {
		t.Fatal(err)
	}
	if calls := link.took(); len(calls) != 1 {
		t.Fatalf("同一个对话开了 %d 次：%v", len(calls), calls)
	}
	link.onCall = nil
	second, err := client.Open(context.Background(), Spec{Cwd: "/h"})
	if err != nil {
		t.Fatal(err)
	}
	client.Disconnected()
	if err := first.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	link.took()
	if err := client.Reconnected(context.Background()); err != nil {
		t.Fatal(err)
	}
	calls := link.took()
	if len(calls) != 1 || !strings.Contains(calls[0].params, `"conv":"`+second.Name()+`"`) {
		t.Fatalf("重连后打开的：%v", calls)
	}
}

func TestLoadEarlier(t *testing.T) {
	link, client, conv, _ := opened(t)
	ctx := context.Background()
	if err := conv.LoadEarlier(ctx, false); err != nil {
		t.Fatal(err)
	}
	if err := conv.LoadEarlier(ctx, true); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 2 || got[0] != (call{"conv.earlier", `{"conv":"w1"}`}) || got[1] != (call{"conv.earlier", `{"conv":"w1","rest":true}`}) {
		t.Fatalf("请求 %v", got)
	}
	// 新的一页从 0 起插进来，状态里是还剩多少、在不在读
	client.Handle(line(`{"type":"conv.state","conv":"w1","earlier":120,"earlier_loading":true}`))
	client.Handle(line(`{"type":"conv.items","conv":"w1","at":0,"remove":0,"items":[{"kind":"user","id":"old"}]}`))
	if u := conv.Take(); u.State.Earlier != 120 || !u.State.EarlierLoading || ids(t, conv.Items()) != "old,a,b" {
		t.Fatalf("更早的一页：%+v %s", u.State, ids(t, conv.Items()))
	}
	if !IsEarlierStale(errors.New("会话记录在别处改过，重新打开这个对话再看更早的内容")) || IsEarlierStale(errors.New("正在读")) {
		t.Error("认不出会话记录在别处改过")
	}
}

// 核心没给「会话记录在别处改过」带 code，这边认原文：原文改了这里就失败，改成按 code 认。
func TestEarlierStaleMatchesTheCore(t *testing.T) {
	// 经 windows/testdata 里指向 helper/ 的符号链接读：模块目录外的路径 go test 的缓存不认
	src, err := os.ReadFile("../../testdata/helper/conv/earlier.go")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(src), `"`+EarlierStale) {
		t.Fatalf("helper/conv/earlier.go 里没有 %q 开头的错误了", EarlierStale)
	}
}

// 增量只解变了的字段：拿走的那份不会被后来的推送改掉（切片、指针、结构体都是新解的），推来的结构体整个换、不和旧的合并。
func TestStateIncrementsDoNotTouchTakenStates(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","model":"opus","mode":{"user":"plan","process":"default"},
		"queued":[{"uuid":"q1","text":"一"},{"uuid":"q2","text":"二"}],"todos":[{"key":"1","content":"读代码","active_form":"读代码","status":"pending"}]}`))
	old := conv.Take().State
	client.Handle(line(`{"type":"conv.state","conv":"w1","model":"haiku","mode":{"user":"default"},
		"queued":[{"uuid":"q3","text":"三"}],"todos":[{"key":"1","content":"读代码","active_form":"读代码","status":"completed"}]}`))
	s := conv.Take().State
	if *old.Model != "opus" || old.Mode.User != "plan" || len(old.Queued) != 2 || old.Queued[0].Text != "一" || old.Todos[0].Status != "pending" {
		t.Fatalf("拿走的那份被改了：%+v", old)
	}
	if *s.Model != "haiku" || s.Mode != (Mode{User: "default"}) || len(s.Queued) != 1 || s.Queued[0].Text != "三" ||
		s.Todos[0].Status != "completed" || s.Title != "修 bug" {
		t.Fatalf("新的一份：%+v", s)
	}
}
