package ui

import (
	"slices"
	"strconv"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// todoStrip 是输入栏上方的任务清单条的界面状态（同手机版 TodoStrip.kt）：一行显示做到第几项、正在做哪一项，
// 点开是整份清单；全部做完、这一轮也结束了就不再显示；× 把这一份收掉，清单再有变化时重新出来。
// 清单是核心归并好的（conv.state 的 todos）。
type todoStrip struct {
	expanded bool
	// dismissed 是收掉的那一份清单。
	dismissed []sessionproto.Todo
}

// list 是现在要显示的清单；不显示时为 nil。
func (s *todoStrip) list(state chat.State) []sessionproto.Todo {
	l := state.Todos
	if len(l) == 0 || todosDone(l) == len(l) && !state.Busy || slices.Equal(l, s.dismissed) {
		return nil
	}
	return l
}

// todoSummary 是清单条一行里的说法：正在做的那一项，没在做的时候是下一项。
func todoSummary(l []sessionproto.Todo) string {
	if cur := todoAt(l, sessionproto.TodoInProgress); cur >= 0 && todosDone(l) < len(l) {
		return l[cur].ActiveFormIn(i18n.Locale())
	}
	if next := todoAt(l, sessionproto.TodoPending); next >= 0 {
		return locales.TodoNext(l[next].ContentIn(i18n.Locale()))
	}
	return locales.TodoAllDone()
}

// todoPosition 是「第几项/共几项」：做完的加上正在做的那一项。
func todoPosition(l []sessionproto.Todo) string {
	n := todosDone(l)
	if todoAt(l, sessionproto.TodoInProgress) >= 0 {
		n++
	}
	return strconv.Itoa(min(n, len(l))) + "/" + strconv.Itoa(len(l))
}

// todoStrip 画任务清单条，样子照排队条（输入栏一带的面板）。
func (a *App) todoStrip(c *ui.Context, p *chatPane) {
	s := &p.todo
	l := s.list(p.state)
	if l == nil {
		return
	}
	k := widget.Of(c)
	summary := todoSummary(l)
	panel(c, func() {
		ui.Row(c).Padding(0, 4, 0, 0).Gap(4).AlignItems(ui.Center).Children(func() {
			label := locales.TodoExpand()
			if s.expanded {
				label = locales.TodoCollapse()
			}
			head := ui.ButtonBase(c).Label(label).Grow(1).MinWidth(0).Padding(8, 4, 8, 12).Gap(8).AlignItems(ui.Center).
				Cursor(ui.CursorPointer).Expanded(s.expanded).
				Tooltip(locales.TodoProgress(todosDone(l), len(l)))
			head.Children(func() {
				todoRing(c, l)
				tone := k.PrimaryText
				if todosDone(l) == len(l) {
					tone = k.Success
				}
				ui.Text(c, todoPosition(l)).FontSize(widget.SizeBodyS).FontWeight(widget.Medium).FontFeatures("tnum").TextColor(tone)
				ui.Text(c, summary).FontSize(widget.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").Grow(1).MinWidth(0)
				chevron := widget.IconChevronDown
				if s.expanded {
					chevron = widget.IconChevronUp
				}
				ui.Icon(c, chevron).Size(16, 16).TextColor(k.Secondary).Shrink(0)
			})
			head.OnClick(func() { s.expanded = !s.expanded })
			widget.FlatButton(c, widget.IconClose, locales.TodoDismiss(), 32, 16).OnClick(func() { s.dismissed = l })
		})
		if !s.expanded {
			return
		}
		ui.Box(c).Height(1).Margin(0, 12).Background(k.Divider)
		ui.Scroll(c.Key("todo/list")).FillWidth().MaxHeight(232).Children(func() {
			ui.Column(c).FillWidth().Padding(2, 0, 6, 0).Children(func() {
				for _, t := range l {
					todoRow(c.Key("todo/"+t.Key), t)
				}
			})
		})
	})
}

// todoRow 是展开后清单里的一项：状态记号和这一项（进行中的用进行中的说法、加粗，做完的划掉、淡色）。
func todoRow(c *ui.Context, t sessionproto.Todo) {
	k := widget.Of(c)
	ui.Row(c).FillWidth().Padding(6, 14, 2, 14).Gap(9).AlignItems(ui.Start).Children(func() {
		ui.Box(c).Size(16, 16).Margin(2, 0, 0, 0).Center().Shrink(0).Children(func() { todoMark(c, t.Status) })
		shown := t.ContentIn(c.Locale())
		if t.Status == sessionproto.TodoInProgress {
			shown = t.ActiveFormIn(c.Locale())
		}
		text := ui.Text(c, shown).FontSize(widget.SizeBodyS).LineHeight(1.4).Grow(1).MinWidth(0)
		switch t.Status {
		case sessionproto.TodoCompleted:
			text.TextColor(k.Tertiary).Strikethrough()
		case sessionproto.TodoInProgress:
			text.TextColor(k.Text).FontWeight(widget.Medium)
		default:
			text.TextColor(k.Text.Alpha(0.85))
		}
	})
}

// todoMark 是一项的状态：做完是绿底对勾，进行中是主题色圆圈里一个实心点，待做是空心圆圈。
func todoMark(c *ui.Context, s string) {
	k := widget.Of(c)
	switch s {
	case sessionproto.TodoCompleted:
		ui.Box(c).Size(15, 15).Radius(7.5).Background(k.Success).Center().Label(locales.TodoDone()).Children(func() {
			ui.Icon(c, widget.IconOK).Size(11, 11).TextColor(k.OnPrimary)
		})
	case sessionproto.TodoInProgress:
		ui.Box(c).Size(15, 15).Radius(7.5).Border(1.5, k.Primary).Center().Label(locales.TodoInProgress()).Children(func() {
			ui.Box(c).Size(6, 6).Radius(3).Background(k.Primary)
		})
	default:
		ui.Box(c).Size(15, 15).Radius(7.5).Border(1.2, k.Tertiary).Label(locales.TodoPending())
	}
}

// todoRing 是完成比例的小圆环（16）；全部做完时是绿底对勾。
func todoRing(c *ui.Context, l []sessionproto.Todo) {
	done := todosDone(l)
	if done == len(l) {
		todoMark(c, sessionproto.TodoCompleted)
		return
	}
	widget.RingArc(c, 16, float32(done)/float32(len(l)), widget.Of(c).Primary).Shrink(0)
}

// todosDone 是清单里做完了几项。
func todosDone(l []sessionproto.Todo) int {
	n := 0
	for _, t := range l {
		if t.Status == sessionproto.TodoCompleted {
			n++
		}
	}
	return n
}

// todoAt 是清单里第一项状态是 status 的下标，没有时为 -1。
func todoAt(l []sessionproto.Todo, status string) int {
	return slices.IndexFunc(l, func(t sessionproto.Todo) bool { return t.Status == status })
}
