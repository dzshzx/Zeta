package chatview

import (
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/editor"
)

// 对比的行数上限：折叠时先露出的行数，和展开后最多画的行数（再多的只报个数，比如整份重写的大文件）。
const (
	previewLines  = 12
	maxShownLines = 1500
)

// changeView 是一步文件改动的对比：算好的改动，展开全部没有，以及给 mygo 对比视图的状态（换了显示的行数时重建）。
type changeView struct {
	change   *fileChange
	added    int
	removed  int
	total    int
	expanded bool
	diff     *editor.DiffState
	shown    int // diff 里画了几行
}

// changeView 返回一步改文件的对比，不是改文件的步骤返回 nil。算过一次就留着，条目换掉时丢掉（见 Chat.forget）。
func (ch *Chat) changeView(s items.Step) *changeView {
	if !isFileEdit(s.Name) {
		return nil
	}
	if v, ok := ch.changes[s.ID]; ok {
		return v
	}
	var v *changeView
	if f := changeOf(s); f != nil {
		v = &changeView{change: f}
		v.added, v.removed, v.total = f.counts()
	}
	ch.changes[s.ID] = v
	return v
}

// long：改动长过预览，要「展开全部」。
func (v *changeView) long() bool { return v.total > previewLines }

// limit 是现在要画几行。
func (v *changeView) limit() int {
	switch {
	case !v.long():
		return v.total
	case v.expanded:
		return maxShownLines
	}
	return previewLines
}

// state 是给对比视图的状态，画的行数变了时照新的行数重建。
func (v *changeView) state() *editor.DiffState {
	if v.diff != nil && v.shown == v.limit() {
		return v.diff
	}
	shown := takeLines(v.change.hunks, v.limit())
	numbered := v.change.numbered
	d := &editor.DiffState{
		Layout:        editor.DiffUnified,
		Indicators:    editor.DiffClassic,
		FitContent:    true,
		Wrap:          true,
		NoLineNumbers: !numbered,
		LineHeight:    1.45,
	}
	d.RenderSeparator = func(c *ui.Context, sep ui.Element, s *editor.DiffSeparator) {
		hunkGap(c, sep, s, numbered)
	}
	if len(shown) == 1 && (!numbered || startsAtTop(shown[0])) {
		// 只有一段、行号又从 1 起（或不显示行号）：直接给两边的文字，前面不用分隔条说明从哪行起
		d.Old, d.New = editor.NewBuffer(sideText(shown[0], items.Added)), editor.NewBuffer(sideText(shown[0], items.Removed))
	} else {
		d.SetPatch(toPatch(shown))
	}
	v.diff, v.shown = d, v.limit()
	return d
}

// startsAtTop 报告一段对比两边都从第 1 行起（没有某一边的行时那边不算）。
func startsAtTop(h items.Hunk) bool {
	p := toPatch([]items.Hunk{h}).Hunks[0]
	return p.OldStart <= 1 && p.NewStart <= 1
}

// sideText 是一段对比里一边的文字：去掉 skip 那种行（旧的一边去掉新加的，新的一边去掉删掉的）。
func sideText(h items.Hunk, skip items.LineKind) string {
	var lines []string
	for _, l := range h.Lines {
		if l.Kind != skip {
			lines = append(lines, displayLine(l.Text))
		}
	}
	return joinLines(lines)
}

// hunkGap 是对比里每段前面的分隔条：带行号的写从第几行起，不带的只是省略记号。
func hunkGap(c *ui.Context, sep ui.Element, s *editor.DiffSeparator, numbered bool) {
	t := c.Theme()
	text := "⋯"
	if n := hunkStart(s.Header); numbered && n > 0 {
		text = "⋯  第 " + strconv.Itoa(n) + " 行"
	}
	sep.Padding(4, 8).Children(func() {
		ui.Row(c).Grow(1).AlignSelf(ui.Stretch).AlignItems(ui.Center).Padding(0, 10).Radius(6).
			Background(t.Text.Alpha(0.035)).Children(func() {
			ui.Text(c, text).FontSize(11.5).TextColor(t.TextMuted).SingleLine()
		})
	})
}

// editStepRow 是时间线里改文件的一步：标题行带增删行数，下面直接是对比，点标题行收起或展开对比。
func (ch *Chat) editStepRow(c *ui.Context, s items.Step, v *changeView) {
	t := c.Theme()
	k := inkOf(t)
	look := lookOf(s)
	open := ch.flag("edit:"+s.ID, true)
	p := ui.CollapsibleBase(c, open)
	if p.Trigger.Changed() {
		ch.list.HoldPlace()
	}
	p.Trigger.FillWidth().MinHeight(34).Gap(10).AlignItems(ui.Center).Radius(8).Cursor(ui.CursorPointer).Children(func() {
		tint := k.icon
		if s.Running() {
			tint = t.Accent
		}
		ui.Icon(c, look.icon).FontSize(15).TextColor(tint)
		ui.Row(c).Grow(1).Gap(8).AlignItems(ui.Center).Children(func() {
			pulsingText(c, look.title, s.Running(), k.muted)
			changeCounts(c, v)
		})
		chevron(c, p.Progress())
	})
	panel := p.Panel(func() { ch.changeCard(c, s.ID, v, false) })
	if panel.Valid() {
		panel.FillWidth().Padding(0, 0, 8, 25)
	}
}

// collapsedChanges 是工作组折叠时摘要下面露出的改动：最多 collapsedCards 处，多出来的点一下展开整组看。
func (ch *Chat) collapsedChanges(c *ui.Context, steps []items.Step, groupOpen *bool) {
	var shown []items.Step
	count := 0
	for _, s := range steps {
		if ch.changeView(s) == nil {
			continue
		}
		count++
		if len(shown) < collapsedCards {
			shown = append(shown, s)
		}
	}
	if count == 0 {
		return
	}
	ui.Column(c).FillWidth().Gap(8).Padding(0, 14, 10, 14).Children(func() {
		for _, s := range shown {
			ui.Column(c.Key(s.ID)).FillWidth().Children(func() { ch.changeCard(c, s.ID, ch.changeView(s), true) })
		}
		if count > collapsedCards {
			more := ui.Text(c, "还有 "+strconv.Itoa(count-collapsedCards)+" 处改动，展开查看").FontSize(12.5).
				TextColor(c.Theme().Accent).Cursor(ui.CursorPointer).Role(ui.RoleLink).Padding(4)
			more.OnClick(func() {
				*groupOpen = true
				ch.list.HoldPlace()
			})
		}
	})
}

// changeCounts 是「+3 −1」。
func changeCounts(c *ui.Context, v *changeView) {
	added, removed := diffColors(c.Theme())
	ui.Row(c).Gap(5).Shrink(0).Children(func() {
		if v.added > 0 || v.removed == 0 {
			ui.Text(c, "+"+strconv.Itoa(v.added)).Font("monospace").FontSize(12).FontWeight(500).TextColor(added)
		}
		if v.removed > 0 {
			ui.Text(c, "−"+strconv.Itoa(v.removed)).Font("monospace").FontSize(12).FontWeight(500).TextColor(removed)
		}
	})
}

func diffColors(t *ui.Theme) (added, removed ui.Color) {
	if t.Dark {
		return ui.Hex("#5BC27A"), t.Danger
	}
	return ui.Hex("#1E8E3E"), t.Danger
}

// changeCard 是一处文件改动的红绿对比：header 时顶上带文件名和增删行数（时间线里标题行已经有了，就不带）。
// 长的先只露前 previewLines 行。
func (ch *Chat) changeCard(c *ui.Context, id string, v *changeView, header bool) {
	t := c.Theme()
	f := v.change
	border := t.Text.Alpha(0.08)
	ui.Column(c).FillWidth().Radius(10).Border(1, border).Background(t.Text.Alpha(0.02)).Clip().Children(func() {
		if header {
			changeHeader(c, v, border)
		} else if label := f.label(); label != "" {
			ui.Text(c, label).FontSize(11).TextColor(t.TextMuted).Padding(6, 10, 2, 10)
		}
		if v.total == 0 {
			note := "没有改动"
			if f.kind == createChange {
				note = "空文件"
			}
			ui.Text(c, note).FontSize(12).TextColor(t.TextMuted).Padding(8, 10)
		} else {
			editor.DiffView(c.Key("diff"), v.state()).FillWidth()
		}
		if v.long() {
			expandToggle(c, v)
		}
		if f.truncated {
			ui.Text(c, "内容太长，记录里只留了前一部分，对比到截断处为止").FontSize(11).TextColor(t.TextMuted).Padding(0, 10, 7, 10)
		}
	})
}

// changeHeader 是改动卡片顶上的一行：新建或修改的图标、文件名、改动方式和增删行数，下面一条分隔线。
func changeHeader(c *ui.Context, v *changeView, border ui.Color) {
	t := c.Theme()
	f := v.change
	ic := iconEdit
	if f.kind == createChange {
		ic = iconFileAdd
	}
	ui.Row(c).FillWidth().Gap(7).AlignItems(ui.Center).Padding(7, 10).Tooltip(f.path).Children(func() {
		ui.Icon(c, ic).FontSize(14).TextColor(inkOf(t).icon)
		ui.Row(c).Grow(1).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Text(c, baseName(f.path)).FontSize(13).FontWeight(500).TextColor(t.Text.Alpha(0.86)).SingleLine().
				Ellipsis("…").Shrink(1)
			if label := f.label(); label != "" {
				ui.Text(c, label).FontSize(11).TextColor(t.TextMuted).SingleLine()
			}
		})
		changeCounts(c, v)
	})
	ui.Box(c).FillWidth().Height(1).Background(border)
}

// expandToggle 是长改动底下展开、收起的一行；展开后还有太多没画的行时注明。
func expandToggle(c *ui.Context, v *changeView) {
	t := c.Theme()
	text := "展开全部改动 · 共 " + strconv.Itoa(v.total) + " 行"
	if v.expanded {
		text = "收起"
		if hidden := v.total - v.limit(); hidden > 0 {
			text = "收起 · 还有 " + strconv.Itoa(hidden) + " 行太长未显示"
		}
	}
	progress := float32(0)
	if v.expanded {
		progress = 1
	}
	b := ui.ButtonBase(c).FillWidth().Padding(7, 10).Gap(6).AlignItems(ui.Center).Label(text).Cursor(ui.CursorPointer)
	b.Children(func() {
		ui.Text(c, text).FontSize(12).FontWeight(500).TextColor(t.Accent).Grow(1)
		chevron(c, progress)
	})
	if b.Clicked() {
		v.expanded = !v.expanded
	}
}
