package ui

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// reportedModels 是假核心在 conv.prepare 后推来的模型清单（Claude Code 2.1.329 报的样子，同 chat 包测试里的）。
const reportedModels = `[{"value":"default","displayName":"Default (recommended)","resolvedModel":"claude-opus-5-5"},` +
	`{"value":"opus","displayName":"Opus 5.5","resolvedModel":"claude-opus-5-5"},` +
	`{"value":"sonnet","displayName":"Sonnet 5.5","resolvedModel":"claude-sonnet-5-5"},` +
	`{"value":"claude-opus-4-6","displayName":"Opus 4.6","resolvedModel":"claude-opus-4-6"},` +
	`{"value":"claude-sonnet-4-6","displayName":"Sonnet 4.6","resolvedModel":"claude-sonnet-4-6"}]`

// withPrepare 让假核心答 conv.prepare：推来模型清单；别的照设置页的假核心答。返回发来过的方法（按先后）。
func withPrepare(f *settingsFixture) *[]string {
	var methods []string
	f.devices.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			methods = append(methods, c.Method)
			switch c.Method {
			case "conv.open":
				for _, line := range hubtest.Recorded("open", c.Conv()) {
					l.Push(line)
				}
				return nil, nil
			case "conv.prepare":
				l.Push(`{"type":"conv.state","conv":"` + c.Conv() + `","process":true,"models":` + reportedModels + `}`)
				return nil, nil
			case "conv.end", "conv.close":
				return nil, nil
			}
			return f.answer(c)
		})
	}
	return &methods
}

// 没开对话时打开设备页：去那台上开一个不发消息的对话要模型清单，要到后结束进程、放开对话；默认模型的下拉换成真实清单，
// 旧版本收在「更多模型」里。再打开设备页不再去要。
func TestDefaultModelUsesTheDevicesModels(t *testing.T) {
	f := newSettingsFixture(t)
	methods := withPrepare(f)
	f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet"}`)
	f.withHosts(t, "box")
	f.tt.SetSize(1280, 1600)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.until(t, "要到清单、读到默认", func() bool { return f.shows("Sonnet 5.5") })
	convCalls := func() []string {
		var out []string
		for _, m := range *methods {
			if m == "conv.open" || m == "conv.prepare" || m == "conv.end" || m == "conv.close" {
				out = append(out, m)
			}
		}
		return out
	}
	if want := []string{"conv.open", "conv.prepare", "conv.end", "conv.close"}; !slices.Equal(convCalls(), want) {
		t.Fatalf("要清单发的：%q，要 %q", convCalls(), want)
	}
	f.notSee(t, "正在读取这台设备的模型清单。")

	f.press(t, "默认模型")
	f.see(t, "Claude Code 内置的默认", "Opus 5.5", "更多模型")
	f.notSee(t, "Default (recommended)", "Opus 4.6")
	f.click(t, "更多模型")
	f.see(t, "Opus 4.6", "Sonnet 4.6")
	f.click(t, "Opus 4.6")
	f.until(t, "存上", func() bool { return f.shows("Opus 4.6") })

	// 换到常规再回来：记着清单，不再去要
	f.click(t, "常规")
	f.clickButton(t, "box", 0)
	f.until(t, "读到默认", func() bool { return f.shows("Opus 4.6") })
	if n := len(convCalls()); n != 4 {
		t.Fatalf("又去要了：%q", convCalls())
	}
}

// 要不到（这里的假核心不认 conv.prepare）时照旧列常用的别名，「正在要」的说明去掉。
func TestDefaultModelWhenTheDeviceCannotReport(t *testing.T) {
	f := newSettingsFixture(t)
	f.withHosts(t, "box")
	f.tt.SetSize(1280, 1600)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.until(t, "读到新对话默认、没要到清单", func() bool {
		return f.shows("Claude Code 内置的默认") && !f.tt.HasText("正在读取这台设备的模型清单。")
	})
	f.press(t, "默认模型")
	f.see(t, "Opus", "Sonnet", "Haiku", "Fable")
	f.notSee(t, "更多模型")
}

func TestDefaultModelUsesActiveRelay(t *testing.T) {
	f := newSettingsFixture(t)
	methods := withPrepare(f)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		_, _ = fmt.Fprint(w, `{"data":[{"id":"custom-a","display_name":"Custom A"},{"id":"custom-b","display_name":"Custom B"}]}`)
	}))
	t.Cleanup(srv.Close)
	f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet"}`)
	f.serve.Write(t, ".local/state/claude-switch/current", "gw\n")
	f.serve.Write(t, ".config/claude-switch/targets/gw.json", fmt.Sprintf(`{"env":{"ANTHROPIC_BASE_URL":%q,"ANTHROPIC_API_KEY":"fixture"},"model":"custom-a"}`, srv.URL))
	f.withHosts(t, "box")
	f.tt.SetSize(1280, 1600)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.until(t, "读到中转列表和默认模型", func() bool { return f.shows("Custom A") })
	f.press(t, "默认模型")
	f.see(t, "Custom B")
	f.notSee(t, "Opus 5.5", "Sonnet", "Claude Code 内置的默认")
	f.click(t, "Custom B")
	f.until(t, "中转默认模型已更新", func() bool { return f.shows("Custom B") })
	if slices.Contains(*methods, "conv.prepare") {
		t.Fatal("在用中转时不该启动 Claude Code 探清单")
	}
	f.click(t, "默认思考强度")
	f.see(t, "Custom A", "Custom B")
	f.serve.Write(t, ".local/state/claude-switch/current", "claude\n")
	f.click(t, "常规")
	f.clickButton(t, "box", 0)
	f.until(t, "换回账号后仍用原来的用户级默认", func() bool { return f.shows("Sonnet 5.5") })
}

func TestLateCatalogCannotReplaceCurrentSource(t *testing.T) {
	f := newFixture(t)
	f.devices.OnChat = func(l *hubtest.ChatLink) { l.Connect() }
	hosts := f.withHosts(t, "box", "lab")
	x, y := hosts[0], hosts[1]
	// 两台设备的回包按相反顺序应用；box 另外还保留切换和保存前的回包。
	var apply []func()
	f.app.post = func(fn func()) { apply = append(apply, fn) }
	r := f.hub.Remote(x.ID)
	catalog := `{"relay":"before"}`
	f.devices.Chat(x.ID).OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		switch c.Method {
		case "config.models.read":
			return json.RawMessage(catalog), nil
		case "accounts.list":
			return json.RawMessage(`{}`), nil
		case "accounts.switch":
			catalog = `{"relay":"after"}`
		case "accounts.relay_save":
			catalog = `{"relay":"after","models":[{"id":"new-id"}]}`
		}
		return nil, nil
	})
	otherRemote := f.hub.Remote(y.ID)
	f.devices.Chat(y.ID).OnCall(func(hubtest.Call) (json.RawMessage, error) {
		return json.RawMessage(`{"relay":"other"}`), nil
	})
	old := f.app.modelSource(x.ID, r, false)
	other := f.app.modelSource(y.ID, otherRemote, false)
	r.Accounts.SwitchRelay(coreapi.Relay{Name: "after"})
	switched := f.app.modelSource(x.ID, r, false)
	r.Accounts.SaveRelay(accounts.EditRelayDraft(coreapi.Relay{Name: "after", BaseURL: "https://example.com"}), nil)
	current := f.app.modelSource(x.ID, r, false)
	if len(apply) != 4 {
		t.Fatalf("切换和编辑应该各启动一次新请求，两台设备共四个回包，拿到 %d 个", len(apply))
	}
	for i := len(apply) - 1; i >= 0; i-- {
		apply[i]()
	}
	if old.catalog.Relay != "before" || switched.catalog.Relay != "after" {
		t.Fatal("测试没有收到新旧中转的回包")
	}
	if got := f.app.modelSource(y.ID, otherRemote, false); got != other || got.catalog.Relay != "other" {
		t.Fatal("另一台设备的中转回包被覆盖")
	}
	if got := f.app.modelSource(x.ID, r, false); got != current || len(got.catalog.Models) != 1 || got.catalog.Models[0].ID != "new-id" {
		t.Fatal("过时的中转回包污染了当前模型清单")
	}
}

func TestFailedModelCatalogRetriesOnReopen(t *testing.T) {
	f := newFixture(t)
	f.devices.OnChat = func(l *hubtest.ChatLink) { l.Connect() }
	x := f.withHosts(t, "box")[0]
	r := f.hub.Remote(x.ID)
	calls := 0
	f.devices.Chat(x.ID).OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		if c.Method != "config.models.read" {
			t.Fatalf("意外调用：%s", c.Method)
		}
		calls++
		if calls == 1 {
			return nil, errors.New("temporary")
		}
		return json.RawMessage(`{"relay":"gw"}`), nil
	})
	if s := f.app.modelSource(x.ID, r, false); s.ready {
		t.Fatal("RPC 失败时不能当成 Claude Code 订阅")
	}
	if s := f.app.modelSource(x.ID, r, false); s.ready || calls != 1 {
		t.Fatal("不应在每一帧重试")
	}
	if s := f.app.modelSource(x.ID, r, true); !s.ready || s.catalog.Relay != "gw" || calls != 2 {
		t.Fatal("重开时应重试读取模型来源")
	}
}

func TestRelayModelsExpire(t *testing.T) {
	f := newFixture(t)
	f.devices.OnChat = func(l *hubtest.ChatLink) { l.Connect() }
	x := f.withHosts(t, "box")[0]
	r := f.hub.Remote(x.ID)
	clock := now
	f.app.now = func() time.Time { return clock }
	calls := 0
	f.devices.Chat(x.ID).OnCall(func(hubtest.Call) (json.RawMessage, error) {
		calls++
		return json.RawMessage(fmt.Sprintf(`{"relay":"gw","models":[{"id":"model-%d"}]}`, calls)), nil
	})
	first := f.app.modelSource(x.ID, r, false)
	clock = now.Add(modelsTTL - time.Nanosecond)
	if got := f.app.modelSource(x.ID, r, false); got != first {
		t.Fatal("还没过期不应重查")
	}
	clock = now.Add(modelsTTL)
	second := f.app.modelSource(x.ID, r, false)
	if calls != 2 || len(second.catalog.Models) != 1 || second.catalog.Models[0].ID != "model-2" {
		t.Fatal("过期后应更新仍在用的中转清单")
	}
}

func TestComposerUsesRelayModelIDs(t *testing.T) {
	f := newChatFixture(t)
	f.answers["config.models.read"] = `{"relay":"gw","model":"relay-a","models":[{"id":"relay-a","displayName":"Relay A"},{"id":"relay-b","displayName":"Relay B"}]}`
	f.newChat(t)
	f.push(`"models":` + reportedModels)
	f.click(t, "默认（Relay A）")
	f.see(t, "Relay A", "Relay B")
	f.notSee(t, "Opus 5.5", "Sonnet 5.5", "更多模型")
	f.clickMenuItem(t, "Relay B")
	if got := f.sent("conv.model"); !slices.Equal(got, []string{`{"conv":"w1","value":"relay-b"}`}) {
		t.Fatalf("中转模型应该按 ID 选择：%q", got)
	}
}

// 输入栏的模型菜单也分两层（同设置里的默认模型）：上层是各系列最新的，旧版本在「更多模型」里；选了旧版本，那一项右边写着它。
func TestComposerModelMenuSplitsOlder(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	// 空白对话停留到模型目录重读后，启动 Claude Code 仍能收下它报来的清单。
	f.app.now = func() time.Time { return now.Add(modelsTTL) }
	f.push(`"process":true,"models":` + reportedModels)
	if calls := f.sent("config.models.read"); len(calls) != 1 {
		t.Fatalf("模型目录过期后未重读：%q", calls)
	}
	f.click(t, "默认")
	f.see(t, "Opus 5.5", "Sonnet 5.5", "更多模型")
	f.notSee(t, "Opus 4.6")
	f.click(t, "更多模型")
	f.clickMenuItem(t, "Opus 4.6")
	got := f.link().Took()
	if len(got) != 1 || got[0].Method != "conv.model" || got[0].JSON() != `{"conv":"w1","value":"claude-opus-4-6"}` {
		t.Fatalf("选旧版本：%+v", got)
	}
	f.push(`"model":"claude-opus-4-6"`)
	f.click(t, "Opus 4.6") // 按钮上换成了它；点开菜单
	texts := f.tt.Texts()
	n := slices.Index(texts, "更多模型")
	for n >= 0 && n+1 < len(texts) && texts[n+1] == "更多模型" { // 读屏名和字各一条
		n++
	}
	if n < 0 || n+1 >= len(texts) || texts[n+1] != "Opus 4.6" {
		t.Fatalf("更多模型旁边没写选中的旧版本：%q", texts)
	}
}
