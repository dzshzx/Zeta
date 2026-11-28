package hub

import (
	"context"
	"encoding/json"
	"slices"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/hubtest"
)

// 只有 agy 没有 claude 的设备：添加后新对话默认工具自动设为 agy，开新对话带 harness=agy。
func TestAddHostDefaultsToAgyWhenClaudeMissing(t *testing.T) {
	d := hubtest.NewDevices()
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			if c.Method == "conv.open" {
				l.Push(`{"type":"conv.state","conv":"` + c.Conv() + `","harness":"agy"}`)
			}
			return nil, nil
		})
	}
	h, _ := newHub(t, d)
	found := d.Found("agybox")
	found.Claude = ""
	found.Agy = "0.2.0"
	x, err := h.AddHost("agybox", "", found)
	if err != nil {
		t.Fatal(err)
	}
	if h.DefaultHarness(x.ID) != "agy" {
		t.Fatalf("DefaultHarness = %q, want agy", h.DefaultHarness(x.ID))
	}
	h.NewChat()
	calls := d.Chat(x.ID).Took()
	if len(calls) != 1 || calls[0].Method != "conv.open" || calls[0].Str("harness") != "agy" {
		t.Fatalf("NewChat calls: %+v", calls)
	}
	if h.Chat().Harness() != "agy" {
		t.Fatalf("Chat.Harness = %q", h.Chat().Harness())
	}
}

// 未开始的新对话允许切工具（conv.harness）；已有会话按会话的 Harness 打开且不可再切。
func TestChatHarnessSwitchAndOpenSession(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	f.take()
	if !f.hub.Chat().Conv.CanSwitchHarness() || f.state.Harness != "claude" {
		t.Fatalf("initial state: %+v", f.state)
	}
	f.link().Took()
	f.run(func(conv *chat.Conversation) error {
		return conv.SetHarness(context.Background(), "agy")
	})
	calls := f.link().Took()
	if len(calls) != 1 || calls[0].Method != "conv.harness" || calls[0].Str("value") != "agy" {
		t.Fatalf("SetHarness calls: %+v", calls)
	}
	if f.hub.Chat().Harness() != "agy" {
		t.Fatalf("Harness after switch = %q", f.hub.Chat().Harness())
	}

	f.hub.OpenSession(hostlink.Session{ID: "s-agy", File: "/home/box/.gemini/antigravity/s-agy.pb", Cwd: "/w", Harness: "agy", Backend: f.id})
	calls = f.link().Took()
	if len(calls) != 2 || calls[1].Method != "conv.open" || calls[1].Str("harness") != "agy" {
		t.Fatalf("OpenSession calls: %+v", calls)
	}
	f.link().Push(`{"type":"conv.state","conv":"` + calls[1].Conv() + `","session":"s-agy","harness":"agy"}`)
	f.take()
	if f.state.CanSwitchHarness() || f.state.Harness != "agy" {
		t.Fatalf("reopened state: %+v", f.state)
	}
}

// Remote 同时维护 claude 与 agy 两套账号和额度：订阅推送按 harness 分流，agy 登录不要求 # 也不带 claude-switch 程序。
func TestRemoteDualAccountsAndQuotaPush(t *testing.T) {
	f, until := queuedFixture(t)
	r := f.hub.Remote(f.id)
	watches := f.link().QuotaWatches()
	if len(watches) != 2 || watches[0].Str("harness") != "" || watches[1].Str("harness") != "agy" {
		t.Fatalf("quota.watch: %+v", watches)
	}
	f.link().Push(`{"type":"quota.snapshot","harness":"agy","snapshot":{"windows":[{"kind":"model_pool","label":"Gemini Pro","used":0.25,"severity":"normal"}],"updatedAt":"2099-01-01T00:00:00Z"},"relay":""}`)
	until("agy 额度推送", func() bool { _, ok := r.AgyQuota.Snapshot(); return ok })
	if _, ok := r.Quota.Snapshot(); ok {
		t.Fatal("agy 额度推送污染了 claude 额度")
	}

	var gotCode hubtest.Call
	f.link().OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		switch c.Method {
		case "accounts.login_start":
			return json.RawMessage(`{"url":"https://accounts.google.com/o/oauth2/v2/auth?x=1"}`), nil
		case "accounts.login_code":
			gotCode = c
			return json.RawMessage(`{"current":{"name":"agy","email":"agy@example.com","current":true},"accounts":[]}`), nil
		case "quota.get":
			return json.RawMessage(`{"snapshot":null,"fresh":true}`), nil
		}
		return json.RawMessage(`{}`), nil
	})
	r.AgyAccounts.StartLogin()
	until("agy 开始登录", func() bool { _, ok := r.AgyAccounts.Login(); return ok && r.AgyAccounts.Working() == "" })
	r.AgyAccounts.SubmitCode("http://localhost:43115/oauth-callback?code=4/0Axyz")
	until("agy 提交回调链接", func() bool { _, ok := r.AgyAccounts.Login(); return !ok })
	if gotCode.Str("harness") != "agy" || gotCode.Str("program") != "" || gotCode.Str("code") == "" {
		t.Fatalf("agy login_code: %s", gotCode.JSON())
	}
}

// 没有活跃 agy 对话时刷新 agy 额度：不拉起临时对话，直接走核心的 quota.usage 刷新。
func TestAgyQuotaQueriesCoreDirectlyWhenIdle(t *testing.T) {
	f, until := queuedFixture(t)
	r := f.hub.Remote(f.id)
	f.link().Took()
	f.link().OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		switch c.Method {
		case "quota.get":
			return json.RawMessage(`{"snapshot":null,"fresh":false}`), nil
		case "quota.usage":
			return json.RawMessage(`{"fresh":true,"snapshot":{"windows":[{"kind":"model_pool","label":"Gemini Pro","used":0.2,"severity":"normal"}],"updatedAt":"2099-01-01T00:00:00Z"}}`), nil
		}
		return json.RawMessage(`{}`), nil
	})
	r.AgyQuota.Refresh(true)
	until("取完 agy 额度", func() bool { return !r.AgyQuota.Refreshing() })
	want := []string{"quota.get", "quota.usage"}
	if got := f.link().Methods(); !slices.Equal(got, want) {
		t.Fatalf("methods = %v, want %v", got, want)
	}
	if snap, ok := r.AgyQuota.Snapshot(); !ok || len(snap.Windows) != 1 {
		t.Fatalf("snapshot: %+v %v", snap, ok)
	}
}
