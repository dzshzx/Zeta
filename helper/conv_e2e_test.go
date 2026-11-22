package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"slices"
	"strings"
	"testing"
	"time"
)

// 整条链路：客户端经 serve 发 conv.*，核心起 zeta_host.sh，托管脚本起按剧本回话的假 Claude（tools/fake-claude），
// 回答一路推回客户端。用测试版的目录名（zeta-dev）：只有它的托管脚本认假 Claude 的剧本。
func TestConversationThroughCore(t *testing.T) {
	for _, tool := range []string{"jq", "bash"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("假 Claude 要 %s：%v", tool, err)
		}
	}
	e0, p := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	if pid := corePid(p); pid != 0 {
		t.Fatalf("开始前就有核心：%d", pid)
	}
	fakeClaudeIn(t, e, `{"title":"假 Claude 起的标题","steps":[{"thinking":"想一想"},{"text":"你好，我是按剧本回话的假 Claude","chunk":4,"delay":5}]}`)

	c := startServe(t, e, testIdle)
	view := &convView{}
	c.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `,"options":{"auto_title":true}}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "zeta_reply" && m["ok"] == true })
	c.send(`{"id":2,"method":"conv.send","conv":"a","text":"你好，介绍一下自己"}`)
	finished := view.until(t, c, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" })
	if finished["outcome"] != "done" || finished["conv"] != "a" {
		t.Fatalf("一轮的结局：%v", finished)
	}
	view.until(t, c, func(obj) bool { return view.state["title"] == "假 Claude 起的标题" })
	got := view.texts()
	want := []string{"user:你好，介绍一下自己", "work:", "answer:你好，我是按剧本回话的假 Claude"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("拼出来的条目：%v", got)
	}
	if view.state["busy"] != false || view.state["process"] != true || view.state["session"] == "" {
		t.Fatalf("状态：busy %v process %v session %v", view.state["busy"], view.state["process"], view.state["session"])
	}

	// 结束进程：托管目录随之清掉
	c.send(`{"id":3,"method":"conv.end","conv":"a"}`)
	view.until(t, c, func(obj) bool { return view.state["process"] == false })
	deadline := time.Now().Add(15 * time.Second)
	for {
		entries, _ := os.ReadDir(e.hosts)
		live := 0
		for _, d := range entries {
			if !strings.HasPrefix(d.Name(), ".") {
				live++
			}
		}
		if live == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("结束后托管目录还在：%v", entries)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// fakeClaudeIn 把假 Claude 和剧本放到测试版的目录里（tools/fake-claude），返回它的目录；测试版的托管脚本看到剧本就起它。
func fakeClaudeIn(t *testing.T, e *env, scenario string) string {
	t.Helper()
	fake := e.home + "/.config/zeta-dev/fake-claude"
	script, err := os.ReadFile("../tools/fake-claude/fake-claude.sh")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(fake+"/marks", 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(fake+"/fake-claude.sh", script, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(fake+"/scenario.jsonl", []byte(scenario+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return fake
}

func quote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// convView 扮客户端：把 conv.state、conv.items 的整份和增量拼起来。
type convView struct {
	state map[string]any
	items []any
}

func (v *convView) apply(m obj) {
	switch m["type"] {
	case "conv.state":
		if m["full"] == true || v.state == nil {
			v.state = map[string]any{}
		}
		for k, x := range m {
			v.state[k] = x
		}
	case "conv.items":
		items, _ := m["items"].([]any)
		if m["full"] == true {
			v.items = items
			return
		}
		at, _ := m["at"].(json.Number).Int64()
		remove, _ := m["remove"].(json.Number).Int64()
		v.items = append(append(append([]any{}, v.items[:at]...), items...), v.items[at+remove:]...)
	}
}

// until 一行行读 serve 的输出并拼起来，直到 done 对某一行为真，返回那一行；限时 30 秒。
func (v *convView) until(t *testing.T, c *serveClient, done func(obj) bool) obj {
	t.Helper()
	deadline := time.After(30 * time.Second)
	for {
		select {
		case m, ok := <-c.got:
			if !ok {
				t.Fatal("serve 关了输出")
			}
			if m["type"] == "zeta_reply" && m["ok"] == false {
				t.Fatalf("请求出错：%v", m)
			}
			v.apply(m)
			if done(m) {
				return m
			}
		case <-deadline:
			t.Fatalf("30 秒内没等到；现在的条目 %v，状态 %v", v.texts(), v.state)
		}
	}
}

func (v *convView) texts() []string {
	var out []string
	for _, it := range v.items {
		m := it.(map[string]any)
		text, _ := m["text"].(string)
		out = append(out, m["kind"].(string)+":"+text)
	}
	return out
}

// 交班时正在回答的对话不断：旧核心放开托管进程，新核心起来时接回它，这一轮在新核心里答完。
func TestHandoverKeepsATurnRunning(t *testing.T) {
	e0, p := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fake := fakeClaudeIn(t, e, `{"steps":[{"text":"前半","chunk":8,"delay":1},{"wait":"go","ms":20000},{"text":"后半","chunk":8,"delay":1}]}`)

	useBuild(t, "5")
	a := startServe(t, e, testIdle)
	view := &convView{}
	a.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `}`)
	view.until(t, a, func(m obj) bool { return m["type"] == "zeta_reply" })
	a.send(`{"id":2,"method":"conv.send","conv":"a","text":"讲一段"}`)
	view.until(t, a, func(obj) bool { return slices.Contains(view.texts(), "answer:前半") })
	session, _ := view.state["session"].(string)
	old := corePid(p)

	// 新版本的 serve 连上来：旧核心交班，正在回答的那个由新核心接回
	useBuild(t, "9")
	b := startServe(t, e, testIdle)
	activity := &convView{}
	b.send(`{"id":1,"method":"conv.activity","watch":true}`)
	answering := func(state string) func(obj) bool {
		return func(m obj) bool {
			s, _ := m["sessions"].(map[string]any)
			x, _ := s[session].(map[string]any)
			return m["type"] == "conv.activity" && x["activity"] == state
		}
	}
	activity.until(t, b, answering("answering"))
	a.exited()
	if pid := corePid(p); pid == old || pid == 0 {
		t.Fatalf("核心没换：旧 %d，现在 %d", old, pid)
	}

	// 放行剧本：这一轮在新核心里答完
	if err := os.WriteFile(fake+"/marks/go", nil, 0o644); err != nil {
		t.Fatal(err)
	}
	activity.until(t, b, answering("idle"))
	b.send(`{"id":2,"method":"conv.open","conv":"x","session":` + quote(session) + `,"cwd":` + quote(e.home) + `}`)
	reopened := &convView{}
	reopened.until(t, b, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("2") })
	if !slices.ContainsFunc(reopened.texts(), func(s string) bool { return strings.Contains(s, "后半") }) || reopened.state["process"] != true {
		t.Fatalf("接回以后的条目 %v，状态 %v", reopened.texts(), reopened.state)
	}
}

// conv.prepare 经核心只起进程、不发消息；之后那一轮里 Claude Code 报的 rate_limit_event 由核心存进额度，quota.get 直接读到。
func TestPrepareAndRateLimitThroughCore(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fakeLogin(t, e.home, "uuid-a", "a@example.com")
	fakeClaudeIn(t, e, `{"steps":[{"emit":{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning",`+
		`"unifiedWindows":{"five_hour":{"utilization":0.42,"resetsAt":4102444800,"status":"allowed"}}}}},{"text":"好"}]}`)

	c := startServe(t, e, testIdle)
	view := &convView{}
	c.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "zeta_reply" })
	c.send(`{"id":2,"method":"conv.prepare","conv":"a"}`)
	view.until(t, c, func(obj) bool { return view.state["process"] == true })
	if view.state["busy"] != false || len(view.items) != 0 {
		t.Fatalf("预先起进程时发了消息：状态 %v，条目 %v", view.state, view.texts())
	}

	c.send(`{"id":3,"method":"conv.send","conv":"a","text":"你好"}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" })
	c.send(`{"id":4,"method":"quota.get"}`)
	reply := view.until(t, c, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("4") })
	snap, _ := reply["result"].(map[string]any)["snapshot"].(map[string]any)
	windows, _ := snap["windows"].([]any)
	if len(windows) != 1 {
		t.Fatalf("存下的额度：%v", reply)
	}
	if w := windows[0].(map[string]any); w["label"] != "5 小时" || w["used"] != json.Number("0.42") {
		t.Fatalf("5 小时窗口：%v", w)
	}
}
