package main

import (
	"os"
	"strconv"
	"testing"
	"time"
)

// 换账号、换核心前结束本包闲着的托管进程：答完一轮闲着的结束、托管目录清掉，还在回答的不动。
// 经真的 serve、核心和托管脚本起两个假 Claude：剧本第一轮直接答完，第二轮等一个不会放下的标记，一直在回答。
func TestEndIdleHostedLeavesBusyOnes(t *testing.T) {
	e0, _ := coreEnv(t)
	e := newEnvAt(e0.home, "zeta-dev")
	t.Setenv("ZETA_NAME", e.name)
	fake := fakeClaudeIn(t, e, `{"steps":[{"text":"答完了"}]}`+"\n"+`{"steps":[{"wait":"release","ms":60000},{"text":"放开了"}]}`)
	c := startServe(t, e, testIdle)
	view := &convView{}
	for i, conv := range []string{"a", "b"} {
		c.send(`{"id":` + strconv.Itoa(i+1) + `,"method":"conv.open","conv":"` + conv + `","cwd":` + quote(e.home) + `}`)
		view.until(t, c, func(m obj) bool { return m["type"] == "zeta_reply" && m["ok"] == true })
	}
	c.send(`{"id":3,"method":"conv.send","conv":"a","text":"第一轮"}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "conv.turn" && m["phase"] == "finished" && m["conv"] == "a" })
	c.send(`{"id":4,"method":"conv.send","conv":"b","text":"第二轮"}`)
	view.until(t, c, func(m obj) bool { return m["type"] == "conv.turn" && m["conv"] == "b" && m["phase"] == "started" })
	t.Cleanup(func() { _ = os.WriteFile(fake+"/marks/release", nil, 0o644) })

	busy, idle := "", ""
	for deadline := time.Now().Add(15 * time.Second); busy == "" || idle == ""; time.Sleep(50 * time.Millisecond) {
		hosted, _ := hostedProcesses(e)
		if time.Now().After(deadline) {
			t.Fatalf("15 秒内没等到一个闲着、一个在回答的托管进程：%+v", hosted)
		}
		for _, h := range hosted {
			if h.Active() {
				busy = h.Key
			} else if h.Lines > 0 {
				idle = h.Key
			}
		}
	}

	endIdleHosted(e)
	for deadline := time.Now().Add(15 * time.Second); ; time.Sleep(50 * time.Millisecond) {
		hosted, _ := hostedProcesses(e)
		if len(hosted) == 1 && hosted[0].Key == busy {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("闲着的 %s 没结束，或在回答的 %s 被结束了：%+v", idle, busy, hosted)
		}
	}
}
