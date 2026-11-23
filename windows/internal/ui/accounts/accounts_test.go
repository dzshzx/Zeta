package accounts

// 界面测试：账号页和额度明细在无窗口的 ui.Tester 里画，点、看字。主机上的 serve 是假的，照 docs/core.md「账号与额度」
// 回 JSON（方法本身在 helper 的测试里对着真的 claude-switch 跑）。邮箱都是 example.com 的假数据。

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/uitest"
	"github.com/egoist/mygo/ui"
)

// fakeServe 回答 accounts.* 和 quota.*：账号是 accounts，在用的是 current（为空时是认不出的 unknown）。
type fakeServe struct {
	mu       sync.Mutex
	accounts []acct.Account
	current  string
	unknown  *acct.Account
	quota    bool // 主机上存着额度
	calls    []string
}

func (h *fakeServe) Call(_ context.Context, method string, params any) (json.RawMessage, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	p, _ := params.(map[string]any)
	name, _ := p["name"].(string)
	h.calls = append(h.calls, strings.TrimSpace(method+" "+name))
	var out any = struct{}{}
	switch method {
	case "accounts.list":
		out = h.state()
	case "accounts.switch":
		h.current = name
	case "accounts.save":
		h.unknown.Name = "claude-c"
		h.accounts = append(h.accounts, *h.unknown)
		h.current, h.unknown = "claude-c", nil
	case "accounts.remove":
		h.accounts = slices.DeleteFunc(h.accounts, func(a acct.Account) bool { return a.Name == name })
	case "quota.get":
		if !h.quota {
			return json.RawMessage(`{"snapshot":null}`), nil
		}
		out = map[string]any{"snapshot": snapshot()}
	case "quota.usage":
		h.quota = true
		out = map[string]any{"snapshot": snapshot(), "fresh": true}
	default:
		return nil, errors.New("unknown method: " + method)
	}
	return json.Marshal(out)
}

func (h *fakeServe) state() acct.State {
	st := acct.State{Current: h.unknown}
	if h.unknown != nil {
		st.Accounts = append(st.Accounts, *h.unknown)
	}
	for _, a := range h.accounts {
		if a.Name == h.current {
			live := a
			st.Current = &live
			st.Accounts = append([]acct.Account{a}, st.Accounts...)
		} else {
			st.Accounts = append(st.Accounts, a)
		}
	}
	return st
}

func (h *fakeServe) called(call string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return slices.Contains(h.calls, call)
}

// snapshot 是 serve 回的额度：两个每周窗口，两小时后重置。
func snapshot() map[string]any {
	reset := time.Now().Add(2*time.Hour - 30*time.Second).UTC()
	return map[string]any{
		"windows": []map[string]any{
			{"kind": "weekly_all", "label": "每周 · 全部模型", "used": 0.86, "resetsAt": reset, "severity": "warning", "active": true},
			{"kind": "weekly_scoped", "label": "每周 · Fable", "used": 0.40, "resetsAt": reset, "severity": "normal"},
		},
		"subscription": "max", "updatedAt": time.Now().UTC(),
	}
}

type convs struct{ busy bool }

func (c *convs) Busy() bool      { return c.busy }
func (c *convs) CloseIdle() bool { return !c.busy }

// source 是 conv.quota：回一份 get_usage 的应答（内容由假的 serve 读）。
type source struct{ asked atomic.Int32 }

func (s *source) Usage(context.Context) (json.RawMessage, error) {
	s.asked.Add(1)
	return json.RawMessage(`{"rate_limits_available":true}`), nil
}

// fixture 是页面要的一套：界面线程上的队列、假的 serve、额度。
type fixture struct {
	host  *fakeServe
	convs *convs
	src   *source
	posts chan func()
	page  *Page
}

func newFixture() *fixture {
	f := &fixture{
		host: &fakeServe{current: "claude", accounts: []acct.Account{
			{Name: "claude", Email: "a@example.com", Organization: "Org A", Subscription: "max"},
			{Name: "claude-b", Email: "b@example.com", Subscription: "pro"},
		}},
		convs: &convs{},
		src:   &source{},
		posts: make(chan func(), 64),
	}
	loop := acct.Loop{Run: func(fn func()) { go fn() }, Post: func(fn func()) { f.posts <- fn }}
	q := acct.NewQuota(f.host, f.src, loop, nil)
	f.page = &Page{Accounts: acct.NewService(f.host, nil, f.convs, q, loop), Quota: q}
	return f
}

func (f *fixture) until(t *testing.T, tt *ui.Tester, what string, cond func() bool) {
	t.Helper()
	uitest.Until(t, tt, f.posts, what, 30*time.Second, cond)
}

func (f *fixture) loaded() bool {
	_, ok := f.page.Accounts.State()
	_, quota := f.page.Quota.Snapshot()
	return ok && quota && !f.page.Quota.Refreshing()
}

func click(t *testing.T, tt *ui.Tester, text string) {
	t.Helper()
	if err := tt.Click(text); err != nil {
		t.Fatalf("点「%s」：%v；界面上：%q", text, err, tt.Texts())
	}
}

func hasAll(t *testing.T, tt *ui.Tester, texts ...string) {
	t.Helper()
	for _, s := range texts {
		if !tt.HasText(s) {
			t.Errorf("没有「%s」；界面上：%q", s, tt.Texts())
		}
	}
}

func TestPageShowsQuotaAndAccounts(t *testing.T) {
	f := newFixture()
	tt := ui.NewTester(f.page.View, 560, 900)
	f.until(t, tt, "读完", f.loaded)
	hasAll(t, tt, "订阅额度", "每周 · 全部模型", "86%", "每周 · Fable", "40%", "2 小时后重置", "刚刚更新",
		"账号", "a@example.com", "claude · Org A · Max", "当前", "b@example.com", "claude-b · Pro", "切换", "移除")
	if shows(tt, "正在读取") || shows(tt, "记下") {
		t.Errorf("读完以后：%q", tt.Texts())
	}
	if f.src.asked.Load() != 1 {
		t.Errorf("打开页面问了 %d 次额度", f.src.asked.Load())
	}
}

func TestSwitchAsksFirst(t *testing.T) {
	f := newFixture()
	tt := ui.NewTester(f.page.View, 560, 900)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "切换")
	hasAll(t, tt, "切换到 b@example.com？")
	click(t, tt, "取消")
	if f.page.Accounts.Working() != "" || f.host.called("accounts.switch claude-b") {
		t.Fatal("取消了还在换")
	}
	click(t, tt, "切换")
	click(t, tt, "切换") // 对话框里的
	hasAll(t, tt, "正在切换到 b@example.com")
	f.until(t, tt, "换好", func() bool {
		st, _ := f.page.Accounts.State()
		return f.page.Accounts.Working() == "" && st.Current != nil && st.Current.Name == "claude-b"
	})
	if !f.host.called("accounts.switch claude-b") {
		t.Fatalf("没换：%q", f.host.calls)
	}
	// 换了号额度清掉重取
	f.until(t, tt, "重取额度", func() bool { return f.src.asked.Load() == 2 && !f.page.Quota.Refreshing() })
}

func TestRemoveAsksFirst(t *testing.T) {
	f := newFixture()
	tt := ui.NewTester(f.page.View, 560, 900)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "移除")
	hasAll(t, tt, "移除 b@example.com？")
	click(t, tt, "移除") // 对话框里的
	f.until(t, tt, "移除做完", func() bool { return f.host.called("accounts.remove claude-b") && f.page.Accounts.Working() == "" })
}

func TestProblemIsShown(t *testing.T) {
	f := newFixture()
	f.convs.busy = true
	tt := ui.NewTester(f.page.View, 560, 900)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "切换")
	click(t, tt, "切换")
	hasAll(t, tt, "这里还有对话在进行")
}

func TestUnknownLoginCanBeSaved(t *testing.T) {
	f := newFixture()
	f.host.current = ""
	f.host.unknown = &acct.Account{Email: "x@example.com", Subscription: "pro"}
	tt := ui.NewTester(f.page.View, 560, 900)
	f.until(t, tt, "读完", f.loaded)
	hasAll(t, tt, "x@example.com", "Pro · 还没记下名字", "记下")
	click(t, tt, "记下")
	f.until(t, tt, "记下", func() bool {
		st, _ := f.page.Accounts.State()
		return f.page.Accounts.Working() == "" && st.Current != nil && st.Current.Name == "claude-c"
	})
	if shows(tt, "记下") {
		t.Fatal("记下以后不该再有这个按钮")
	}
}

// shows 报告界面上有没有正好是 s 的一段字（HasText 也认包含它的长句）。
func shows(tt *ui.Tester, s string) bool { return slices.Contains(tt.Texts(), s) }
