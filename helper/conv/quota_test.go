package conv

import (
	"encoding/json"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

func TestQuota(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	if _, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`)); err != errNoProcess {
		t.Fatalf("没有进程时：%v", err)
	}
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))

	type result struct {
		v   any
		err error
	}
	done := make(chan result, 1)
	go func() {
		v, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`))
		done <- result{v, err}
	}()
	deadline := time.Now().Add(5 * time.Second)
	for len(l.lines("control_request/get_usage")) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("没发 get_usage")
		}
		time.Sleep(5 * time.Millisecond)
	}
	req := l.lines("control_request/get_usage")[0]
	if req["request"].(map[string]any)["skip_behaviors"] != true {
		t.Fatalf("请求：%v", req)
	}
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+req["request_id"].(string)+
		`","response":{"five_hour":{"utilization":42}}}}`)
	r := <-done
	b, _ := json.Marshal(r.v)
	if r.err != nil || string(b) != `{"five_hour":{"utilization":42}}` {
		t.Fatalf("应答 %s，出错 %v", b, r.err)
	}
}

// fakeQuota 是假的额度存储：记下交来的，ClaimQuery 前 allow 次答 true。
type fakeQuota struct {
	mu     sync.Mutex
	allow  int
	relay  bool // 在用中转：Subscribed 为假
	claims int
	infos  []string
	usages []string
}

func (q *fakeQuota) RateLimit(info json.RawMessage) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.infos = append(q.infos, string(info))
}

func (q *fakeQuota) ClaimQuery() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.claims++
	return q.claims <= q.allow
}

func (q *fakeQuota) Subscribed() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	return !q.relay
}

func (q *fakeQuota) Usage(response json.RawMessage) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.usages = append(q.usages, string(response))
}

func (q *fakeQuota) got() (infos, usages []string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return slices.Clone(q.infos), slices.Clone(q.usages)
}

// 输出里的 rate_limit_event：rate_limit_info 原样交给额度存储，子代理的请求带来的也算；别的事件不交。
func TestRateLimitEventToQuota(t *testing.T) {
	h := newHarness(t)
	q := &fakeQuota{}
	h.m.deps.Quota = q
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	info := `{"status":"allowed_warning","unifiedWindows":{"five_hour":{"utilization":0.81,"resetsAt":1795057510,"status":"allowed_warning"}}}`
	h.feed(c, l, initLine("s1"),
		`{"type":"rate_limit_event","rate_limit_info":`+info+`,"uuid":"u1","session_id":"s1"}`,
		`{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"},"parent_tool_use_id":"toolu_1","session_id":"s1"}`,
		`{"type":"rate_limit_event","session_id":"s1"}`)
	h.feed(c, l, answerLines("m1", "回答")...)
	infos, usages := q.got()
	if !reflect.DeepEqual(infos, []string{info, `{"status":"allowed"}`}) || usages != nil {
		t.Fatalf("交给额度存储的：%q，get_usage %q", infos, usages)
	}
}

// 起进程时顺便问一次额度：get_usage 排在 initialize 之后，成功的应答交给额度存储，出错的不交；
// 额度存储说刚问过（ClaimQuery 为 false）时不问。
func TestQuotaAskedWhenProcessStarts(t *testing.T) {
	h := newHarness(t)
	q := &fakeQuota{allow: 1}
	h.m.deps.Quota = q
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	asks := l.lines("control_request/get_usage")
	if len(asks) != 1 || asks[0]["request"].(map[string]any)["skip_behaviors"] != true {
		t.Fatalf("问额度的请求：%v", asks)
	}
	if order := requestOrder(l); slices.Index(order, "initialize") > slices.Index(order, "get_usage") {
		t.Fatalf("请求的先后：%v", order)
	}
	usage := `{"subscription_type":"max","rate_limits_available":true,"rate_limits":null}`
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+asks[0]["request_id"].(string)+
		`","response":`+usage+`}}`)
	if _, usages := q.got(); !reflect.DeepEqual(usages, []string{usage}) {
		t.Fatalf("交给额度存储的应答：%q", usages)
	}

	// 第二个对话起进程时额度存储说刚问过：不问
	h.call(2, "conv.open", map[string]any{"conv": "b", "cwd": "/w"})
	h.call(2, "conv.send", map[string]any{"conv": "b", "text": "二"})
	h.settle(h.conv(2, "b"))
	if n := len(h.link(1).lines("control_request/get_usage")); n != 0 || q.claimed() != 2 {
		t.Fatalf("刚问过还问了 %d 次（问额度存储 %d 次）", n, q.claims)
	}

	// 答的是错误：不交
	q.mu.Lock()
	q.allow = 3
	q.mu.Unlock()
	h.call(1, "conv.end", map[string]any{"conv": "a"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "三"})
	l = h.link(2)
	h.settle(c)
	ask := l.lines("control_request/get_usage")[0]
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"error","request_id":"`+ask["request_id"].(string)+
		`","error":"no subscription"}}`)
	if _, usages := q.got(); len(usages) != 1 {
		t.Fatalf("出错的应答也交了：%q", usages)
	}
}

// requestOrder 是写进去的控制请求的 subtype，按写的先后。
func requestOrder(l *fakeLink) []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []string
	for _, b := range l.sent {
		var m struct {
			Request struct {
				Subtype string `json:"subtype"`
			} `json:"request"`
		}
		if json.Unmarshal(b, &m) == nil && m.Request.Subtype != "" {
			out = append(out, m.Request.Subtype)
		}
	}
	return out
}

func (q *fakeQuota) claimed() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.claims
}

// 在用中转时没有订阅额度：conv.quota 不问 Claude Code、答 errNoSubscription；起进程时也不顺便问（额度存储的 ClaimQuery
// 在中转上答 false，见 helper/accounts.go 的 quotaSink）。换回账号后照常问。
func TestQuotaNotAskedOnRelay(t *testing.T) {
	h := newHarness(t)
	q := &fakeQuota{relay: true}
	h.m.deps.Quota = q
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	h.settle(c)
	if _, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`)); err != errNoSubscription {
		t.Fatalf("中转上问额度：%v", err)
	}
	if n := len(l.lines("control_request/get_usage")); n != 0 {
		t.Fatalf("中转上发了 %d 次 get_usage", n)
	}

	q.mu.Lock()
	q.relay = false
	q.mu.Unlock()
	done := make(chan error, 1)
	go func() {
		_, err := h.m.Request(1, "conv.quota", []byte(`{"conv":"a"}`))
		done <- err
	}()
	deadline := time.Now().Add(5 * time.Second)
	for len(l.lines("control_request/get_usage")) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("换回账号后没问额度")
		}
		time.Sleep(5 * time.Millisecond)
	}
	req := l.lines("control_request/get_usage")[0]
	h.feed(c, l, `{"type":"control_response","response":{"subtype":"success","request_id":"`+req["request_id"].(string)+`","response":{}}}`)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// conv.quota 在中转上出错时应答的 code 就是协议里的 CodeNoSubscription（客户端按它认）。
func TestNoSubscriptionCode(t *testing.T) {
	if errNoSubscription.Code() != sessionproto.CodeNoSubscription {
		t.Fatalf("code %q", errNoSubscription.Code())
	}
}
