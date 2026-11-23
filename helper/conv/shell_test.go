package conv

import (
	"reflect"
	"testing"
)

func replayLine(text string) string {
	return `{"type":"user","isReplay":true,"message":{"role":"user","content":"` + text + `"}}`
}

func resultFor(ids ...string) string {
	list := ""
	for i, id := range ids {
		if i > 0 {
			list += ","
		}
		list += `"` + id + `"`
	}
	return `{"type":"result","subtype":"success","is_error":false,"num_turns":0,"user_message_uuids":[` + list + `]}`
}

// ! 命令：Claude Code 回放出输出时，命令和输出照原样作为 shouldQuery 为假的用户消息交回去并进上下文；
// 它们的 command_lifecycle 和 result 不算一轮，不改忙闲、不推一轮的起止。
func TestShellCommandJoinsContext(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "!echo hi"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	cmds := l.lines("bash_command")
	if len(cmds) != 1 || cmds[0]["command"] != "echo hi" {
		t.Fatalf("bash_command：%v", cmds)
	}
	id := cmds[0]["uuid"].(string)
	input, output := `<bash-input>echo hi</bash-input>`, `<bash-stdout>hi</bash-stdout><bash-stderr></bash-stderr><bash-exit-code>0</bash-exit-code>`
	// Claude Code 收下就报 started、completed，输出之后才回放
	h.feed(c, l, lifecycle(id, "started"), lifecycle(id, "completed"), replayLine(input))
	if n := len(l.lines("user")); n != 0 {
		t.Fatalf("没等到输出就交回了 %d 条", n)
	}
	h.feed(c, l, replayLine(output))
	users := l.lines("user")
	if len(users) != 2 {
		t.Fatalf("交回去的：%v", users)
	}
	var notes []string
	for i, want := range []string{input, output} {
		u := users[i]
		if u["shouldQuery"] != false || u["message"].(map[string]any)["content"] != want {
			t.Fatalf("第 %d 条交回去的：%v", i, u)
		}
		notes = append(notes, u["uuid"].(string))
	}
	turns := len(h.client(1).turns)
	items := h.texts(1)
	h.feed(c, l, lifecycle(notes[0], "queued"), lifecycle(notes[0], "started"), lifecycle(notes[1], "queued"))
	if h.state(1, "busy") != false {
		t.Fatal("交回上下文的消息让对话忙了")
	}
	h.feed(c, l, resultFor(notes[0]), lifecycle(notes[1], "started"), resultFor(notes[1]))
	if h.state(1, "busy") != false || len(h.client(1).turns) != turns || !reflect.DeepEqual(h.texts(1), items) {
		t.Fatalf("busy %v，一轮的起止 %v，条目 %v（原来 %v）", h.state(1, "busy"), h.client(1).turns[turns:], h.texts(1), items)
	}
	if v, _ := c.Call(func() (any, error) { return len(c.contextNotes), nil }); v != 0 {
		t.Fatalf("记号没收掉：%v", v)
	}
}

// 交回上下文的消息和用户发的并成一轮时，那个 result 照常结束这一轮。
func TestShellContextMergedIntoTurn(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "!sleep 5"})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	id := l.lines("bash_command")[0]["uuid"].(string)
	h.feed(c, l, lifecycle(id, "started"), lifecycle(id, "completed"), replayLine(`<bash-input>sleep 5</bash-input>`))
	// 命令还在跑，用户先发了一条
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "问一句"})
	h.settle(c)
	ask := l.lines("user")[0]["uuid"].(string)
	h.feed(c, l, replayLine(`<bash-stdout></bash-stdout><bash-stderr></bash-stderr>`))
	users := l.lines("user")
	if len(users) != 3 {
		t.Fatalf("发出去的：%v", users)
	}
	h.feed(c, l, answerLines("m1", "好的")...)
	h.feed(c, l, resultFor(ask, users[1]["uuid"].(string), users[2]["uuid"].(string)))
	if h.state(1, "busy") != false {
		t.Fatal("并成一轮的 result 没结束这一轮")
	}
	turns := h.client(1).turns
	if last := turns[len(turns)-1]; last["outcome"] != "done" {
		t.Fatalf("一轮的起止：%v", turns)
	}
}
