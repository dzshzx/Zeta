package conv

import (
	"testing"
	"time"
)

// 还没发过消息的分支：断线后按 conv.state 的 key 重新打开拿到的是同一个；没人拿着时留得比别的对话久。
func TestReopenForkByKey(t *testing.T) {
	h, _, _ := twoTurns(t)
	first := h.item(1, "answer", "回答一")
	b := h.forkFrom(first["id"])
	key, _ := h.clientState(1, "b")["key"].(string)
	if key == "" {
		t.Fatal("状态里没有 key")
	}
	h.m.Gone(1)
	now := time.Now()
	releasable := func(at time.Time) bool {
		v, _ := b.Call(func() (any, error) { return b.releasable(at), nil })
		return v == true
	}
	if releasable(now) || releasable(now.Add(releaseAfter)) {
		t.Fatal("没发过消息的分支 30 秒就收掉了")
	}
	h.call(2, "conv.open", map[string]any{"conv": "again", "key": key, "cwd": "/w"})
	if h.conv(2, "again") != b {
		t.Fatal("按 key 打开的不是那个分支")
	}
	h.m.Gone(2)
	if releasable(now) || !releasable(now.Add(forkReleaseAfter)) {
		t.Fatal("分支留的时长不对")
	}
}

func TestReopenGoneKey(t *testing.T) {
	h := newHarness(t)
	_, err := h.m.Request(1, "conv.open", []byte(`{"conv":"a","key":"c99","cwd":"/w"}`))
	if coded, ok := err.(interface{ Code() string }); !ok || coded.Code() != "conversation_gone" {
		t.Fatalf("不在了的 key：%v", err)
	}
	// 带了会话号就退回按会话号打开
	h.call(1, "conv.open", map[string]any{"conv": "a", "key": "c99", "session": "s1", "cwd": "/w"})
	if h.conv(1, "a") == nil {
		t.Fatal("没按会话号打开")
	}
}

// 只给会话号：核心自己找会话记录载入历史。
func TestOpenBySessionFindsTheRecord(t *testing.T) {
	h := newHarness(t)
	h.m.deps.SessionFile = func(session string) string { return "/found/" + session + ".jsonl" }
	h.history = []byte(`{"type":"zeta_history","skipped":0}` + "\n")
	h.call(1, "conv.open", map[string]any{"conv": "a", "session": "s1", "cwd": "/w"})
	waitLoaded(t, h, h.conv(1, "a"))
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.historyArgs) != 1 || h.historyArgs[0][0] != "/found/s1.jsonl" {
		t.Fatalf("载入的记录：%v", h.historyArgs)
	}
}
