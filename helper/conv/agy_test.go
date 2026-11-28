package conv

import (
	"encoding/json"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

type fakeAgyQuota struct {
	subscribed bool
	usages     []string
}

func (q *fakeAgyQuota) RateLimit(json.RawMessage) {}
func (q *fakeAgyQuota) ClaimQuery() bool          { return false }
func (q *fakeAgyQuota) Subscribed() bool          { return q.subscribed }
func (q *fakeAgyQuota) Usage(raw json.RawMessage) { q.usages = append(q.usages, string(raw)) }

func newAgyHarness(t *testing.T, extra func(*Deps)) *harness {
	t.Helper()
	h := &harness{t: t, clients: map[int]*fakeClient{}, desks: make(chan string, 16)}
	deps := Deps{
		Start: func(o StartOptions, ev Events) (Link, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			l := &fakeLink{key: o.Key, events: ev}
			h.links = append(h.links, l)
			h.starts = append(h.starts, o)
			return l, nil
		},
		History: func(args ...string) ([]byte, error) {
			return []byte(`{"type":"zeta_history","earlier":0}` + "\n"), nil
		},
	}
	if extra != nil {
		extra(&deps)
	}
	h.m = NewManager(deps, func(client int, msg any) {
		b, _ := json.Marshal(msg)
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		h.mu.Lock()
		defer h.mu.Unlock()
		h.client(client).receive(t, m)
	})
	t.Cleanup(func() {
		h.m.mu.Lock()
		all := make([]*Conversation, 0, len(h.m.all))
		for c := range h.m.all {
			all = append(all, c)
		}
		h.m.mu.Unlock()
		for _, c := range all {
			c.close()
			c.bg.Wait()
		}
	})
	return h
}

func callConvErr(t *testing.T, h *harness, method string, params map[string]any) error {
	t.Helper()
	params["method"] = method
	b, _ := json.Marshal(params)
	_, err := h.m.Request(1, method, b)
	return err
}

func TestAgyHarnessSwitchBeforeStart(t *testing.T) {
	cmds := NewCommandStore(filepath.Join(t.TempDir(), "commands.json"))
	cmds.RememberFor(sessionproto.HarnessClaude, "/w", json.RawMessage(`[{"name":"compact"}]`))
	cmds.RememberFor(sessionproto.HarnessAgy, "/w", json.RawMessage(`[{"name":"plan"}]`))
	h := newAgyHarness(t, func(d *Deps) {
		d.Commands = cmds
		d.DefaultModeFor = func(harness string) string {
			if harness == sessionproto.HarnessAgy {
				return "auto"
			}
			return "default"
		}
	})
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	if h.state(1, "harness") != "claude" || !strings.Contains(stringify(h.state(1, "mode")), "default") {
		t.Fatalf("初始状态不对：harness=%v mode=%v", h.state(1, "harness"), h.state(1, "mode"))
	}
	if !strings.Contains(stringify(h.state(1, "commands")), "compact") {
		t.Fatalf("初始命令桶不对：%v", h.state(1, "commands"))
	}

	h.call(1, "conv.harness", map[string]any{"conv": "a", "value": "agy"})
	if h.state(1, "harness") != "agy" || !strings.Contains(stringify(h.state(1, "mode")), "auto") || h.state(1, "can_rewind") != false {
		t.Fatalf("切到 agy 后状态不对：harness=%v mode=%v rewind=%v",
			h.state(1, "harness"), h.state(1, "mode"), h.state(1, "can_rewind"))
	}
	if got := stringify(h.state(1, "commands")); !strings.Contains(got, "plan") || strings.Contains(got, "compact") {
		t.Fatalf("切到 agy 后命令桶未隔离：%s", got)
	}

	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "开始"})
	if err := callConvErr(t, h, "conv.harness", map[string]any{"conv": "a", "value": "claude"}); err == nil {
		t.Fatal("对话开始后不应允许再切换引擎")
	}
}

func stringify(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func TestAgyLaunchArgsAndNoClaudeHints(t *testing.T) {
	h := newAgyHarness(t, nil)
	h.call(1, "conv.open", map[string]any{
		"conv":    "a",
		"cwd":     "/w",
		"title":   "测试标题",
		"harness": "agy",
		"options": map[string]any{"system_prompt": "遵守规范", "phone": true},
	})
	h.call(1, "conv.preset", map[string]any{"conv": "a", "model": "gemini-4-pro", "effort": "high", "hint": "额外提示"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "你好"})

	if len(h.starts) != 1 {
		t.Fatalf("启动次数不对：%d", len(h.starts))
	}
	st := h.starts[0]
	if st.Harness != "agy" {
		t.Fatalf("StartOptions.Harness = %q", st.Harness)
	}
	if _, has := st.Env["CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING"]; has {
		t.Fatalf("agy 不应注入 Claude 文件快照环境变量：%v", st.Env)
	}
	for _, want := range []string{"--cwd", "/w", "--model", "gemini-4-pro", "--effort", "high", "--title", "测试标题", "--phone-mcp", "--append-system-prompt"} {
		if !slices.Contains(st.Args, want) {
			t.Fatalf("缺少启动参数 %q：%v", want, st.Args)
		}
	}
	joined := strings.Join(st.Args, "\n")
	if strings.Contains(joined, "downscaled copy") || !strings.Contains(joined, "遵守规范") || !strings.Contains(joined, "额外提示") {
		t.Fatalf("系统提示拼接不对：%s", joined)
	}
}

func TestAgyUpdateStepsAndRewindDisabled(t *testing.T) {
	h := newAgyHarness(t, nil)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "harness": "agy"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "执行计划"})
	c, l := h.conv(1, "a"), h.link(0)
	const sid = "11111111-2222-3333-4444-555555555555"
	h.feed(c, l,
		initLine(sid),
		`{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"tc-steps","name":"update_steps","input":{"steps":[{"title":"第一步","status":"done"},{"title":"第二步","status":"in_progress"},{"title":"第三步","status":"pending"}]}}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tc-steps","content":"ok"}]}}`,
	)
	h.feed(c, l, answerLines("m2", "全部就绪")...)
	h.feed(c, l, resultOK)

	todos := stringify(h.state(1, "todos"))
	for _, want := range []string{`"第一步"`, `"completed"`, `"第二步"`, `"in_progress"`, `"第三步"`, `"pending"`} {
		if !strings.Contains(todos, want) {
			t.Fatalf("update_steps 未还原成清单（缺 %s）：%s", want, todos)
		}
	}
	u := h.item(1, "user", "执行计划")
	a := h.item(1, "answer", "全部就绪")
	if u["rewindable"] == true || a["forkable"] == true {
		t.Fatalf("agy 条目不应标记可撤回或可分支：u=%v a=%v", u, a)
	}
	uid, _ := u["id"].(string)
	aid, _ := a["id"].(string)
	for _, call := range []struct {
		method string
		params map[string]any
	}{
		{"conv.rewrite", map[string]any{"conv": "a", "target": uid, "text": "改"}},
		{"conv.drop", map[string]any{"conv": "a", "target": uid}},
		{"conv.fork", map[string]any{"conv": "a", "as": "b", "last": aid}},
	} {
		if err := callConvErr(t, h, call.method, call.params); err == nil {
			t.Fatalf("agy 对话调用 %s 应当返回不支持错误", call.method)
		}
	}
}

func TestAgyAutoDetectSessionAndDirectQuota(t *testing.T) {
	const sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	q := &fakeAgyQuota{subscribed: true}
	queried := 0
	h := newAgyHarness(t, func(d *Deps) {
		d.SessionHarness = func(s string) string {
			if s == sid {
				return sessionproto.HarnessAgy
			}
			return sessionproto.HarnessClaude
		}
		d.QuotaFor = func(harness string) Quota {
			if harness == sessionproto.HarnessAgy {
				return q
			}
			return nil
		}
		d.QueryAgyQuota = func() (json.RawMessage, error) {
			queried++
			return json.RawMessage(`{"rate_limits_available":true,"windows":[]}`), nil
		}
	})
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": sid, "cwd": "/w"})
	if h.state(1, "harness") != "agy" {
		t.Fatalf("未自动识别已有 agy 会话：%v", h.state(1, "harness"))
	}
	h.call(1, "conv.quota", map[string]any{"conv": "a"})
	if queried != 1 || len(q.usages) != 1 {
		t.Fatalf("无进程时 conv.quota 未走直接查询：queried=%d usages=%v", queried, q.usages)
	}
}
