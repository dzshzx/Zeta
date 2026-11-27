package chatview

import (
	"time"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/hub/mathtex"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
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
// 折叠着也直接露出改了哪些文件。工具结果里的图（Read 打开的图片、截图）不收在卡片里，画在卡片下面（workImages）。
func (ch *Chat) workGroup(c *ui.Context, it items.Item, row int) {
	t := c.Theme()
	k := inkOf(t)
	open := ch.flag("work:"+it.ID, ch.ExpandWork)
	running := lastRunning(it.Steps)
	widget.Surface{Shape: widget.ShapeRow, Edge: k.border}.Paint(ui.Column(c)).FillWidth().MarginY(4).Clip().Children(func() {
		p := ui.CollapsibleBase(c, open)
		if p.Trigger.Changed() {
			// 点开最后一张卡时，点的那一行留在原处，不跟着底部往上跑
			ch.list.HoldPlace()
		}
		const summaryHeight = 42
		p.Trigger.MinHeight(summaryHeight).Padding(0, 14).Gap(10).AlignItems(ui.Center).Cursor(ui.CursorPointer).Children(func() {
			ic, tint := iconDone, k.icon
			if running != nil {
				ic, tint = stepIcon(*running), t.Accent
			}
			ui.Icon(c, ic).FontSize(15).TextColor(tint)
			pulsingText(c, workTitle(it.Steps, running), running != nil, k.muted).Grow(1)
			if *open {
				ui.Box(c).Size(17, 17).Shrink(0) // 给同一位置的整组收起按钮留空
			} else {
				chevron(c, p.Progress())
			}
		})
		if *open {
			// 按钮与摘要共用一行，但直接属于整张卡：摘要滚走后它还能留在视野里，卡片滚走时一起离开。
			p.Trigger.Margin(0, 0, -summaryHeight, 0)
			b := widget.IconButton(c.Key("collapse-work"), widget.IconChevronUp, locales.ChatCollapse())
			b.AlignSelf(ui.End).Margin(5, 6, 5, 0).Sticky().Top(ch.Top)
			if !b.Hovered() && !b.Pressed() {
				b.Background(widget.Of(c).Window)
			}
			b.OnClick(func() {
				*open = false
				ch.list.ScrollTo(row, ui.Start)
			})
		}
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
	ch.workImages(c, it.Steps)
}

// workImages 画一组工具结果里的全部图：卡片下面一行（放不下折行），按步骤先后；点了看大图，左右翻的是这一组的全部图。
func (ch *Chat) workImages(c *ui.Context, steps []items.Step) {
	var all []items.Image
	for _, s := range steps {
		all = append(all, s.Images...)
	}
	if len(all) == 0 {
		return
	}
	ui.Row(c.Key("work-images")).Wrap().Gap(8).Margin(4, 0, 6, 0).MaxWidthPercent(100).Children(func() {
		for at, im := range all {
			if ch.waitsForTap(im) {
				ui.Box(c.Key(at)).Children(func() { ch.remotePrompt(c, im) })
			} else {
				ch.opensImage(ui.Box(c.Key(at)), all, at).Children(func() { ch.picture(c, im) })
			}
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
		return locales.ChatWorkRunningStep(tools, lookOf(*running).title)
	case running != nil:
		return locales.ChatThinking()
	case tools > 0:
		return locales.ChatStepsDone(tools)
	}
	return locales.ChatThinkingDone()
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
	title, detail := stepText(s, c.Locale())
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
		b := rowStyle(ui.ButtonBase(c)).Label(title).Description(locales.ChatViewSubagentSteps()).Cursor(ui.CursorPointer)
		b.Children(func() {
			header(0, false)
			ui.Icon(c, iconForward).FontSize(16).TextColor(t.Accent)
		})
		b.OnClick(func() { open(s.ID) })
		return
	}
	if detail == "" {
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

// stepText 是一步的标题和展开后的详情（核心写的结果按界面语言 locale 出字）。
func stepText(s items.Step, locale string) (title, detail string) {
	if s.Kind == items.Thinking {
		switch {
		case s.Thinking:
			title = locales.ChatThinking()
		case s.Seconds != nil:
			title = locales.ChatThinkingDoneIn(*s.Seconds)
		default:
			title = locales.ChatThinkingDone()
		}
		return title, s.Text
	}
	look := lookOf(s)
	return look.title, stepDetail(look, s.ResultSaid(locale))
}

// stepDetailView 是一步展开后的样子：思考按 Markdown 排成淡色字，工具调用是等宽字的灰底块（结果里的图在卡片下面，见 workImages）。
func (ch *Chat) stepDetailView(c *ui.Context, s items.Step, detail string) {
	t := c.Theme()
	if s.Kind == items.Thinking {
		markdown.View(c, mathtex.Source(detail), &ch.thinking).FillWidth()
		return
	}
	bg := t.Text.Alpha(0.035)
	if t.Dark {
		bg = t.Text.Alpha(0.06)
	}
	ui.Text(c, detail).Selectable().Font(widget.Mono).FontSize(11.5).LineHeight(1.2).TextColor(t.Text.Alpha(0.78)).
		FillWidth().Padding(8, 10).Radius(10).Background(bg)
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
		dot, label = t.Success, locales.ChatStepDone()
	case s.Status == items.ToolFailed:
		dot, label = t.Danger, locales.ChatStepFailed()
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

// pulsingText 是一行标题；active（还在进行）时轻微呼吸：一圈 1.6 秒分 16 步。透明度变了要重建界面，所以步子比只重画的
// 提示少（widget.Step）。
func pulsingText(c *ui.Context, text string, active bool, color ui.Color) ui.Element {
	e := ui.Text(c, text).FontSize(13.5).TextColor(color).SingleLine().Ellipsis("…").Shrink(1)
	if active {
		phase, next := widget.Step(c.Now(), 1600*time.Millisecond, 16)
		e.Opacity(0.45 + 0.55*triangle(phase))
		c.After(next)
	}
	return e
}
