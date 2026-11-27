package mobile

import (
	"encoding/json"
	"slices"
	"sync"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/hubtest"
)

// fakeAccounts 是设备上的 accounts.*、quota.*：两个账号 a（在用）、b，一个中转 r1；换过去的记在 current。
type fakeAccounts struct {
	mu      sync.Mutex
	current string
	calls   []hubtest.Call
}

func (f *fakeAccounts) answer(call hubtest.Call) (json.RawMessage, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, call)
	switch call.Method {
	case "accounts.switch":
		f.current = call.Str("name")
	case "accounts.login_start":
		return json.RawMessage(`{"url":"https://claude.ai/oauth/authorize?x=1"}`), nil
	case "accounts.relay_models":
		return json.RawMessage(`{"models":[{"id":"m1"},{"id":"m2","displayName":"M2"}]}`), nil
	case "quota.get":
		if f.current == "r1" {
			return quotaAnswer("r1"), nil
		}
		return quotaAnswer(""), nil
	case "accounts.list":
		acct := func(name string) map[string]any {
			return map[string]any{"name": name, "email": name + "@x", "plan": "Max", "current": f.current == name, "parked": true}
		}
		list := map[string]any{"accounts": []any{acct("a"), acct("b")},
			"relays": []any{map[string]any{"name": "r1", "baseUrl": "https://r1", "hasKey": true, "keyEditable": false,
				"current": f.current == "r1"}}}
		if f.current != "r1" {
			list["current"] = acct(f.current)
		}
		return json.Marshal(list)
	}
	return json.RawMessage(`{}`), nil
}

func (f *fakeAccounts) called(method string) []hubtest.Call {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []hubtest.Call
	for _, c := range f.calls {
		if c.Method == method {
			out = append(out, c)
		}
	}
	return out
}

func accountsCore(t *testing.T) (*Core, string, chan struct{}, *fakeAccounts) {
	t.Helper()
	c, d, changed := newWatchedCore(t)
	f := &fakeAccounts{current: "a"}
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(f.answer)
	}
	id := addDevice(t, c)
	call[map[string]bool](t, c, "accounts.open", map[string]string{"id": id})
	until(t, c, changed, func() bool {
		r := c.hub.Remote(id)
		_, loaded := r.Accounts.State()
		return loaded && !r.Quota.Refreshing() && r.Quota.Shown()
	})
	return c, id, changed, f
}

// 账号页：账号、额度；切换交给设备，换到中转后不显示订阅额度。
func TestAccountsSwitch(t *testing.T) {
	c, id, changed, f := accountsCore(t)
	v := call[Accounts](t, c, "accounts", map[string]string{"id": id})
	if !v.Loaded || len(v.Accounts) != 2 || !v.Accounts[0].Current || v.Accounts[1].Current || v.Quota == nil ||
		len(v.Quota.Windows) != 1 || v.Quota.Windows[0].Percent != 42 || v.Quota.Windows[0].Severity != "warning" {
		t.Fatalf("打开时 %+v", v)
	}
	if len(v.Relays) != 1 || v.Relays[0].KeyEditable || v.Relay != "" {
		t.Fatalf("中转 %+v", v.Relays)
	}

	call[map[string]bool](t, c, "accounts.switch", map[string]string{"id": id, "name": "b"})
	until(t, c, changed, func() bool { return c.hub.Remote(id).Accounts.Working() == "" && len(f.called("accounts.switch")) == 1 })
	until(t, c, changed, func() bool { return !c.hub.Remote(id).Quota.Refreshing() })
	if v := call[Accounts](t, c, "accounts", map[string]string{"id": id}); !v.Accounts[1].Current || v.Problem != "" {
		t.Fatalf("换到 b 后 %+v", v)
	}
	failed(t, c, "accounts.switch", map[string]string{"id": id, "name": "nobody"})

	call[map[string]bool](t, c, "relay.switch", map[string]string{"id": id, "name": "r1"})
	until(t, c, changed, func() bool { return c.hub.Remote(id).Accounts.Working() == "" && len(f.called("accounts.switch")) == 2 })
	until(t, c, changed, func() bool { r := c.hub.Remote(id); return !r.Quota.Refreshing() && r.Quota.Relay() == "r1" })
	if v := call[Accounts](t, c, "accounts", map[string]string{"id": id}); v.Relay != "r1" || v.Quota != nil {
		t.Fatalf("在用中转时 relay %q，额度 %+v", v.Relay, v.Quota)
	}
}

// 添加账号：拿到授权网址；码不完整时退回，完整的交给设备。
func TestAccountsLogin(t *testing.T) {
	c, id, changed, f := accountsCore(t)
	call[map[string]bool](t, c, "accounts.login", map[string]string{"id": id})
	until(t, c, changed, func() bool { _, ok := c.hub.Remote(id).Accounts.Login(); return ok })
	v := call[Accounts](t, c, "accounts", map[string]string{"id": id})
	if v.Login == nil || v.Login.URL != "https://claude.ai/oauth/authorize?x=1" {
		t.Fatalf("登录 %+v", v.Login)
	}
	call[map[string]bool](t, c, "accounts.code", map[string]string{"id": id, "code": "abc"})
	if v := call[Accounts](t, c, "accounts", map[string]string{"id": id}); v.Login == nil || v.Login.Notice == "" {
		t.Fatalf("不完整的码 %+v", v.Login)
	}
	call[map[string]bool](t, c, "accounts.code", map[string]string{"id": id, "code": "abc#def"})
	until(t, c, changed, func() bool { _, ok := c.hub.Remote(id).Accounts.Login(); return !ok })
	if got := f.called("accounts.login_code"); len(got) != 1 || got[0].Str("code") != "abc#def" {
		t.Fatalf("交给设备的 %+v", got)
	}
}

// 中转：编辑草稿（名字加好后不能改）、拉模型列表、保存；关了以后操作出错。
func TestRelayDraft(t *testing.T) {
	c, id, changed, f := accountsCore(t)
	call[map[string]bool](t, c, "relay.edit", map[string]string{"id": id, "name": "r1"})
	call[map[string]bool](t, c, "relay.draft", map[string]string{"id": id, "name": "改名", "base_url": "https://r2", "model": "m2"})
	v := call[Accounts](t, c, "accounts", map[string]string{"id": id})
	if d := v.Draft; d == nil || d.Create || d.Name != "r1" || d.BaseURL != "https://r2" || d.KeyEditable || !d.Ready || !d.CanFetch {
		t.Fatalf("草稿 %+v", v.Draft)
	}
	call[map[string]bool](t, c, "relay.fetch", map[string]string{"id": id})
	until(t, c, changed, func() bool { m, ok := c.relay.d.Models(); return ok && len(m) == 2 })
	call[map[string]bool](t, c, "relay.save", map[string]string{"id": id})
	until(t, c, changed, func() bool { return c.relay.saved })
	saved := f.called("accounts.relay_save")
	if len(saved) != 1 || saved[0].Str("name") != "r1" || saved[0].Str("baseUrl") != "https://r2" {
		t.Fatalf("保存的 %+v", saved)
	}
	if v := call[Accounts](t, c, "accounts", map[string]string{"id": id}); v.Draft == nil || !v.Draft.Saved ||
		!slices.ContainsFunc(v.Draft.Models, func(m coreapi.RelayModel) bool { return m.ID == "m2" }) {
		t.Fatalf("保存后 %+v", v.Draft)
	}
	call[map[string]bool](t, c, "relay.close", nil)
	failed(t, c, "relay.save", map[string]string{"id": id})
}
