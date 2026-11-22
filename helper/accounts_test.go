package main

// serve 的 accounts.*、quota.*：直接调入口 handleAccounts，在临时 HOME 里真跑 claude-switch（go tool -n 给出编好的程序）。
// 登录状态是假的：邮箱用 example.com，令牌是随手写的字符串。

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// accountsHome 摆好一个临时 HOME：装上 claude-switch，登录 uuid-a。
func accountsHome(t *testing.T) *env {
	t.Helper()
	// 编哪一版 claude-switch 由 go.mod、go.sum 定；子进程读它们，go test 的缓存看不见，这里先读一遍
	for _, f := range []string{"go.mod", "go.sum"} {
		if _, err := os.ReadFile(f); err != nil {
			t.Fatal(err)
		}
	}
	out, err := exec.Command("go", "tool", "-n", "claude-switch").Output()
	if err != nil {
		t.Fatalf("要 go 编 claude-switch：%v", err)
	}
	bin, err := os.ReadFile(strings.TrimSpace(string(out)))
	if err != nil {
		t.Fatal(err)
	}
	home := t.TempDir()
	path := filepath.Join(home, ".cache/zeta/bin/claude-switch-0123456789abcdef")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, bin, 0o755); err != nil {
		t.Fatal(err)
	}
	fakeLogin(t, home, "uuid-a", "a@example.com")
	return newEnvAt(home, "zeta")
}

func fakeLogin(t *testing.T, home, uuid, email string) {
	t.Helper()
	writeFile(t, filepath.Join(home, ".claude.json"),
		`{"oauthAccount":{"accountUuid":"`+uuid+`","emailAddress":"`+email+`"}}`)
	writeFile(t, filepath.Join(home, ".claude/.credentials.json"),
		`{"claudeAiOauth":{"accessToken":"fake-`+uuid+`","refreshToken":"fake-r-`+uuid+`","subscriptionType":"max"}}`)
}

// callAccounts 调一个方法，结果按 JSON 读回来（同客户端收到的样子）。
func callAccounts(t *testing.T, e *env, method, params string) (obj, error) {
	t.Helper()
	result, err := handleAccounts(context.Background(), e, method, json.RawMessage(params))
	if err != nil {
		return nil, err
	}
	data, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	var m obj
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return m, nil
}

func mustAccounts(t *testing.T, e *env, method, params string) obj {
	t.Helper()
	m, err := callAccounts(t, e, method, params)
	if err != nil {
		t.Fatalf("%s：%v", method, err)
	}
	return m
}

func currentName(m obj) any {
	cur, _ := m["current"].(map[string]any)
	if cur == nil {
		return nil
	}
	return cur["name"]
}

func TestServeAccounts(t *testing.T) {
	e := accountsHome(t)
	home := e.home
	list := mustAccounts(t, e, "accounts.list", `{"id":1,"method":"accounts.list"}`)
	if currentName(list) != "claude" || len(list["accounts"].([]any)) != 1 || len(list["procs"].([]any)) != 0 {
		t.Fatalf("list：%v", list)
	}
	if got := mustAccounts(t, e, "accounts.prepare_login", ``); got["name"] != "claude-b" {
		t.Fatalf("prepare_login：%v", got)
	}
	fakeLogin(t, home, "uuid-b", "b@example.com")
	mustAccounts(t, e, "accounts.switch", `{"name":"claude"}`)
	list = mustAccounts(t, e, "accounts.list", ``)
	if currentName(list) != "claude" || len(list["accounts"].([]any)) != 2 {
		t.Fatalf("换回以后：%v", list)
	}
	mustAccounts(t, e, "accounts.remove", `{"name":"claude-b"}`)

	// 认得出的出错带代号
	_, err := callAccounts(t, e, "accounts.switch", `{"name":"claude-b"}`)
	var coded interface{ Code() string }
	if !errors.As(err, &coded) || coded.Code() != "no_account" {
		t.Fatalf("换到删掉的号：%v", err)
	}
	_, err = callAccounts(t, e, "accounts.list", `{"program":".cache/zeta/bin/claude-switch-ffffffffffffffff"}`)
	if !errors.As(err, &coded) || coded.Code() != "claude_switch_missing" {
		t.Fatalf("客户端带的版本没装：%v", err)
	}
	if _, err := callAccounts(t, e, "accounts.nope", ``); err == nil || !strings.Contains(err.Error(), "unknown method") {
		t.Fatalf("不认识的方法：%v", err)
	}
	if _, err := callAccounts(t, e, "accounts.list", `[1]`); err == nil {
		t.Fatal("参数不是对象时要报错")
	}
}

// 入口可以并发调用（serve 把慢的请求放进 goroutine）。
func TestServeAccountsConcurrent(t *testing.T) {
	e := accountsHome(t)
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for range 8 {
		wg.Go(func() {
			if _, err := handleAccounts(context.Background(), e, "accounts.list", nil); err != nil {
				errs <- err
			}
			if _, err := handleAccounts(context.Background(), e, "quota.get", nil); err != nil {
				errs <- err
			}
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
}

const serveUsage = `{"subscription_type":"max","rate_limits_available":true,"rate_limits":{"limits":[
 {"kind":"weekly_all","group":"weekly","percent":86,"severity":"warning","resets_at":"2099-11-21T17:00:00Z","is_active":true}]}}`

func TestServeQuota(t *testing.T) {
	e := accountsHome(t)
	home := e.home
	if got := mustAccounts(t, e, "quota.get", ``); got["snapshot"] != nil {
		t.Fatalf("还没有数：%v", got)
	}
	got := mustAccounts(t, e, "quota.usage", `{"response":`+serveUsage+`}`)
	snap, _ := got["snapshot"].(map[string]any)
	if got["fresh"] != true || snap == nil || snap["subscription"] != "max" {
		t.Fatalf("usage：%v", got)
	}
	windows := mustAccounts(t, e, "quota.get", ``)["snapshot"].(map[string]any)["windows"].([]any)
	if w := windows[0].(map[string]any); w["label"] != "每周 · 全部模型" || w["used"] != 0.86 || w["severity"] != "warning" {
		t.Fatalf("get：%v", w)
	}
	got = mustAccounts(t, e, "quota.event", `{"info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.3,"resetsAt":1795057510,"status":"allowed"}}}}`)
	if got["changed"] != true || len(got["snapshot"].(map[string]any)["windows"].([]any)) != 2 {
		t.Fatalf("event：%v", got)
	}
	// 换了号读不到上一个号的
	fakeLogin(t, home, "uuid-b", "b@example.com")
	if got := mustAccounts(t, e, "quota.get", ``); got["snapshot"] != nil {
		t.Fatalf("新号：%v", got)
	}
	if _, err := callAccounts(t, e, "quota.usage", `{"response":{"models":[]}}`); err == nil {
		t.Fatal("不是额度的应答要报错")
	}
}

// 经 serve 走一遍：请求和 id、method 平级带参数，答的 zeta_reply 带 result；出错带 code；慢的请求不挡 ping。
func TestServeAccountsOverServe(t *testing.T) {
	e := accountsHome(t)
	inProcessCore(t, e, fastWatch)
	c := startServe(t, e, testIdle)
	c.send(`{"id":1,"method":"accounts.list"}`)
	c.send(`{"id":2,"method":"ping"}`)
	got := map[string]obj{}
	for len(got) < 2 {
		r := c.next("zeta_reply")
		got[string(r["id"].(json.Number))] = r
	}
	result, _ := got["1"]["result"].(map[string]any)
	if got["1"]["ok"] != true || currentName(result) != "claude" {
		t.Fatalf("accounts.list：%v", got["1"])
	}
	c.send(`{"id":3,"method":"accounts.switch","name":"nobody"}`)
	if r := c.next("zeta_reply"); r["ok"] != false || r["code"] != "no_account" || r["error"] != "这个账号已经不在了" {
		t.Fatalf("换到没有的号：%v", r)
	}
	c.send(`{"id":4,"method":"quota.get"}`)
	if r := c.next("zeta_reply"); r["ok"] != true || r["result"].(map[string]any)["snapshot"] != nil {
		t.Fatalf("quota.get：%v", r)
	}
	_ = c.in.Close()
	c.exited()
}

// 对话交来的额度（conv.Deps.Quota）存进 quota.get 读的那份；起进程时顺便问的，五分钟里这台主机上只问一次。
func TestQuotaSink(t *testing.T) {
	home := t.TempDir()
	fakeLogin(t, home, "uuid-a", "a@example.com")
	e := newEnvAt(home, "zeta")
	now := time.Date(2026, 11, 22, 8, 0, 0, 0, time.UTC)
	q := newQuotaSink(e, nil)
	q.now = func() time.Time { return now }

	if !q.ClaimQuery() || q.ClaimQuery() {
		t.Fatal("头一次该问、紧接着不该再问")
	}
	now = now.Add(autoQuotaEvery - time.Second)
	if q.ClaimQuery() {
		t.Fatal("没过五分钟又问了")
	}
	now = now.Add(time.Second)
	if !q.ClaimQuery() {
		t.Fatal("过了五分钟没再问")
	}

	q.Usage(json.RawMessage(serveUsage))
	q.RateLimit(json.RawMessage(`{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.3,"resetsAt":1795057510,"status":"allowed"}}}`))
	q.Usage(json.RawMessage(`{"models":[]}`)) // 不是额度的应答：记进日志，存着的不动
	snap := mustAccounts(t, e, "quota.get", ``)["snapshot"].(map[string]any)
	var labels []string
	for _, w := range snap["windows"].([]any) {
		labels = append(labels, w.(map[string]any)["label"].(string))
	}
	if snap["subscription"] != "max" || strings.Join(labels, "|") != "5 小时|每周 · 全部模型" {
		t.Fatalf("存下的额度：%v", snap)
	}
}
