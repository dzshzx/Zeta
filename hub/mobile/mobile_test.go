package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/settings"
)

func newCore(t *testing.T) (*Core, *hubtest.Devices) {
	c, d, _ := newWatchedCore(t)
	return c, d
}

// newWatchedCore 另给一个收「有变化了」的通道（每次变化最多攒一个）。
func newWatchedCore(t *testing.T) (*Core, *hubtest.Devices, chan struct{}) {
	t.Helper()
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	d := hubtest.NewDevices()
	changed := make(chan struct{}, 1)
	c := New(s, d, func() {
		select {
		case changed <- struct{}{}:
		default:
		}
	})
	c.Do(c.hub.Start)
	return c, d, changed
}

// until 等到 ok 为真：每有一次变化看一次（ok 在核心的 goroutine 上跑）。等的是事件，不设时限，卡住时由 go test 的超时报出来。
func until(t *testing.T, c *Core, changed chan struct{}, ok func() bool) {
	t.Helper()
	for {
		var done bool
		c.Do(func() { done = ok() })
		if done {
			return
		}
		<-changed
	}
}

func call[T any](t *testing.T, c *Core, method string, params any) T {
	t.Helper()
	raw, _ := json.Marshal(params)
	out := c.Call(method, raw)
	var failed struct{ Error string }
	if json.Unmarshal(out, &failed) == nil && failed.Error != "" {
		t.Fatalf("%s：%s", method, failed.Error)
	}
	var v T
	if err := json.Unmarshal(out, &v); err != nil {
		t.Fatalf("%s 的结果 %s：%v", method, out, err)
	}
	return v
}

func failure(c *Core, method string, params string) string {
	var failed struct{ Error string }
	_ = json.Unmarshal(c.Call(method, []byte(params)), &failed)
	return failed.Error
}

// 加设备要先探测，探测的凭据只能用一次；加上以后列着它的会话，点开一个就是聊天页里正在打开的对话。
func TestProbeAddListOpen(t *testing.T) {
	c, d := newCore(t)
	p := call[Probed](t, c, "probe", map[string]string{"target": " me@box "})
	if p.Token == "" || p.Fingerprint == "" || p.Hostname != "me@box" {
		t.Fatalf("探测 %+v", p)
	}
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	if why := failure(c, "add", `{"token":"`+p.Token+`"}`); why != locales.MobileProbeFirst() {
		t.Errorf("凭据用了第二次：%q", why)
	}
	id := call[State](t, c, "state", nil).Devices[0].ID
	d.Push(id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{{ID: "s1", Title: "第一个", Cwd: "/home/me/w", Updated: 2}}})
	c.Do(func() {}) // 推送经 post 排进核心的 goroutine，等它跑完
	st := call[State](t, c, "state", nil)
	if len(st.Devices) != 1 || st.Devices[0].Target != "me@box" || st.Backend != id || st.Listed != id {
		t.Fatalf("设备 %+v，backend %q，listed %q", st.Devices, st.Backend, st.Listed)
	}
	if len(st.Sessions) != 1 || st.Sessions[0].Title != "第一个" || st.Sessions[0].Place != "w" || st.Chat != nil {
		t.Fatalf("会话 %+v，对话 %+v", st.Sessions, st.Chat)
	}
	if why := failure(c, "open", `{"backend":"`+id+`","id":"nope"}`); why != locales.MobileSessionGone() {
		t.Errorf("点开不在列表里的：%q", why)
	}
	call[map[string]bool](t, c, "open", map[string]string{"backend": id, "id": "s1"})
	st = call[State](t, c, "state", nil)
	if st.Chat == nil || st.Chat.Session != "s1" || st.Chat.Backend != id || st.Chat.Title != "第一个" {
		t.Fatalf("对话 %+v", st.Chat)
	}
	call[map[string]bool](t, c, "close", nil)
	if st = call[State](t, c, "state", nil); st.Chat != nil {
		t.Errorf("关掉后还有对话 %+v", st.Chat)
	}
	call[map[string]bool](t, c, "remove", map[string]string{"id": id})
	if st = call[State](t, c, "state", nil); len(st.Devices) != 0 {
		t.Errorf("删掉后还有设备 %+v", st.Devices)
	}
}

func TestBadCalls(t *testing.T) {
	c, _ := newCore(t)
	if why := failure(c, "nope", ""); !strings.Contains(why, "unknown method") {
		t.Errorf("没有的方法：%q", why)
	}
	if why := failure(c, "state", "[1]"); !strings.Contains(why, "JSON object") {
		t.Errorf("参数不是对象：%q", why)
	}
	if why := failure(c, "send", `{"text":"你好"}`); why != locales.MobileChatNotOpen() {
		t.Errorf("没有对话时发消息：%q", why)
	}
}
