package ui

import (
	"cmp"
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
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
	v.list.LoadImage, v.list.LoadInlineImage, v.list.LoadMarkdownImage = p.list.LoadImage, p.list.LoadInlineImage, p.list.LoadMarkdownImage
	v.list.OpenLink = p.list.OpenLink
	v.list.AutoLoadRemote = p.list.AutoLoadRemote
	v.list.OpenSubagent = func(id string) { a.openSubagent(p, id) }
	v.list.OpenImages = func(list []items.Image, index int) { a.openImages(v.list, list, index) }
	v.list.ExpandWork = true
	// 内容最宽同设置页，步骤连成一张卡片
	v.list.Width, v.list.Framed = widget.PageWidth, true
	p.pages.stack = append(p.pages.stack, subpage{agent: v})
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Watch(ctx, chat.SubagentTarget(toolUseID), true)
	}, func(err error) { v.failed = err.Error() })
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

// subagentState 是概要卡片上的状态：核心给的结论（conv.subagent 的 state）换成文案，在跑和结局照任务的说法（taskState）。
// 还没推来时是正在读取。
func subagentState(s chat.Subagent) (string, tone) {
	switch s.State {
	case sessionproto.SubagentRunning:
		return taskState(tasks.StateRunning, false)
	case sessionproto.SubagentCompleted, sessionproto.SubagentFailed, sessionproto.SubagentStopped:
		return taskState(tasks.State(s.State), false)
	case sessionproto.SubagentReadFailed:
		return locales.SubagentStateReadFailed(), toneQuiet
	case sessionproto.SubagentMissing:
		return locales.SubagentStateMissing(), toneQuiet
	case sessionproto.SubagentLoading, "":
		// 空串是第一次推送还没到
		return locales.SubagentStateLoading(), toneQuiet
	}
	// unfinished 和认不出的值（核心以后加的）
	return locales.SubagentStateUnfinished(), toneQuiet
}

// noStepsText 是一步都还没有、又不在跑时的说明：failed 是这边没看上的原因，别的照核心给的状态说。
func noStepsText(s chat.Subagent, failed string) string {
	switch {
	case failed != "":
		return locales.SubagentWatchFailed(failed)
	case s.State == sessionproto.SubagentMissing:
		return locales.SubagentMissing()
	case s.State == sessionproto.SubagentReadFailed:
		return s.Failure
	case s.State == sessionproto.SubagentLoading, s.State == "":
		return locales.CommonLoading()
	}
	return locales.SubagentNoSteps()
}

// subagentPage 是子代理页：顶上是概要（说明、状态、类型、模型、工具调用）和交给它的指令，下面是它的步骤，
// 和聊天里的条目一样画，装在一张白色卡片里，卡片跟着步骤的多少长；运行中最后一行在转。整页一起滚动（同手机版）：
// 概要和指令是聊天列表的头部（Header），还没有步骤时头部下面是说明卡片。
func (a *App) subagentPage(c *ui.Context, p *chatPane, v *subagentView) {
	var s chat.Subagent
	if p.src.Conv != nil {
		s, _ = p.src.Conv.Subagent(v.toolUseID)
	}
	if s.Rev != v.rev {
		v.follow(s)
	}
	v.list.Cwd, v.list.Home = p.list.Cwd, p.list.Home
	widget.Header(c, locales.SubagentTitle(), func() { a.back(p) })
	// 每一帧照这一版的 s 重设
	l := v.list
	l.Header = func(c *ui.Context) {
		subagentHead(c, v, s)
		if len(l.Items()) == 0 {
			noSteps(c, v, s)
		}
	}
	l.Footer = nil
	if s.State == sessionproto.SubagentRunning {
		l.Footer = func(c *ui.Context) { working(c).Padding(10, 0, 14, 0) }
	}
	l.View(c).Grow(1).MinHeight(0)
}

// subagentHead 是步骤上面的部分：概要、交给它的指令和「步骤」这个分组标题。
func subagentHead(c *ui.Context, v *subagentView, s chat.Subagent) {
	// 概要由核心给：记录旁的 meta 还没读到时它先用发起子代理的 Agent 调用的参数填上
	var meta chat.SubagentMeta
	if s.Meta != nil {
		meta = *s.Meta
	}
	meta.Description = cmp.Or(meta.Description, locales.SubagentTitle())
	subagentSummary(c, s, meta)
	if s.Prompt != "" {
		promptBlock(c, v, s.Prompt)
	}
	title := locales.SubagentSteps()
	if s.Skipped > 0 {
		title = locales.SubagentStepsSkipped(s.Skipped)
	}
	ui.Text(c, title).FontSize(widget.SizeBodyXS).FontWeight(widget.Medium).TextColor(widget.Of(c).Secondary).Padding(8, 32, 8, 32)
}

// subagentSummary 是概要卡片：第一行说明和状态，第二行类型、模型、思考强度、工具调用次数（知道的才列）。手机版一项一行，
// 桌面版宽，压成两行。
func subagentSummary(c *ui.Context, s chat.Subagent, meta chat.SubagentMeta) {
	k := widget.Of(c)
	description := meta.Description
	var facts []string
	if meta.AgentType != "" {
		facts = append(facts, locales.SubagentKind(meta.AgentType))
	}
	if meta.Model != "" {
		facts = append(facts, locales.SubagentModel(meta.Model))
	}
	if meta.Effort != "" {
		facts = append(facts, locales.SubagentEffort(effortLabel(meta.Effort)))
	}
	if s.ToolUses > 0 {
		facts = append(facts, locales.SubagentToolUses(s.ToolUses))
	}
	widget.Group(c, "", "", func(l *widget.List) {
		l.Custom(func() {
			ui.Column(c).Grow(1).MinWidth(0).Gap(2).Padding(4, 0).Children(func() {
				ui.Text(c, description).FontSize(widget.SizeHeadlineXS).TextColor(k.Text).MaxLines(2).Selectable()
				if len(facts) > 0 {
					ui.Text(c, strings.Join(facts, " · ")).FontSize(widget.SizeBodyS).TextColor(k.Secondary).SingleLine().Ellipsis("…")
				}
			})
			state, tone := subagentState(s)
			ui.Text(c, state).FontSize(widget.SizeBodyM).TextColor(tone.color(k)).Shrink(0)
		})
	})
}

// promptBlock 是交给它的指令：长的先只露三行，点了展开（展开后最高 240，里面滚动），展开了可以再收起。
func promptBlock(c *ui.Context, v *subagentView, prompt string) {
	k := widget.Of(c)
	long := len([]rune(prompt)) > 160 || strings.Count(prompt, "\n") > 2
	widget.Group(c, locales.CommonPrompt(), "", func(*widget.List) {
		text := func() ui.Element {
			return ui.Text(c, prompt).FontSize(widget.SizeBodyM).LineHeight(1.5).TextColor(k.Text).FillWidth().Padding(12, 16)
		}
		switch {
		case !long:
			text().Selectable()
		case !v.promptOpen:
			text().MaxLines(3).Ellipsis("…").Cursor(ui.CursorPointer).Role(ui.RoleButton).Label(locales.SubagentExpandPrompt()).
				OnClick(func() { v.promptOpen = true })
		default:
			ui.Scroll(c.Key("subagent/prompt")).FillWidth().MaxHeight(240).Children(func() { text().Selectable() })
			widget.SmallButton(c, locales.SubagentCollapse()).OnClick(func() { v.promptOpen = false }).Margin(0, 16, 10, 16).AlignSelf(ui.Start)
		}
	})
}

// noSteps 是一步都还没有时的卡片：在跑时是「正在工作」，不然说明为什么没有。
func noSteps(c *ui.Context, v *subagentView, s chat.Subagent) {
	k := widget.Of(c)
	card := widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(ui.Column(c)).Margin(0, 16)
	card.Children(func() {
		if s.State == sessionproto.SubagentRunning {
			working(c).Padding(14, 24)
			return
		}
		ui.Text(c, noStepsText(s, v.failed)).FontSize(widget.SizeBodyS).TextColor(k.Secondary).Padding(14, 16)
	})
}

// working 是在跑时步骤末尾转着的那一行，边距由调用方定。
func working(c *ui.Context) ui.Element {
	return ui.Row(c).Gap(10).AlignItems(ui.Center).Children(func() {
		ui.Spinner(c).Size(14, 14)
		ui.Text(c, locales.SubagentWorking()).FontSize(widget.SizeBodyS).TextColor(widget.Of(c).Secondary)
	})
}
