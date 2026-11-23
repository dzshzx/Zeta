package hub

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 侧栏对会话的操作（同 Kotlin 版的 SessionList、SessionTools）：置顶（pins.go）、改名、归档、删除、导出。
// 都是那台核心的 sessions.* 方法（docs/core.md「会话」），走给对话用的那条 serve 连接（导出走取大块数据的那条，见 bulk.go）。
// 界面先改，操作马上看得到；没成时改回去，原因交给调用方。改完的结果核心经 sessions.watch 推来，不另外重读。

// sessionsTimeout 是一次 sessions.* 最多等多久（删除、导出要读写大的会话记录）。
const sessionsTimeout = 2 * time.Minute

// sessionsCall 是在后台对设备 backend 的核心发 sessions.* 请求的办法（给对话用的那条连接）。在主线程上取，在后台调用。
func (h *Hub) sessionsCall(backend string) *coreapi.Client {
	return coreapi.New(connCaller{h: h, backend: backend, conn: h.chatConn(backend)})
}

// connCaller 经设备 backend 上给对话用的连接 conn 发请求：不是核心答的出错（连不上）时，一次都没连上的连接丢掉，下次重新连。
type connCaller struct {
	h       *Hub
	backend string
	conn    *chatConn
}

func (c connCaller) Call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	raw, err := c.conn.Call(ctx, method, params)
	if _, replied := hostlink.ReplyCode(err); err != nil && !replied {
		c.h.post(func() { c.h.dropDeadConn(c.backend, c.conn) })
	}
	return raw, err
}

// callers 是 list 涉及的各台设备的核心（设备编号 → open 给的）。在主线程上取。
func callers(list []hostlink.Session, open func(backend string) *coreapi.Client) map[string]*coreapi.Client {
	calls := map[string]*coreapi.Client{}
	for _, s := range list {
		if calls[s.Backend] == nil {
			calls[s.Backend] = open(s.Backend)
		}
	}
	return calls
}

// background 在后台跑 work，它返回的函数（不是 nil 时）交回主线程跑。timeout 是 work 的时限；为 0 时不设（work 做的事自己有时限）。
func (h *Hub) background(timeout time.Duration, work func(ctx context.Context) func()) {
	h.run(func() {
		ctx := context.Background()
		if timeout > 0 {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, timeout)
			defer cancel()
		}
		if then := work(ctx); then != nil {
			h.post(then)
		}
	})
}

// sameSession：a、b 是同一台设备上的同一个会话。
func sameSession(a, b hostlink.Session) bool { return a.Backend == b.Backend && a.ID == b.ID }

// patch 改列表里（连同聊天窗格开着的）会话 s 的样子。
func (h *Hub) patch(s hostlink.Session, change func(x *hostlink.Session)) {
	for i := range h.sessions {
		if sameSession(h.sessions[i], s) {
			change(&h.sessions[i])
		}
	}
	if c := h.chat; c != nil && sameSession(c.Session, s) {
		change(&c.Session)
	}
	h.changed()
}

// forget 把会话 s 从列表和新回答的标记里去掉，返回把它照原样放回原处的办法（核心没删时用；这期间列表里又有了它就不动）。
func (h *Hub) forget(s hostlink.Session) (restore func()) {
	same := func(x hostlink.Session) bool { return sameSession(x, s) }
	at := slices.IndexFunc(h.sessions, same)
	key := Key(s)
	unread := h.unread[key]
	var old hostlink.Session
	if at >= 0 {
		old = h.sessions[at]
		h.sessions = slices.Delete(h.sessions, at, at+1)
	}
	delete(h.unread, key)
	h.changed()
	return func() {
		if at < 0 || slices.ContainsFunc(h.sessions, same) {
			return
		}
		h.sessions = slices.Insert(h.sessions, min(at, len(h.sessions)), old)
		if unread {
			h.unread[key] = true
		}
		h.changed()
	}
}

// Rename 给会话 s 改名（sessions.rename；核心里开着它的对话，标题由核心跟着改）。先显示 title 原样，核心答了换成它实际写下的
// （空白压过、截过的）；没改成时改回去，原因交给 failed（主线程上）。title 是空白时什么也不做。
func (h *Hub) Rename(s hostlink.Session, title string, failed func(error)) {
	if strings.TrimSpace(title) == "" || s.ID == "" {
		return
	}
	old := s.Title
	h.patch(s, func(x *hostlink.Session) { x.Title = title })
	core := h.sessionsCall(s.Backend)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		written, err := core.RenameSession(ctx, s.ID, title)
		return func() {
			h.patch(s, func(x *hostlink.Session) {
				switch {
				case x.Title != title: // 这期间又改过的不动
				case err != nil:
					x.Title = old
				default:
					x.Title = written
				}
			})
			if err != nil {
				failed(err)
			}
		}
	})
}

// SetArchived 归档（archived 为真）或取消归档 list：只是列表里显不显示，会话记录不动；归档时核心顺手关掉它在 tmux 里的窗口。
// 归档的里面有聊天窗格正开着的，聊天窗格关掉它（还在进行的在核心里接着跑完）。没改成的改回去，原因交给 failed。
func (h *Hub) SetArchived(list []hostlink.Session, archived bool, failed func(error)) {
	list = slices.DeleteFunc(slices.Clone(list), func(s hostlink.Session) bool { return s.Archived == archived || s.ID == "" })
	if len(list) == 0 {
		return
	}
	for _, s := range list {
		if archived && h.chat != nil && h.showing(s.Backend, s.ID) {
			h.CloseChat()
		}
		h.patch(s, func(x *hostlink.Session) { x.Archived = archived })
	}
	h.each(list, func(ctx context.Context, core *coreapi.Client, s hostlink.Session) error {
		return core.ArchiveSession(ctx, s.ID, archived)
	}, func(s hostlink.Session, err error) {
		if err != nil {
			h.patch(s, func(x *hostlink.Session) { x.Archived = !archived })
		}
	}, failed)
}

// ErrActive：要删的会话正在进行，核心没删（sessions.delete 答 code active）。
var ErrActive = errors.New("对话正在进行，结束后再删")

// Delete 删掉 list 里的会话：会话记录连同 Claude Code 存在同名目录里的东西（核心顺手结束开着它的进程、关掉 tmux 里的窗口）。
// 能不能删由核心定，这边不预先筛（docs/core.md「会话」的 sessions.delete）：正在进行的它不删，答 active，这边换成 ErrActive。
// 列表里、新回答的标记马上去掉，删成了的聊天窗格开着时关掉；答 active 的核心没动，照原样放回列表；别的没删成的不知道删到哪一步，
// 收尾时那几台各重订一次列表、拿整份对上。原因交给 failed（几个里有没删成的时包着第一个原因）。
func (h *Hub) Delete(list []hostlink.Session, failed func(error)) {
	list = slices.DeleteFunc(slices.Clone(list), func(s hostlink.Session) bool { return s.ID == "" })
	if len(list) == 0 {
		return
	}
	restore := map[string]func(){}
	for _, s := range list {
		restore[Key(s)] = h.forget(s)
	}
	stale := map[string]bool{} // 要重订列表的设备
	h.each(list, func(ctx context.Context, core *coreapi.Client, s hostlink.Session) error {
		_, err := core.DeleteSession(ctx, s.ID)
		switch code, _ := hostlink.ReplyCode(err); code {
		case sessionproto.CodeNoSession:
			return nil // 已经不在了
		case sessionproto.CodeActive:
			return ErrActive
		}
		return err
	}, func(s hostlink.Session, err error) {
		switch {
		case errors.Is(err, ErrActive):
			restore[Key(s)]()
		case err != nil:
			stale[s.Backend] = true
		case h.chat != nil && h.showing(s.Backend, s.ID):
			h.CloseChat()
		}
	}, func(err error) {
		// 各个的结果都交过 settle 才到这里
		for _, id := range slices.Sorted(maps.Keys(stale)) {
			h.rewatch(id)
		}
		failed(err)
	})
}

// each 在后台对 list 里的会话各做一次 do（见 fanOut），每个的结果交给主线程上的 settle，最后把没成的说成一个错误交给 failed。
func (h *Hub) each(list []hostlink.Session, do func(ctx context.Context, core *coreapi.Client, s hostlink.Session) error,
	settle func(s hostlink.Session, err error), failed func(error)) {
	calls := callers(list, h.sessionsCall)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		errs := fanOut(list, func(s hostlink.Session) error { return do(ctx, calls[s.Backend], s) })
		return func() {
			bad := 0
			var first error
			for i, err := range errs {
				settle(list[i], err)
				if err != nil {
					bad++
					first = cmp.Or(first, err)
				}
			}
			switch {
			case first == nil:
			case len(list) == 1:
				failed(first)
			default:
				failed(fmt.Errorf("%d 个没成功：%w", bad, first))
			}
		}
	})
}

// perDevice 是批量操作时每台设备最多同时发几个请求。
const perDevice = 4

// fanOut 对 list 里的会话各做一次 do 并等它们做完：每台设备最多 perDevice 个同时做，几台之间互不等。
// 第 i 个结果是 list[i] 的。阻塞，在后台调用。
func fanOut[T any](list []hostlink.Session, do func(s hostlink.Session) T) []T {
	out := make([]T, len(list))
	slots := map[string]chan struct{}{}
	for _, s := range list {
		if slots[s.Backend] == nil {
			slots[s.Backend] = make(chan struct{}, perDevice)
		}
	}
	var wg sync.WaitGroup
	for i, s := range list {
		slot := slots[s.Backend]
		wg.Go(func() {
			slot <- struct{}{}
			defer func() { <-slot }()
			out[i] = do(s)
		})
	}
	wg.Wait()
	return out
}

// rewatch 重新订阅设备 id 的会话列表，拿一份整份的（操作没成、要和那边对上时）。
func (h *Hub) rewatch(id string) {
	if _, ok := h.watches[id]; ok {
		h.unwatch(id)
		h.watch(id)
	}
}

// Exported 是导出成的一份 Markdown：存成的文件名和内容。
type Exported struct {
	Name, Content string
}

// Export 把 list 导出成 Markdown（照 Eta 的格式），交给主线程上的 done。每台设备发一次 sessions.export（带 sessions），
// 各台答来的各个会话的导出（parts）按 list 的先后排好，用 sessionproto.CombineExports 合成，文件名用 sessionproto.ExportFileName，
// 时刻是 now（utc_offset 也照它的时区）。只选了一台、一个会话时也是这样。导不出来的跳过，一个都没导出来时把原因交给 failed。
func (h *Hub) Export(list []hostlink.Session, now time.Time, done func(Exported), failed func(error)) {
	if len(list) == 0 {
		return
	}
	var heads []hostlink.Session // 每台一个，按第一次出现的先后
	ids := map[string][]string{} // 设备编号 → 要导出的会话号
	for _, s := range list {
		if ids[s.Backend] == nil {
			heads = append(heads, s)
		}
		ids[s.Backend] = append(ids[s.Backend], s.ID)
	}
	_, offset := now.Zone()
	calls := callers(heads, h.bulkCall)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		type result struct {
			parts []sessionproto.ExportPart
			err   error
		}
		results := fanOut(heads, func(s hostlink.Session) (r result) {
			answer, err := calls[s.Backend].ExportSessions(ctx, ids[s.Backend], offset/60)
			return result{answer.Parts, err}
		})
		got := map[string]sessionproto.ExportPart{} // Key → 那个会话的导出
		var first error
		for i, r := range results {
			first = cmp.Or(first, r.err)
			for _, p := range r.parts {
				got[Key(hostlink.Session{Backend: heads[i].Backend, ID: p.Session})] = p
			}
		}
		var parts []sessionproto.ExportPart
		for _, s := range list {
			if p, ok := got[Key(s)]; ok {
				parts = append(parts, p)
				delete(got, Key(s)) // 同一个只算一次
			}
		}
		return func() {
			if len(parts) == 0 {
				failed(cmp.Or(first, error(errNothingExported)))
				return
			}
			title, markdown := sessionproto.CombineExports(parts)
			done(Exported{Name: sessionproto.ExportFileName(title, now), Content: markdown})
		}
	})
}

// errNothingExported：几台都答了，可一个会话都没导出来（不该发生：核心一个都导不出来时自己答出错）。
var errNothingExported = errors.New("没有导出任何对话")
