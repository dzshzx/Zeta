package main

import (
	"fmt"
	"path/filepath"
	"strings"
	"testing"
)

// snapshots 是 call 途中推来的额度（quota.snapshot 的 snapshot，几条就几个）。
func snapshots(c *serveClient) []any {
	var out []any
	for _, m := range c.stashed("quota.snapshot") {
		out = append(out, m["snapshot"])
	}
	return out
}

// 订阅额度：先推一份现在的；别的客户端存了数也推（带着新的窗口）；只读的请求不推；退订以后不推。
func TestQuotaWatch(t *testing.T) {
	home := t.TempDir()
	fakeLogin(t, home, "uuid-a", "a@example.com")
	e := newEnvAt(home, "zeta")
	inProcessCore(t, e, fastWatch)
	c := startServe(t, e, testIdle)
	other := startServe(t, e, testIdle)

	event := func(five int) string {
		return fmt.Sprintf(`"info":{"status":"allowed","resetsAt":1795057510,"rateLimitType":"five_hour","isUsingOverage":false,`+
			`"unifiedWindows":{"five_hour":{"utilization":%d,"resetsAt":1795057510,"status":"allowed"}}}`, five)
	}
	r := c.call("quota.watch", "")
	if pushes := snapshots(c); r["ok"] != true || len(pushes) != 1 || pushes[0] != nil {
		t.Fatalf("订阅时该先推一份（还没有数，是 null）：%v %v", pushes, r)
	}
	other.ok("quota.event", event(30))
	c.call("quota.get", "")
	if pushes := snapshots(c); len(pushes) != 1 || !strings.Contains(fmt.Sprint(pushes[0]), "used:30") {
		t.Fatalf("别的客户端存了新数，该推一次、带着 5 小时窗口：%v", pushes)
	}
	c.call("quota.get", "")
	if pushes := snapshots(c); len(pushes) != 0 {
		t.Fatalf("只读了一下却推了：%v", pushes)
	}
	c.call("quota.watch", `"watch":false`)
	other.ok("quota.event", event(60))
	c.call("quota.get", "")
	if pushes := snapshots(c); len(pushes) != 0 {
		t.Fatalf("退订了还推：%v", pushes)
	}
}

// 对话交来的额度存下以后通知订阅那边；存不成的不通知。
func TestQuotaSinkTellsTheWatch(t *testing.T) {
	home := t.TempDir()
	fakeLogin(t, home, "uuid-a", "a@example.com")
	told := 0
	q := newQuotaSink(newEnvAt(home, "zeta"), func() { told++ })
	q.RateLimit([]byte(`{"status":"allowed","rateLimitType":"five_hour","unifiedWindows":{"five_hour":{"utilization":10,"resetsAt":1795057510,"status":"allowed"}}}`))
	if told != 1 {
		t.Fatalf("存了额度事件该通知一次：%d", told)
	}
	q.Usage([]byte(`{"models":[]}`))
	if told != 1 {
		t.Fatalf("不是额度的应答存不成，不该通知：%d", told)
	}
}

// relays 是 call 途中推来的额度里的 relay（几条就几个）。
func pushedRelays(c *serveClient) (snaps []any, relays []any) {
	for _, m := range c.stashed("quota.snapshot") {
		snaps, relays = append(snaps, m["snapshot"]), append(relays, m["relay"])
	}
	return snaps, relays
}

// 在用中转时没有订阅额度：推的额度为 null、带着中转名，不推上一个号的旧数；quota.get、quota.usage、quota.event 不读不存；
// 对话也不顺便问（quotaSink.ClaimQuery 为假）、交来的不存。换回账号后旧数照常回来、照常问。
func TestQuotaHiddenOnRelay(t *testing.T) {
	home := t.TempDir()
	fakeLogin(t, home, "uuid-a", "a@example.com")
	e := newEnvAt(home, "zeta")
	inProcessCore(t, e, fastWatch)
	c := startServe(t, e, testIdle)
	other := startServe(t, e, testIdle)
	event := `"info":{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":30,"resetsAt":1795057510,"status":"allowed"}}}`
	c.call("quota.watch", "")
	pushedRelays(c)
	other.ok("quota.event", event)
	c.call("quota.get", "")
	if snaps, relays := pushedRelays(c); len(snaps) != 1 || snaps[0] == nil || relays[0] != "" {
		t.Fatalf("在用账号时：%v %v", snaps, relays)
	}

	// 换到中转（claude-switch 的切换记录指着一个非账号目标）
	writeFile(t, filepath.Join(home, ".config/claude-switch/targets/gw.json"), `{"env":{"ANTHROPIC_BASE_URL":"https://relay.example.com"}}`)
	writeFile(t, filepath.Join(home, ".local/state/claude-switch/current"), "gw\n")
	other.ok("quota.event", event)
	c.call("quota.get", "")
	if snaps, relays := pushedRelays(c); len(snaps) != 1 || snaps[0] != nil || relays[0] != "gw" {
		t.Fatalf("在用中转时推的：%v %v", snaps, relays)
	}
	got := mustAccounts(t, e, "quota.get", ``)
	if got["snapshot"] != nil || got["relay"] != "gw" {
		t.Fatalf("在用中转时 quota.get：%v", got)
	}
	if got := mustAccounts(t, e, "quota.usage", `{"response":`+serveUsage+`}`); got["snapshot"] != nil || got["relay"] != "gw" {
		t.Fatalf("在用中转时 quota.usage：%v", got)
	}
	q := newQuotaSink(e, nil)
	if q.Subscribed() || q.ClaimQuery() {
		t.Fatal("在用中转时对话不该顺便问额度")
	}
	q.RateLimit([]byte(`{"status":"allowed","unifiedWindows":{"five_hour":{"utilization":99,"resetsAt":1795057510,"status":"allowed"}}}`))

	// 换回账号：旧数还在（中转上交来的 99% 没存），对话照常问
	writeFile(t, filepath.Join(home, ".local/state/claude-switch/current"), "claude\n")
	got = mustAccounts(t, e, "quota.get", ``)
	if got["relay"] != "" || !strings.Contains(fmt.Sprint(got["snapshot"]), "used:30") {
		t.Fatalf("换回账号后 quota.get：%v", got)
	}
	if !q.Subscribed() || !q.ClaimQuery() {
		t.Fatal("换回账号后对话该照常问额度")
	}
}
