package ui

import (
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
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

// loadLayout 从设置读侧栏的宽度和收没收起，以及界面缩放。
func (a *App) loadLayout() {
	s := a.hub.Settings()
	a.layout = layout{width: s.SidebarWidth(), hidden: s.SidebarHidden()}
	a.zoom = zoomLevel(s.Zoom())
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
// 在设置里时侧栏是设置的分类，收起了也照样显示。
func (a *App) frame(c *ui.Context, main func()) {
	// 侧栏收起、进了设置、行被滤掉时没建到菜单开着的那一行，清掉记着的（见 sessionOps.moreOpen）
	a.ops.moreSeen = false
	defer func() {
		if !a.ops.moreSeen {
			a.ops.moreOpen = ""
		}
	}()
	l := &a.layout
	if l.hidden && !a.inSettings() {
		ui.Box(c).Fill().Children(func() {
			ui.Column(c).Fill().Children(main)
			ui.Row(c).Absolute().Top(10).Left(10).Gap(2).Children(func() {
				widget.IconButton(c, widget.IconSidebar, locales.LayoutShowSidebar()).OnClick(a.toggleSidebar)
				if len(a.hub.Hosts()) > 0 {
					widget.IconButton(c, widget.IconNewChat, locales.LayoutNewChat()).OnClick(a.newChat)
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

// shortcut 是窗口的一个快捷键：键、在设置「常规」里怎么写和做什么，按下时 run。needs 是什么时候才有：
// 0 一直有，needHosts 加过设备以后，needSession 开着一个会话时。
type shortcut struct {
	mods  ui.Modifiers
	key   ui.Key
	keys  string        // 设置里写的键，同一行管几个键时写在第一个上，别的空着
	label func() string // 按界面当前的语言取字；同一行管的别的键是 nil
	needs int
	run   func(a *App, s hostlink.Session)
}

const (
	needHosts = 1 + iota
	needSession
)

// shortcuts 是窗口的快捷键，键位照 ChatGPT Windows 客户端命令表里的默认键（安装包 app.asar 的 defaultKeybindings）；聊天页自己的（查找、停止）在 chatKeys。
var shortcuts = []shortcut{
	{ui.Cmd, ui.KeyN, "Ctrl+N / Ctrl+Shift+O", locales.CommonNewChat, 0, func(a *App, _ hostlink.Session) { a.newChat() }},
	{ui.Cmd | ui.Shift, ui.KeyO, "", nil, 0, func(a *App, _ hostlink.Session) { a.newChat() }},
	{ui.Cmd, ui.KeyK, "Ctrl+K", locales.ShortcutSearch, needHosts, func(a *App, _ hostlink.Session) { a.focusSearch() }},
	{ui.Cmd, ui.KeyComma, "Ctrl+,", locales.CommonSettings, 0, func(a *App, _ hostlink.Session) { a.open(pageSettings) }},
	{ui.Cmd, ui.KeyB, "Ctrl+B / Ctrl+Shift+S", locales.ShortcutToggleSidebar, 0, func(a *App, _ hostlink.Session) { a.toggleSidebar() }},
	{ui.Cmd | ui.Shift, ui.KeyS, "", nil, 0, func(a *App, _ hostlink.Session) { a.toggleSidebar() }},
	// ChatGPT 的上一个、下一个对话是 Ctrl+Alt+[ ] 和 Ctrl+Alt+← →，它的 Ctrl+Tab 切标签页；Zeta 没有标签页，Ctrl+Tab 也切对话
	{ui.Ctrl | ui.Alt, ui.KeyBracketRight, "Ctrl+Alt+] / Ctrl+Alt+→ / Ctrl+Tab", locales.ShortcutNextChat, needHosts, step(1)},
	{ui.Ctrl | ui.Alt, ui.KeyRight, "", nil, needHosts, step(1)},
	{ui.Ctrl, ui.KeyTab, "", nil, needHosts, step(1)},
	{ui.Ctrl | ui.Alt, ui.KeyBracketLeft, "Ctrl+Alt+[ / Ctrl+Alt+← / Ctrl+Shift+Tab", locales.ShortcutPreviousChat, needHosts, step(-1)},
	{ui.Ctrl | ui.Alt, ui.KeyLeft, "", nil, needHosts, step(-1)},
	{ui.Ctrl | ui.Shift, ui.KeyTab, "", nil, needHosts, step(-1)},
	{ui.Cmd, ui.Key1, "Ctrl+1 … 9", locales.ShortcutNthChat, needHosts, nth(0)},
	{ui.Cmd, ui.Key2, "", nil, needHosts, nth(1)},
	{ui.Cmd, ui.Key3, "", nil, needHosts, nth(2)},
	{ui.Cmd, ui.Key4, "", nil, needHosts, nth(3)},
	{ui.Cmd, ui.Key5, "", nil, needHosts, nth(4)},
	{ui.Cmd, ui.Key6, "", nil, needHosts, nth(5)},
	{ui.Cmd, ui.Key7, "", nil, needHosts, nth(6)},
	{ui.Cmd, ui.Key8, "", nil, needHosts, nth(7)},
	{ui.Cmd, ui.Key9, "", nil, needHosts, nth(8)},
	{ui.Ctrl | ui.Alt, ui.KeyA, "Ctrl+Alt+A", locales.ShortcutNextWaiting, needHosts, func(a *App, _ hostlink.Session) { a.nextWaiting() }},
	{ui.Ctrl | ui.Shift, ui.KeyA, "Ctrl+Shift+A", locales.ShortcutArchive, needSession, func(a *App, s hostlink.Session) { a.archive(s) }},
	{ui.Ctrl | ui.Alt, ui.KeyP, "Ctrl+Alt+P", locales.ShortcutPin, needSession, func(a *App, s hostlink.Session) { a.togglePin(s) }},
	{ui.Ctrl | ui.Alt, ui.KeyR, "Ctrl+Alt+R", locales.ShortcutRename, needSession, func(a *App, s hostlink.Session) { a.rename(s) }},
	// 缩放（zoom.go）：加号和等号是同一个键，按不按 Shift 都放大；小键盘的减号、0 也是这两个键，小键盘的加号 mygo 认不出
	{ui.Cmd, ui.KeyEqual, "Ctrl++", locales.ShortcutZoomIn, 0, func(a *App, _ hostlink.Session) { a.setZoom(zoomStep(a.zoom, 1)) }},
	{ui.Cmd | ui.Shift, ui.KeyEqual, "", nil, 0, func(a *App, _ hostlink.Session) { a.setZoom(zoomStep(a.zoom, 1)) }},
	{ui.Cmd, ui.KeyMinus, "Ctrl+-", locales.ShortcutZoomOut, 0, func(a *App, _ hostlink.Session) { a.setZoom(zoomStep(a.zoom, -1)) }},
	{ui.Cmd, ui.Key0, "Ctrl+0", locales.ShortcutZoomReset, 0, func(a *App, _ hostlink.Session) { a.setZoom(100) }},
}

func step(d int) func(a *App, _ hostlink.Session) {
	return func(a *App, _ hostlink.Session) { a.stepSession(d) }
}

func nth(i int) func(a *App, _ hostlink.Session) {
	return func(a *App, _ hostlink.Session) { a.openNth(i) }
}

// chatKeys 是只在焦点落在某处时才响的键（聊天页的在 chat.go、find.go 里接，侧栏列表的在 sidebar.go），只列在设置「常规」里。
// 侧栏列表的 Ctrl+A 照 ChatGPT 客户端多选列表的 Mod+A（全选，焦点在输入框里时不管）。
var chatKeys = []struct {
	keys  string
	label func() string
}{
	{"Ctrl+F", locales.ShortcutFind},
	{"Enter / Shift+Enter", locales.ShortcutFindStep},
	{"Ctrl+A", locales.ShortcutSelectAll},
}

// shortcuts 接上窗口的快捷键。对话框开着时都不响（OnWindowShortcut 的规矩）。
func (a *App) shortcuts(c *ui.Context) {
	a.selectionKeys(c)
	hosts := len(a.hub.Hosts()) > 0
	s := a.currentSession()
	for _, sc := range shortcuts {
		if sc.needs == needHosts && !hosts || sc.needs == needSession && s.ID == "" {
			continue
		}
		c.OnWindowShortcut(sc.mods, sc.key, func() { sc.run(a, s) })
	}
}

// focusSearch 把焦点放进侧栏的搜索框（在设置里时先回到对话，侧栏收着时先展开）。
func (a *App) focusSearch() {
	a.leaveSettings()
	if a.layout.hidden {
		a.toggleSidebar()
	}
	a.layout.searchFocus = true
}

// shortcutsSection 是设置「常规」里的快捷键：窗口的和聊天页的，一行一个，键在右边。
func shortcutsSection(c *ui.Context) {
	widget.Group(c, locales.ShortcutsTitle(), "", func(l *widget.List) {
		for _, sc := range shortcuts {
			if sc.keys != "" {
				l.Row(widget.Row{Key: sc.keys, Title: sc.label(), Value: sc.keys})
			}
		}
		for _, k := range chatKeys {
			l.Row(widget.Row{Key: k.keys, Title: k.label(), Value: k.keys})
		}
	})
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
