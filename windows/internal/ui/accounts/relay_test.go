package accounts

// 中转一组和编辑页：点行换过去（先确认）、行尾「编辑」、添加、拉模型列表后选一个、保存交的参数、删除先确认。
// 密钥都是 sk-test- 开头的假值。

import (
	"encoding/json"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/egoist/mygo/ui"
)

const testKey = "sk-test-relay-0000000000000001"

// relayCall 回 accounts.relay_*（h.mu 已经拿着）。
func (h *fakeServe) relayCall(method, name string, p map[string]any) (json.RawMessage, error) {
	switch method {
	case "accounts.relay_save":
		url, _ := p["baseUrl"].(string)
		model, _ := p["model"].(string)
		if create, _ := p["create"].(bool); create {
			h.relays = append(h.relays, coreapi.Relay{Name: name, BaseURL: url, Model: model, HasKey: true, KeyEditable: true})
			break
		}
		for i := range h.relays {
			if h.relays[i].Name == name {
				h.relays[i].BaseURL, h.relays[i].Model = url, model
			}
		}
	case "accounts.relay_remove":
		h.relays = slices.DeleteFunc(h.relays, func(r coreapi.Relay) bool { return r.Name == name })
	case "accounts.relay_models":
		return json.Marshal(map[string]any{"models": h.models})
	}
	return json.RawMessage(`{}`), nil
}

func (h *fakeServe) lastParams() map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.params) == 0 {
		return nil
	}
	return h.params[len(h.params)-1]
}

// relayFixture 是有一个中转 cpa 的账号页，在用的是账号 claude。
func relayFixture() *fixture {
	f := newFixture()
	f.host.relays = []coreapi.Relay{{Name: "cpa", BaseURL: "https://relay.example.com/cpa2", Model: "gpt-5",
		HasKey: true, KeyEditable: true}}
	f.host.models = []coreapi.RelayModel{{ID: "claude-sonnet-4-5", DisplayName: "Claude Sonnet 4.5"}, {ID: "gpt-5"}}
	return f
}

func TestRelayGroup(t *testing.T) {
	f := relayFixture()
	tt := ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", f.loaded)
	hasAll(t, tt, "中转", "cpa", "https://relay.example.com/cpa2 · 默认模型 gpt-5", "编辑", "添加中转")
}

// 点一行先问，确认了才换；换好后中转那一行写「当前」，没有订阅额度一组，也不再问 Claude Code。
func TestRelaySwitch(t *testing.T) {
	f := relayFixture()
	tt := ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", f.loaded)
	asked := f.src.asked.Load()
	click(t, tt, "cpa")
	hasAll(t, tt, "切换到中转 cpa？")
	click(t, tt, "切换") // 对话框里的；账号 claude-b 那行也有「切换」，对话框盖在上面
	f.until(t, tt, "换好", func() bool {
		st, _ := f.page.Accounts.State()
		return f.page.Accounts.Working() == "" && st.CurrentRelay() != nil && !f.page.Quota.Refreshing()
	})
	if !f.host.called("accounts.switch cpa") {
		t.Fatalf("没换：%q", f.host.calls)
	}
	hasAll(t, tt, "cpa", "当前")
	if shows(tt, "订阅额度") || shows(tt, "86%") || shows(tt, "刷新") || f.src.asked.Load() != asked {
		t.Fatalf("在用中转还显示额度或问了 Claude Code：%q，问 %d 次", tt.Texts(), f.src.asked.Load())
	}
}

// 行尾「编辑」打开编辑页，不触发换过去；编辑页 Back 回到账号页。
func TestRelayEditOpens(t *testing.T) {
	f := relayFixture()
	tt := ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "编辑")
	if f.page.edit == nil || f.page.askSwitchRelay {
		t.Fatalf("编辑：%v，问换过去：%v", f.page.edit, f.page.askSwitchRelay)
	}
	if f.page.Title() != "编辑中转 cpa" {
		t.Fatalf("%q", f.page.Title())
	}
	hasAll(t, tt, "名称加好以后不能改", "要是 Anthropic Messages 协议的接入点；OpenAI 协议的上游请经能翻译的中转接入。", "密钥", "默认模型", "删除", "保存")
	if !f.page.Back() || f.page.edit != nil || f.page.Back() {
		t.Fatal("Back 该回到账号页")
	}
}

// 添加：填好后拉模型列表、点一个选上，保存交的参数照草稿；保存成了回到账号页，新的中转在列表里。
func TestRelayAdd(t *testing.T) {
	f := relayFixture()
	tt := ui.NewTester(f.page.View, 640, 1600)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "添加中转")
	d := f.page.edit
	if d == nil || !d.Create || f.page.Title() != "添加中转" {
		t.Fatalf("没打开添加：%+v", d)
	}
	hasAll(t, tt, "填好地址和密钥后能获取模型列表")
	d.Name, d.BaseURL, d.Key = "new", "https://n.example.com", testKey
	tt.Frame()
	if shows(tt, testKey) {
		t.Fatal("密钥该遮着")
	}
	if shows(tt, "协议") {
		t.Fatal("不该再有协议选择")
	}
	click(t, tt, "获取模型列表")
	f.until(t, tt, "拉到模型", func() bool { return tt.HasText("Claude Sonnet 4.5") })
	hasAll(t, tt, "claude-sonnet-4-5", "gpt-5")
	click(t, tt, "Claude Sonnet 4.5")
	if d.Model != "claude-sonnet-4-5" {
		t.Fatalf("选的模型：%q", d.Model)
	}
	hasAll(t, tt, "已选")
	click(t, tt, "保存")
	f.until(t, tt, "保存", func() bool { return f.page.edit == nil && f.page.Accounts.Working() == "" })
	want := map[string]any{"name": "new", "baseUrl": "https://n.example.com", "key": testKey, "model": "claude-sonnet-4-5",
		"create": true}
	got, _ := json.Marshal(f.host.lastParams()) // 不带鉴权方式
	w, _ := json.Marshal(want)
	if string(got) != string(w) {
		t.Fatalf("交的参数：%s", got)
	}
	hasAll(t, tt, "new", "https://n.example.com · 默认模型 claude-sonnet-4-5")
	if strings.Contains(strings.Join(tt.Texts(), "\n"), testKey) {
		t.Fatal("界面上出现了密钥")
	}
}

// 删除先确认（危险样式），删成了回到账号页；在用的中转删不了，说明先换走。
func TestRelayRemove(t *testing.T) {
	f := relayFixture()
	tt := ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "编辑")
	click(t, tt, "删除")
	hasAll(t, tt, "删除中转 cpa？")
	click(t, tt, "取消")
	if f.host.called("accounts.relay_remove cpa") {
		t.Fatal("取消了还在删")
	}
	click(t, tt, "删除")
	click(t, tt, "删除") // 对话框里的
	f.until(t, tt, "删完", func() bool { return f.page.edit == nil && f.page.Accounts.Working() == "" })
	if !f.host.called("accounts.relay_remove cpa") || shows(tt, "cpa") {
		t.Fatalf("没删：%q %q", f.host.calls, tt.Texts())
	}

	f = relayFixture()
	f.host.current, f.host.relay = "", "cpa"
	tt = ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", func() bool { _, ok := f.page.Accounts.State(); return ok && !f.page.Quota.Refreshing() })
	click(t, tt, "编辑")
	hasAll(t, tt, "正在用这个中转：改了以后，新起的对话进程才用上。要删除先换到别的账号或中转。")
	click(t, tt, "删除")
	if f.page.askRemoveRelay {
		t.Fatal("在用的中转不该能删")
	}
}

// 用户自己放的中转（密钥不是从文件读的）：密钥框灰着并说明，交的参数里不带密钥。
func TestRelayKeyReadOnly(t *testing.T) {
	f := relayFixture()
	f.host.relays[0].KeyEditable = false
	tt := ui.NewTester(f.page.View, 640, 1400)
	f.until(t, tt, "读完", f.loaded)
	click(t, tt, "编辑")
	hasAll(t, tt, "密钥不是从文件读的，Zeta 改不了；在它的设置文件里改")
	f.page.edit.Key = testKey
	tt.Frame()
	click(t, tt, "保存")
	f.until(t, tt, "保存", func() bool { return f.page.edit == nil && f.page.Accounts.Working() == "" })
	if p := f.host.lastParams(); p["key"] != "" || p["create"] != false || p["name"] != "cpa" {
		t.Fatalf("交的参数：%v", p)
	}
}
