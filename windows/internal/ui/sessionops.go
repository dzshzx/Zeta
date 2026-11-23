package ui

import (
	"errors"
	"slices"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// 侧栏对会话的操作：右键菜单（或行尾的「…」）里置顶、重命名、导出、归档，删除单独一组。多选照 Windows 资源管理器：
// Ctrl+点击加选或去掉一个，Shift+点击从上次点的那个选到这个；在选中的行上右键是对这几个一起归档、导出、删除，
// Delete 删除选中的，Esc 或不带键地点一下别的行取消选择。归档的会话收在底部的入口里单独看。事情交给中枢，
// 没成时弹框说原因。

// sessionOps 是侧栏这些操作的界面状态。
type sessionOps struct {
	selected map[string]bool // 多选选中的（hub.Key）
	anchor   string          // 上次不带 Shift 点的那个（hub.Key），Shift+点击从它选起
	archived bool            // 侧栏在看归档

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
}

// sessionMenu 是一行会话的右键菜单。
func (a *App) sessionMenu(m *ui.Menu, s hostlink.Session) {
	if chosen := a.chosen(); len(chosen) > 1 && a.ops.selected[hub.Key(s)] {
		a.batchMenu(m, chosen)
		return
	}
	pin := "置顶"
	if a.hub.Pinned(s) {
		pin = "取消置顶"
	}
	if m.Item(pin).Shortcut(ui.Ctrl|ui.Alt, ui.KeyP).Chosen() {
		a.togglePin(s)
	}
	if m.Item("重命名").Shortcut(ui.Ctrl|ui.Alt, ui.KeyR).Chosen() {
		a.rename(s)
	}
	if m.Item("导出").Chosen() {
		a.export([]hostlink.Session{s})
	}
	archive := "归档"
	if s.Archived {
		archive = "取消归档"
	}
	if m.Item(archive).Shortcut(ui.Ctrl|ui.Shift, ui.KeyA).Chosen() {
		a.archive(s)
	}
	m.Separator()
	// 正在进行的也不置灰：能不能删以核心的应答为准（hub.Delete）
	if m.Item("删除").Danger().Chosen() {
		a.ops.deleting.target, a.ops.deleting.open = s, true
	}
}

// failed 是操作没成时弹框说一声的办法。
func (a *App) failed(title string) func(error) {
	return func(err error) { a.say(title, err.Error()) }
}

// deleteFailed 是删除没成时弹框说一声：核心答正在进行的（有一个就算）说「这个对话正在进行」。
func (a *App) deleteFailed(err error) {
	title := "删除没成功"
	if errors.Is(err, hub.ErrActive) {
		title = "这个对话正在进行"
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
			path, err := a.saveFile("导出对话", e.Name, []mygo.FileFilter{{Name: "Markdown", Extensions: []string{"md"}}}, []byte(e.Content))
			return func() {
				switch {
				case err != nil:
					a.say("导出没成功", err.Error())
				case path != "" && many:
					a.clearSelection()
				}
			}
		})
	}, a.failed("导出没成功"))
}

// clearSelection 取消多选。
func (a *App) clearSelection() { a.ops.selected = nil }

// visible 是侧栏眼前这份列表（搜索结果、归档或置顶/今天/更早），按显示的先后。
func (a *App) visible() []hostlink.Session {
	h := a.hub
	switch {
	case h.Searching():
		return h.Results(a.currentSession(), a.now())
	case a.ops.archived:
		return h.Archived(a.now(), a.currentSession())
	}
	g := h.Groups(a.now(), a.currentSession())
	return slices.Concat(g.Pinned, g.Today, g.Earlier)
}

// chosen 是选中的会话，按列表里的先后（开着的会话还不在列表里时排在最后）；已经不在的不算。侧栏每帧算一次，传给要用的地方。
func (a *App) chosen() []hostlink.Session {
	var out []hostlink.Session
	seen := map[string]bool{}
	pick := func(s hostlink.Session) {
		if key := hub.Key(s); s.ID != "" && a.ops.selected[key] && !seen[key] {
			seen[key] = true
			out = append(out, s)
		}
	}
	for _, s := range a.hub.Sessions() {
		pick(s)
	}
	pick(a.currentSession())
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

// batchMenu 是在选中的几行上右键的菜单，管选中的 chosen：归档或取消归档（选中的全是归档的才是取消归档）、导出、删除。
func (a *App) batchMenu(m *ui.Menu, chosen []hostlink.Session) {
	n := strconv.Itoa(len(chosen)) + " 个对话"
	archive := slices.ContainsFunc(chosen, func(s hostlink.Session) bool { return !s.Archived })
	label := "归档"
	if !archive {
		label = "取消归档"
	}
	if m.Item(label + " " + n).Chosen() {
		a.hub.SetArchived(chosen, archive, a.failed(label+"没成功"))
		a.clearSelection()
	}
	if m.Item("导出 " + n).Chosen() {
		a.export(chosen)
	}
	m.Separator()
	if m.Item("删除 "+n).Shortcut(0, ui.KeyDelete).Danger().Chosen() {
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
		coui.IconButton(c, coui.IconBack, "返回全部对话").OnClick(func() { a.ops.archived = false })
		return
	}
	if n := len(a.hub.Archived(a.now(), a.currentSession())); n > 0 {
		coui.IconButton(c, coui.IconArchive, "已归档 · "+strconv.Itoa(n)).OnClick(func() { a.ops.archived = true })
	}
}

// archivedGroup 是看归档时的列表：一组「已归档 · N」，照常按时间排。
func (a *App) archivedGroup(c *ui.Context) {
	list := a.hub.Archived(a.now(), a.currentSession())
	if len(list) == 0 {
		a.ops.archived = false // 都取消归档了：回到全部对话
		paneMessage(c, "没有归档的对话")
		return
	}
	sectionTitle(c, "已归档 · "+strconv.Itoa(len(list)), true)
	for _, s := range list {
		a.sessionRow(c, s, "")
	}
}

// sessionDialogs 是这些操作的对话框：重命名、删除一个、删除几个。
func (a *App) sessionDialogs(c *ui.Context) {
	o := &a.ops
	h := a.hub
	if r := &o.renaming; r.open {
		if coui.InputDialog(c, &r.open, "重命名", "", &r.draft, "对话标题", "确定", strings.TrimSpace(r.draft) != "") {
			h.Rename(r.target, r.draft, a.failed("改名没成功"))
		}
	}
	if d := &o.deleting; d.open {
		if coui.Alert(c, &d.open, "删除这个对话？", deleteName(d.target)+"\n会话记录文件会被一并删除，无法恢复。",
			coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
			h.Delete([]hostlink.Session{d.target}, a.deleteFailed)
		}
	}
	if b := &o.batch; b.open {
		if coui.Alert(c, &b.open, "删除 "+strconv.Itoa(len(b.list))+" 个对话？",
			"会话记录会被删除，Claude Code 为它们存的工具结果、子代理记录等也一并删除，无法恢复。",
			coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
			h.Delete(b.list, a.deleteFailed)
			a.clearSelection()
		}
	}
}

// deleteName 是删除确认框里这个对话的名字：标题或首句，最多 60 字。
func deleteName(s hostlink.Session) string {
	name := []rune(rowTitle(s))
	return string(name[:min(60, len(name))])
}

// togglePin 置顶或取消置顶 s（右键菜单、Ctrl+Alt+P）。
func (a *App) togglePin(s hostlink.Session) {
	what := "置顶"
	if a.hub.Pinned(s) {
		what = "取消置顶"
	}
	a.hub.TogglePin(s, a.failed(what+"没成功"))
}

// rename 弹出给 s 改名的对话框（右键菜单、Ctrl+Alt+R）：先填着现在的标题，没有标题时是第一句话的前 40 字。
func (a *App) rename(s hostlink.Session) {
	r := &a.ops.renaming
	r.target, r.draft, r.open = s, rowTitle(s), true
	if s.Title == "" {
		r.draft = string([]rune(s.Preview)[:min(40, len([]rune(s.Preview)))])
	}
}

// archive 归档或取消归档 s（右键菜单、Ctrl+Shift+A）。
func (a *App) archive(s hostlink.Session) {
	what := "归档"
	if s.Archived {
		what = "取消归档"
	}
	a.hub.SetArchived([]hostlink.Session{s}, !s.Archived, a.failed(what+"没成功"))
}
