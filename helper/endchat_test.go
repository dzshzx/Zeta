package main

import (
	"encoding/json"
	"slices"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/conv"
)

// 结束对话（conv.end）时核心里的对话没接着进程，而本包托管、闲着的进程还在跑这个会话：照样结束它。
// 核心只在有人发消息或进程在干活时接着托管进程；对话没人拿着闲满 30 秒、核心换了（交班不接闲着的）以后，
// 再打开这个会话的对话 proc 是空的，进程要等那台主机的闲置超时（可设到一小时）才由看门狗收掉。
// 以前 conv.end 只结束对话接着的进程，这时什么也不做，列表里这个会话一直是运行中（手机上「结束对话」没反应）。
// 这里用交班造出这个样子：旧核心里答完一轮，新核心接班（闲着的不接回），在新核心里打开同一个会话再结束。
func TestEndChatStopsHostedProcessNotAttached(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fakeClaudeIn(t, e, `{"steps":[{"text":"答完了"}]}`)

	useBuild(t, "5")
	a := startServe(t, e, testIdle)
	view := &convView{}
	a.send(`{"id":1,"method":"conv.open","conv":"a","cwd":` + quote(e.home) + `}`)
	view.until(t, a, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("1") })
	a.send(`{"id":2,"method":"conv.send","conv":"a","text":"第一轮"}`)
	view.until(t, a, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" })
	session, _ := view.state["session"].(string)
	if session == "" {
		t.Fatalf("没拿到会话号：%v", view.state)
	}

	// 新版本的 serve 连上来：旧核心交班，闲着的托管进程留着、新核心不接
	_ = a.in.Close()
	a.exited()
	useBuild(t, "9")
	b := startServe(t, e, testIdle)
	reopened := &convView{}
	b.send(`{"id":1,"method":"conv.open","conv":"x","session":` + quote(session) + `,"cwd":` + quote(e.home) + `}`)
	reopened.until(t, b, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("1") })
	if reopened.state["process"] != false || !hostedFor(t, e, session) {
		t.Fatalf("前提不对：核心里的对话应当没接着进程（process %v），托管进程应当还在（%v）", reopened.state["process"], hostedFor(t, e, session))
	}

	b.send(`{"id":2,"method":"conv.end","conv":"x"}`)
	reopened.until(t, b, func(m obj) bool { return m["type"] == "zeta_reply" && m["id"] == json.Number("2") })
	for deadline := time.Now().Add(15 * time.Second); hostedFor(t, e, session); time.Sleep(50 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("结束对话后，本包托管、跑着这个会话的进程 15 秒内还没结束")
		}
	}
}

// hostedFor：本包有活着的托管进程在跑会话 session（zeta-helper hosts 列出的）。
func hostedFor(t *testing.T, e *env, session string) bool {
	t.Helper()
	hosted, err := hostedProcesses(e)
	if err != nil {
		t.Fatal(err)
	}
	return slices.ContainsFunc(hosted, func(h conv.Hosted) bool { return h.Session == session })
}
