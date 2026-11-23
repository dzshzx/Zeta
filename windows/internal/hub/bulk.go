package hub

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
)

// bulkIdle 是取大块数据的连接闲多久关掉。
const bulkIdle = time.Minute

// bulkLink 是一台设备上另起的一条 serve 连接，只取大块数据（sessions.image、sessions.export）：serve 的应答是一整行，
// 几 MB 的图和对话的推送挤在同一条连接上时，流式输出要排在它后面。第一次要用时才连，闲 idle 之后关掉，下次要用时再连。
// Call 在后台 goroutine 上调用。
type bulkLink struct {
	open func() host.ChatLink
	idle time.Duration

	mu    sync.Mutex
	link  host.ChatLink
	busy  int         // 在等应答的请求
	timer *time.Timer // 闲着时关掉连接的定时器
	gen   int         // 第几个定时器：到点时已经换了新的就不算
}

// Call 见 host.ChatLink。没连上（不是核心答的出错，也不是时限到了）时连接丢掉，下次重新连。
func (b *bulkLink) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	b.mu.Lock()
	if b.link == nil {
		b.link = b.open()
	}
	link := b.link
	b.busy++
	if b.timer != nil {
		b.timer.Stop()
		b.timer = nil
	}
	b.mu.Unlock()

	raw, err := link.Call(ctx, method, params)

	b.mu.Lock()
	defer b.mu.Unlock()
	b.busy--
	if _, replied := hostlink.ReplyCode(err); err != nil && ctx.Err() == nil && !replied && b.link == link {
		b.link = nil
		go link.Close() // 拿着 mu，不在这里等
	}
	if b.busy == 0 && b.link != nil {
		b.gen++
		gen := b.gen
		b.timer = time.AfterFunc(b.idle, func() { b.closeIdle(gen) })
	}
	return raw, err
}

// closeIdle：第 gen 个定时器到点了，期间没有新的请求时关掉连接。
func (b *bulkLink) closeIdle(gen int) {
	b.mu.Lock()
	link := b.link
	if b.gen != gen || b.busy > 0 {
		b.mu.Unlock()
		return
	}
	b.link, b.timer = nil, nil
	b.mu.Unlock()
	if link != nil {
		_ = link.Close()
	}
}

// Close 关掉连接（设备删了、连法改了）。之后再 Call 会重新连。
func (b *bulkLink) Close() {
	b.mu.Lock()
	link := b.link
	b.link = nil
	if b.timer != nil {
		b.timer.Stop()
		b.timer = nil
	}
	b.mu.Unlock()
	if link != nil {
		_ = link.Close()
	}
}

// bulkCall 是在后台对设备 backend 的核心取大块数据的办法（另一条连接，见 bulkLink）。在主线程上取，在后台调用。
func (h *Hub) bulkCall(backend string) sessionsCaller {
	b := h.bulks[backend]
	if b == nil {
		x, _ := h.Host(backend)
		devices := h.devices
		b = &bulkLink{open: func() host.ChatLink { return devices.OpenBulk(x) }, idle: h.bulkIdle}
		h.bulks[backend] = b
	}
	return b.Call
}

// closeBulk 关掉设备 id 上取大块数据的连接。
func (h *Hub) closeBulk(id string) {
	if b := h.bulks[id]; b != nil {
		delete(h.bulks, id)
		b.Close()
	}
}
