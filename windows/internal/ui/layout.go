package ui

import (
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 窗口的布局和快捷键（照 ChatGPT 的 Windows 客户端）：左边侧栏、右边当前页，中间的分隔线能拖，双击回到默认宽度；
// Ctrl+B 收起、展开侧栏。宽度和收没收起存在设置里（拖的时候先记在内存，关窗口时存，见 SaveLayout）。

// 侧栏能拖到的最窄、最宽。
const (
	sidebarMin = 220
	sidebarMax = 480
)

// layout 是窗口布局的状态。
type layout struct {
	width  float32 // 侧栏宽度
	hidden bool    // 侧栏收起来了
	dirty  bool    // 拖过，还没存
	// searchFocus：下一帧把焦点放进侧栏的搜索框（Ctrl+K）。
	searchFocus bool
}

// loadLayout 从设置读侧栏的宽度和收没收起。
func (a *App) loadLayout() {
	s := a.hub.Settings()
	a.layout = layout{width: s.SidebarWidth(), hidden: s.SidebarHidden()}
}

// SaveLayout 存拖过的侧栏宽度（关窗口时调）。
func (a *App) SaveLayout() {
	if a.layout.dirty {
		a.hub.Settings().SetSidebarWidth(a.layout.width)
		a.layout.dirty = false
	}
}

// toggleSidebar 收起或展开侧栏，马上存。
func (a *App) toggleSidebar() {
	a.layout.hidden = !a.layout.hidden
	a.hub.Settings().SetSidebarHidden(a.layout.hidden)
}

// frame 排侧栏和右边的页 main：侧栏开着时两者之间是能拖的分隔线；收起时 main 占满，左上角浮着展开侧栏和新对话两个按钮。
func (a *App) frame(c *ui.Context, main func()) {
	l := &a.layout
	if l.hidden {
		ui.Box(c).Fill().Children(func() {
			ui.Column(c).Fill().Children(main)
			ui.Row(c).Absolute().Top(10).Left(10).Gap(2).Children(func() {
				coui.IconButton(c, coui.IconSidebar, "展开侧栏 (Ctrl+B)").OnClick(a.toggleSidebar)
				if len(a.hub.Hosts()) > 0 {
					coui.IconButton(c, coui.IconNewChat, "新对话 (Ctrl+N)").OnClick(a.newChat)
				}
			})
		})
		return
	}
	split := ui.Split(c, &l.width, func() { a.sidebar(c) }, func() {
		ui.Column(c).Fill().Children(main)
	}).Fill().SplitReset(settings.DefaultSidebarWidth)
	if split.Changed() {
		l.width = min(max(l.width, sidebarMin), sidebarMax)
		l.dirty = true
	}
}

// shortcuts 是窗口的快捷键（键位同 ChatGPT 的 Windows 客户端）。对话框开着时都不响（OnWindowShortcut 的规矩）。
func (a *App) shortcuts(c *ui.Context) {
	c.OnWindowShortcut(ui.Cmd, ui.KeyN, a.newChat)
	c.OnWindowShortcut(ui.Cmd, ui.KeyComma, func() { a.open(pageSettings) })
	c.OnWindowShortcut(ui.Cmd, ui.KeyB, a.toggleSidebar)
	if len(a.hub.Hosts()) == 0 {
		return
	}
	c.OnWindowShortcut(ui.Cmd, ui.KeyK, func() {
		if a.layout.hidden {
			a.toggleSidebar()
		}
		a.layout.searchFocus = true
	})
	c.OnWindowShortcut(ui.Ctrl, ui.KeyTab, func() { a.stepSession(1) })
	c.OnWindowShortcut(ui.Ctrl|ui.Shift, ui.KeyTab, func() { a.stepSession(-1) })
	for i, key := range []ui.Key{ui.Key1, ui.Key2, ui.Key3, ui.Key4, ui.Key5, ui.Key6, ui.Key7, ui.Key8, ui.Key9} {
		c.OnWindowShortcut(ui.Cmd, key, func() { a.openNth(i) })
	}
	c.OnWindowShortcut(ui.Ctrl|ui.Alt, ui.KeyA, a.nextWaiting)
	s := a.currentSession()
	if s.ID == "" {
		return
	}
	c.OnWindowShortcut(ui.Ctrl|ui.Shift, ui.KeyA, func() { a.archive(s) })
	c.OnWindowShortcut(ui.Ctrl|ui.Alt, ui.KeyP, func() { a.togglePin(s) })
	c.OnWindowShortcut(ui.Ctrl|ui.Alt, ui.KeyR, func() { a.rename(s) })
}

// openSession 在聊天窗格里打开会话 s。
func (a *App) openSession(s hostlink.Session) {
	a.page = pageHome
	a.query = ""
	a.hub.OpenSession(s)
}

// stepSession 打开侧栏列表里当前会话的下一个（step 1）或上一个（-1），到头了绕回去；没开着列表里的会话时从头或尾开始。
func (a *App) stepSession(step int) {
	list := a.visible()
	if len(list) == 0 {
		return
	}
	at := -1
	for i, s := range list {
		if a.showing(s) {
			at = i
		}
	}
	switch {
	case at < 0 && step > 0:
		at = 0
	case at < 0:
		at = len(list) - 1
	default:
		at = (at + step + len(list)) % len(list)
	}
	a.openSession(list[at])
}

// openNth 打开侧栏列表里第 i 个（从 0 数）会话，没有那么多时什么也不做。
func (a *App) openNth(i int) {
	if list := a.visible(); i < len(list) {
		a.openSession(list[i])
	}
}

// nextWaiting 打开当前会话之后、下一个等你回应（确认、提问）的会话，绕到头再找；没有就什么也不做。
func (a *App) nextWaiting() {
	list := a.visible()
	at := -1
	for i, s := range list {
		if a.showing(s) {
			at = i
		}
	}
	for n := 1; n <= len(list); n++ {
		s := list[(at+n+len(list))%len(list)]
		if !a.showing(s) && a.hub.Status(s, hub.Local{}).Activity == hub.Waiting {
			a.openSession(s)
			return
		}
	}
}
