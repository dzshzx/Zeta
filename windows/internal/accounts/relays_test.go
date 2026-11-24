package accounts

// 中转：列出、换过去、添加修改删除、拉模型列表，在用中转时额度不取。serve 是 service_test.go 里的假 serve，这里补上
// accounts.relay_* 的应答（照 docs/core.md「账号与额度」）。密钥都是 sk-test- 开头的假值。

import (
	"encoding/json"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
)

// testKey 是假密钥：出错文案、Problem 里都不该出现它。
const testKey = "sk-test-relay-0000000000000001"

// relayCall 回 accounts.relay_save、relay_remove、relay_models（f.mu 已经拿着）。
func (f *fakeServe) relayCall(method, name string, p map[string]any) (json.RawMessage, error) {
	switch method {
	case "accounts.relay_save":
		key, _ := p["key"].(string)
		url, _ := p["baseUrl"].(string)
		model, _ := p["model"].(string)
		i := slices.IndexFunc(f.relays, func(r coreapi.Relay) bool { return r.Name == name })
		if create, _ := p["create"].(bool); create {
			switch {
			case i >= 0 || slices.ContainsFunc(f.accounts, func(a coreapi.Account) bool { return a.Name == name }):
				return nil, codeErr{"errRelayNameTaken", "这个名称已经有账号或中转在用了"}
			case key == "":
				return nil, codeErr{"errRelayKeyRequired", "添加中转要填密钥"}
			}
			f.relays = append(f.relays, coreapi.Relay{Name: name, BaseURL: url, Model: model, HasKey: true, KeyEditable: true})
			return json.RawMessage(`{}`), nil
		}
		if i < 0 {
			return nil, codeErr{"errRelayMissing", "这个中转已经不在了"}
		}
		r := &f.relays[i]
		r.BaseURL, r.Model = url, model
		r.HasKey = r.HasKey || key != ""
	case "accounts.relay_remove":
		if name == f.relay {
			return nil, codeErr{"errRelayInUse", "正在用这个中转，先换到别的账号或中转再删"}
		}
		f.relays = slices.DeleteFunc(f.relays, func(r coreapi.Relay) bool { return r.Name == name })
	case "accounts.relay_models":
		if f.modelsErr != nil {
			return nil, f.modelsErr
		}
		return json.Marshal(map[string]any{"models": f.models})
	}
	return json.RawMessage(`{}`), nil
}

func (f *fakeServe) lastParams() map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.params) == 0 {
		return nil
	}
	return f.params[len(f.params)-1]
}

// relayFixture 是装好 claude-switch、有两个中转的一套；在用的是账号 claude。
func relayFixture() *fixture {
	f := newFixture()
	f.serve.installed = true
	f.serve.relays = []coreapi.Relay{
		{Name: "cpa", BaseURL: "https://relay.example.com/cpa2", Model: "gpt-5", HasKey: true, KeyEditable: true},
		{Name: "mine", BaseURL: "https://mine.example.com", HasKey: true},
	}
	return f
}

func TestRelaysListed(t *testing.T) {
	f := relayFixture()
	f.serve.current, f.serve.relay = "", "cpa"
	f.svc.Refresh()
	f.loop.until(t, "读到账号", func() bool { _, ok := f.svc.State(); return ok })
	st, _ := f.svc.State()
	if len(st.Relays) != 2 || st.Current != nil || st.CurrentRelay() == nil || st.CurrentRelay().Name != "cpa" {
		t.Fatalf("%+v", st)
	}
	// 读到在用中转：额度跟着知道，不显示订阅额度
	if f.quota.Subscribed() || f.quota.Shown() || f.quota.Relay() != "cpa" {
		t.Fatalf("额度：%q", f.quota.Relay())
	}
}

// resetCalls 清掉假 serve 记下的请求。
func (f *fakeServe) resetCalls() {
	f.mu.Lock()
	f.calls = nil
	f.mu.Unlock()
}

// 换到中转同换号：收掉闲着的对话进程；换好后额度清掉，不再问 Claude Code（不发 conv.quota、quota.usage）。换回账号后照常。
func TestSwitchToRelay(t *testing.T) {
	f := relayFixture()
	f.serve.quota = snapshotJSON(40)
	f.quota.Refresh(false)
	f.loop.until(t, "取到额度", func() bool { _, ok := f.quota.Snapshot(); return ok && !f.quota.Refreshing() })
	asked := f.src.asked.Load()

	f.svc.SwitchRelay(f.serve.relays[0])
	if f.svc.Working() != "正在切换到 cpa" {
		t.Fatalf("%q", f.svc.Working())
	}
	f.loop.until(t, "换好", func() bool { return f.svc.Working() == "" && !f.quota.Refreshing() })
	st, _ := f.svc.State()
	if f.svc.Problem() != "" || st.CurrentRelay() == nil || st.CurrentRelay().Name != "cpa" || f.convs.closed != 1 {
		t.Fatalf("%q %+v，收掉 %d 次", f.svc.Problem(), st, f.convs.closed)
	}
	if _, ok := f.quota.Snapshot(); ok || f.quota.Subscribed() {
		t.Fatal("在用中转时不该有额度")
	}
	f.serve.resetCalls()
	f.quota.Refresh(true)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if f.src.asked.Load() != asked || f.serve.called("conv.quota") || f.serve.called("quota.usage") {
		t.Fatalf("在用中转时问了额度：问 %d 次，%q", f.src.asked.Load(), f.serve.calls)
	}
	if _, ok := f.quota.Snapshot(); ok || f.quota.Subscribed() || f.quota.Shown() {
		t.Fatal("在用中转时不该显示额度")
	}

	// 换回账号：额度重取
	f.svc.Switch(coreapi.Account{Name: "claude-b", Email: "b@example.com"})
	f.loop.until(t, "换回", func() bool { return f.svc.Working() == "" && current(f.svc) == "claude-b" })
	f.loop.until(t, "重取额度", func() bool { _, ok := f.quota.Snapshot(); return ok && !f.quota.Refreshing() })
	if !f.quota.Shown() || f.src.asked.Load() != asked+1 {
		t.Fatalf("换回账号后：%q，问 %d 次", f.quota.Relay(), f.src.asked.Load())
	}
}

func TestSwitchToRelayRefused(t *testing.T) {
	f := relayFixture()
	f.convs.busy = true
	f.svc.SwitchRelay(f.serve.relays[0])
	if f.svc.Problem() != errOwnBusy.Error() || f.svc.Working() != "" {
		t.Fatalf("%q", f.svc.Problem())
	}
	f.convs.busy = false
	f.serve.blocker = "终端里开着 1 个 Claude Code"
	f.svc.SwitchRelay(f.serve.relays[0])
	f.loop.until(t, "做完", func() bool { return f.svc.Working() == "" })
	if f.svc.Problem() != f.serve.blocker || f.serve.called("accounts.switch cpa") {
		t.Fatalf("别处挡着：%q", f.svc.Problem())
	}
}

// 没读过账号时，额度先读 quota.get：核心说在用中转就不问 Claude Code；终端里换回账号后，下一次读到就恢复。
func TestQuotaFollowsCore(t *testing.T) {
	f := relayFixture()
	f.serve.current, f.serve.relay = "", "cpa"
	f.serve.quota = snapshotJSON(40)
	f.quota.Refresh(true)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if f.src.asked.Load() != 0 || f.serve.called("conv.quota") || f.serve.called("quota.usage") || !f.serve.called("quota.get") {
		t.Fatalf("在用中转：问 %d 次，%q", f.src.asked.Load(), f.serve.calls)
	}
	if f.quota.Subscribed() || f.quota.Relay() != "cpa" || f.quota.err != nil {
		t.Fatalf("%q %v", f.quota.Relay(), f.quota.err)
	}

	f.serve.mu.Lock()
	f.serve.current, f.serve.relay = "claude", ""
	f.serve.mu.Unlock()
	f.quota.Refresh(true)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if _, ok := f.quota.Snapshot(); !ok || !f.quota.Subscribed() {
		t.Fatal("换回账号后该有额度")
	}
	f.quota.Refresh(true) // 知道是订阅了：照常问 Claude Code
	f.loop.until(t, "问完", func() bool { return !f.quota.Refreshing() })
	if f.src.asked.Load() != 1 {
		t.Fatalf("问了 %d 次", f.src.asked.Load())
	}
}

// 还不知道在用的是不是中转时不显示额度（在用中转的设备不先闪一下）；读失败时显示，让原因看得见；读到订阅账号后显示。
func TestQuotaShownOnlyWhenKnown(t *testing.T) {
	f := relayFixture()
	if f.quota.Shown() || f.quota.Subscribed() {
		t.Fatal("还没读过就显示了")
	}
	f.serve.mu.Lock()
	f.serve.failQuota = true
	f.serve.mu.Unlock()
	f.quota.Refresh(false)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if !f.quota.Shown() || f.quota.Subscribed() || f.quota.err == nil {
		t.Fatalf("读失败时：%v", f.quota.err)
	}
	f.serve.mu.Lock()
	f.serve.failQuota = false
	f.serve.mu.Unlock()
	f.quota.Refresh(false)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if !f.quota.Shown() || !f.quota.Subscribed() {
		t.Fatal("读到订阅账号后该显示")
	}
}

// 读过以后终端里换成了中转：conv.quota 被核心拒绝（errNoSubscription），重读 quota.get 拿到中转名，不算出错。
func TestQuotaRefusedByCore(t *testing.T) {
	f := relayFixture()
	f.quota.Refresh(false)
	f.loop.until(t, "取到", func() bool { _, ok := f.quota.Snapshot(); return ok && !f.quota.Refreshing() })
	f.serve.mu.Lock()
	f.serve.current, f.serve.relay = "", "cpa"
	f.serve.calls = nil
	f.serve.mu.Unlock()
	f.quota.now = func() time.Time { return time.Now().Add(time.Minute) } // 过了两次之间的间隔
	f.quota.Refresh(true)
	f.loop.until(t, "读完", func() bool { return !f.quota.Refreshing() })
	if f.quota.Subscribed() || f.quota.Relay() != "cpa" || f.quota.err != nil || f.serve.called("quota.usage") {
		t.Fatalf("%q %v %q", f.quota.Relay(), f.quota.err, f.serve.calls)
	}
}

func TestDraftRules(t *testing.T) {
	d := NewRelayDraft()
	if d.Ready() || d.CanFetch() {
		t.Fatalf("空草稿：%+v", d)
	}
	d.Name, d.BaseURL = "new", "https://n.example.com"
	if d.Ready() || d.CanFetch() {
		t.Fatal("添加时没填密钥不能保存、不能拉模型")
	}
	d.Key = testKey
	if !d.Ready() || !d.CanFetch() {
		t.Fatal("填齐了")
	}

	e := EditRelayDraft(coreapi.Relay{Name: "cpa", BaseURL: "https://r.example.com", HasKey: true, KeyEditable: true})
	if !e.Ready() || !e.CanFetch() || e.Input().Key != "" {
		t.Fatalf("编辑时密钥空着是不改：%+v", e.Input())
	}
	ro := EditRelayDraft(coreapi.Relay{Name: "mine", BaseURL: "https://m.example.com", HasKey: true})
	ro.Key = testKey
	if ro.Input().Key != "" {
		t.Fatal("Zeta 改不了的密钥不该交")
	}
}

// 添加：交的参数照草稿（去掉首尾空白），做成后重读账号、调 saved；编辑：名字不变、密钥空着是不改。
func TestSaveRelay(t *testing.T) {
	f := relayFixture()
	d := NewRelayDraft()
	d.Name, d.BaseURL, d.Key, d.Model = " new ", "https://n.example.com/ ", testKey, "claude-sonnet-4-5"
	saved := 0
	f.svc.SaveRelay(d, func() { saved++ })
	if f.svc.Working() != "正在保存 new" {
		t.Fatalf("%q", f.svc.Working())
	}
	f.loop.until(t, "保存", func() bool { return f.svc.Working() == "" })
	want := map[string]any{"program": "bin/claude-switch-1", "name": "new", "baseUrl": "https://n.example.com/", "key": testKey,
		"model": "claude-sonnet-4-5", "create": true}
	if got := f.serve.lastParams(); !sameParams(got, want) {
		t.Fatalf("交的参数：%v", got)
	}
	st, _ := f.svc.State()
	if saved != 1 || f.svc.Problem() != "" || len(st.Relays) != 3 || f.convs.closed != 0 {
		t.Fatalf("saved %d，%q，%+v", saved, f.svc.Problem(), st.Relays)
	}

	e := EditRelayDraft(f.serve.relays[0])
	e.Model = ""
	f.svc.SaveRelay(e, nil)
	f.loop.until(t, "保存", func() bool { return f.svc.Working() == "" })
	got := f.serve.lastParams()
	if got["name"] != "cpa" || got["key"] != "" || got["model"] != "" || got["create"] != false || got["auth"] != nil {
		t.Fatalf("编辑交的参数：%v", got)
	}
}

// 没保存成：说原因（核心按语言写好的），不带密钥。
func TestSaveRelayProblemHasNoKey(t *testing.T) {
	f := relayFixture()
	d := NewRelayDraft()
	d.Name, d.BaseURL, d.Key = "cpa", "https://x.example.com", testKey
	saved := false
	f.svc.SaveRelay(d, func() { saved = true })
	f.loop.until(t, "做完", func() bool { return f.svc.Working() == "" })
	if saved || f.svc.Problem() != "没有保存：这个名称已经有账号或中转在用了" || strings.Contains(f.svc.Problem(), testKey) {
		t.Fatalf("%v %q", saved, f.svc.Problem())
	}
}

func TestRemoveRelay(t *testing.T) {
	f := relayFixture()
	removed := 0
	f.svc.RemoveRelay("mine", func() { removed++ })
	if f.svc.Working() != "正在删除 mine" {
		t.Fatalf("%q", f.svc.Working())
	}
	f.loop.until(t, "删完", func() bool { return f.svc.Working() == "" })
	st, _ := f.svc.State()
	if removed != 1 || len(st.Relays) != 1 {
		t.Fatalf("%d %+v", removed, st.Relays)
	}
	f.serve.current, f.serve.relay = "", "cpa"
	f.svc.RemoveRelay("cpa", func() { removed++ })
	f.loop.until(t, "删完", func() bool { return f.svc.Working() == "" })
	if removed != 1 || f.svc.Problem() != "没有删除：正在用这个中转，先换到别的账号或中转再删" {
		t.Fatalf("删在用的：%q", f.svc.Problem())
	}
}

// 拉模型列表：带草稿里的地址和密钥，结果记在草稿里；出错说原因。
func TestFetchRelayModels(t *testing.T) {
	f := relayFixture()
	f.serve.models = []coreapi.RelayModel{{ID: "claude-sonnet-4-5", DisplayName: "Claude Sonnet 4.5"}, {ID: "gpt-5"}}
	d := NewRelayDraft()
	d.Name, d.BaseURL, d.Key = "new", "https://n.example.com", testKey
	f.svc.FetchRelayModels(d)
	if !d.Loading() || d.CanFetch() {
		t.Fatal("拉的时候不能再拉")
	}
	f.loop.until(t, "拉到", func() bool { return !d.Loading() })
	models, fetched := d.Models()
	if !fetched || len(models) != 2 || d.ModelsProblem() != "" {
		t.Fatalf("%v %v %q", fetched, models, d.ModelsProblem())
	}
	want := map[string]any{"program": "bin/claude-switch-1", "name": "new", "baseUrl": "https://n.example.com", "key": testKey}
	if got := f.serve.lastParams(); !sameParams(got, want) {
		t.Fatalf("交的参数：%v", got)
	}

	f.serve.modelsErr = codeErr{"errModelsAuth", "中转拒绝了这个密钥"}
	f.svc.FetchRelayModels(d)
	f.loop.until(t, "拉完", func() bool { return !d.Loading() })
	if d.ModelsProblem() != "没取到模型列表：中转拒绝了这个密钥" || strings.Contains(d.ModelsProblem(), testKey) {
		t.Fatalf("%q", d.ModelsProblem())
	}
	if models, _ := d.Models(); len(models) != 2 {
		t.Fatal("没拉成时留着上一次的列表")
	}
}

func TestFilterModels(t *testing.T) {
	models := []coreapi.RelayModel{{ID: "claude-sonnet-4-5", DisplayName: "Claude Sonnet 4.5"}, {ID: "gpt-5"}, {ID: "claude-opus-4-1"}}
	if got := FilterModels(models, " SONNET "); len(got) != 1 || got[0].ID != "claude-sonnet-4-5" {
		t.Fatalf("%v", got)
	}
	if got := FilterModels(models, ""); len(got) != 3 {
		t.Fatalf("%v", got)
	}
	if got := FilterModels(models, "claude"); len(got) != 2 {
		t.Fatalf("%v", got)
	}
}

// sameParams 比较请求参数（JSON 解出来的 map）。鉴权方式桌面版不选：got 里的 auth 要么没有、要么是空的，不算进比较。
func sameParams(got, want map[string]any) bool {
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	return string(a) == string(b)
}
