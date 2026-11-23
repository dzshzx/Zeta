package conv

import (
	"strings"
	"testing"
)

// 输入 /exit 或 /quit（Claude Code 的 exit 命令和别名）：照终端里那样结束进程，不发给 Claude Code、不进条目；
// 下次发消息时起新进程，--resume 接上。带了文件的不算，照常发。
func TestExitCommandEndsTheProcess(t *testing.T) {
	for i, cmd := range []string{"/exit", " /quit \n"} {
		h := newHarness(t)
		h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
		h.call(1, "conv.send", map[string]any{"conv": "a", "text": "一"})
		c, l := h.conv(1, "a"), h.link(0)
		h.feed(c, l, initLine("s1"), resultFor())
		users, items := len(l.lines("user")), len(h.texts(1))
		h.call(1, "conv.send", map[string]any{"conv": "a", "text": cmd})
		h.settle(c)
		l.mu.Lock()
		stopped := l.stopped
		l.mu.Unlock()
		if !stopped || h.state(1, "process") != false {
			t.Fatalf("%d：%q 没结束进程（stopped %v，process %v）", i, cmd, stopped, h.state(1, "process"))
		}
		if len(l.lines("user")) != users || len(h.texts(1)) != items {
			t.Fatalf("%d：%q 发给了 Claude Code 或进了条目：%v", i, cmd, h.texts(1))
		}
		h.call(1, "conv.send", map[string]any{"conv": "a", "text": "二"})
		if len(h.starts) != 2 || !strings.Contains(strings.Join(h.starts[1].Args, " "), "--resume s1") {
			t.Fatalf("%d：结束后再发没接上：%v", i, h.starts)
		}
	}
}

func TestExitWithFilesIsAMessage(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "/exit", "files": []string{"/w/a.txt"}})
	c, l := h.conv(1, "a"), h.link(0)
	h.settle(c)
	l.mu.Lock()
	stopped := l.stopped
	l.mu.Unlock()
	if stopped || len(l.lines("user")) != 1 {
		t.Fatalf("带文件的 /exit 该照常发：stopped %v，发出 %v", stopped, l.lines("user"))
	}
}
