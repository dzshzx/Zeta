package chatview

import (
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// ink 是时间线里弱化的颜色：边框、标题字、图标，由主文字色调淡（数值同手机版 ChatItems.kt 的工作组卡片）。
type ink struct {
	border, muted, icon ui.Color
}

func inkOf(t *ui.Theme) ink {
	if t.Dark {
		return ink{ui.RGBA(255, 255, 255, 0.10), ui.RGBA(255, 255, 255, 0.56), ui.RGBA(255, 255, 255, 0.50)}
	}
	return ink{ui.RGBA(0, 0, 0, 0.065), ui.RGBA(0, 0, 0, 0.52), ui.RGBA(0, 0, 0, 0.45)}
}

// collapsedCards：工作组折叠时，在摘要下面直接露出的改动最多几处。
const collapsedCards = 3

// workGroup 画一组连续的思考与工具调用：圆角细边框的卡片，默认折叠成一行摘要，点开是逐步的时间线；
// 折叠着也直接露出改了哪些文件。
func (ch *Chat) workGroup(c *ui.Context, it items.Item) {
	t := c.Theme()
	k := inkOf(t)
	open := ch.flag("work:"+it.ID, false)
	running := lastRunning(it.Steps)
	coui.Surface{Shape: coui.ShapeRow, Edge: k.border}.Paint(ui.Column(c)).FillWidth().MarginY(4).Clip().Children(func() {
		p := ui.CollapsibleBase(c, open)
		if p.Trigger.Changed() {
			// 点开最后一张卡时，点的那一行留在原处，不跟着底部往上跑
			ch.list.HoldPlace()
		}
		p.Trigger.MinHeight(42).Padding(0, 14).Gap(10).AlignItems(ui.Center).Cursor(ui.CursorPointer).Children(func() {
			ic, tint := iconDone, k.icon
			if running != nil {
				ic, tint = stepIcon(*running), t.Accent
			}
			ui.Icon(c, ic).FontSize(15).TextColor(tint)
			pulsingText(c, workTitle(it.Steps, running), running != nil, k.muted).Grow(1)
			chevron(c, p.Progress())
		})
		if !*open && p.Progress() == 0 {
			ch.collapsedChanges(c, it.Steps, open)
		}
		panel := p.Panel(func() {
			ui.Box(c).FillWidth().Height(1).Background(k.border).Margin(0, 0, 4, 0)
			for _, s := range it.Steps {
				ui.Column(c.Key(s.ID)).FillWidth().Children(func() { ch.stepRow(c, s) })
			}
		})
		if panel.Valid() {
			panel.FillWidth().Padding(0, 14, 8, 14)
		}
	})
}

// workTitle 是工作组标题行的字：在跑的是哪一步，或者做完了几步。
func workTitle(steps []items.Step, running *items.Step) string {
	tools := 0
	for _, s := range steps {
		if s.Kind == items.Tool {
			tools++
		}
	}
	switch {
	case running != nil && running.Kind == items.Tool:
		return "正在处理第 " + strconv.Itoa(tools) + " 步 · " + lookOf(*running).title
	case running != nil:
		return "正在思考"
	case tools > 0:
		return "已完成 " + strconv.Itoa(tools) + " 个步骤"
	}
	return "思考已完成"
}

// lastRunning 是组里最后一个还在进行的步骤，没有时为 nil。
func lastRunning(steps []items.Step) *items.Step {
	for i := len(steps) - 1; i >= 0; i-- {
		if steps[i].Running() {
			return &steps[i]
		}
	}
	return nil
}

func stepIcon(s items.Step) *ui.SVG {
	if s.Kind == items.Thinking {
		return iconThinking
	}
	return lookOf(s).icon
}

// isSubagent：发起子代理的工具（Task 是 Agent 的旧名）。
func isSubagent(name string) bool { return name == "Agent" || name == "Task" }

// stepRow 画时间线里的一步：改文件的直接是红绿对比；发起子代理的点了进子代理查看页；别的点开看详情。
func (ch *Chat) stepRow(c *ui.Context, s items.Step) {
	if v := ch.changeView(s); v != nil {
		ch.editStepRow(c, s, v)
		return
	}
	t := c.Theme()
	k := inkOf(t)
	title, detail := stepText(s)
	header := func(progress float32, expandable bool) {
		tint := k.icon
		if s.Running() {
			tint = t.Accent
		}
		ui.Icon(c, stepIcon(s)).FontSize(15).TextColor(tint)
		pulsingText(c, title, s.Running(), k.muted).Grow(1)
		statusMark(c, s)
		if expandable {
			chevron(c, progress)
		}
	}
	rowStyle := func(e ui.Element) ui.Element {
		return e.FillWidth().MinHeight(34).Gap(10).AlignItems(ui.Center).Radius(8)
	}
	if isSubagent(s.Name) && ch.OpenSubagent != nil {
		open := ch.OpenSubagent
		b := rowStyle(ui.ButtonBase(c)).Label(title).Description("查看子代理的步骤").Cursor(ui.CursorPointer)
		b.Children(func() {
			header(0, false)
			ui.Icon(c, iconForward).FontSize(16).TextColor(t.Accent)
		})
		b.OnClick(func() { open(s.ID) })
		return
	}
	if detail == "" && len(s.Images) == 0 {
		rowStyle(ui.Row(c)).Children(func() { header(0, false) })
		return
	}
	open := ch.flag("step:"+s.ID, false)
	p := ui.CollapsibleBase(c, open)
	if p.Trigger.Changed() {
		ch.list.HoldPlace()
	}
	rowStyle(p.Trigger).Cursor(ui.CursorPointer).Children(func() { header(p.Progress(), true) })
	panel := p.Panel(func() {
		ch.stepDetailView(c, s, detail)
	})
	if panel.Valid() {
		panel.FillWidth().Gap(8).Padding(0, 0, 8, 25)
	}
}

// stepText 是一步的标题和展开后的详情。
func stepText(s items.Step) (title, detail string) {
	if s.Kind == items.Thinking {
		switch {
		case s.Thinking:
			title = "正在思考"
		case s.Seconds != nil:
			title = "思考已完成 · 用时 " + strconv.Itoa(*s.Seconds) + " 秒"
		default:
			title = "思考已完成"
		}
		return title, s.Text
	}
	look := lookOf(s)
	return look.title, stepDetail(look, s.Result)
}

// stepDetailView 是一步展开后的样子：思考按 Markdown 排成淡色字，工具调用是等宽字的灰底块，下面是结果里的图。
func (ch *Chat) stepDetailView(c *ui.Context, s items.Step, detail string) {
	t := c.Theme()
	if s.Kind == items.Thinking {
		markdown.View(c, detail, &ch.thinking).FillWidth()
		return
	}
	if detail != "" {
		bg := t.Text.Alpha(0.035)
		if t.Dark {
			bg = t.Text.Alpha(0.06)
		}
		ui.Text(c, detail).Selectable().Font(coui.Mono).FontSize(11.5).LineHeight(1.2).TextColor(t.Text.Alpha(0.78)).
			FillWidth().Padding(8, 10).Radius(10).Background(bg)
	}
	for k, im := range s.Images {
		ui.Box(c.Key(k)).Children(func() { ch.picture(c, im) })
	}
}

// statusMark 是工具调用标题行末尾的结果：绿点「已完成」或红点「失败」；思考和还在跑的没有。
func statusMark(c *ui.Context, s items.Step) {
	t := c.Theme()
	var dot ui.Color
	var label string
	switch {
	case s.Kind != items.Tool:
		return
	case s.Status == items.ToolSuccess:
		dot, label = t.Success, "已完成"
	case s.Status == items.ToolFailed:
		dot, label = t.Danger, "失败"
	default:
		return
	}
	ui.Row(c).Gap(5).AlignItems(ui.Center).Shrink(0).Children(func() {
		ui.Box(c).Size(6, 6).Radius(3).Background(dot)
		ui.Text(c, label).FontSize(12).TextColor(t.Text.Alpha(0.7)).SingleLine()
	})
}

// chevron 是展开箭头：收着朝右，展开时转到朝下；progress 是展开到了哪（0 到 1）。
func chevron(c *ui.Context, progress float32) {
	ui.Icon(c, iconChevron).FontSize(17).TextColor(c.Theme().TextMuted.Alpha(0.7)).Rotate(-90 + 90*progress).Shrink(0)
}

// pulsingText 是一行标题；active（还在进行）时轻微呼吸。
func pulsingText(c *ui.Context, text string, active bool, color ui.Color) ui.Element {
	e := ui.Text(c, text).FontSize(13.5).TextColor(color).SingleLine().Ellipsis("…").Shrink(1)
	if active {
		e.Opacity(0.45 + 0.55*triangle(e.Loop("pulse", 1600*time.Millisecond, ui.Linear)))
	}
	return e
}
