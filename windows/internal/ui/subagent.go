package ui

import (
	"cmp"
	"context"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// subagentView 是子代理页的界面状态（同手机版 SubagentScreen.kt）：看着 Agent 调用 toolUseID 发起的子代理，
// 它的记录用聊天列表画（条目和聊天里的一样，它又派的子代理也能点进去）。
type subagentView struct {
	toolUseID string
	list      *chatview.Chat
	// rev 是套给列表的那一版（chat.Subagent.Rev）；failed 是没看上的原因。
	rev    int
	failed string
	// promptOpen：交给它的指令展开了。
	promptOpen bool
}

// openSubagent 打开子代理页（聊天里点了发起子代理的那一步、任务详情里点了查看它的步骤）：开始看着它。
func (a *App) openSubagent(p *chatPane, toolUseID string) {
	v := &subagentView{toolUseID: toolUseID, list: chatview.New()}
	v.list.LoadImage, v.list.LoadMarkdownImage, v.list.OpenLink = p.list.LoadImage, p.list.LoadMarkdownImage, p.list.OpenLink
	v.list.OpenSubagent = func(id string) { a.openSubagent(p, id) }
	v.list.OpenImages = func(list []items.Image, index int) { a.openImages(v.list, list, index) }
	v.list.ExpandWork = true
	p.pages.stack = append(p.pages.stack, subpage{agent: v})
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.WatchSubagent(ctx, toolUseID, true)
	}, func(err error) { v.failed = err.Error() })
}

// unwatchSubagent 离开子代理页：下面的页里没有看着同一个的，就不再看着它。
func (a *App) unwatchSubagent(p *chatPane, v *subagentView) {
	for _, s := range p.pages.stack {
		if s.agent != nil && s.agent.toolUseID == v.toolUseID {
			return
		}
	}
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.WatchSubagent(ctx, v.toolUseID, false)
	}, nil)
}

// follow 把列表跟到子代理 s 这一版：照协议层收到的增量一段段换（文件改动的对比只丢换掉的那几步），
// 整份推来过、差得太多或对不上时整份换。
func (v *subagentView) follow(s chat.Subagent) {
	changes, ok := s.ChangesSince(v.rev)
	for _, ch := range changes {
		if ch.Full {
			v.list.SetItems(ch.Items)
		} else if v.list.Splice(ch.At, ch.Remove, ch.Items) != nil {
			ok = false
			break
		}
	}
	if !ok {
		v.list.SetItems(s.Items)
	}
	v.rev = s.Rev
}

// subagentState 是子代理的状态，照任务的状态说（taskState）：在跑优先；还没读到记录时是「正在读取」；
// 不在跑又不知道结局的，是进程没等它做完就没了。
func subagentState(s chat.Subagent) (string, tone) {
	switch {
	case s.Running:
		return taskState(tasks.StateRunning, false)
	case !s.Loaded:
		return "正在读取", toneQuiet
	}
	switch outcome := tasks.State(s.Outcome); outcome {
	case tasks.StateCompleted, tasks.StateFailed, tasks.StateStopped:
		return taskState(outcome, false)
	}
	return "未做完", toneQuiet
}

// noStepsText 是一步都还没有时的说明。
func noStepsText(s chat.Subagent, failed string) string {
	switch {
	case failed != "":
		return "没看上：" + failed
	case s.Missing:
		return "没有找到它的记录：Claude Code 还没写出来，或者会话已经删了"
	case !s.Loaded && s.Failure != "":
		return s.Failure
	case !s.Loaded:
		return "正在读取…"
	}
	return "还没有步骤"
}

// subagentPage 是子代理页：顶上是概要（说明、状态、类型、模型、工具调用）和交给它的指令，下面是它的步骤，
// 和聊天里的条目一样画，装在一张白色卡片里，卡片跟着步骤的多少长；运行中最后一行在转。整页一起滚动（同手机版）：
// 有步骤时概要和指令画在聊天列表的第一行上面，还没有步骤时是一页普通的滚动内容。
func (a *App) subagentPage(c *ui.Context, p *chatPane, v *subagentView) {
	var s chat.Subagent
	if p.src.Conv != nil {
		s, _ = p.src.Conv.Subagent(v.toolUseID)
	}
	if s.Rev != v.rev {
		v.follow(s)
	}
	coui.Header(c, "子代理", func() { a.back(p) })
	n := len(v.list.Items())
	if n == 0 {
		// 和聊天列表留一样的边：上 12、下 20、两边 24
		ui.Scroll(c.Key("subagent/page")).Grow(1).MinHeight(0).Children(func() {
			ui.Column(c).FillWidth().Padding(12, 24, 20, 24).Children(func() {
				subagentColumn(c).Children(func() {
					subagentHead(c, v, s)
					noSteps(c, v, s)
				})
			})
		})
		return
	}
	// 每一帧照这一版的 s 重设：第一行上面是概要，每一行是步骤卡片的一段
	v.list.Row = func(c *ui.Context, row int, draw func()) {
		subagentColumn(c).Children(func() {
			if row == 0 {
				subagentHead(c, v, s)
			}
			stepSegment(c, row == 0, row == n-1, func() {
				draw()
				if row == n-1 && s.Running {
					working(c).Padding(10, 0, 14, 0)
				}
			})
		})
	}
	v.list.View(c).Grow(1).MinHeight(0)
}

// subagentColumn 是子代理页内容的那一列：居中，最宽同设置页。
func subagentColumn(c *ui.Context) ui.Element {
	return ui.Column(c).FillWidth().MaxWidth(coui.PageWidth).AlignSelf(ui.Center)
}

// subagentHead 是步骤上面的部分：概要、交给它的指令和「步骤」这个分组标题。
func subagentHead(c *ui.Context, v *subagentView, s chat.Subagent) {
	// 概要由核心给：记录旁的 meta 还没读到时它先用发起子代理的 Agent 调用的参数填上
	var description, kind, model string
	if m := s.Meta; m != nil {
		description, kind, model = m.Description, m.AgentType, m.Model
	}
	subagentSummary(c, s, cmp.Or(description, "子代理"), kind, model)
	if s.Prompt != "" {
		promptBlock(c, v, s.Prompt)
	}
	title := "步骤"
	if s.Skipped > 0 {
		title += " · 更早的 " + strconv.Itoa(s.Skipped) + " 条记录未载入"
	}
	ui.Text(c, title).FontSize(coui.SizeBodyXS).FontWeight(coui.Medium).TextColor(coui.Of(c).Secondary).Padding(8, 32, 8, 32)
}

// subagentSummary 是概要卡片：第一行说明和状态，第二行类型、模型、工具调用次数（知道的才列）。手机版一项一行，
// 桌面版宽，压成两行。
func subagentSummary(c *ui.Context, s chat.Subagent, description, kind, model string) {
	k := coui.Of(c)
	var facts []string
	if kind != "" {
		facts = append(facts, "子代理类型 "+kind)
	}
	if model != "" {
		facts = append(facts, "模型 "+model)
	}
	if s.ToolUses > 0 {
		facts = append(facts, "工具调用 "+strconv.Itoa(s.ToolUses)+" 次")
	}
	coui.Group(c, "", "", func(l *coui.List) {
		l.Custom(func() {
			ui.Column(c).Grow(1).MinWidth(0).Gap(2).Padding(4, 0).Children(func() {
				ui.Text(c, description).FontSize(coui.SizeHeadlineXS).TextColor(k.Text).MaxLines(2).Selectable()
				if len(facts) > 0 {
					ui.Text(c, strings.Join(facts, " · ")).FontSize(coui.SizeBodyS).TextColor(k.Secondary).SingleLine().Ellipsis("…")
				}
			})
			state, tone := subagentState(s)
			ui.Text(c, state).FontSize(coui.SizeBodyM).TextColor(tone.color(k)).Shrink(0)
		})
	})
}

// promptBlock 是交给它的指令：长的先只露三行，点了展开（展开后最高 240，里面滚动），展开了可以再收起。
func promptBlock(c *ui.Context, v *subagentView, prompt string) {
	k := coui.Of(c)
	long := len([]rune(prompt)) > 160 || strings.Count(prompt, "\n") > 2
	coui.Group(c, "交给它的指令", "", func(*coui.List) {
		text := func() ui.Element {
			return ui.Text(c, prompt).FontSize(coui.SizeBodyM).LineHeight(1.5).TextColor(k.Text).FillWidth().Padding(12, 16)
		}
		switch {
		case !long:
			text().Selectable()
		case !v.promptOpen:
			text().MaxLines(3).Ellipsis("…").Cursor(ui.CursorPointer).Role(ui.RoleButton).Label("展开指令").
				OnClick(func() { v.promptOpen = true })
		default:
			ui.Scroll(c.Key("subagent/prompt")).FillWidth().MaxHeight(240).Children(func() { text().Selectable() })
			coui.SmallButton(c, "收起").OnClick(func() { v.promptOpen = false }).Margin(0, 16, 10, 16).AlignSelf(ui.Start)
		}
	})
}

// stepSegment 是步骤卡片里一行的那一段：白底，只有第一段画上面的圆角、最后一段画下面的圆角，几段连成一张卡片。
// 卡片里边上 12、下 16、两边 24（同原先列表在卡片里的边）。
func stepSegment(c *ui.Context, first, last bool, draw func()) {
	shape := coui.ShapeCard
	var top, bottom float32
	if first {
		top = 12
	} else {
		shape.Radii[0], shape.Radii[1] = 0, 0
	}
	if last {
		bottom = 16
	} else {
		shape.Radii[2], shape.Radii[3] = 0, 0
	}
	card := coui.Surface{Shape: shape, Fill: coui.Of(c).Card}.Paint(ui.Column(c)).Margin(0, 16).Clip()
	card.Padding(top, 24, bottom, 24).Children(draw)
}

// noSteps 是一步都还没有时的卡片：在跑时是「正在工作」，不然说明为什么没有。
func noSteps(c *ui.Context, v *subagentView, s chat.Subagent) {
	k := coui.Of(c)
	card := coui.Surface{Shape: coui.ShapeCard, Fill: k.Card}.Paint(ui.Column(c)).Margin(0, 16)
	card.Children(func() {
		if s.Running {
			working(c).Padding(14, 24)
			return
		}
		ui.Text(c, noStepsText(s, v.failed)).FontSize(coui.SizeBodyS).TextColor(k.Secondary).Padding(14, 16)
	})
}

// working 是在跑时步骤末尾转着的那一行，边距由调用方定。
func working(c *ui.Context) ui.Element {
	return ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
		ui.Spinner(c).Size(14, 14)
		ui.Text(c, "正在工作").FontSize(coui.SizeBodyS).TextColor(coui.Of(c).Secondary)
	})
}
