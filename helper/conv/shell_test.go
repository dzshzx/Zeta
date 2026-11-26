package conv

import (
	"encoding/json"
	"reflect"
	"testing"
)

func replayLine(text string) string {
	b, _ := json.Marshal(map[string]any{"type": "user", "isReplay": true, "message": map[string]any{"role": "user", "content": text}})
	return string(b)
}

// noteEvents 是 Claude Code（2.1.330 实测）对一条 shouldQuery 为假的用户消息依次出的：lifecycle queued、started，
// 带它 uuid 的 init 和 result（num_turns 0），lifecycle completed。
func noteEvents(session, id string) []string {
	ids := []string{id}
	initLine, _ := json.Marshal(map[string]any{"type": "system", "subtype": "init", "session_id": session, "model": "claude-fake",
		"permissionMode": "default", "user_message_uuids": ids})
	result, _ := json.Marshal(map[string]any{"type": "result", "subtype": "success", "num_turns": 0, "user_message_uuids": ids})
	return []string{lifecycle(id, "queued"), lifecycle(id, "started"), string(initLine), string(result), lifecycle(id, "completed")}
}

func resultFor(ids ...string) string {
	b, _ := json.Marshal(map[string]any{"type": "result", "subtype": "success", "user_message_uuids": ids})
	return string(b)
}

// startShell 在新对话里发 ! 命令，返回 bash_command 的 uuid。
func startShell(h *harness, command string) (*Conversation, *fakeLink, string) {
	h.t.Helper()
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "!" + command})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	cmds := l.lines("bash_command")
	if len(cmds) != 1 || cmds[0]["command"] != command {
		h.t.Fatalf("bash_command：%v", cmds)
	}
	return c, l, cmds[0]["uuid"].(string)
}

// ! 命令：Claude Code 回放出输出时，照终端版把命令和输出交回去：命令那行 shouldQuery 为假，只并进上下文，
// 它的 lifecycle、init 和 result 不算一轮，不改忙闲、不推一轮的起止，init 报的会话号照记；
// 输出那行是普通的用户消息，模型照它回一轮，这一轮照常开始、结束。
func TestShellCommandJoinsContext(t *testing.T) {
	h := newHarness(t)
	c, l, id := startShell(h, "echo hi")
	input, output := `<bash-input>echo hi</bash-input>`, `<bash-stdout>hi</bash-stdout><bash-stderr></bash-stderr><bash-exit-code>0</bash-exit-code>`
	// Claude Code 收下就报 started、completed，输出之后才回放；新进程在这之前没有 init
	h.feed(c, l, lifecycle(id, "started"), lifecycle(id, "completed"), replayLine(input))
	if n := len(l.lines("user")); n != 0 {
		t.Fatalf("没等到输出就交回了 %d 条", n)
	}
	h.feed(c, l, replayLine(output))
	users := l.lines("user")
	if len(users) != 2 {
		t.Fatalf("交回去的：%v", users)
	}
	note, ask := users[0], users[1]
	if note["shouldQuery"] != false || note["message"].(map[string]any)["content"] != input {
		t.Fatalf("命令那行：%v", note)
	}
	if _, set := ask["shouldQuery"]; set || ask["message"].(map[string]any)["content"] != output {
		t.Fatalf("输出那行：%v", ask)
	}
	turns, items := len(h.client(1).turns), h.texts(1)
	h.feed(c, l, noteEvents("s1", note["uuid"].(string))...)
	if h.state(1, "busy") != false || h.state(1, "session") != "s1" || len(h.client(1).turns) != turns || !reflect.DeepEqual(h.texts(1), items) {
		t.Fatalf("命令那行：忙 %v，会话 %v，一轮的起止 %v，条目 %v（原来 %v）", h.state(1, "busy"), h.state(1, "session"),
			h.client(1).turns[turns:], h.texts(1), items)
	}
	askID := ask["uuid"].(string)
	h.feed(c, l, lifecycle(askID, "queued"), lifecycle(askID, "started"), initLine("s1"))
	if h.state(1, "busy") != true {
		t.Fatal("输出交回去以后没开始回答")
	}
	h.feed(c, l, answerLines("m1", "看到了")...)
	h.feed(c, l, resultFor(askID), lifecycle(askID, "completed"))
	if h.state(1, "busy") != false {
		t.Fatal("回答完了还在忙")
	}
	if ts := h.client(1).turns; ts[len(ts)-1]["outcome"] != "done" {
		t.Fatalf("一轮的起止：%v", ts)
	}
	if got := h.texts(1); got[len(got)-1] != "answer:看到了" {
		t.Fatalf("条目：%v", got)
	}
	if v, _ := c.Call(func() (any, error) { return len(c.contextNotes), nil }); v != 0 {
		t.Fatalf("记号没收掉：%v", v)
	}
}

// 命令还在跑时用户发了一条：交回上下文的消息和它并成一轮，那个 result 照常结束这一轮，记号也收掉。
func TestShellContextMergedIntoTurn(t *testing.T) {
	h := newHarness(t)
	c, l, id := startShell(h, "sleep 5")
	h.feed(c, l, lifecycle(id, "started"), lifecycle(id, "completed"), replayLine(`<bash-input>sleep 5</bash-input>`))
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "问一句"})
	h.settle(c)
	ask := l.lines("user")[0]["uuid"].(string)
	h.feed(c, l, initLine("s1"), replayLine(`<bash-stdout></bash-stdout><bash-stderr></bash-stderr>`))
	users := l.lines("user")
	if len(users) != 3 {
		t.Fatalf("发出去的：%v", users)
	}
	h.feed(c, l, answerLines("m1", "好的")...)
	h.feed(c, l, resultFor(ask, users[1]["uuid"].(string), users[2]["uuid"].(string)))
	if h.state(1, "busy") != false {
		t.Fatal("并成一轮的 result 没结束这一轮")
	}
	if turns := h.client(1).turns; turns[len(turns)-1]["outcome"] != "done" {
		t.Fatalf("一轮的起止：%v", turns)
	}
	if v, _ := c.Call(func() (any, error) { return len(c.contextNotes), nil }); v != 0 {
		t.Fatalf("并成一轮后记号没收掉：%v", v)
	}
}

// 上一条 ! 命令还没出输出时，再发 ! 由 Zeta 拦下放回输入框，不交给 Claude Code（它会答 Not run，两条的输出就配错了）。
func TestSecondShellWhileRunning(t *testing.T) {
	h := newHarness(t)
	c, l, id := startShell(h, "sleep 5")
	h.feed(c, l, lifecycle(id, "started"), lifecycle(id, "completed"), replayLine(`<bash-input>sleep 5</bash-input>`))
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "!ls"})
	h.settle(c)
	if n := len(l.lines("bash_command")); n != 1 {
		t.Fatalf("第二条也交出去了：%d 条 bash_command", n)
	}
	if recalled, _ := h.state(1, "recalled").(map[string]any); recalled["text"] != "!ls" {
		t.Fatalf("没放回输入框：%v", recalled)
	}
	h.feed(c, l, replayLine(`<bash-stdout></bash-stdout><bash-stderr></bash-stderr>`))
	h.call(1, "conv.take_recalled", map[string]any{"conv": "a"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "!ls"})
	h.settle(c)
	if n := len(l.lines("bash_command")); n != 2 {
		t.Fatalf("上一条出了输出以后还发不出去：%d 条 bash_command", n)
	}
}
