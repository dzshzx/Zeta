package hub

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// queuedFixture 同 newChatFixture，只是交回主线程的事排进队列，until 在测试的 goroutine 上执行（账号、额度在后台取）。
func queuedFixture(t *testing.T) (*chatFixture, func(what string, done func() bool)) {
	t.Helper()
	f := newChatFixture(t)
	posts := make(chan func(), 100)
	f.hub.post = func(fn func()) { posts <- fn }
	until := func(what string, done func() bool) {
		t.Helper()
		deadline := time.After(10 * time.Second)
		for !done() {
			select {
			case fn := <-posts:
				fn()
			case <-deadline:
				t.Fatalf("等不到%s", what)
			}
		}
	}
	return f, until
}

// 设置的请求走这台给对话用的那条连接：config.*、accounts.* 都发到它上面，不另起连接。
func TestRemoteUsesChatConnection(t *testing.T) {
	f, until := queuedFixture(t)
	r := f.hub.Remote(f.id)
	if r == nil || f.hub.Remote(f.id) != r {
		t.Fatal("同一台要拿到同一份")
	}
	link := f.link()
	link.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		switch c.Method {
		case "config.defaults.read":
			return json.RawMessage(`{"model":"sonnet","permissionMode":"plan","efforts":[],"effort":"","compactWindow":0}`), nil
		case "accounts.list":
			return json.RawMessage(`{"current":null,"accounts":[],"procs":[],"blocker":""}`), nil
		}
		return nil, nil
	})
	d, err := r.Core.Defaults(context.Background())
	if err != nil || d.Model != "sonnet" || d.PermissionMode != "plan" {
		t.Fatalf("默认值：%+v %v", d, err)
	}
	r.Accounts.Refresh()
	until("读到账号", func() bool { _, ok := r.Accounts.State(); return ok || r.Accounts.Problem() != "" })
	if r.Accounts.Problem() != "" {
		t.Fatal(r.Accounts.Problem())
	}
	if got := link.Methods(); !slices.Equal(got, []string{"config.defaults.read", "accounts.list"}) {
		t.Fatalf("发的请求：%v", got)
	}
	if f.devices.Chats() != 1 {
		t.Fatalf("起了 %d 条连接", f.devices.Chats())
	}
}

// 一次都没连上的连接在连不上时丢掉，下次按新的连接重建；serve 答的出错（ReplyError）不算连不上。
func TestRemoteDropsDeadConnection(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	x, err := h.AddHost("box", "", d.Found("box"))
	if err != nil {
		t.Fatal(err)
	}
	r := h.Remote(x.ID)
	link := d.Chat(x.ID)
	link.OnCall(func(hubtest.Call) (json.RawMessage, error) {
		return nil, &hostlink.ReplyError{Message: "不认识的方法"}
	})
	if _, err := r.Core.Defaults(context.Background()); err == nil {
		t.Fatal("出错没有交回来")
	}
	if h.Remote(x.ID) != r || link.Closed() {
		t.Fatal("serve 答的出错不该丢掉连接")
	}
	link.OnCall(func(hubtest.Call) (json.RawMessage, error) { return nil, errors.New("连不上「box」") })
	if _, err := r.Core.Defaults(context.Background()); err == nil {
		t.Fatal("出错没有交回来")
	}
	if !link.Closed() {
		t.Fatal("一次都没连上的连接没丢掉")
	}
	if next := h.Remote(x.ID); next == r || d.Chats() != 2 {
		t.Fatalf("没有按新的连接重建（连接 %d 条）", d.Chats())
	}
}

// 额度：开着进程的对话借来问（conv.quota）；没有时读主机上存着的（quota.get）。
func TestQuotaAsksOpenProcess(t *testing.T) {
	quota := func(link *hubtest.ChatLink) {
		link.Took()
		link.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			if c.Method == "conv.quota" {
				return json.RawMessage(`{"rate_limits_available":true}`), nil
			}
			return json.RawMessage(`{"snapshot":null,"fresh":true}`), nil
		})
	}
	f, until := queuedFixture(t)
	r := f.hub.Remote(f.id)
	quota(f.link())
	r.Quota.Refresh(true)
	until("取完额度", func() bool { return !r.Quota.Refreshing() })
	if got := f.link().Methods(); !slices.Equal(got, []string{"quota.get"}) {
		t.Fatalf("没有对话时：%v", got)
	}

	f, until = queuedFixture(t)
	r = f.hub.Remote(f.id)
	f.hub.NewChat()
	until("打开对话", func() bool { return f.hub.Chat().Conv != nil })
	f.take()
	f.replay("turn") // 答完一轮，进程开着
	if !f.hub.Chat().Conv.State().Process {
		t.Fatal("录的这一段里进程应该开着")
	}
	quota(f.link())
	r.Quota.Refresh(true)
	until("取完额度", func() bool { return !r.Quota.Refreshing() })
	if got := f.link().Methods(); !slices.Equal(got, []string{"conv.quota", "quota.usage"}) {
		t.Fatalf("有对话时：%v", got)
	}
}

// 换号前看本客户端在这台上的对话：在回答时算忙；闲着时收掉进程（conv.end）。
func TestAccountsSeeOwnConversations(t *testing.T) {
	f := newChatFixture(t)
	f.hub.NewChat()
	f.take()
	f.replay("turn")
	convs := deviceConvs{f.hub.Remote(f.id).conn, f.hub.run}
	if convs.Busy() {
		t.Fatal("答完了还算忙")
	}
	f.link().Took()
	if !convs.CloseIdle() {
		t.Fatal("闲着却收不掉")
	}
	if got := f.link().Methods(); !slices.Equal(got, []string{"conv.end"}) {
		t.Fatalf("收掉进程：%v", got)
	}
	f.link().Push(`{"type":"conv.state","conv":"w1","busy":true}`) // 回答中
	if !convs.Busy() || convs.CloseIdle() {
		t.Fatal("在回答时不算忙")
	}
}

// 起进程用这台的自动标题、自动压缩；离开设备页时交给开着的对话。
func TestOptionsFollowDeviceSettings(t *testing.T) {
	f := newChatFixture(t)
	dev := claudecfg.DeviceSettings(f.hub.Settings().Store(), f.id)
	if err := dev.SetCompactPercent(70); err != nil {
		t.Fatal(err)
	}
	if err := dev.SetAutoTitle(false); err != nil {
		t.Fatal(err)
	}
	f.hub.NewChat()
	open := f.link().Took()[0]
	if open.Method != "conv.open" || open.JSON() != `{"conv":"w1","cwd":"/home/box","options":{"env":{"CLAUDE_AUTOCOMPACT_PCT_OVERRIDE":"70"},"compact_percent":70,"desk":true,"image_refs":true}}` {
		t.Fatalf("打开：%s %s", open.Method, open.JSON())
	}
	if err := dev.SetAutoCompact(false); err != nil {
		t.Fatal(err)
	}
	f.hub.ApplyOptions(f.id)
	got := f.link().Took()
	if len(got) != 1 || got[0].Method != "conv.options" || got[0].JSON() != `{"conv":"w1","options":{"env":{"DISABLE_AUTO_COMPACT":"1"},"desk":true,"image_refs":true}}` {
		t.Fatalf("改了以后：%+v", got)
	}
}

var _ accounts.UsageSource = usageSource{}
