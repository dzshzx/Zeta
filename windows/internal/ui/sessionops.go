package ui

import (
	"cmp"
	"errors"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// 侧栏对会话的操作：右键菜单（或行尾的「…」）里置顶、重命名、导出、归档、结束对话（还在运行的），删除单独一组。多选照 Windows 资源管理器：
// Ctrl+点击加选或去掉一个，Shift+点击从上次点的那个选到这个，焦点在侧栏列表上时 Ctrl+A 全选眼前列着的（多选时右键菜单
// 最上面也有「全选」）；在选中的行上右键是对这几个一起归档、导出、结束、删除，Delete 删除选中的，Esc 或不带键地点一下别的行
// 取消选择。归档的会话收在底部的入口里单独看。事情交给中枢，没成时弹框说原因。

// sessionOps 是侧栏这些操作的界面状态。
type sessionOps struct {
	selected map[string]bool // 多选选中的（hub.Key）
	anchor   string          // 上次不带 Shift 点的那个（hub.Key），Shift+点击从它选起
	archived bool            // 侧栏在看归档
	// moreOpen 是行尾「…」的菜单开着的那一行（hub.Key）：按钮平时只在悬停时出现，菜单开着时也要照建，
	// 不然鼠标一移到菜单上行就不算悬停，按钮不建，mygo 连菜单一起收掉。moreSeen 是这一帧建到了菜单开着的那一行：
	// 一帧建完没建到（菜单关了、行被搜索滤掉、侧栏收起、进了设置）就清掉 moreOpen（App.frame），免得那一行再出现时菜单自己弹开
	moreOpen string
	moreSeen bool

	renaming struct {
		target hostlink.Session
		draft  string
		open   bool
	}
	// deleting、batch 是删除一个、几个的确认框。正在进行的删不删由核心定（hub.Delete），这边不筛。
	deleting struct {
		target hostlink.Session
		open   bool
	}
	batch struct {
		list []hostlink.Session
		open bool
	}
	// ending 是结束对话的确认框（结束一律先问）；busy：其中有还在进行或在别处运行的，打开时定下（endStopsWork）。
	ending struct {
		list []hostlink.Session
		busy bool
		open bool
	}
}

// sessionMenu 是一行会话的右键菜单。
func (a *App) sessionMenu(m *ui.Menu, s hostlink.Session) {
	if chosen := a.chosen(); len(chosen) > 1 && a.ops.selected[hub.Key(s)] {
		a.batchMenu(m, chosen)
		return
	}
	if len(a.ops.selected) > 0 {
		a.selectAllItem(m) // 多选着（哪怕只选了一个）时最上面是全选
	}
	pin := locales.SessionPin()
	if a.hub.Pinned(s) {
		pin = locales.SessionUnpin()
	}
	if m.Item(pin).Shortcut(ui.Ctrl|ui.Alt, ui.KeyP).Chosen() {
		a.togglePin(s)
	}
	if m.Item(locales.SessionRename()).Shortcut(ui.Ctrl|ui.Alt, ui.KeyR).Chosen() {
		a.rename(s)
	}
	if m.Item(locales.SessionExport()).Chosen() {
		a.export([]hostlink.Session{s})
	}
	archive := locales.SessionArchive()
	if s.Archived {
		archive = locales.SessionUnarchive()
	}
	if m.Item(archive).Shortcut(ui.Ctrl|ui.Shift, ui.KeyA).Chosen() {
		a.archive(s)
	}
	if !s.Ended && m.Item(locales.SessionEnd()).Chosen() {
		a.end([]hostlink.Session{s})
	}
	m.Separator()
	// 正在进行的也不置灰：能不能删以核心的应答为准（hub.Delete）
	if m.Item(locales.CommonDelete()).Danger().Chosen() {
		a.ops.deleting.target, a.ops.deleting.open = s, true
	}
}

// failed 是操作没成时弹框说一声的办法。
func (a *App) failed(title string) func(error) {
	return func(err error) { a.say(title, err.Error()) }
}

// deleteFailed 是删除没成时弹框说一声：核心答正在进行的（有一个就算）说「这个对话正在进行」。
func (a *App) deleteFailed(err error) {
	title := locales.SessionDeleteFailed()
	if errors.Is(err, hub.ErrActive) {
		title = locales.SessionActive()
	}
	a.say(title, err.Error())
}

// export 导出 list：中枢取来 Markdown，经 saveFile 选好地方存下。几个一起导出、存好了退出多选。
func (a *App) export(list []hostlink.Session) {
	if len(list) == 0 {
		return
	}
	many := len(list) > 1
	a.hub.Export(list, a.now(), func(e hub.Exported) {
		a.async(func() func() {
			path, err := a.saveFile(locales.SessionExportDialogTitle(), e.Name, []mygo.FileFilter{{Name: "Markdown", Extensions: []string{"md"}}}, []byte(e.Content))
			return func() {
				switch {
				case err != nil:
					a.say(locales.SessionExportFailed(), err.Error())
				case path != "" && many:
					a.clearSelection()
				}
			}
		})
	}, a.failed(locales.SessionExportFailed()))
}

// clearSelection 取消多选。
func (a *App) clearSelection() { a.ops.selected = nil }

// visible 是侧栏眼前这份列表（搜索结果、归档或几组会话），按显示的先后。
func (a *App) visible() []hostlink.Session {
	h := a.hub
	switch {
	case h.Searching():
		return h.Results(a.currentSession(), a.now())
	case a.ops.archived:
		return h.Archived(a.now(), a.currentSession())
	}
	g := h.Groups(a.now(), a.currentSession())
	return slices.Concat(g.Pinned, g.Today, g.Earlier, g.Chats)
}

// chosen 是选中的会话，照侧栏所选的排序（hub.Ordered，置顶的不提前；开着的会话不在列着的设备上时排在最后）；已经不在的不算。侧栏每帧算一次，传给要用的地方。
func (a *App) chosen() []hostlink.Session {
	var out []hostlink.Session
	seen := map[string]bool{}
	pick := func(s hostlink.Session) {
		if key := hub.Key(s); s.ID != "" && a.ops.selected[key] && !seen[key] {
			seen[key] = true
			out = append(out, s)
		}
	}
	current := a.currentSession()
	for _, s := range a.hub.Ordered(a.now(), current) {
		pick(s)
	}
	pick(current) // 不在列着的设备上时 Ordered 里没有它
	return out
}

// clickRow 是点了会话 s 的那一行：Ctrl 加选或去掉它（头一回加选时先把开着的那个算进去），Shift 从 anchor 选到它，
// 不带键是取消多选、打开它。
func (a *App) clickRow(s hostlink.Session, mods ui.Modifiers) {
	o := &a.ops
	key := hub.Key(s)
	switch {
	case mods&ui.Cmd != 0:
		if len(o.selected) == 0 {
			o.selected = map[string]bool{}
			if cur := a.currentSession(); cur.ID != "" {
				o.selected[hub.Key(cur)] = true
			}
		}
		if o.selected[key] {
			delete(o.selected, key)
		} else {
			o.selected[key] = true
		}
		o.anchor = key
	case mods&ui.Shift != 0:
		a.selectRange(key)
	default:
		a.clearSelection()
		o.anchor = key
		a.openSession(s)
	}
}

// selectRange 选上侧栏列表里从 anchor（没有时是开着的那个，再没有时是第一个）到 key 的那一段，别的去掉。
func (a *App) selectRange(key string) {
	list := a.visible()
	index := func(k string) int {
		return slices.IndexFunc(list, func(s hostlink.Session) bool { return hub.Key(s) == k })
	}
	from, to := index(a.ops.anchor), index(key)
	if from < 0 {
		from = index(hub.Key(a.currentSession()))
	}
	if from < 0 || to < 0 {
		from = max(to, 0)
	}
	if from > to {
		from, to = to, from
	}
	a.ops.selected = map[string]bool{}
	for _, s := range list[from : to+1] {
		a.ops.selected[hub.Key(s)] = true
	}
}

// selectAll 选上侧栏眼前列着的全部会话（Ctrl+A、多选菜单的「全选」）：搜索时是搜索结果，看归档时是归档的。
// 照资源管理器换掉原来的选择，不留眼前列表之外的（比如搜索前选的），免得批量操作碰到看不见的。
func (a *App) selectAll() {
	selected := map[string]bool{}
	for _, s := range a.visible() {
		if s.ID != "" {
			selected[hub.Key(s)] = true
		}
	}
	if len(selected) > 0 {
		a.ops.selected = selected
	}
}

// allSelected：眼前列着的会话都选上了。
func (a *App) allSelected() bool {
	return !slices.ContainsFunc(a.visible(), func(s hostlink.Session) bool { return s.ID != "" && !a.ops.selected[hub.Key(s)] })
}

// selectAllItem 是多选时右键菜单最上面的「全选」和一条分隔线；眼前列着的都选上了时灰着。
func (a *App) selectAllItem(m *ui.Menu) {
	if m.Item(locales.SessionSelectAll()).Shortcut(ui.Cmd, ui.KeyA).Disabled(a.allSelected()).Chosen() {
		a.selectAll()
	}
	m.Separator()
}

// batchMenu 是在选中的几行上右键的菜单，管选中的 chosen：全选，归档或取消归档（选中的全是归档的才是取消归档）、导出、
// 结束（其中还在运行的，有才列）、删除。
func (a *App) batchMenu(m *ui.Menu, chosen []hostlink.Session) {
	a.selectAllItem(m)
	n := len(chosen)
	archive := slices.ContainsFunc(chosen, func(s hostlink.Session) bool { return !s.Archived })
	label, failed := locales.SessionBatchArchive(n), locales.SessionArchiveFailed()
	if !archive {
		label, failed = locales.SessionBatchUnarchive(n), locales.SessionUnarchiveFailed()
	}
	if m.Item(label).Chosen() {
		a.hub.SetArchived(chosen, archive, a.failed(failed))
		a.clearSelection()
	}
	if m.Item(locales.SessionBatchExport(n)).Chosen() {
		a.export(chosen)
	}
	if running := runningOf(chosen); len(running) > 0 && m.Item(locales.SessionBatchEnd(len(running))).Chosen() {
		a.end(running)
		a.clearSelection()
	}
	m.Separator()
	if m.Item(locales.SessionBatchDelete(n)).Shortcut(0, ui.KeyDelete).Danger().Chosen() {
		a.ops.batch.list, a.ops.batch.open = chosen, true
	}
}

// selectionKeys 是有选中的会话时的键：Delete 问删不删选中的，Esc 取消选择。
func (a *App) selectionKeys(c *ui.Context) {
	if len(a.ops.selected) == 0 {
		return
	}
	c.OnWindowShortcut(0, ui.KeyDelete, func() {
		if chosen := a.chosen(); len(chosen) > 0 {
			a.ops.batch.list, a.ops.batch.open = chosen, true
		}
	})
	c.OnWindowShortcut(0, ui.KeyEscape, a.clearSelection)
}

// archiveToggle 是侧栏底部归档的入口（同手机版 ArchiveToggle）：看归档时是返回全部对话，有归档的会话时是进归档，都没有时不显示。
func (a *App) archiveToggle(c *ui.Context) {
	if a.ops.archived {
		widget.IconButton(c, widget.IconBack, locales.SessionBackToAll()).OnClick(func() { a.ops.archived = false })
		return
	}
	if n := len(a.hub.Archived(a.now(), a.currentSession())); n > 0 {
		widget.IconButton(c, widget.IconArchive, locales.SessionArchivedCount(n)).OnClick(func() { a.ops.archived = true })
	}
}

// archivedGroup 是看归档时的列表：一组「已归档 · N」，照常按时间排。
func (a *App) archivedGroup(c *ui.Context) {
	list := a.hub.Archived(a.now(), a.currentSession())
	if len(list) == 0 {
		a.ops.archived = false // 都取消归档了：回到全部对话
		paneMessage(c, locales.SessionNoArchived())
		return
	}
	sectionTitle(c, locales.SessionArchivedCount(len(list)), true)
	for _, s := range list {
		a.sessionRow(c, s, "", false)
	}
}

// sessionDialogs 是这些操作的对话框：重命名、删除一个、删除几个。
func (a *App) sessionDialogs(c *ui.Context) {
	o := &a.ops
	h := a.hub
	if r := &o.renaming; r.open {
		if widget.InputDialog(c, &r.open, locales.SessionRename(), "", &r.draft, locales.SessionRenamePlaceholder(), locales.CommonOk(), strings.TrimSpace(r.draft) != "") {
			h.Rename(r.target, r.draft, a.failed(locales.SessionRenameFailed()))
		}
	}
	if d := &o.deleting; d.open {
		if widget.Alert(c, &d.open, locales.SessionDeleteTitle(), locales.SessionDeleteMessage(deleteName(d.target)),
			widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.CommonDelete(), Style: widget.Destructive}) == 1 {
			h.Delete([]hostlink.Session{d.target}, a.deleteFailed)
		}
	}
	if b := &o.batch; b.open {
		if widget.Alert(c, &b.open, locales.SessionBatchDeleteTitle(len(b.list)), locales.SessionBatchDeleteMessage(),
			widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.CommonDelete(), Style: widget.Destructive}) == 1 {
			h.Delete(b.list, a.deleteFailed)
			a.clearSelection()
		}
	}
	if e := &o.ending; e.open {
		title := locales.SessionBatchEndTitle(len(e.list))
		if len(e.list) == 1 {
			title = locales.SessionEndTitle()
		}
		if widget.Alert(c, &e.open, title, endConfirmSummary(e.list, e.busy, a.occupantOf(e.list[0])),
			widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.SessionEndButton(), Style: widget.Destructive}) == 1 {
			h.End(e.list, a.failed(locales.SessionEndFailed()))
		}
	}
}

// runningOf 是 list 里还在运行（有活着的 Claude Code 进程）的会话。
func runningOf(list []hostlink.Session) []hostlink.Session {
	return slices.DeleteFunc(slices.Clone(list), func(s hostlink.Session) bool { return s.Ended })
}

// end 结束 list 里的对话（右键菜单的「结束对话」，同手机版的结束对话、终端里的 /exit）：一律先弹确认框（列表上看不出它在
// 做什么，一点就停掉不合适）。说明在打开时定下：其中有还在进行（在回答、等确认、有后台任务）或在别处运行的，说会一并停掉。
func (a *App) end(list []hostlink.Session) {
	a.ops.ending.list, a.ops.ending.busy, a.ops.ending.open = list, slices.ContainsFunc(list, a.endStopsWork), true
}

// endStopsWork：结束 s 会停掉正在做的事——它在别处运行（会停掉那边的进程），或还在进行。
func (a *App) endStopsWork(s hostlink.Session) bool {
	if s.External {
		return true
	}
	switch a.rowStatus(s, a.showing(s)).Activity {
	case hub.Waiting, hub.Answering, hub.Background:
		return true
	}
	return false
}

// endConfirmSummary 是结束确认框的说明（同手机版 endConfirmSummary、侧栏的 endPrompt）：在别处运行的连那边的进程一起
// 停掉，还在进行的（busy）一并停掉，闲着的只停掉背后的进程。o 是那边的样子（结束的正是开着的这个对话、核心查到了占着它的
// 进程时），不知道时为 nil，只说「别处」。
func endConfirmSummary(list []hostlink.Session, busy bool, o *sessionproto.Occupancy) string {
	var what string
	switch {
	case len(list) > 1 && busy:
		what = locales.SessionEndBusyMany()
	case len(list) > 1:
		what = locales.SessionEndIdleMany()
	case list[0].External:
		place := locales.SessionEndElsewhere()
		if o != nil {
			place = cmp.Or(occupantPlace(o.App, o.Device, o.Local), place)
		}
		what = cjkSpaced(locales.SessionEndExternal(place))
	case busy:
		what = locales.SessionEndBusy()
	default:
		what = locales.SessionEndIdle()
	}
	return locales.SessionEndConfirm(what)
}

// occupantOf 是占着会话 s 的进程的样子：只有 s 就是开着的对话、核心查到了占着它的进程时才知道，别的为 nil。
func (a *App) occupantOf(s hostlink.Session) *sessionproto.Occupancy {
	if p := a.pane; p != nil && p.state.Blocked && p.state.Session == s.ID {
		return p.state.Occupant
	}
	return nil
}

// cjkSpaced 在 s 里汉字和英文字母、数字相接处补一个空格（见 spaced）：拼进整句的地点（「work-pc 的终端里」）两边用。
func cjkSpaced(s string) string { return spaced(strings.Split(s, "")...) }

// deleteName 是删除确认框里这个对话的名字：标题或首句，最多 60 字。
func deleteName(s hostlink.Session) string {
	name := []rune(rowTitle(s))
	return string(name[:min(60, len(name))])
}

// togglePin 置顶或取消置顶 s（右键菜单、Ctrl+Alt+P）。
func (a *App) togglePin(s hostlink.Session) {
	failed := locales.SessionPinFailed()
	if a.hub.Pinned(s) {
		failed = locales.SessionUnpinFailed()
	}
	a.hub.TogglePin(s, a.failed(failed))
}

// rename 弹出给 s 改名的对话框（右键菜单、Ctrl+Alt+R）：先填着现在的标题，没有标题时是第一句话的前 40 字。
func (a *App) rename(s hostlink.Session) {
	r := &a.ops.renaming
	r.target, r.draft, r.open = s, rowTitle(s), true
	if s.Title == "" {
		first := []rune(hub.SessionLabel(s, i18n.Locale()))
		r.draft = string(first[:min(40, len(first))])
	}
}

// archive 归档或取消归档 s（右键菜单、Ctrl+Shift+A）。
func (a *App) archive(s hostlink.Session) {
	failed := locales.SessionArchiveFailed()
	if s.Archived {
		failed = locales.SessionUnarchiveFailed()
	}
	a.hub.SetArchived([]hostlink.Session{s}, !s.Archived, a.failed(failed))
}
