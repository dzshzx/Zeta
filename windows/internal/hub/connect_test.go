package hub

import (
	"context"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// 「连接」开关：关掉时这台的会话列表订阅、对话连接、SSH 连接都断开，之后要连它的都答已断开、不去连；聊天窗格留着那个会话、
// 没有打开着的对话。开关存进设置。打开时会话列表重新订阅，聊天窗格里的会话在新连接上按会话号重新打开。
func TestDisconnectingADeviceStopsConnectingUntilReconnected(t *testing.T) {
	f := newChatFixture(t)
	h, d, id := f.hub, f.devices, f.id
	h.NewChat()
	f.take()
	f.replay("turn")
	session := f.state.Session
	first := f.link()
	if err := h.SetConnected(id, false); err != nil {
		t.Fatal(err)
	}
	if !first.Closed() || slices.Contains(d.Watching(), id) || slices.Contains(d.Retained(), id) {
		t.Fatalf("断开后连接还在：对话连接关了 %v，订阅 %v，SSH 留着 %v", first.Closed(), d.Watching(), d.Retained())
	}
	if c := h.Chat(); c == nil || c.Conv != nil || c.Opening || c.Session.ID != session || c.Backend != id {
		t.Fatalf("断开后的聊天窗格：%+v", c)
	}
	if !h.Off(id) || h.Problem() != locales.HubOffProblem() || h.Remote(id) != nil {
		t.Fatalf("断开后：Off %v，问题 %q", h.Off(id), h.Problem())
	}
	chats := d.Chats()
	_, err := h.sessionsCall(id).RenameSession(context.Background(), "s1", "新名字")
	h.OpenSession(hostlink.Session{ID: "s2", Backend: id})
	h.Refresh()
	if err == nil || !strings.Contains(err.Error(), "已断开") || d.Chats() != chats || slices.Contains(d.Watching(), id) {
		t.Fatalf("断开后又去连了：%v，对话连接 %d → %d，订阅 %v", err, chats, d.Chats(), d.Watching())
	}
	if c := h.Chat(); c.Opening || c.Conv != nil {
		t.Fatalf("断开的设备上打开会话：%+v", c)
	}

	h.OpenSession(hostlink.Session{ID: session, Backend: id})
	if err := h.SetConnected(id, true); err != nil {
		t.Fatal(err)
	}
	calls := f.link().Took()
	if d.Chats() != chats+1 || len(calls) == 0 || calls[0].Method != "conv.open" || calls[0].Str("session") != session {
		t.Fatalf("连上后没重新打开：%d 条连接，%+v", d.Chats(), calls)
	}
	if c := h.Chat(); c.Conv == nil || h.Off(id) || h.Problem() == locales.HubOffProblem() || !slices.Contains(d.Watching(), id) {
		t.Fatalf("连上后：%+v，问题 %q，订阅 %v", c, h.Problem(), d.Watching())
	}
}

// 开关存在设置里：重新读设置还是关着。
func TestConnectSwitchIsSaved(t *testing.T) {
	f := newChatFixture(t)
	dir := t.TempDir()
	s, err := settings.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetHosts(f.hub.Hosts()); err != nil {
		t.Fatal(err)
	}
	h := New(s, f.devices, func(fn func()) { fn() }, func(fn func()) { fn() })
	if err := h.SetConnected(f.id, false); err != nil {
		t.Fatal(err)
	}
	again, err := settings.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if hosts := again.Hosts(); len(hosts) != 1 || !hosts[0].Off {
		t.Fatalf("重读设置：%+v", hosts)
	}
}
