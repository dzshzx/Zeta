package ui

import (
	"cmp"
	"context"
	"strconv"
	"strings"

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
	v.list.ExpandWork = true
	p.pages.stack = append(p.pages.stack, subpage{kind: pageSubagent, agent: v})
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.WatchSubagent(ctx, toolUseID, true)
	}, func(err error) { v.failed = err.Error() })
}

// unwatchSubagent 离开子代理页：下面的页里没有看着同一个的，就不再看着它。
func (a *App) unwatchSubagent(p *chatPane, v *subagentView) {
	for _, s := range p.pages.stack {
		if s.kind == pageSubagent && s.agent.toolUseID == v.toolUseID {
			return
		}
	}
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.WatchSubagent(ctx, v.toolUseID, false)
	}, nil)
}

// subagentState 是子代理的状态：在跑优先；不在跑又不知道结局的，是进程没等它做完就没了。
func subagentState(s chat.Subagent) string {
	switch {
	case !s.Loaded && !s.Running:
		return "正在读取"
	case s.Running:
		return "运行中"
	case s.Outcome == "completed":
		return "已完成"
	case s.Outcome == "failed":
		return "失败"
	case s.Outcome == "stopped":
		return "已停止"
	}
	return "未做完"
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

// agentCall 是发起子代理的那一步（参数里有说明、类型、指令）：在聊天里，或在开着的子代理页的记录里（它又派的）。
func (p *chatPane) agentCall(toolUseID string) (items.Step, bool) {
	lists := [][]items.Item{p.list.Items()}
	for _, s := range p.pages.stack {
		if s.kind == pageSubagent {
			lists = append(lists, s.agent.list.Items())
		}
	}
	for _, list := range lists {
		for _, it := range list {
			for _, st := range it.Steps {
				if st.Kind == items.Tool && st.ID == toolUseID {
					return st, true
				}
			}
		}
	}
	return items.Step{}, false
}

// subagentPage 是子代理页：顶上是概要（说明、状态、类型、模型、工具调用）和交给它的指令，下面是它的步骤，
// 和聊天里的条目一样画；运行中最后一行在转。
func (a *App) subagentPage(c *ui.Context, p *chatPane, v *subagentView) {
	var s chat.Subagent
	if p.src.Conv != nil {
		s, _ = p.src.Conv.Subagent(v.toolUseID)
	}
	if s.Rev != v.rev {
		v.list.SetItems(s.Items)
		v.rev = s.Rev
	}
	call, _ := p.agentCall(v.toolUseID)
	description, kind, model, prompt := call.Str("description"), call.Str("subagent_type"), "", s.Prompt
	if m := s.Meta; m != nil {
		description, kind, model = cmp.Or(m.Description, description), cmp.Or(m.AgentType, kind), m.Model
	}
	prompt = cmp.Or(prompt, call.Str("prompt"))
	coui.Header(c, "子代理", func() { a.back(p) })
	ui.Column(c).FillWidth().MaxWidth(coui.PageWidth).AlignSelf(ui.Center).Grow(1).MinHeight(0).Padding(4, 0, 16, 0).Children(func() {
		subagentSummary(c, s, cmp.Or(description, "子代理"), kind, model)
		if prompt != "" {
			promptBlock(c, v, prompt)
		}
		subagentSteps(c, v, s)
	})
}

// subagentSummary 是概要卡片：第一行说明和状态，第二行类型、模型、工具调用次数（知道的才列）。手机版一项一行，
// 桌面版的步骤列表要留够高度，压成两行。
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
			color := k.Tertiary
			switch {
			case s.Running:
				color = k.PrimaryText
			case s.Outcome == "completed":
				color = k.Success
			case s.Outcome == "failed":
				color = k.Error
			}
			ui.Text(c, subagentState(s)).FontSize(coui.SizeBodyM).TextColor(color).Shrink(0)
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

// subagentSteps 是它的步骤：白色卡片里的聊天列表，占满剩下的高度。
func subagentSteps(c *ui.Context, v *subagentView, s chat.Subagent) {
	k := coui.Of(c)
	title := "步骤"
	if s.Skipped > 0 {
		title += " · 更早的 " + strconv.Itoa(s.Skipped) + " 条记录未载入"
	}
	ui.Text(c, title).FontSize(coui.SizeBodyXS).FontWeight(coui.Medium).TextColor(k.Secondary).Padding(8, 32, 8, 32)
	card := coui.Surface{Shape: coui.ShapeCard, Fill: k.Card}.Paint(ui.Column(c)).Margin(0, 16).Clip().Grow(1).MinHeight(0)
	card.Children(func() {
		if len(v.list.Items()) == 0 && !s.Running {
			ui.Text(c, noStepsText(s, v.failed)).FontSize(coui.SizeBodyS).TextColor(k.Secondary).Padding(14, 16)
			return
		}
		v.list.View(c).Grow(1).MinHeight(0)
		if s.Running {
			ui.Row(c).Padding(10, 24, 14, 24).Gap(10).AlignItems(ui.Center).Children(func() {
				ui.Spinner(c).Size(14, 14)
				ui.Text(c, "正在工作").FontSize(coui.SizeBodyS).TextColor(k.Secondary)
			})
		}
	})
}
