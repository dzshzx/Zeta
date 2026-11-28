package mobile

import (
	"encoding/json"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
)

func answerMobileAgy(l *hubtest.ChatLink, req hubtest.Call) (json.RawMessage, error) {
	if req.Method == "conv.open" {
		h := req.Str("harness")
		if h == "" {
			h = "claude"
		}
		l.Push(`{"type":"conv.state","conv":"` + req.Conv() + `","harness":"` + h + `"}`)
		return nil, nil
	}
	if req.Method == "quota.get" && req.Str("harness") == "agy" {
		return json.RawMessage(`{"snapshot":{"windows":[{"kind":"model_pool","group":"session","used":0.65,"severity":"normal","label":"Gemini Pro"}],"updatedAt":"2099-01-01T00:00:00Z"},"relay":""}`), nil
	}
	if req.Method == "accounts.list" {
		return json.RawMessage(`{"accounts":[],"relays":[]}`), nil
	}
	return json.RawMessage(`{}`), nil
}

// 设备默认工具（device.setharness）驱动首页设备行的额度提示与会话列表/对话状态的 harness 字段。
func TestMobileDeviceHarnessAndSessionState(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(req hubtest.Call) (json.RawMessage, error) { return answerMobileAgy(l, req) })
	}
	id := addBareDevice(t, c)
	if got := device(t, c, id); got.DefaultHarness != "claude" {
		t.Fatalf("initial DefaultHarness = %q", got.DefaultHarness)
	}
	call[map[string]bool](t, c, "device.setharness", map[string]string{"id": id, "harness": "agy"})
	call[map[string]bool](t, c, "settings.open", nil)
	until(t, c, changed, func() bool {
		r := c.hub.Remote(id)
		_, loaded := r.AgyAccounts.State()
		return loaded && !r.AgyQuota.Refreshing() && r.AgyQuota.Shown()
	})
	if got := device(t, c, id); got.DefaultHarness != "agy" || got.Used != 65 {
		t.Fatalf("agy device hint: %+v", got)
	}

	d.Push(id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s-agy", Title: "Agy 会话", Cwd: "/w", Harness: "agy", Updated: 10},
	}})
	c.Do(func() {})
	st := call[State](t, c, "state", nil)
	if len(st.Sessions) != 1 || st.Sessions[0].Harness != "agy" {
		t.Fatalf("sessions: %+v", st.Sessions)
	}
	call[map[string]bool](t, c, "new", map[string]string{"backend": id})
	until(t, c, changed, func() bool { ch := c.hub.Chat(); return ch != nil && ch.Conv != nil })
	st = call[State](t, c, "state", nil)
	if st.Chat == nil || st.Chat.Harness != "agy" || !st.Chat.CanSwitchHarness {
		t.Fatalf("new agy chat: %+v", st.Chat)
	}
}

// 输入栏切换工具（composer.harness）与配置页模型列表（claudecfg.models 带 harness=agy）互不串缓存。
func TestMobileComposerHarnessAndModels(t *testing.T) {
	x := openComposer(t, t.TempDir())
	st := call[State](t, x.c, "state", nil)
	if st.Chat == nil || st.Chat.Harness != "claude" || !st.Chat.CanSwitchHarness {
		t.Fatalf("initial chat: %+v", st.Chat)
	}
	call[map[string]bool](t, x.c, "composer.harness", map[string]string{"value": "agy"})
	hCall := x.waitCall(t, "conv.harness")
	if hCall.Str("value") != "agy" {
		t.Fatalf("conv.harness: %s", hCall.JSON())
	}
	x.push(t, `"harness":"agy"`)
	id := call[State](t, x.c, "state", nil).Devices[0].ID // 触发 modelMenu 启动 agy 的 ModelCatalog 查询
	until(t, x.c, x.changed, func() bool {
		s := x.c.comp().models[id+":agy"]
		return s != nil && s.ready
	})
	if comp := composerOf(t, x.c); len(comp.Model.Latest) == 0 || comp.Model.Latest[0].Value != "gemini-3.1-pro-high" {
		t.Fatalf("composer agy models: %+v", comp.Model)
	}
	got := call[modelChoices](t, x.c, "claudecfg.models", map[string]string{"backend": id, "harness": "agy"})
	if got.Source != "harness" || len(got.Models) == 0 || got.Models[0].Value != "gemini-3.1-pro-high" {
		t.Fatalf("claudecfg.models(agy): %+v", got)
	}
}

// 账号与中转路由带 harness=agy 时全部打到 AgyAccounts / AgyQuota，支持无 # 的回调链接登录。
func TestMobileAccountsHarnessRouting(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	var codeCall, saveCall hubtest.Call
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(req hubtest.Call) (json.RawMessage, error) {
			switch req.Method {
			case "accounts.list":
				if req.Str("harness") == "agy" {
					return json.RawMessage(`{"current":{"name":"agy","email":"agy@example.com","current":true},"accounts":[{"name":"agy","email":"agy@example.com","current":true}],"relays":[]}`), nil
				}
				return json.RawMessage(`{"accounts":[],"relays":[]}`), nil
			case "accounts.login_start":
				return json.RawMessage(`{"url":"https://accounts.google.com/o/oauth2/v2/auth?x=1"}`), nil
			case "accounts.login_code":
				codeCall = req
			case "accounts.relay_save":
				saveCall = req
			case "quota.get":
				return quotaAnswer(""), nil
			}
			return json.RawMessage(`{}`), nil
		})
	}
	id := addBareDevice(t, c)
	call[map[string]bool](t, c, "accounts.open", map[string]string{"id": id, "harness": "agy"})
	until(t, c, changed, func() bool {
		_, loaded := c.hub.Remote(id).AgyAccounts.State()
		return loaded
	})
	v := call[Accounts](t, c, "accounts", map[string]string{"id": id, "harness": "agy"})
	if len(v.Accounts) != 1 || v.Accounts[0].Email != "agy@example.com" {
		t.Fatalf("agy accounts: %+v", v)
	}
	call[map[string]bool](t, c, "accounts.login", map[string]string{"id": id, "harness": "agy"})
	until(t, c, changed, func() bool {
		a := c.hub.Remote(id).AgyAccounts
		_, ok := a.Login()
		return ok && a.Working() == ""
	})
	call[map[string]bool](t, c, "accounts.code", map[string]string{"id": id, "harness": "agy", "code": "http://localhost:43115/oauth-callback?code=4/0Axyz"})
	until(t, c, changed, func() bool { _, ok := c.hub.Remote(id).AgyAccounts.Login(); return !ok })
	if codeCall.Str("harness") != "agy" || codeCall.Str("program") != "" {
		t.Fatalf("login_code: %s", codeCall.JSON())
	}
	call[map[string]bool](t, c, "relay.edit", map[string]string{"id": id, "harness": "agy"})
	call[map[string]bool](t, c, "relay.draft", map[string]string{"id": id, "harness": "agy", "name": "g1", "base_url": "https://generativelanguage.googleapis.com", "key": "k-test", "model": "gemini-3.1-pro-high"})
	call[map[string]bool](t, c, "relay.save", map[string]string{"id": id, "harness": "agy"})
	until(t, c, changed, func() bool { return c.relay.saved })
	if saveCall.Str("harness") != "agy" || saveCall.Str("name") != "g1" {
		t.Fatalf("relay_save: %s", saveCall.JSON())
	}
}
