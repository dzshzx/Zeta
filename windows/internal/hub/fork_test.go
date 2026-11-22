package hub

import (
	"encoding/json"
	"errors"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

func TestForkSwitchesTheChat(t *testing.T) {
	f := newChatFixture(t)
	f.hub.OpenSession(hostlink.Session{ID: "s1", Backend: f.id, Cwd: "/home/box/web", Title: "修登录页"})
	src := f.hub.Chat()
	l := f.link()
	l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		if c.Method == "conv.fork" {
			l.Push(`{"type":"conv.state","conv":"` + c.Str("as") + `","full":true,"key":"c2","title":"分支 · 修登录页","cwd":"/home/box/web"}`)
		}
		return nil, nil
	})
	l.Took()
	var failed error
	f.hub.Fork("a1", func(err error) { failed = err })
	c := f.hub.Chat()
	if failed != nil || c == src || c.Conv == nil || c.Backend != f.id || c.Session.Cwd != "/home/box/web" || !c.Fresh() {
		t.Fatalf("分支后：%+v，出错 %v", c, failed)
	}
	if got := l.Methods(); len(got) != 2 || got[0] != "conv.fork" || got[1] != "conv.close" {
		t.Fatalf("请求：%v", got)
	}
	if s := c.Conv.State(); s.Title != "分支 · 修登录页" {
		t.Fatalf("新对话：%+v", s)
	}

	// 没分成：聊天窗格不换，原因交回
	l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		return nil, errors.New("不能从这里分支：找不到这一项，或下一轮的消息 Claude Code 不认得")
	})
	f.hub.Fork("zz", func(err error) { failed = err })
	if f.hub.Chat() != c || failed == nil {
		t.Fatalf("没分成：%v", failed)
	}
}
