package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
)

// addDevice 加一台假设备，核心的请求交给 answer。
func addDevice(t *testing.T, c *Core, d *hubtest.Devices, answer func(hubtest.Call) (json.RawMessage, error)) string {
	t.Helper()
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(answer)
	}
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	return call[State](t, c, "state", nil).Devices[0].ID
}

// 自动压缩、压缩时机、自动标题存在客户端本地，各台各设；没设过时是默认值，改了读回来是新的。
func TestClaudeLocalSettings(t *testing.T) {
	c, d := newCore(t)
	id := addDevice(t, c, d, func(hubtest.Call) (json.RawMessage, error) { return nil, nil })
	got := call[claudeLocal](t, c, "claudecfg.local", map[string]string{"backend": id})
	if !got.AutoCompact || !got.AutoTitle || got.CompactPercent != 0 || len(got.CompactPercents) == 0 || got.Cwd == "" {
		t.Fatalf("默认 %+v", got)
	}
	got = call[claudeLocal](t, c, "claudecfg.setlocal", map[string]any{"backend": id, "autoTitle": false, "compactPercent": 70})
	if got.AutoTitle || got.CompactPercent != 70 || !got.AutoCompact {
		t.Fatalf("改后 %+v", got)
	}
	if again := call[claudeLocal](t, c, "claudecfg.local", map[string]string{"backend": id}); again.AutoTitle || again.CompactPercent != 70 {
		t.Fatalf("读回 %+v", again)
	}
	raw, _ := json.Marshal(map[string]any{"backend": "gone"})
	if out := string(c.Call("claudecfg.local", raw)); !strings.Contains(out, `"error"`) {
		t.Fatalf("没有的设备答 %s", out)
	}
}

// 有生效中转时是中转报的具体 ID；没有时要 Claude Code 的清单，假连接起不来时用常用的别名。
func TestClaudeModelChoices(t *testing.T) {
	c, d := newCore(t)
	relay := true
	id := addDevice(t, c, d, func(call hubtest.Call) (json.RawMessage, error) {
		if call.Method == "config.models.read" && relay {
			return json.RawMessage(`{"relay":"r","model":"m1","models":[{"id":"m1","displayName":"M One"}]}`), nil
		}
		if call.Method == "config.models.read" {
			return json.RawMessage(`{"relay":"","model":"","models":[]}`), nil
		}
		return nil, nil
	})
	got := call[modelChoices](t, c, "claudecfg.models", map[string]string{"backend": id})
	if got.Source != "relay" || len(got.Models) != 1 || got.Models[0].Label != "M One" || got.Models[0].ID != "m1" {
		t.Fatalf("中转 %+v", got)
	}
	relay = false
	got = call[modelChoices](t, c, "claudecfg.models", map[string]string{"backend": id})
	if got.Source != "harness" || len(got.Models) == 0 || got.Models[0].Value != "fable" {
		t.Fatalf("Claude Code %+v", got)
	}
}

// claudecfg.slow 只转标「慢」的几个方法，结果原样交回。
func TestClaudeSlowForward(t *testing.T) {
	c, d := newCore(t)
	id := addDevice(t, c, d, func(call hubtest.Call) (json.RawMessage, error) {
		if call.Method == "config.mcp.tools" {
			return json.RawMessage(`[{"name":"` + call.Str("name") + `","description":""}]`), nil
		}
		return nil, nil
	})
	got := call[[]map[string]string](t, c, "claudecfg.slow", map[string]any{"backend": id, "method": "config.mcp.tools",
		"params": map[string]string{"name": "gh", "scope": "user"}})
	if len(got) != 1 || got[0]["name"] != "gh" {
		t.Fatalf("工具 %v", got)
	}
	raw, _ := json.Marshal(map[string]any{"backend": id, "method": "config.memory.read"})
	if out := string(c.Call("claudecfg.slow", raw)); !strings.Contains(out, `"error"`) {
		t.Fatalf("不慢的方法也转了：%s", out)
	}
}
