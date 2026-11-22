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
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// 侧栏对会话的操作（同 Kotlin 版的 SessionList、SessionTools）：改名、归档、删除、导出，和取回不在最近 300 个里的置顶会话。
// 都是那台核心的 sessions.* 方法（docs/core.md「会话」），走给对话用的那条 serve 连接。界面先改，操作马上看得到；
// 没成时改回去，原因交给调用方。改完的结果核心经 sessions.watch 推来，不另外重读。

// sessionsTimeout 是一次 sessions.* 最多等多久（删除、导出要读写大的会话记录）。
const sessionsTimeout = 2 * time.Minute

// sessionsCall 是在后台对设备 backend 的核心发一个 sessions.* 请求的办法。在主线程上取，在后台调用。
// 不是核心答的出错（连不上）时，一次都没连上的连接丢掉，下次重新连。
func (h *Hub) sessionsCall(backend string) func(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
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

// background 在后台跑 work（带 sessionsTimeout 的时限），它返回的函数交回主线程跑。
func (h *Hub) background(work func(ctx context.Context) func()) {
	h.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), sessionsTimeout)
		apply := work(ctx)
		cancel()
		h.post(apply)
	})
}

// all 是列着的会话加上单独取来的（far），新的在前；同一个会话只算一次。
func (h *Hub) all() []hostlink.Session {
	var extra []hostlink.Session
	for _, list := range h.far {
		for _, s := range list {
			if !slices.ContainsFunc(h.sessions, func(x hostlink.Session) bool { return x.Backend == s.Backend && x.ID == s.ID }) {
				extra = append(extra, s)
			}
		}
	}
	if len(extra) == 0 {
		return h.sessions
	}
	return byTime(append(slices.Clone(h.sessions), extra...))
}

// patch 改列表里（连同单独取来的、聊天窗格开着的）会话 s 的样子。
func (h *Hub) patch(s hostlink.Session, change func(x *hostlink.Session)) {
	same := func(x hostlink.Session) bool { return x.Backend == s.Backend && x.ID == s.ID }
	for i := range h.sessions {
		if same(h.sessions[i]) {
			change(&h.sessions[i])
		}
	}
	for _, list := range h.far {
		for i := range list {
			if same(list[i]) {
				change(&list[i])
			}
		}
	}
	if c := h.chat; c != nil && same(c.Session) {
		change(&c.Session)
	}
}

// forget 把会话 s 从列表、单独取来的、置顶和新回答的标记里去掉。
func (h *Hub) forget(s hostlink.Session) {
	same := func(x hostlink.Session) bool { return x.Backend == s.Backend && x.ID == s.ID }
	h.sessions = slices.DeleteFunc(h.sessions, same)
	h.far[s.Backend] = slices.DeleteFunc(h.far[s.Backend], same)
	key := pinKey(s)
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
	h.background(func(ctx context.Context) func() {
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

type sessionsCaller = func(ctx context.Context, method string, params map[string]any) (json.RawMessage, error)

// each 在后台对 list 里的会话一个个做 do（各台用各自的连接），没成的交给主线程上的 undo，最后把没成的说成一个错误交给 failed。
func (h *Hub) each(list []hostlink.Session, do func(ctx context.Context, call sessionsCaller, s hostlink.Session) error,
	undo func(hostlink.Session), failed func(error)) {
	calls := map[string]sessionsCaller{}
	for _, s := range list {
		if calls[s.Backend] == nil {
			calls[s.Backend] = h.sessionsCall(s.Backend)
		}
	}
	h.background(func(ctx context.Context) func() {
		var bad []hostlink.Session
		var first error
		for _, s := range list {
			if err := do(ctx, calls[s.Backend], s); err != nil {
				bad = append(bad, s)
				if first == nil {
					first = err
				}
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
// 几个会话各自照原样、中间用分隔线隔开，文件名写「N个对话」。时刻用本机的 now。导不出来的跳过，一个都没导出来时把原因交给 failed。
func (h *Hub) Export(list []hostlink.Session, now time.Time, done func(Exported), failed func(error)) {
	calls := map[string]sessionsCaller{}
	for _, s := range list {
		if calls[s.Backend] == nil {
			calls[s.Backend] = h.sessionsCall(s.Backend)
		}
	}
	if len(list) == 0 {
		return
	}
	h.background(func(ctx context.Context) func() {
		var parts []export
		var first error
		for _, s := range list {
			raw, err := calls[s.Backend](ctx, "sessions.export", map[string]any{"session": s.ID})
			var e export
			if err == nil {
				err = json.Unmarshal(raw, &e)
			}
			if err != nil {
				first = cmp.Or(first, err)
				continue
			}
			parts = append(parts, e)
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

// syncFar 取回设备 id 上置顶、却不在列表（最近 300 个）里的会话（sessions.lookup），放进侧栏的置顶组。
// 问过的不再问；没问成的下次那台推整份列表时再问。那边确实已经没有的（别处删掉了），置顶去掉。
func (h *Hub) syncFar(id string) {
	var missing []string
	for key := range h.pins {
		backend, sid, ok := strings.Cut(key, "/")
		if !ok || backend != id || h.farAsked[key] {
			continue
		}
		if slices.ContainsFunc(h.sessions, func(s hostlink.Session) bool { return s.Backend == id && s.ID == sid }) {
			continue
		}
		missing = append(missing, sid)
	}
	if len(missing) == 0 {
		return
	}
	slices.Sort(missing)
	for _, sid := range missing {
		h.farAsked[id+"/"+sid] = true
	}
	call := h.sessionsCall(id)
	h.background(func(ctx context.Context) func() {
		raw, err := call(ctx, "sessions.lookup", map[string]any{"sessions": missing})
		var found []hostlink.Session
		if err == nil {
			err = json.Unmarshal(raw, &found)
		}
		return func() {
			if err != nil {
				log.Printf("zeta: 没取到不在列表里的置顶会话：%v", err)
				for _, sid := range missing {
					delete(h.farAsked, id+"/"+sid)
				}
				return
			}
			if _, listed := h.watches[id]; !listed {
				return
			}
			for i := range found {
				found[i].Backend = id
			}
			h.far[id] = append(slices.DeleteFunc(h.far[id], func(s hostlink.Session) bool {
				return slices.ContainsFunc(found, func(f hostlink.Session) bool { return f.ID == s.ID })
			}), found...)
			for _, sid := range missing {
				if !slices.ContainsFunc(found, func(f hostlink.Session) bool { return f.ID == sid }) {
					h.forget(hostlink.Session{Backend: id, ID: sid})
				}
			}
		}
	})
}
