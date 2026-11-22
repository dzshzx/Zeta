package ui

import (
	"bytes"
	"encoding/json"
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// todoStrip 是输入栏上方的任务清单条的界面状态（同手机版 TodoStrip.kt）：一行显示做到第几项、正在做哪一项，
// 点开是整份清单；全部做完、这一轮也结束了就不再显示；× 把这一份收掉，清单再有变化时重新出来。
type todoStrip struct {
	expanded bool
	// dismissed 是收掉的那一份清单的签名（TodoList.Signature）。
	dismissed string
	// seedRaw、seed 是上次解开的 todo_seed：它只在载入更早的历史时才变，原文没变就不再解。
	seedRaw json.RawMessage
	seed    []items.Step
	// todos、sig 是按条目的版本 rev、seedRaw 和在不在回答（busy）算出的清单和它的签名，三样都没变就不重算；
	// 全部做完、这一轮也结束了时为空。零值正好是空列表、没有 seed 时算出来的。
	rev   int
	busy  bool
	todos chat.TodoList
	sig   string
}

// list 是现在要显示的清单和它的签名；不显示时清单为 nil。
func (s *todoStrip) list(entries *chatview.Chat, state chat.State) (chat.TodoList, string) {
	seedChanged := !bytes.Equal(s.seedRaw, state.TodoSeed)
	if seedChanged || entries.Rev() != s.rev || state.Busy != s.busy {
		if seedChanged {
			s.seedRaw, s.seed = state.TodoSeed, chat.TodoSeed(state.TodoSeed)
		}
		s.rev, s.busy = entries.Rev(), state.Busy
		s.todos, s.sig = nil, ""
		if l := chat.Todos(entries.Items(), s.seed); l != nil && (!l.AllDone() || state.Busy) {
			s.todos, s.sig = l, l.Signature()
		}
	}
	if s.todos == nil || s.sig == s.dismissed {
		return nil, ""
	}
	return s.todos, s.sig
}

// todoSummary 是清单条一行里的说法：正在做的那一项，没在做的时候是下一项。
func todoSummary(l chat.TodoList) string {
	if cur := l.Current(); cur != nil && !l.AllDone() {
		return cur.ActiveForm
	}
	if next := l.Next(); next != nil {
		return "下一项：" + next.Content
	}
	return "全部完成"
}

// todoPosition 是「第几项/共几项」：做完的加上正在做的那一项。
func todoPosition(l chat.TodoList) string {
	n := l.Done()
	if l.Current() != nil {
		n++
	}
	return strconv.Itoa(min(n, len(l))) + "/" + strconv.Itoa(len(l))
}

// todoStrip 画任务清单条，样子照排队条（输入栏一带的面板）。
func (a *App) todoStrip(c *ui.Context, p *chatPane) {
	s := &p.todo
	l, sig := s.list(p.list, p.state)
	if l == nil {
		return
	}
	k := coui.Of(c)
	summary := todoSummary(l)
	panel(c, func() {
		ui.Row(c).Padding(0, 4, 0, 0).Gap(4).AlignItems(ui.Center).Children(func() {
			label := "展开任务清单"
			if s.expanded {
				label = "收起任务清单"
			}
			head := ui.ButtonBase(c).Label(label).Grow(1).MinWidth(0).Padding(8, 4, 8, 12).Gap(8).AlignItems(ui.Center).
				Cursor(ui.CursorPointer).Expanded(s.expanded).
				Tooltip("已完成 " + strconv.Itoa(l.Done()) + " 项，共 " + strconv.Itoa(len(l)) + " 项")
			head.Children(func() {
				todoRing(c, l)
				tone := k.PrimaryText
				if l.AllDone() {
					tone = k.Success
				}
				ui.Text(c, todoPosition(l)).FontSize(coui.SizeBodyS).FontWeight(coui.Medium).FontFeatures("tnum").TextColor(tone)
				ui.Text(c, summary).FontSize(coui.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").Grow(1).MinWidth(0)
				chevron := ui.Icon(c, coui.IconChevronDown).Size(16, 16).TextColor(k.Secondary).Shrink(0)
				if s.expanded {
					chevron.Rotate(180)
				}
			})
			head.OnClick(func() { s.expanded = !s.expanded })
			coui.FlatButton(c, coui.IconClose, "收掉这份任务清单", 32, 16).OnClick(func() { s.dismissed = sig })
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
func todoRow(c *ui.Context, t chat.Todo) {
	k := coui.Of(c)
	ui.Row(c).FillWidth().Padding(6, 14, 2, 14).Gap(9).AlignItems(ui.Start).Children(func() {
		ui.Box(c).Size(16, 16).Margin(2, 0, 0, 0).Center().Shrink(0).Children(func() { todoMark(c, t.Status) })
		shown := t.Content
		if t.Status == chat.TodoInProgress {
			shown = t.ActiveForm
		}
		text := ui.Text(c, shown).FontSize(coui.SizeBodyS).LineHeight(1.4).Grow(1).MinWidth(0)
		switch t.Status {
		case chat.TodoCompleted:
			text.TextColor(k.Tertiary).Strikethrough()
		case chat.TodoInProgress:
			text.TextColor(k.Text).FontWeight(coui.Medium)
		default:
			text.TextColor(k.Text.Alpha(0.85))
		}
	})
}

// todoMark 是一项的状态：做完是绿底对勾，进行中是主题色圆圈里一个实心点，待做是空心圆圈。
func todoMark(c *ui.Context, s chat.TodoStatus) {
	k := coui.Of(c)
	switch s {
	case chat.TodoCompleted:
		ui.Box(c).Size(15, 15).Radius(7.5).Background(k.Success).Center().Label("已完成").Children(func() {
			ui.Icon(c, coui.IconOK).Size(11, 11).TextColor(k.OnPrimary)
		})
	case chat.TodoInProgress:
		ui.Box(c).Size(15, 15).Radius(7.5).Border(1.5, k.Primary).Center().Label("进行中").Children(func() {
			ui.Box(c).Size(6, 6).Radius(3).Background(k.Primary)
		})
	default:
		ui.Box(c).Size(15, 15).Radius(7.5).Border(1.2, k.Tertiary).Label("待做")
	}
}

// todoRing 是完成比例的小圆环（16）；全部做完时是绿底对勾。
func todoRing(c *ui.Context, l chat.TodoList) {
	k := coui.Of(c)
	if l.AllDone() {
		todoMark(c, chat.TodoCompleted)
		return
	}
	ring := ui.Box(c).Size(16, 16).Radius(8).Border(2.2, k.Track).Shrink(0)
	if done := l.Done(); done > 0 {
		f := float32(done) / float32(len(l))
		ring.BorderConicGradient(ui.ConicGradient{Stops: []ui.GradientStop{
			{Color: k.Primary, At: 0}, {Color: k.Primary, At: f}, {Color: k.Track, At: f}, {Color: k.Track, At: 1},
		}})
	}
}
