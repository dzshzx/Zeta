package main

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// upTo 收到应答 id 为止，返回途中推来的额度（quota.snapshot 的 snapshot，几条就几个）和应答。
func upTo(t *testing.T, c *serveClient, id int) (pushes []any, reply obj) {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case m := <-c.got:
			switch {
			case m["type"] == "quota.snapshot":
				pushes = append(pushes, m["snapshot"])
			case m["type"] == "zeta_reply" && fmt.Sprint(m["id"]) == fmt.Sprint(id):
				return pushes, m
			}
		case <-deadline:
			t.Fatalf("5 秒内没等到应答 %d", id)
		}
	}
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
	c.send(`{"id":1,"method":"quota.watch"}`)
	if pushes, r := upTo(t, c, 1); r["ok"] != true || len(pushes) != 1 || pushes[0] != nil {
		t.Fatalf("订阅时该先推一份（还没有数，是 null）：%v %v", pushes, r)
	}
	other.send(`{"id":1,"method":"quota.event",` + event(30) + `}`)
	if _, r := upTo(t, other, 1); r["ok"] != true {
		t.Fatalf("quota.event：%v", r)
	}
	c.send(`{"id":2,"method":"quota.get"}`)
	pushes, _ := upTo(t, c, 2)
	if len(pushes) != 1 || !strings.Contains(fmt.Sprint(pushes[0]), "used:30") {
		t.Fatalf("别的客户端存了新数，该推一次、带着 5 小时窗口：%v", pushes)
	}
	c.send(`{"id":3,"method":"quota.get"}`)
	if pushes, _ := upTo(t, c, 3); len(pushes) != 0 {
		t.Fatalf("只读了一下却推了：%v", pushes)
	}
	c.send(`{"id":4,"method":"quota.watch","watch":false}`)
	upTo(t, c, 4)
	other.send(`{"id":3,"method":"quota.event",` + event(60) + `}`)
	upTo(t, other, 3)
	c.send(`{"id":5,"method":"quota.get"}`)
	if pushes, _ := upTo(t, c, 5); len(pushes) != 0 {
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
