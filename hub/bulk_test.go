package hub

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/hub/hubtest"
)

// 几 MB 的图在另一条连接上取：它没传完时，对话的推送照样送到。
func TestBigRepliesDoNotHoldUpTheChat(t *testing.T) {
	f := newChatFixture(t)
	f.hub.OpenSession(hostlink.Session{Backend: f.id, ID: "s1"})
	conv := f.hub.Chat().Conv.Name()
	f.take()
	f.devices.ImageData = []byte("png!")
	started, release := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() {
		select {
		case <-release:
		default:
			close(release)
		}
	})
	f.devices.OnSessions = func(_ string, c hubtest.Call) {
		if c.Method == "sessions.image" {
			close(started)
			<-release
		}
	}
	load := f.hub.ImageLoader(f.id)
	loaded := make(chan error, 1)
	go func() {
		_, err := load("/home/box/.claude/projects/-w/s1.jsonl", "0.1")
		loaded <- err
	}()
	<-started

	pushed := make(chan struct{})
	go func() {
		f.link().Push(`{"type":"conv.state","conv":"` + conv + `","full":true,"title":"推来的标题"}`)
		close(pushed)
	}()
	select {
	case <-pushed:
	case <-time.After(5 * time.Second):
		t.Fatal("取图没完时对话的推送被挡住了")
	}
	if f.take(); f.state.Title != "推来的标题" {
		t.Errorf("推送没送到：%+v", f.state)
	}
	close(release)
	if err := <-loaded; err != nil {
		t.Fatal(err)
	}
	if n := len(f.devices.Bulks()); n != 1 {
		t.Errorf("取大块数据的连接起了 %d 条", n)
	}
}

// 取大块数据的连接第一次要用时才连，闲一会儿关掉，再要用时重新连；导出也走它。
func TestBulkLinkOpensLazilyAndClosesWhenIdle(t *testing.T) {
	f := newChatFixture(t)
	f.hub.bulkIdle = 20 * time.Millisecond
	f.devices.ImageData = []byte("png!")
	load := f.hub.ImageLoader(f.id)
	if n := len(f.devices.Bulks()); n != 0 {
		t.Fatalf("还没取就连了 %d 条", n)
	}
	if _, err := load("/f", "0.0"); err != nil {
		t.Fatal(err)
	}
	first := f.devices.Bulks()[0]
	deadline := time.Now().Add(5 * time.Second)
	for !first.Closed() {
		if time.Now().After(deadline) {
			t.Fatal("闲着没关")
		}
		time.Sleep(5 * time.Millisecond)
	}
	f.hub.bulks[f.id].idle = time.Hour
	f.hub.Export(f.listed()[:1], time.Now(), func(Exported) {}, func(err error) { t.Error(err) })
	if bulks := f.devices.Bulks(); len(bulks) != 2 || bulks[1].Closed() {
		t.Errorf("再要用时没重新连：%d 条", len(bulks))
	}
	f.hub.closeBulk(f.id)
	if !f.devices.Bulks()[1].Closed() {
		t.Error("closeBulk 没关掉")
	}
}

// 批量操作每台设备最多 4 个同时发，几台之间互不等。
func TestBatchesRunFourAtATimePerHost(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	h.ListAll()
	var list []hostlink.Session
	for i := range 6 {
		list = append(list, hostlink.Session{Backend: a.ID, ID: fmt.Sprintf("a%d", i)})
	}
	list = append(list, hostlink.Session{Backend: b.ID, ID: "b0"}, hostlink.Session{Backend: b.ID, ID: "b1"})

	var mu sync.Mutex
	busy, peak := map[string]int{}, map[string]int{}
	release := make(chan struct{})
	d.OnSessions = func(host string, _ hubtest.Call) {
		mu.Lock()
		busy[host]++
		peak[host] = max(peak[host], busy[host])
		mu.Unlock()
		<-release
		mu.Lock()
		busy[host]--
		mu.Unlock()
	}
	done := make(chan struct{})
	go func() { // 测试里后台的事就地做：SetArchived 等到都答完才返回
		defer close(done)
		h.SetArchived(list, true, func(err error) { t.Error(err) })
	}()
	deadline := time.Now().Add(5 * time.Second)
	for {
		mu.Lock()
		na, nb := busy[a.ID], busy[b.ID]
		mu.Unlock()
		if na == perDevice && nb == 2 {
			break
		}
		if time.Now().After(deadline) {
			close(release)
			<-done
			t.Fatalf("没有同时发：a %d 个、b %d 个", na, nb)
		}
		time.Sleep(time.Millisecond)
	}
	close(release)
	<-done
	if peak[a.ID] != perDevice || peak[b.ID] != 2 {
		t.Errorf("同时发的最多：a %d、b %d", peak[a.ID], peak[b.ID])
	}
	if got := d.SessionCalls(); len(got) != len(list) {
		t.Errorf("发出 %q", got)
	}
}

// 几台设备的一起导出：每台一个请求、带它的几个；各台答来的 parts 照选中的先后排好再合成（先答完的不排到前面，
// 也不按设备分堆）。
func TestExportKeepsTheOrder(t *testing.T) {
	d := hubtest.NewDevices()
	h, _ := newHub(t, d)
	a, _ := h.AddHost("a", "", d.Found("a"))
	b, _ := h.AddHost("b", "", d.Found("b"))
	list := []hostlink.Session{{Backend: b.ID, ID: "b0"}, {Backend: a.ID, ID: "a0"}, {Backend: b.ID, ID: "b1"}}
	answered := make(chan struct{})
	d.OnSessions = func(host string, _ hubtest.Call) {
		if host == a.ID {
			defer close(answered)
			return
		}
		select { // 等 a 答完再答
		case <-answered:
		case <-time.After(5 * time.Second):
		}
	}
	var got Exported
	h.Export(list, time.Date(2026, 11, 22, 9, 5, 0, 0, time.UTC), func(e Exported) { got = e }, func(err error) { t.Error(err) })
	var order []string
	for _, part := range strings.Split(got.Content, "\n---\n\n") {
		order = append(order, strings.TrimPrefix(strings.SplitN(part, "\n", 2)[0], "# 对话 "))
	}
	if fmt.Sprint(order) != "[b0 a0 b1]" || got.Name != "Zeta-3个对话-20261122-0905.md" {
		t.Errorf("接起来的先后 %v，文件名 %q", order, got.Name)
	}
	if calls := d.SessionCalls(); len(calls) != 2 {
		t.Errorf("发出 %q", calls)
	}
}

// background 带时限时 ctx 到点取消，为 0 时不设时限；做完的交回主线程。
func TestBackgroundTimeout(t *testing.T) {
	h, _ := newHub(t, hubtest.NewDevices())
	var limited, unlimited bool
	h.background(time.Minute, func(ctx context.Context) func() {
		_, limited = ctx.Deadline()
		return func() {}
	})
	applied := false
	h.background(0, func(ctx context.Context) func() {
		_, unlimited = ctx.Deadline()
		return func() { applied = true }
	})
	if !limited || unlimited || !applied {
		t.Errorf("带时限 %v，不带 %v，交回 %v", limited, !unlimited, applied)
	}
}
