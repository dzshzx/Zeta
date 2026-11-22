package ui

import (
	"os"
	"slices"
	"strconv"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// 侧栏对会话的操作（同手机版 SessionPane、SessionSelection.kt）：右键菜单里选择、置顶、重命名、导出、归档，删除单独一组；
// 多选时顶上换成「已选 N 个」和全选，底下换成归档、导出、删除；归档的会话收在底部的入口里单独看。事情交给中枢，
// 没成时弹框说原因。

// sessionOps 是侧栏这些操作的界面状态。
type sessionOps struct {
	selecting bool
	selected  map[string]bool // 选中的（置顶键）
	archived  bool            // 侧栏在看归档

	renaming struct {
		target hostlink.Session
		draft  string
		open   bool
	}
	// deleting 是单条删除的确认框。打开时就定下是只提示（blocked：正在进行）还是确认删除，开着时不再变：
	// 否则对话恰好在框开着时结束，「知道了」会在指针下面变成同一位置的「删除」。
	deleting struct {
		target  hostlink.Session
		blocked bool
		open    bool
	}
	batch struct {
		list    []hostlink.Session
		skipped int
		open    bool
	}
	// save 让用户选导出存到哪（系统的保存对话框），取消时是空。阻塞，在后台调用；测试里换掉。
	save func(name string) (string, error)
}

func systemSave(name string) (string, error) {
	return mygo.Dialog.Save(mygo.SaveDialogOptions{Title: "导出对话", DefaultPath: name,
		Filters: []mygo.FileFilter{{Name: "Markdown", Extensions: []string{"md"}}}})
}

// sessionMenu 是一行会话的右键菜单。
func (a *App) sessionMenu(m *ui.Menu, s hostlink.Session) {
	h := a.hub
	if m.Item("选择").Chosen() {
		a.ops.selecting, a.ops.selected = true, map[string]bool{settings.PinKey(s.Backend, s.ID): true}
	}
	pin := "置顶"
	if h.Pinned(s) {
		pin = "取消置顶"
	}
	if m.Item(pin).Chosen() {
		h.TogglePin(s)
	}
	if m.Item("重命名").Chosen() {
		a.ops.renaming.target, a.ops.renaming.draft, a.ops.renaming.open = s, rowTitle(s), true
		if s.Title == "" {
			a.ops.renaming.draft = string([]rune(s.Preview)[:min(40, len([]rune(s.Preview)))])
		}
	}
	if m.Item("导出").Chosen() {
		a.export([]hostlink.Session{s})
	}
	archive := "归档"
	if s.Archived {
		archive = "取消归档"
	}
	if m.Item(archive).Chosen() {
		h.SetArchived([]hostlink.Session{s}, !s.Archived, a.failed(archive+"没成功"))
	}
	m.Separator()
	if m.Item("删除").Danger().Chosen() {
		a.ops.deleting.target, a.ops.deleting.blocked, a.ops.deleting.open = s, h.Active(s, a.local(s)), true
	}
}

// failed 是操作没成时弹框说一声的办法。
func (a *App) failed(title string) func(error) {
	return func(err error) { a.say(title, err.Error()) }
}

// local 是聊天窗格里开着的对话的样子，s 不是它时是零值（判断能不能删用）。
func (a *App) local(s hostlink.Session) hub.Local {
	p := a.pane
	if p == nil || !a.showing(s) {
		return hub.Local{}
	}
	return hub.Local{Waiting: len(p.state.Prompts) > 0, Busy: p.state.Busy, Tasks: p.state.RunningTasks()}
}

// export 导出 list：中枢取来 Markdown，系统的保存对话框选好地方存下。几个一起导出、存好了退出多选。
func (a *App) export(list []hostlink.Session) {
	if len(list) == 0 {
		return
	}
	save := a.ops.save
	if save == nil {
		save = systemSave
	}
	many := len(list) > 1
	a.hub.Export(list, a.now(), func(e hub.Exported) {
		a.async(func() func() {
			path, err := save(e.Name)
			if err == nil && path != "" {
				err = os.WriteFile(path, []byte(e.Content), 0o644)
			}
			return func() {
				switch {
				case err != nil:
					a.say("导出没成功", err.Error())
				case path != "" && many:
					a.exitSelection()
				}
			}
		})
	}, a.failed("导出没成功"))
}

func (a *App) exitSelection() { a.ops.selecting, a.ops.selected = false, nil }

// visible 是侧栏眼前这份列表（搜索结果、归档或置顶/今天/更早），全选管的是它。
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

// chosen 是选中的会话，按列表里的先后；已经不在的不算。
func (a *App) chosen() []hostlink.Session {
	var out []hostlink.Session
	seen := map[string]bool{}
	for _, s := range append(slices.Clone(a.hub.Sessions()), a.currentSession()) {
		key := settings.PinKey(s.Backend, s.ID)
		if s.ID != "" && a.ops.selected[key] && !seen[key] {
			seen[key] = true
			out = append(out, s)
		}
	}
	return out
}

// toggle 勾上或去掉会话 s。
func (a *App) toggle(s hostlink.Session) {
	key := settings.PinKey(s.Backend, s.ID)
	if a.ops.selected == nil {
		a.ops.selected = map[string]bool{}
	}
	if a.ops.selected[key] {
		delete(a.ops.selected, key)
	} else {
		a.ops.selected[key] = true
	}
}

// allSelected：眼前这份都选上了。
func (a *App) allSelected(scope []hostlink.Session) bool {
	return len(scope) > 0 && !slices.ContainsFunc(scope, func(s hostlink.Session) bool {
		return !a.ops.selected[settings.PinKey(s.Backend, s.ID)]
	})
}

// selectionHeader 是多选时侧栏的顶上一行（同时是标题栏）：退出多选、已选几个、全选或全不选（别处选中的不动）。
func (a *App) selectionHeader(c *ui.Context) {
	k := coui.Of(c)
	scope := a.visible()
	all := a.allSelected(scope)
	ui.Row(c).Height(coui.TitleBarHeight).Padding(0, 12, 0, 16).Gap(10).AlignItems(ui.Center).Shrink(0).DragWindow().Children(func() {
		coui.CircleButton(c, coui.IconClose, "退出多选").OnClick(a.exitSelection)
		title := "选择对话"
		if n := len(a.chosen()); n > 0 {
			title = "已选 " + strconv.Itoa(n) + " 个"
		}
		ui.Text(c, title).FontSize(coui.SizeHeadlineS).FontWeight(600).TextColor(k.Text).Grow(1).SingleLine()
		label := "全选"
		if all {
			label = "全不选"
		}
		b := ui.ButtonBase(c).Label(label).Padding(8, 10).Radius(999).Cursor(ui.CursorPointer).Disabled(len(scope) == 0)
		if b.Hovered() {
			b.Background(k.Hover)
		}
		b.Children(func() { ui.Text(c, label).FontSize(coui.SizeBodyL).FontWeight(coui.Medium).TextColor(k.PrimaryText) })
		b.OnClick(func() {
			for _, s := range scope {
				key := settings.PinKey(s.Backend, s.ID)
				if all {
					delete(a.ops.selected, key)
				} else {
					a.ops.selected[key] = true
				}
			}
		})
	})
}

// selectionActions 是多选时侧栏底下一排：归档或取消归档（选中的全是归档的才是取消归档）、导出、删除（红字）。没选中时都点不了。
func (a *App) selectionActions(c *ui.Context) {
	chosen := a.chosen()
	archive := len(chosen) == 0 || slices.ContainsFunc(chosen, func(s hostlink.Session) bool { return !s.Archived })
	label := "归档"
	if !archive {
		label = "取消归档"
	}
	none := len(chosen) == 0
	ui.Row(c).Padding(10, 16).Gap(8).Shrink(0).Children(func() {
		coui.SmallButton(c, label).Grow(1).Basis(0).Disabled(none).OnClick(func() {
			a.hub.SetArchived(chosen, archive, a.failed(label+"没成功"))
			a.exitSelection()
		})
		coui.SmallButton(c, "导出").Grow(1).Basis(0).Disabled(none).OnClick(func() { a.export(chosen) })
		coui.SmallDangerButton(c, "删除").Grow(1).Basis(0).Disabled(none).OnClick(func() {
			removable := slices.DeleteFunc(slices.Clone(chosen), func(s hostlink.Session) bool { return a.hub.Active(s, a.local(s)) })
			if len(removable) == 0 {
				a.say("不能删除", "选中的对话都在进行中，先停止它们，或等它们结束。")
				return
			}
			a.ops.batch.list, a.ops.batch.skipped, a.ops.batch.open = removable, len(chosen)-len(removable), true
		})
	})
}

// archiveToggle 是侧栏底部归档的入口（同手机版 ArchiveToggle）：看归档时是返回全部对话，有归档的会话时是进归档，都没有时不显示。
func (a *App) archiveToggle(c *ui.Context) {
	if a.ops.archived {
		coui.CircleButton(c, coui.IconBack, "返回全部对话").OnClick(func() { a.ops.archived = false })
		return
	}
	if n := len(a.hub.Archived(a.now(), a.currentSession())); n > 0 {
		coui.CircleButton(c, coui.IconArchive, "已归档 · "+strconv.Itoa(n)).OnClick(func() { a.ops.archived = true })
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

// sessionDialogs 是这些操作的对话框：重命名、删除一个（或只提示正在进行）、删除几个。
func (a *App) sessionDialogs(c *ui.Context) {
	o := &a.ops
	h := a.hub
	if r := &o.renaming; r.open {
		if coui.InputDialog(c, &r.open, "重命名", "", &r.draft, "对话标题", "确定", hub.CleanTitle(r.draft) != "") {
			h.Rename(r.target, r.draft, a.failed("改名没成功"))
		}
	}
	if d := &o.deleting; d.open {
		name := deleteName(d.target)
		if d.blocked {
			coui.Alert(c, &d.open, "这个对话正在进行", name+"\n先停止它，或等它结束，再删除。", coui.Action{Label: "知道了", Style: coui.Recommend})
		} else if coui.Alert(c, &d.open, "删除这个对话？", name+"\n会话记录文件会被一并删除，无法恢复。",
			coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
			// 确认框开着时它开始进行了就不删：删除会结束它的对话进程
			if h.Active(d.target, a.local(d.target)) {
				a.say("没有删除", "这个对话正在进行。")
			} else {
				h.Delete([]hostlink.Session{d.target}, a.failed("删除没成功"))
			}
		}
	}
	if b := &o.batch; b.open {
		summary := "会话记录会被删除，Claude Code 为它们存的工具结果、子代理记录等也一并删除，无法恢复。"
		if b.skipped > 0 {
			summary += "\n另有 " + strconv.Itoa(b.skipped) + " 个正在进行的对话不会删除。"
		}
		if coui.Alert(c, &b.open, "删除 "+strconv.Itoa(len(b.list))+" 个对话？", summary,
			coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
			list := slices.DeleteFunc(slices.Clone(b.list), func(s hostlink.Session) bool { return h.Active(s, a.local(s)) })
			h.Delete(list, a.failed("删除没成功"))
			a.exitSelection()
		}
	}
}

// deleteName 是删除确认框里这个对话的名字：标题或首句，最多 60 字。
func deleteName(s hostlink.Session) string {
	name := []rune(rowTitle(s))
	return string(name[:min(60, len(name))])
}
