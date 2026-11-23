package coreapi

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"
	"time"
)

// accountsCaller 记下发出的方法名和参数，回 reply。
type accountsCaller struct {
	method string
	params map[string]any
	reply  string
}

func (f *accountsCaller) Call(_ context.Context, method string, p map[string]any) (json.RawMessage, error) {
	f.method, f.params = method, p
	if f.reply == "" {
		return nil, nil
	}
	return json.RawMessage(f.reply), nil
}

// TestAccountsMethods 核对每个函数发的方法名、参数，和解出来的结果（结果照 helper/accounts.go 的实际写法）。
func TestAccountsMethods(t *testing.T) {
	reset := time.Date(2026, 11, 23, 5, 0, 0, 0, time.UTC)
	snap := `{"windows":[{"kind":"session","group":"session","used":0.86,"resetsAt":"2026-11-23T05:00:00Z","severity":"warning","active":true,"label":"5 小时"}],"updatedAt":"2026-11-23T03:00:00Z"}`
	wantSnap := &QuotaSnapshot{
		Windows:   []QuotaWindow{{Kind: "session", Group: "session", Used: 0.86, ResetsAt: reset, Severity: SeverityWarning, Active: true, Label: "5 小时"}},
		UpdatedAt: time.Date(2026, 11, 23, 3, 0, 0, 0, time.UTC),
	}
	usage := json.RawMessage(`{"rate_limits_available":true}`)
	info := json.RawMessage(`{"status":"allowed"}`)
	cases := []struct {
		name       string
		reply      string
		run        func(*Client) (any, error)
		method     string
		params     map[string]any
		wantResult any
	}{
		{
			name:  "列账号",
			reply: `{"current":{"name":"claude","email":"a@example.com","subscription":"max","current":true,"parked":true},"accounts":[{"name":"claude","email":"a@example.com","subscription":"max","current":true,"parked":true},{"name":"claude-b","refreshExpiresAt":1700000000000,"parked":true}],"procs":[{"pid":42,"owner":"zeta","status":"idle","kind":"interactive"}],"blocker":"终端里有 Claude Code 开着"}`,
			run: func(cl *Client) (any, error) {
				return cl.ListAccounts(context.Background(), ".cache/zeta/bin/claude-switch-1")
			},
			method: "accounts.list",
			params: map[string]any{"program": ".cache/zeta/bin/claude-switch-1"},
			wantResult: AccountList{
				Current: &Account{Name: "claude", Email: "a@example.com", Subscription: "max", Current: true, Parked: true},
				Accounts: []Account{
					{Name: "claude", Email: "a@example.com", Subscription: "max", Current: true, Parked: true},
					{Name: "claude-b", RefreshExpiresAt: 1700000000000, Parked: true},
				},
				Procs:   []ClaudeProcess{{PID: 42, Owner: "zeta", Status: "idle", Kind: "interactive"}},
				Blocker: "终端里有 Claude Code 开着",
			},
		},
		{
			name:  "不带 program",
			reply: `{"current":null,"accounts":[],"procs":[]}`,
			run: func(cl *Client) (any, error) {
				return cl.ListAccounts(context.Background(), "")
			},
			method:     "accounts.list",
			params:     map[string]any{},
			wantResult: AccountList{Accounts: []Account{}, Procs: []ClaudeProcess{}},
		},
		{
			name:  "换号",
			reply: `{}`,
			run: func(cl *Client) (any, error) {
				return nil, cl.SwitchAccount(context.Background(), "bin/cs", "claude-b")
			},
			method: "accounts.switch",
			params: map[string]any{"program": "bin/cs", "name": "claude-b"},
		},
		{
			name:  "记下",
			reply: `{"name":"claude-c"}`,
			run: func(cl *Client) (any, error) {
				return cl.SaveAccount(context.Background(), "bin/cs")
			},
			method:     "accounts.save",
			params:     map[string]any{"program": "bin/cs"},
			wantResult: "claude-c",
		},
		{
			name:  "登录前",
			reply: `{"name":"claude-d"}`,
			run: func(cl *Client) (any, error) {
				return cl.PrepareLogin(context.Background(), "bin/cs")
			},
			method:     "accounts.prepare_login",
			params:     map[string]any{"program": "bin/cs"},
			wantResult: "claude-d",
		},
		{
			name:  "开始登录",
			reply: `{"url":"https://claude.ai/oauth/authorize?x=1","name":"claude-d"}`,
			run: func(cl *Client) (any, error) {
				return cl.LoginStart(context.Background(), "bin/cs")
			},
			method:     "accounts.login_start",
			params:     map[string]any{"program": "bin/cs"},
			wantResult: LoginStart{URL: "https://claude.ai/oauth/authorize?x=1", Name: "claude-d"},
		},
		{
			name:  "交授权码",
			reply: `{"name":"claude-d"}`,
			run: func(cl *Client) (any, error) {
				return cl.LoginCode(context.Background(), "bin/cs", "a#b")
			},
			method:     "accounts.login_code",
			params:     map[string]any{"program": "bin/cs", "code": "a#b"},
			wantResult: "claude-d",
		},
		{
			name:  "取消登录",
			reply: `{}`,
			run: func(cl *Client) (any, error) {
				return nil, cl.LoginCancel(context.Background())
			},
			method: "accounts.login_cancel",
			params: map[string]any{},
		},
		{
			name:  "删号",
			reply: `{}`,
			run: func(cl *Client) (any, error) {
				return nil, cl.RemoveAccount(context.Background(), "bin/cs", "claude-b")
			},
			method: "accounts.remove",
			params: map[string]any{"program": "bin/cs", "name": "claude-b"},
		},
		{
			name:  "读额度",
			reply: `{"snapshot":` + snap + `}`,
			run: func(cl *Client) (any, error) {
				return cl.Quota(context.Background())
			},
			method:     "quota.get",
			params:     map[string]any{},
			wantResult: wantSnap,
		},
		{
			name:  "还没有额度",
			reply: `{"snapshot":null}`,
			run: func(cl *Client) (any, error) {
				return cl.Quota(context.Background())
			},
			method:     "quota.get",
			params:     map[string]any{},
			wantResult: (*QuotaSnapshot)(nil),
		},
		{
			name: "订阅额度",
			run: func(cl *Client) (any, error) {
				return nil, cl.WatchQuota(context.Background(), true)
			},
			method: "quota.watch",
			params: map[string]any{"watch": true},
		},
		{
			name: "退订额度",
			run: func(cl *Client) (any, error) {
				return nil, cl.WatchQuota(context.Background(), false)
			},
			method: "quota.watch",
			params: map[string]any{"watch": false},
		},
		{
			name:  "交 get_usage",
			reply: `{"snapshot":` + snap + `,"fresh":false}`,
			run: func(cl *Client) (any, error) {
				return cl.SubmitQuotaUsage(context.Background(), usage)
			},
			method:     "quota.usage",
			params:     map[string]any{"response": usage},
			wantResult: QuotaUsage{Snapshot: wantSnap, Fresh: false},
		},
		{
			name:  "交 rate_limit_event",
			reply: `{"snapshot":` + snap + `,"changed":true}`,
			run: func(cl *Client) (any, error) {
				return cl.SubmitQuotaEvent(context.Background(), info)
			},
			method:     "quota.event",
			params:     map[string]any{"info": info},
			wantResult: QuotaEvent{Snapshot: wantSnap, Changed: true},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := &accountsCaller{reply: c.reply}
			got, err := c.run(New(f))
			if err != nil {
				t.Fatal(err)
			}
			if f.method != c.method || !reflect.DeepEqual(f.params, c.params) {
				t.Errorf("发的是 %s %v，要的是 %s %v", f.method, f.params, c.method, c.params)
			}
			if !reflect.DeepEqual(got, c.wantResult) {
				t.Errorf("结果是 %#v，要的是 %#v", got, c.wantResult)
			}
		})
	}
}

// TestAccountsSnapshotAt 核对按客户端时钟看额度和挑头条：被拒时是最满的，过了重置、解除时刻后从零算、是服务器挑的。
func TestAccountsSnapshotAt(t *testing.T) {
	now := time.Now()
	s := QuotaSnapshot{Blocked: true, BlockedUntil: now.Add(time.Minute), Windows: []QuotaWindow{
		{Kind: "session", Used: 1, ResetsAt: now.Add(time.Minute), Severity: SeverityCritical},
		{Kind: "weekly_all", Used: 0.5, Active: true},
	}}
	if h, _ := s.Headline(); h.Kind != "session" {
		t.Fatalf("被拒时头条是最满的：%+v", h)
	}
	later := s.At(now.Add(2 * time.Minute))
	if later.Blocked || later.Windows[0].Used != 0 || later.Windows[0].Severity != SeverityNormal || s.Windows[0].Used != 1 {
		t.Fatalf("%+v", later)
	}
	if h, _ := later.Headline(); h.Kind != "weekly_all" {
		t.Fatalf("服务器挑的：%+v", h)
	}
}
