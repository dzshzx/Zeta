package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
)

// core 把 config.*、diag.*、system.* 原样转给设备的核心、结果原样交回；别的方法不转（对话、账号要经中枢）。
func TestCoreForwardsOnlyListedGroups(t *testing.T) {
	c, d := newCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			if call.Method == "config.memory.read" {
				return json.RawMessage(`{"text":"` + call.Str("scope") + `"}`), nil
			}
			return nil, nil
		})
	}
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	id := call[State](t, c, "state", nil).Devices[0].ID

	got := call[map[string]string](t, c, "core", map[string]any{"backend": id, "method": "config.memory.read",
		"params": map[string]string{"scope": "user"}})
	if got["text"] != "user" {
		t.Fatalf("转发的结果 %v", got)
	}
	raw, _ := json.Marshal(map[string]any{"backend": id, "method": "conv.send"})
	if out := string(c.Call("core", raw)); !strings.Contains(out, `"error"`) {
		t.Fatalf("conv.send 不该转发，答的是 %s", out)
	}
	raw, _ = json.Marshal(map[string]any{"backend": "gone", "method": "diag.list"})
	if out := string(c.Call("core", raw)); !strings.Contains(out, `"error"`) {
		t.Fatalf("没有的设备答的是 %s", out)
	}
}

// 登记过的方法名不能再登记一次。
func TestRegisterTwicePanics(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("重名没有 panic")
		}
	}()
	reading("state", func(*Core, none) (any, error) { return nil, nil })
}

// 慢的方法（连 MCP 服务器、跑 claude update）经 core 转发时也能用，结果原样交回。
func TestCoreForwardsSlowMethods(t *testing.T) {
	c, d := newCore(t)
	id := addAnswering(t, c, d, func(call hubtest.Call) (json.RawMessage, error) {
		if call.Method == "config.mcp.tools" {
			return json.RawMessage(`[{"name":"` + call.Str("name") + `"}]`), nil
		}
		return nil, nil
	})
	got := call[[]map[string]string](t, c, "core", map[string]any{"backend": id, "method": "config.mcp.tools",
		"params": map[string]string{"name": "gh"}})
	if len(got) != 1 || got[0]["name"] != "gh" {
		t.Fatalf("工具 %v", got)
	}
	if slowMethods["config.mcp.tools"] <= coreTimeout {
		t.Fatal("连 MCP 服务器的方法该比一般的等得久")
	}
}
