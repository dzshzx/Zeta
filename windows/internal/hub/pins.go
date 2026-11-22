package hub

import (
	"context"
	"log"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// 置顶（docs/core.md「会话」的 sessions.pin）：标记在各台的核心里，列表项带 pinned，几个客户端看到的是同一份；
// 置顶了却不在最新 300 个里的会话核心也列出来。

// Pinned：会话 s 置顶了（核心报的，或本机设置里还没迁过去的）。
func (h *Hub) Pinned(s hostlink.Session) bool { return s.Pinned || h.pins.has(s) }

// TogglePin 置顶或取消置顶会话 s（sessions.pin）：只是标记，会话记录不动。界面先改，核心改完经 sessions.watch 推来；
// 没改成时改回去，原因交给 failed（主线程上）。取消置顶时本机设置里还没迁过去的那一项也删掉。
func (h *Hub) TogglePin(s hostlink.Session, failed func(error)) {
	if s.ID == "" {
		return
	}
	pinned := !h.Pinned(s)
	if !pinned {
		h.dropLocalPins(s.Backend, []string{s.ID})
	}
	h.patch(s, func(x *hostlink.Session) { x.Pinned = pinned })
	h.each([]hostlink.Session{s}, func(ctx context.Context, call sessionsCaller, s hostlink.Session) error {
		_, err := call(ctx, "sessions.pin", map[string]any{"session": s.ID, "pinned": pinned})
		return err
	}, func(s hostlink.Session, err error) {
		if err != nil {
			h.patch(s, func(x *hostlink.Session) {
				if x.Pinned == pinned { // 这期间又改过的不动
					x.Pinned = !pinned
				}
			})
		}
	}, failed)
}

// 本机置顶的迁移：从前置顶只记在本机设置里（settings.Pins，键是 settings.PinKey），现在记在核心里。
// 每台设备的会话列表订阅连上（收到 zeta_hello）时，本机设置里还有这台的置顶就逐个发 sessions.pin，答成功或答 no_session 的
// 从本机设置里删掉；同一个进程里每台只迁一次，没迁成的留到下次启动再迁。迁完之前置顶取列表项的 pinned 和本机记的并集（Pinned）。
//
// 什么时候删：带这段迁移的 Windows 版发出后的下一次发版时删掉。连同 localPins、loadPins、migratePins、dropLocalPins、
// Hub.pins、Pinned 里的 h.pins.has、TogglePin 里的 dropLocalPins、greeted 里的 migratePins，和 settings 的 keyPins、Pins、
// SetPins、PinKey 及它们的测试（settings_test 里「置顶不跟着删」那句断言）一起删。到那时没连过的设备上的置顶会丢，
// 只是标记，会话不丢。

// localPins 是本机设置里还没迁到核心的置顶。
type localPins struct {
	keys  map[string]bool // settings.PinKey 的写法
	tried map[string]bool // 这个进程里迁过（或正在迁）的设备：没迁成的也不再试，等下次启动
}

// loadPins 读本机设置里记的置顶。
func loadPins(s *settings.Settings) localPins {
	p := localPins{keys: map[string]bool{}, tried: map[string]bool{}}
	for _, k := range s.Pins() {
		p.keys[k] = true
	}
	return p
}

func (p localPins) has(s hostlink.Session) bool {
	if len(p.keys) == 0 {
		return false
	}
	return p.keys[settings.PinKey(s.Backend, s.ID)]
}

// migratePins 把本机设置里设备 id 的置顶迁到它的核心：逐个发 sessions.pin，答成功或答 no_session（会话已经没了）的从本机设置里删掉，
// 别的出错留着，下次启动再迁。这台在这个进程里迁过或没有要迁的时什么也不做。
func (h *Hub) migratePins(id string) {
	if h.pins.tried[id] {
		return
	}
	prefix := settings.PinKey(id, "")
	var list []hostlink.Session
	for k := range h.pins.keys {
		if sid, ok := strings.CutPrefix(k, prefix); ok && sid != "" {
			list = append(list, hostlink.Session{Backend: id, ID: sid})
		}
	}
	if len(list) == 0 {
		return
	}
	slices.SortFunc(list, func(a, b hostlink.Session) int { return strings.Compare(a.ID, b.ID) })
	h.pins.tried[id] = true
	call := h.sessionsCall(id)
	h.background(sessionsTimeout, func(ctx context.Context) func() {
		errs := fanOut(list, func(s hostlink.Session) error {
			_, err := call(ctx, "sessions.pin", map[string]any{"session": s.ID, "pinned": true})
			return err
		})
		return func() {
			var done []string
			for i, err := range errs {
				s := list[i]
				code, _ := hostlink.ReplyCode(err)
				switch {
				case err == nil:
					h.patch(s, func(x *hostlink.Session) { x.Pinned = true }) // 不等核心推来，免得闪一下
				case code != sessionproto.CodeNoSession:
					log.Printf("zeta: 置顶没迁到「%s」：%v", h.Name(id), err)
					continue
				}
				done = append(done, s.ID)
			}
			h.dropLocalPins(id, done)
		}
	})
}

// dropLocalPins 把设备 id 的会话 sessions 从本机设置的置顶里删掉。
func (h *Hub) dropLocalPins(id string, sessions []string) {
	drop := map[string]bool{}
	for _, sid := range sessions {
		if k := settings.PinKey(id, sid); h.pins.keys[k] {
			drop[k] = true
			delete(h.pins.keys, k)
		}
	}
	if len(drop) == 0 {
		return
	}
	h.changed()
	logSave(h.settings.SetPins(slices.DeleteFunc(h.settings.Pins(), func(k string) bool { return drop[k] })))
}
