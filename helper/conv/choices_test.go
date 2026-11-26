package conv

import (
	"slices"
	"testing"
)

// argAfter 是参数 args 里 flag 后面的那一项；没有 flag 时为空。
func argAfter(args []string, flag string) string {
	if i := slices.Index(args, flag); i >= 0 && i+1 < len(args) {
		return args[i+1]
	}
	return ""
}

// 新对话里选的模型、思考强度和权限模式，核心收掉对话后由客户端在 conv.open 的 choices 里带回来：
// 新建的对话照它们定，发第一条消息时起的进程带上。
func TestOpenRestoresChoices(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w",
		"choices": map[string]any{"model": "sonnet", "effort": "low", "mode": "plan"}})
	c := h.conv(1, "a")
	h.settle(c)
	if h.state(1, "model") != "sonnet" || h.state(1, "effort") != "low" {
		t.Fatalf("模型 %v，思考强度 %v", h.state(1, "model"), h.state(1, "effort"))
	}
	if m := h.state(1, "mode").(map[string]any); m["user"] != "plan" {
		t.Fatalf("权限模式：%v", m)
	}
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
	h.settle(c)
	args := h.starts[0].Args
	if argAfter(args, "--model") != "sonnet" || argAfter(args, "--effort") != "low" || argAfter(args, "--permission-mode") != "plan" {
		t.Fatalf("起进程的参数：%v", args)
	}
}

// 选回默认：模型写 default，思考强度、权限模式写空串，都回到没指定。
func TestOpenRestoresDefaultChoices(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.model", map[string]any{"conv": "a", "value": "sonnet"})
	h.call(1, "conv.effort", map[string]any{"conv": "a", "value": "low"})
	h.call(1, "conv.mode", map[string]any{"conv": "a", "value": "plan"})
	key, _ := h.state(1, "key").(string)
	h.m.Gone(1)
	h.call(2, "conv.open", map[string]any{"conv": "b", "key": key, "cwd": "/w",
		"choices": map[string]any{"model": "default", "effort": "", "mode": ""}})
	c := h.conv(2, "b")
	h.settle(c)
	if h.state(2, "model") != nil || h.state(2, "effort") != nil {
		t.Fatalf("模型 %v，思考强度 %v", h.state(2, "model"), h.state(2, "effort"))
	}
	if m := h.state(2, "mode").(map[string]any); m["user"] != "" {
		t.Fatalf("权限模式：%v", m)
	}
	h.call(2, "conv.send", map[string]any{"conv": "b", "text": "一"})
	h.settle(c)
	if args := h.starts[0].Args; slices.Contains(args, "--model") || slices.Contains(args, "--effort") {
		t.Fatalf("起进程的参数：%v", args)
	}
}

// 核心里还在的对话按 key 重开时：和核心里的一样就什么都不发；不一样（选的那次没送到）照客户端的，进程已经起好时当场换。
func TestReopenAppliesOnlyChangedChoices(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "choices": map[string]any{"model": "sonnet"}})
	h.call(1, "conv.prepare", map[string]any{"conv": "a"})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	key, _ := h.state(1, "key").(string)
	h.m.Gone(1)
	h.call(2, "conv.open", map[string]any{"conv": "b", "key": key, "cwd": "/w", "choices": map[string]any{"model": "sonnet"}})
	h.settle(c)
	if got := l.lines("control_request/set_model"); len(got) != 0 {
		t.Fatalf("选择没变还发了 set_model：%v", got)
	}
	h.m.Gone(2)
	h.call(3, "conv.open", map[string]any{"conv": "c", "key": key, "cwd": "/w", "choices": map[string]any{"model": "opus"}})
	h.settle(c)
	got := l.lines("control_request/set_model")
	if len(got) != 1 || got[0]["request"].(map[string]any)["model"] != "opus" || h.state(3, "model") != "opus" {
		t.Fatalf("set_model %v，模型 %v", got, h.state(3, "model"))
	}
}

// 有了会话号就不看 choices：接着会话时模型由会话记录或这个对话里新选的定。
func TestChoicesIgnoredWithSession(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w",
		"choices": map[string]any{"model": "sonnet", "effort": "low"}})
	h.settle(h.conv(1, "a"))
	if h.state(1, "model") != nil || h.state(1, "effort") != nil || h.state(1, "model_from_session") != true {
		t.Fatalf("模型 %v，思考强度 %v", h.state(1, "model"), h.state(1, "effort"))
	}
}
