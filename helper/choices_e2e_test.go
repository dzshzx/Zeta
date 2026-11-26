package main

import (
	"encoding/json"
	"path/filepath"
	"slices"
	"testing"
)

// hostedArgs 是对话 view 背后托管的那个 Claude Code（这里是假 Claude）进程的命令行参数：按 conv.state 的 host_key
// 找托管目录，读它记的进程号，再读 /proc 里的命令行。
func hostedArgs(t *testing.T, e *env, view *convView) []string {
	t.Helper()
	key, _ := view.state["host_key"].(string)
	if key == "" {
		t.Fatalf("状态里没有 host_key：%v", view.state)
	}
	pid, ok := readPid(filepath.Join(e.hosts, key, "pid"))
	if !ok {
		t.Fatalf("托管目录 %s 里没有进程号", key)
	}
	argv, ok := procArgv(pid)
	if !ok {
		t.Fatalf("读不到托管进程 %d 的命令行", pid)
	}
	args := make([]string, len(argv))
	for i, a := range argv {
		args[i] = string(a)
	}
	return args
}

func wantModel(t *testing.T, args []string, model string) {
	t.Helper()
	if i := slices.Index(args, "--model"); i < 0 || i+1 >= len(args) || args[i+1] != model {
		t.Fatalf("起进程的参数里没有 --model %s：%v", model, args)
	}
}

// 客户端等 conv.model 的应答超时、放弃了（手机端 60 秒）：请求已经写出去，核心照样执行。同一条连接上的 conv.* 一个一个处理，
// 所以它总在客户端接着发的 conv.send 之前生效，第一条消息起的进程带着选的模型。
func TestModelChoiceNotAwaitedStillPrecedesTheSend(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fakeClaudeIn(t, e, `{"steps":[{"text":"好"}]}`)
	c := startServe(t, e, testIdle)
	view := &convView{}
	c.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("1") })
	c.send(`{"id":2,"method":"conv.model","conv":"a","value":"sonnet"}`)
	c.send(`{"id":3,"method":"conv.send","conv":"a","text":"你好"}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" })
	wantModel(t, hostedArgs(t, e, view), "sonnet")
}

// 选了模型以后核心交班（Zeta 升级、换共享文件夹）：新核心里没有这个还没发过消息的对话。客户端照 CoreConversation 的做法重连：
// 按 key 重开答 conversation_gone，再按打开时的参数带上 choices 新开，第一条消息起的进程带着选的模型。
func TestModelChoiceSurvivesHandover(t *testing.T) {
	e0, p := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fakeClaudeIn(t, e, `{"steps":[{"text":"好"}]}`)

	useBuild(t, "5")
	a := startServe(t, e, testIdle)
	before := &convView{}
	a.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `}`)
	before.until(t, a, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("1") })
	a.send(`{"id":2,"method":"conv.model","conv":"a","value":"sonnet"}`)
	before.until(t, a, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("2") })
	key, _ := before.state["key"].(string)
	old := corePid(p)

	useBuild(t, "9")
	b := startServe(t, e, testIdle)
	view := &convView{}
	if r := b.call("conv.open", `"conv":"a","key":`+quote(key)+`,"cwd":`+quote(e.home)); r["code"] != "conversation_gone" {
		t.Fatalf("按旧 key 重开：%v", r)
	}
	a.exited()
	if pid := corePid(p); pid == old || pid == 0 {
		t.Fatalf("核心没换：旧 %d，现在 %d", old, pid)
	}
	b.send(`{"id":2,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `,"choices":{"model":"sonnet"}}`)
	view.until(t, b, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("2") })
	if view.state["model"] != "sonnet" {
		t.Fatalf("新开的对话的模型：%v", view.state["model"])
	}
	b.send(`{"id":3,"method":"conv.send","conv":"a","text":"你好"}`)
	view.until(t, b, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" })
	wantModel(t, hostedArgs(t, e, view), "sonnet")
}
