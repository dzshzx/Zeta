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
)

// accountsHome 摆好一个临时 HOME：装上 claude-switch，登录 uuid-a。
func accountsHome(t *testing.T) string {
	t.Helper()
	out, err := exec.Command("go", "tool", "-n", "claude-switch").Output()
	if err != nil {
		t.Fatalf("要 go 编 claude-switch：%v", err)
	}
	bin, err := os.ReadFile(strings.TrimSpace(string(out)))
	if err != nil {
		t.Fatal(err)
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("ZETA_NAME", "zeta")
	path := filepath.Join(home, ".cache/zeta/bin/claude-switch-0123456789abcdef")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, bin, 0o755); err != nil {
		t.Fatal(err)
	}
	fakeLogin(t, home, "uuid-a", "a@example.com")
	return home
}

func fakeLogin(t *testing.T, home, uuid, email string) {
	t.Helper()
	writeFile(t, filepath.Join(home, ".claude.json"),
		`{"oauthAccount":{"accountUuid":"`+uuid+`","emailAddress":"`+email+`"}}`)
	writeFile(t, filepath.Join(home, ".claude/.credentials.json"),
		`{"claudeAiOauth":{"accessToken":"fake-`+uuid+`","refreshToken":"fake-r-`+uuid+`","subscriptionType":"max"}}`)
}

// callAccounts 调一个方法，结果按 JSON 读回来（同客户端收到的样子）。
func callAccounts(t *testing.T, method, params string) (obj, error) {
	t.Helper()
	result, err := handleAccounts(context.Background(), method, json.RawMessage(params))
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

func mustAccounts(t *testing.T, method, params string) obj {
	t.Helper()
	m, err := callAccounts(t, method, params)
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
	home := accountsHome(t)
	list := mustAccounts(t, "accounts.list", `{"id":1,"method":"accounts.list"}`)
	if currentName(list) != "claude" || len(list["accounts"].([]any)) != 1 || len(list["procs"].([]any)) != 0 {
		t.Fatalf("list：%v", list)
	}
	if got := mustAccounts(t, "accounts.prepare_login", ``); got["name"] != "claude-b" {
		t.Fatalf("prepare_login：%v", got)
	}
	fakeLogin(t, home, "uuid-b", "b@example.com")
	mustAccounts(t, "accounts.switch", `{"name":"claude"}`)
	list = mustAccounts(t, "accounts.list", ``)
	if currentName(list) != "claude" || len(list["accounts"].([]any)) != 2 {
		t.Fatalf("换回以后：%v", list)
	}
	mustAccounts(t, "accounts.remove", `{"name":"claude-b"}`)

	// 认得出的出错带代号
	_, err := callAccounts(t, "accounts.switch", `{"name":"claude-b"}`)
	var coded interface{ Code() string }
	if !errors.As(err, &coded) || coded.Code() != "no_account" {
		t.Fatalf("换到删掉的号：%v", err)
	}
	_, err = callAccounts(t, "accounts.list", `{"program":".cache/zeta/bin/claude-switch-ffffffffffffffff"}`)
	if !errors.As(err, &coded) || coded.Code() != "claude_switch_missing" {
		t.Fatalf("客户端带的版本没装：%v", err)
	}
	if _, err := callAccounts(t, "accounts.nope", ``); err == nil || !strings.Contains(err.Error(), "unknown method") {
		t.Fatalf("不认识的方法：%v", err)
	}
	if _, err := callAccounts(t, "accounts.list", `[1]`); err == nil {
		t.Fatal("参数不是对象时要报错")
	}
}

// 入口可以并发调用（serve 把慢的请求放进 goroutine）。
func TestServeAccountsConcurrent(t *testing.T) {
	accountsHome(t)
	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for range 8 {
		wg.Go(func() {
			if _, err := handleAccounts(context.Background(), "accounts.list", nil); err != nil {
				errs <- err
			}
			if _, err := handleAccounts(context.Background(), "quota.get", nil); err != nil {
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
	home := accountsHome(t)
	if got := mustAccounts(t, "quota.get", ``); got["snapshot"] != nil {
		t.Fatalf("还没有数：%v", got)
	}
	got := mustAccounts(t, "quota.usage", `{"response":`+serveUsage+`}`)
	snap, _ := got["snapshot"].(map[string]any)
	if got["fresh"] != true || snap == nil || snap["subscription"] != "max" {
		t.Fatalf("usage：%v", got)
	}
	windows := mustAccounts(t, "quota.get", ``)["snapshot"].(map[string]any)["windows"].([]any)
	if w := windows[0].(map[string]any); w["label"] != "每周 · 全部模型" || w["used"] != 0.86 || w["severity"] != "warning" {
		t.Fatalf("get：%v", w)
	}
	got = mustAccounts(t, "quota.event", `{"info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":0.3,"resetsAt":1795057510,"status":"allowed"}}}}`)
	if got["changed"] != true || len(got["snapshot"].(map[string]any)["windows"].([]any)) != 2 {
		t.Fatalf("event：%v", got)
	}
	// 换了号读不到上一个号的
	fakeLogin(t, home, "uuid-b", "b@example.com")
	if got := mustAccounts(t, "quota.get", ``); got["snapshot"] != nil {
		t.Fatalf("新号：%v", got)
	}
	if _, err := callAccounts(t, "quota.usage", `{"response":{"models":[]}}`); err == nil {
		t.Fatal("不是额度的应答要报错")
	}
}
