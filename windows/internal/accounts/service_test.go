package accounts

// 客户端这边只调 serve 的方法、整理状态；方法本身在 helper 的测试里对着真的 claude-switch 跑。这里的 serve 是假的：
// 照 docs/core.md「账号与额度」回 JSON，记下收到的请求。邮箱都是 example.com 的假数据。

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

	"github.com/dzshzx/Zeta/helper/coreapi"
)

// fakeServe 是一台主机上的 serve：账号是 accounts，在用的是 current（为空时是认不出的 unknown），额度是 quota。
type fakeServe struct {
	mu        sync.Mutex
	accounts  []coreapi.Account
	current   string
	unknown   *coreapi.Account
	blocker   string
	installed bool
	quota     string // quota.get 回的 snapshot（JSON），空是 null
	calls     []string
}

type codeErr struct{ code, msg string }

func (e codeErr) Error() string { return e.msg }
func (e codeErr) Code() string  { return e.code }

func (f *fakeServe) Call(_ context.Context, method string, p map[string]any) (json.RawMessage, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	name, _ := p["name"].(string)
	f.calls = append(f.calls, strings.TrimSpace(method+" "+name))
	if strings.HasPrefix(method, "accounts.") && !f.installed {
		return nil, codeErr{"claude_switch_missing", "claude-switch 还没装上"}
	}
	if strings.HasPrefix(method, "accounts.") && p["program"] != "bin/claude-switch-1" {
		return nil, errors.New("没带 claude-switch 的路径")
	}
	var out any = struct{}{}
	switch method {
	case "accounts.list":
		out = f.state()
	case "accounts.switch":
		f.current = name
	case "accounts.save":
		f.unknown.Name = "claude-c"
		f.accounts = append(f.accounts, *f.unknown)
		f.current, f.unknown = "claude-c", nil
	case "accounts.remove":
		if name == f.current {
			return nil, errors.New("claude-b 是在用的账号，未改动")
		}
		f.accounts = slices.DeleteFunc(f.accounts, func(a coreapi.Account) bool { return a.Name == name })
	case "quota.get":
		return json.RawMessage(`{"snapshot":` + or(f.quota, "null") + `}`), nil
	case "quota.usage":
		raw, _ := json.Marshal(p["response"])
		if !strings.Contains(string(raw), "rate_limits_available") {
			return nil, errors.New("不是额度的应答")
		}
		f.quota = snapshotJSON(86)
		return json.RawMessage(`{"snapshot":` + f.quota + `,"fresh":true}`), nil

	default:
		return nil, errors.New("unknown method: " + method)
	}
	return json.Marshal(out)
}

func or(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

func (f *fakeServe) state() coreapi.AccountList {
	st := coreapi.AccountList{Current: f.unknown, Blocker: f.blocker}
	if f.unknown != nil {
		st.Accounts = append(st.Accounts, *f.unknown)
	}
	for _, a := range f.accounts {
		if a.Name == f.current {
			live := a
			st.Current = &live
			st.Accounts = append([]coreapi.Account{a}, st.Accounts...)
		} else {
			st.Accounts = append(st.Accounts, a)
		}
	}
	return st
}

func (f *fakeServe) called(call string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Contains(f.calls, call)
}

// snapshotJSON 是 serve 回的一份额度：每周窗口用了 percent%，两小时后重置。
func snapshotJSON(percent int) string {
	b, _ := json.Marshal(map[string]any{
		"windows": []map[string]any{{"kind": "weekly_all", "label": "每周 · 全部模型", "used": float64(percent) / 100,
			"resetsAt": time.Now().Add(2 * time.Hour).UTC(), "severity": map[bool]string{true: "critical", false: "warning"}[percent >= 95], "active": true}},
		"subscription": "max", "updatedAt": time.Now().UTC(),
	})
	return string(b)
}

func newServe() *fakeServe {
	return &fakeServe{current: "claude", accounts: []coreapi.Account{
		{Name: "claude", Email: "a@example.com", Subscription: "max"},
		{Name: "claude-b", Email: "b@example.com", Subscription: "pro"},
	}}
}

// program 是假的 claude-switch：Install 只是记下装过。
type program struct {
	serve    *fakeServe
	installs int
}

func (p *program) Path() string { return "bin/claude-switch-1" }

func (p *program) Install(context.Context) error {
	p.installs++
	p.serve.mu.Lock()
	p.serve.installed = true
	p.serve.mu.Unlock()
	return nil
}

type fakeConvs struct {
	busy, refuse bool
	closed       int
}

func (f *fakeConvs) Busy() bool { return f.busy }
func (f *fakeConvs) CloseIdle() bool {
	if f.refuse {
		return false
	}
	f.closed++
	return true
}

type source struct {
	asked atomic.Int32
	err   error
}

func (s *source) Usage(context.Context) (json.RawMessage, error) {
	s.asked.Add(1)
	if s.err != nil {
		return nil, s.err
	}
	return json.RawMessage(`{"rate_limits_available":true}`), nil
}

// loop 是测试里的界面线程：post 交来的函数排队，until 在测试的 goroutine 上逐个执行，直到条件成立。
type loop struct{ ch chan func() }

func newLoop() *loop          { return &loop{ch: make(chan func(), 64)} }
func (l *loop) post(f func()) { l.ch <- f }

// accounts 是 Service、Quota 用的 Loop：后台的事真在后台跑，交回的排进 ch，until 里跑。
func (l *loop) accounts() Loop { return Loop{Run: func(f func()) { go f() }, Post: l.post} }
func (l *loop) until(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.After(10 * time.Second)
	for !cond() {
		select {
		case f := <-l.ch:
			f()
		case <-deadline:
			t.Fatalf("等不到%s", what)
		}
	}
}

type fixture struct {
	serve *fakeServe
	prog  *program
	convs *fakeConvs
	src   *source
	loop  *loop
	quota *Quota
	svc   *Service
}

func newFixture() *fixture {
	f := &fixture{serve: newServe(), convs: &fakeConvs{}, src: &source{}, loop: newLoop()}
	f.prog = &program{serve: f.serve}
	f.quota = NewQuota(coreapi.New(f.serve), f.src, f.loop.accounts(), nil)
	f.svc = NewService(coreapi.New(f.serve), f.prog, f.convs, f.quota, f.loop.accounts())
	return f
}

func current(s *Service) string {
	st, ok := s.State()
	if !ok || st.Current == nil {
		return ""
	}
	return st.Current.Name
}

func TestInstallsClaudeSwitchWhenMissing(t *testing.T) {
	f := newFixture()
	f.svc.Refresh()
	f.loop.until(t, "读到账号", func() bool { _, ok := f.svc.State(); return ok })
	st, _ := f.svc.State()
	if f.prog.installs != 1 || len(st.Accounts) != 2 || current(f.svc) != "claude" || f.svc.Problem() != "" {
		t.Fatalf("装了 %d 次，%+v，%q", f.prog.installs, st, f.svc.Problem())
	}
}

func TestSwitch(t *testing.T) {
	f := newFixture()
	f.serve.installed = true
	f.svc.Switch(coreapi.Account{Name: "claude-b", Email: "b@example.com"})
	if f.svc.Working() != "正在切换到 b@example.com" {
		t.Fatalf("%q", f.svc.Working())
	}
	f.svc.Switch(coreapi.Account{Name: "claude"})
	if f.svc.Problem() != "上一件事还没做完" {
		t.Fatalf("%q", f.svc.Problem())
	}
	f.loop.until(t, "换好", func() bool { return f.svc.Working() == "" && current(f.svc) == "claude-b" })
	if f.svc.Problem() != "" || f.convs.closed != 1 {
		t.Fatalf("%q，收掉 %d 次", f.svc.Problem(), f.convs.closed)
	}
	// 换了号：额度向 Claude Code 重要一次
	f.loop.until(t, "重取额度", func() bool { _, ok := f.quota.Snapshot(); return ok && !f.quota.Refreshing() })
	if f.src.asked.Load() != 1 || !f.serve.called("quota.usage") {
		t.Fatalf("问了 %d 次，%q", f.src.asked.Load(), f.serve.calls)
	}
}

func TestSwitchRefused(t *testing.T) {
	f := newFixture()
	f.serve.installed = true
	f.convs.busy = true
	f.svc.Switch(coreapi.Account{Name: "claude-b"})
	if f.svc.Problem() != errOwnBusy.Error() || f.svc.Working() != "" {
		t.Fatalf("本客户端有对话在进行：%q", f.svc.Problem())
	}

	f.convs.busy, f.convs.refuse = false, true
	f.svc.Switch(coreapi.Account{Name: "claude-b"})
	f.loop.until(t, "做完", func() bool { return f.svc.Working() == "" })
	if f.svc.Problem() != errOwnBusy.Error() || f.serve.called("accounts.switch claude-b") {
		t.Fatalf("查进程时有对话开始了：%q", f.svc.Problem())
	}

	f.convs.refuse = false
	f.serve.blocker = "另一个 Zeta（测试版）开着 1 个对话"
	f.svc.Switch(coreapi.Account{Name: "claude-b"})
	f.loop.until(t, "做完", func() bool { return f.svc.Working() == "" })
	if f.svc.Problem() != f.serve.blocker || f.convs.closed != 0 || f.serve.called("accounts.switch claude-b") {
		t.Fatalf("别处挡着：%q，收掉 %d 次", f.svc.Problem(), f.convs.closed)
	}
}

func TestSaveAndRemove(t *testing.T) {
	f := newFixture()
	f.serve.installed = true
	f.serve.current, f.serve.unknown = "", &coreapi.Account{Email: "x@example.com"}
	f.svc.Save()
	f.loop.until(t, "记下", func() bool { return f.svc.Working() == "" && current(f.svc) == "claude-c" })

	f.svc.Remove(coreapi.Account{Name: "claude-b", Email: "b@example.com"})
	if f.svc.Working() != "正在移除 b@example.com" {
		t.Fatalf("%q", f.svc.Working())
	}
	f.loop.until(t, "移除", func() bool { return f.svc.Working() == "" })
	st, _ := f.svc.State()
	if f.svc.Problem() != "" || len(st.Accounts) != 2 {
		t.Fatalf("%q %+v", f.svc.Problem(), st.Accounts)
	}
	f.svc.Remove(coreapi.Account{Name: "claude-c"})
	f.loop.until(t, "移除", func() bool { return f.svc.Working() == "" })
	if !strings.HasPrefix(f.svc.Problem(), "没有移除：") {
		t.Fatalf("删在用的号：%q", f.svc.Problem())
	}
}

func TestUnreachableHost(t *testing.T) {
	l := newLoop()
	svc := NewService(coreapi.New(callerFunc(func() error { return errors.New("连不上") })), nil, &fakeConvs{}, nil, l.accounts())
	svc.Refresh()
	l.until(t, "读完", func() bool { return svc.Problem() != "" })
	if svc.Problem() != "读不了账号：连不上" {
		t.Fatalf("%q", svc.Problem())
	}
}

type callerFunc func() error

func (f callerFunc) Call(context.Context, string, map[string]any) (json.RawMessage, error) {
	return nil, f()
}

// 数够新时只读主机上存着的；旧了、点刷新时向 Claude Code 要，两次至少隔 15 秒。
func TestQuotaRefreshRules(t *testing.T) {
	clock := time.Now()
	f := newFixture()
	f.quota.now = func() time.Time { return clock }
	q := f.quota
	if q.Footnote(clock) != "还没有额度数据" {
		t.Fatalf("%q", q.Footnote(clock))
	}
	q.Refresh(false)
	if q.Footnote(clock) != "正在取额度" {
		t.Fatalf("%q", q.Footnote(clock))
	}
	f.loop.until(t, "取到", func() bool { return !q.Refreshing() })
	if f.src.asked.Load() != 1 || q.Footnote(clock) != "刚刚更新" {
		t.Fatalf("没有数时要问：%d，%q", f.src.asked.Load(), q.Footnote(clock))
	}

	q.Refresh(true) // 不到 15 秒：只读存着的
	f.loop.until(t, "读完", func() bool { return !q.Refreshing() })
	q.Refresh(false) // 数够新：只读存着的
	f.loop.until(t, "读完", func() bool { return !q.Refreshing() })
	if f.src.asked.Load() != 1 || !f.serve.called("quota.get") {
		t.Fatalf("问了 %d 次，%q", f.src.asked.Load(), f.serve.calls)
	}

	clock = clock.Add(20 * time.Second)
	f.src.err = errors.New("Claude Code 起不来")
	q.Refresh(true)
	f.loop.until(t, "问完", func() bool { return !q.Refreshing() })
	if f.src.asked.Load() != 2 || q.Footnote(clock) != "刚刚更新。没取到最新的额度：Claude Code 起不来" {
		t.Fatalf("%d %q", f.src.asked.Load(), q.Footnote(clock))
	}
	if _, ok := q.Snapshot(); !ok {
		t.Fatal("没取到时留着已有的")
	}
}

func TestQuotaWithoutSource(t *testing.T) {
	l := newLoop()
	serve := newServe()
	serve.quota = snapshotJSON(40)
	q := NewQuota(coreapi.New(serve), nil, l.accounts(), nil)
	q.Refresh(true)
	l.until(t, "读完", func() bool { return !q.Refreshing() })
	s, ok := q.Snapshot()
	if h, _ := s.Headline(); !ok || h.Percent() != 40 || h.Label != "每周 · 全部模型" {
		t.Fatalf("%+v", s)
	}
	if serve.called("quota.usage") {
		t.Fatal("没有 UsageSource 时不该要")
	}
}
