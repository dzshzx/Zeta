package hub

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// 侧栏对会话的操作（同 Kotlin 版的 SessionList、SessionTools）：改名、归档、删除、导出，和取回不在最近 300 个里的置顶会话。
// 都是那台核心的 sessions.* 方法（docs/core.md「会话」），走给对话用的那条 serve 连接（导出走取大块数据的那条，见 bulk.go）。
// 界面先改，操作马上看得到；没成时改回去，原因交给调用方。改完的结果核心经 sessions.watch 推来，不另外重读。

// sessionsTimeout 是一次 sessions.* 最多等多久（删除、导出要读写大的会话记录）。
const sessionsTimeout = 2 * time.Minute

// sessionsCaller 是在后台对一台设备的核心发一个 sessions.* 请求的办法。
type sessionsCaller = func(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)

// sessionsCall 是在后台对设备 backend 的核心发 sessions.* 请求的办法。在主线程上取，在后台调用。
// 不是核心答的出错（连不上）时，一次都没连上的连接丢掉，下次重新连。
func (h *Hub) sessionsCall(backend string) sessionsCaller {
	conn := h.chatConn(backend)
	return func(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
		raw, err := conn.Call(ctx, method, params)
		var reply *hostlink.ReplyError
		if err != nil && !errors.As(err, &reply) {
			h.post(func() { h.dropDeadConn(backend, conn) })
		}
		return raw, err
	}
}

// callers 是 list 涉及的各台设备发请求的办法（设备编号 → open 给的办法）。在主线程上取。
func callers(list []hostlink.Session, open func(backend string) sessionsCaller) map[string]sessionsCaller {
	calls := map[string]sessionsCaller{}
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

// forget 把会话 s 从列表、置顶和新回答的标记里去掉。
func (h *Hub) forget(s hostlink.Session) {
	h.sessions = slices.DeleteFunc(h.sessions, func(x hostlink.Session) bool { return sameSession(x, s) })
	delete(h.far[s.Backend], s.ID)
	h.changed()
	key := Key(s)
	delete(h.unread, key)
	if h.pins[key] {
		h.TogglePin(s)
	}
}

// Active：会话 s 正在进行（在回答、等确认、后台任务没完，这边开着的或核心报的），或正被别处的 Claude Code 开着。不能删。
func (h *Hub) Active(s hostlink.Session, local Local) bool {
	if s.External {
		return true
	}
	switch h.Status(s, local).Activity {
	case Waiting, Answering, Background:
		return true
	}
	return false
}

// CleanTitle 是改名时实际用的标题：空白压成一个空格、最多 200 字（同核心的 sessions.rename）。
func CleanTitle(title string) string {
	runes := []rune(strings.Join(strings.Fields(title), " "))
	if len(runes) > 200 {
		runes = runes[:200]
	}
	return strings.TrimSpace(string(runes))
}

// Rename 给会话 s 改名（sessions.rename；核心里开着它的对话，标题由核心跟着改）。没改成时改回去，原因交给 failed（主线程上）。
func (h *Hub) Rename(s hostlink.Session, title string, failed func(error)) {
	clean := CleanTitle(title)
	if clean == "" || s.ID == "" {
		return
	}
	old := s.Title
	h.patch(s, func(x *hostlink.Session) { x.Title = clean })
	undo := func(err error) {
		h.patch(s, func(x *hostlink.Session) {
			if x.Title == clean { // 这期间又改过的不动
				x.Title = old
			}
		})
		failed(err)
	}
	call := h.sessionsCall(s.Backend)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		_, err := call(ctx, "sessions.rename", map[string]any{"session": s.ID, "title": clean})
		return func() {
			if err != nil {
				undo(err)
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
	h.each(list, func(ctx context.Context, call sessionsCaller, s hostlink.Session) error {
		_, err := call(ctx, "sessions.archive", map[string]any{"session": s.ID, "archived": archived})
		return err
	}, func(s hostlink.Session) {
		h.patch(s, func(x *hostlink.Session) { x.Archived = !archived })
	}, failed)
}

// Delete 删掉 list 里的会话：会话记录连同 Claude Code 存在同名目录里的东西（核心顺手结束开着它的进程、关掉 tmux 里的窗口）。
// 正在进行的（Active）调用方先筛掉。聊天窗格正开着的关掉；列表里、置顶、新回答的标记马上去掉；
// 没删成的由重读那台的列表放回去，原因交给 failed。
func (h *Hub) Delete(list []hostlink.Session, failed func(error)) {
	list = slices.DeleteFunc(slices.Clone(list), func(s hostlink.Session) bool { return s.ID == "" })
	if len(list) == 0 {
		return
	}
	for _, s := range list {
		if h.chat != nil && h.showing(s.Backend, s.ID) {
			h.CloseChat()
		}
		h.forget(s)
	}
	h.each(list, func(ctx context.Context, call sessionsCaller, s hostlink.Session) error {
		_, err := call(ctx, "sessions.delete", map[string]any{"session": s.ID})
		var reply *hostlink.ReplyError
		if errors.As(err, &reply) && reply.Code() == "no_session" {
			return nil // 已经不在了
		}
		return err
	}, func(s hostlink.Session) { h.rewatch(s.Backend) }, failed)
}

// each 在后台对 list 里的会话各做一次 do（见 fanOut），没成的交给主线程上的 undo，最后把没成的说成一个错误交给 failed。
func (h *Hub) each(list []hostlink.Session, do func(ctx context.Context, call sessionsCaller, s hostlink.Session) error,
	undo func(hostlink.Session), failed func(error)) {
	calls := callers(list, h.sessionsCall)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		errs := fanOut(list, func(s hostlink.Session) error { return do(ctx, calls[s.Backend], s) })
		var bad []hostlink.Session
		var first error
		for i, err := range errs {
			if err != nil {
				bad = append(bad, list[i])
				first = cmp.Or(first, err)
			}
		}
		return func() {
			for _, s := range bad {
				undo(s)
			}
			switch {
			case first == nil:
			case len(list) == 1:
				failed(first)
			default:
				failed(fmt.Errorf("%d 个没成功：%w", len(bad), first))
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

// export 是 sessions.export 的结果（另有 file_name：核心按主机的本地时间建议的文件名，这边按本机时间自己起，不用它）。
type export struct {
	Title    string `json:"title"`
	Markdown string `json:"markdown"`
}

// Export 把 list 导出成 Markdown（照 Eta 的格式），交给主线程上的 done：一个会话是它的导出原样、文件名照它的标题；
// 几个会话各自照原样、按 list 的先后、中间用分隔线隔开，文件名写「N个对话」。时刻用本机的 now。导不出来的跳过，
// 一个都没导出来时把原因交给 failed。
func (h *Hub) Export(list []hostlink.Session, now time.Time, done func(Exported), failed func(error)) {
	if len(list) == 0 {
		return
	}
	calls := callers(list, h.bulkCall)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		type result struct {
			export
			err error
		}
		results := fanOut(list, func(s hostlink.Session) (r result) {
			raw, err := calls[s.Backend](ctx, "sessions.export", map[string]any{"session": s.ID})
			if err == nil {
				err = json.Unmarshal(raw, &r.export)
			}
			r.err = err
			return r
		})
		var parts []export
		var first error
		for _, r := range results {
			if r.err != nil {
				first = cmp.Or(first, r.err)
				continue
			}
			parts = append(parts, r.export)
		}
		return func() {
			if len(parts) == 0 {
				failed(first)
				return
			}
			done(combineExports(parts, now))
		}
	})
}

// combineExports 把几份导出合成一个文件（同 Kotlin 的 combineExports）。
func combineExports(parts []export, now time.Time) Exported {
	if len(parts) == 1 {
		return Exported{Name: ExportFileName(parts[0].Title, now), Content: parts[0].Markdown}
	}
	texts := make([]string, len(parts))
	for i, p := range parts {
		texts[i] = strings.TrimRight(p.Markdown, " \t\r\n") + "\n"
	}
	return Exported{Name: ExportFileName(fmt.Sprintf("%d个对话", len(parts)), now), Content: strings.Join(texts, "\n---\n\n")}
}

var fileNameUnsafe = regexp.MustCompile(`[\\/:*?"<>|\p{C}]`)

// ExportFileName 是导出文件的名字，照 Eta：Zeta-标题-年月日-时分.md；标题去掉文件名里不能用的字符、最多 40 字，空的写「对话」。
func ExportFileName(title string, now time.Time) string {
	clean := []rune(strings.TrimSpace(fileNameUnsafe.ReplaceAllString(strings.Join(strings.Fields(title), " "), "")))
	if len(clean) > 40 {
		clean = clean[:40]
	}
	name := strings.TrimSpace(string(clean))
	if name == "" {
		name = "对话"
	}
	return "Zeta-" + name + "-" + now.Format("20060102-1504") + ".md"
}

// syncFar 取回设备 id 上置顶、却不在列表（最近 300 个）里的会话（sessions.lookup），并进列表（侧栏的置顶组），
// 记在 far 里：之后那台推来的列表不再把它们拿掉。问过的不再问；没问成的下次那台推整份列表时再问。
// 那边确实已经没有的（别处删掉了），置顶去掉。
func (h *Hub) syncFar(id string) {
	var missing []string
	for key := range h.pins {
		backend, sid, ok := strings.Cut(key, "/")
		if !ok || backend != id || h.farAsked[id][sid] {
			continue
		}
		pinned := hostlink.Session{Backend: id, ID: sid}
		if !slices.ContainsFunc(h.sessions, func(s hostlink.Session) bool { return sameSession(s, pinned) }) {
			missing = append(missing, sid)
		}
	}
	if len(missing) == 0 {
		return
	}
	slices.Sort(missing)
	for _, sid := range missing {
		mark(h.farAsked, id, sid)
	}
	call := h.sessionsCall(id)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		raw, err := call(ctx, "sessions.lookup", map[string]any{"sessions": missing})
		var found []hostlink.Session
		if err == nil {
			err = json.Unmarshal(raw, &found)
		}
		return func() {
			if err != nil {
				log.Printf("zeta: 没取到不在列表里的置顶会话：%v", err)
				for _, sid := range missing {
					delete(h.farAsked[id], sid)
				}
				return
			}
			h.farFound(id, missing, found)
		}
	})
}

// farFound：问了设备 id 的会话 missing，找到了 found。找到的并进列表，没找到的（别处删掉了）置顶去掉。那台已经不列了时不管。
func (h *Hub) farFound(id string, missing []string, found []hostlink.Session) {
	if _, listed := h.watches[id]; !listed {
		return
	}
	added := false
	for _, s := range found {
		s.Backend = id
		mark(h.far, id, s.ID)
		if !slices.ContainsFunc(h.sessions, func(x hostlink.Session) bool { return sameSession(x, s) }) {
			h.sessions, added = append(h.sessions, s), true
		}
	}
	if added {
		h.sessions = byTime(h.sessions)
		h.changed()
	}
	for _, sid := range missing {
		if !slices.ContainsFunc(found, func(f hostlink.Session) bool { return f.ID == sid }) {
			h.forget(hostlink.Session{Backend: id, ID: sid})
		}
	}
}

// mark 在按设备分的集合 m 里记下设备 id 的会话 sid。
func mark(m map[string]map[string]bool, id, sid string) {
	if m[id] == nil {
		m[id] = map[string]bool{}
	}
	m[id][sid] = true
}
