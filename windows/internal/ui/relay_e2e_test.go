package ui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"

	"github.com/egoist/mygo/ui"
)

// 中转的整条链路：账号页经 SSH 起的真 serve 和核心（临时家目录，claude-switch 按内容哈希装上去）添加中转——填名称、地址、
// 密钥（没有协议或鉴权方式可选），「获取模型列表」（核心向本机的假中转要 /v1/models，带着密钥）、点一个模型、保存；再点那一行
// 换过去：核心报在用中转，账号页和设置侧栏都不再有订阅额度；在用的中转编辑页里删不了。密钥是假的。
func TestRelayThroughServe(t *testing.T) {
	const key = "sk-test-e2e-relay-0001"
	var keyed atomic.Bool
	relay := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		// 核心两个头都带（不让选鉴权方式）
		if r.Header.Get("X-Api-Key") != key || r.Header.Get("Authorization") != "Bearer "+key {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		keyed.Store(true)
		_ = json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"id": "claude-e2e-1", "display_name": "E2E Model"}}})
	}))
	defer relay.Close()

	f := newCoreFixture(t, 1800)
	// 装过 Claude Code 的主机都有 ~/.claude；没有它时 claude-switch 换不过去（建不了 ~/.claude/.oauth_refresh.lock）
	if err := os.MkdirAll(filepath.Join(f.server.Home, ".claude"), 0o755); err != nil {
		t.Fatal(err)
	}
	f.click(t, "设置")
	r := f.hub.Remote(f.host.ID)
	f.until(t, "读到账号", func() bool { _, ok := r.Accounts.State(); return ok || r.Accounts.Problem() != "" })
	f.app.device = f.host.ID
	f.app.open(pageDevice)
	f.app.openAccounts()
	f.until(t, "账号页", func() bool { return f.shows("添加中转") && !r.Quota.Refreshing() })

	f.click(t, "添加中转")
	f.until(t, "编辑页", func() bool { return f.shows("获取模型列表") })
	f.tt.Type("e2e") // 名称框自动带焦点
	f.press(t, "地址")
	f.tt.Type(relay.URL)
	f.press(t, "密钥")
	f.tt.Type(key)
	f.tt.Frame()
	f.notSee(t, key) // 遮着
	if f.shows("协议") {
		t.Fatal("不该再有协议选择")
	}
	f.click(t, "获取模型列表")
	f.until(t, "拉到模型", func() bool { return f.shows("E2E Model") || f.showsPrefix("没取到模型列表") })
	if !keyed.Load() {
		t.Fatalf("没带密钥拉到模型：%q", f.tt.Texts())
	}
	f.click(t, "E2E Model")
	f.click(t, "保存")
	f.until(t, "保存好", func() bool { return f.shows("e2e") && f.shows("添加中转") && r.Accounts.Working() == "" })
	if p := r.Accounts.Problem(); p != "" {
		t.Fatalf("没保存成：%s", p)
	}
	f.see(t, relay.URL+" · 默认模型 claude-e2e-1")
	target := filepath.Join(f.server.Home, ".config", "claude-switch", "targets", "e2e.json")
	if _, err := os.Stat(target); err != nil {
		t.Fatalf("没写设置目标：%v", err)
	}

	f.click(t, "e2e")
	f.until(t, "问换不换", func() bool { return f.shows("切换到中转 e2e？") })
	f.clickButton(t, "切换", 0)
	f.until(t, "换好", func() bool {
		st, ok := r.Accounts.State()
		return ok && r.Accounts.Working() == "" && st.CurrentRelay() != nil && !r.Quota.Refreshing()
	})
	if p := r.Accounts.Problem(); p != "" {
		t.Fatalf("没换成：%s", p)
	}
	r.Quota.Refresh(true) // 核心的 quota.get 报在用中转
	f.until(t, "读完额度", func() bool { return !r.Quota.Refreshing() })
	if r.Quota.Relay() != "e2e" || r.Quota.Shown() {
		t.Fatalf("在用中转：relay %q，显示额度 %v", r.Quota.Relay(), r.Quota.Shown())
	}
	f.see(t, "当前", "中转 e2e") // 中转那一行、设置侧栏那台的小字
	if f.shows("订阅额度") || f.shows("刷新") {
		t.Fatalf("在用中转还显示额度：%q", f.tt.Texts())
	}

	f.clickButton(t, "编辑", 0)
	f.until(t, "编辑页", func() bool { return f.shows("获取模型列表") })
	f.see(t, "正在用这个中转：改了以后，新起的对话进程才用上。要删除先换到别的账号或中转。")
	if a, ok := f.tt.AccessByRole(ui.RoleButton, "删除"); ok && !a.Disabled {
		t.Fatal("在用的中转不该能删")
	}
}
