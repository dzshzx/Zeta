package ui

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/prompt"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// promptForm 是确认、提问对话框里填的东西，跟着一个请求（id），换了请求就重来。
// 只有明确点按钮才算回应，换会话、点别处都不替用户做决定（同手机版）。
type promptForm struct {
	id       string
	feedback string
	// remember：勾了「以后不再询问」，允许时按 Claude Code 给的建议记下来。
	remember bool
	// planMode 是批准计划以后的权限模式。
	planMode string
	answers  []questionAnswer
	// values、errors 是 MCP 表单各项填的（键 → 输入框里的字，多选是选中的各值）和核心说不对的原因。
	values map[string][]string
	// texts 是表单里输入框的字（键 → 字），输入框直接改它；交出去时并进 values。
	texts  map[string]*string
	errors map[string]string
	// sending：回应发出去了，等核心收起这个请求。
	sending bool
}

// questionAnswer 是一个问题的作答：选中的选项、自己写的（「其他」）和备注。
type questionAnswer struct {
	chosen  []string
	otherOn bool
	other   string
	notes   string
}

// text 是交回去的回答：多选按选项的顺序用 ", " 连起来，自己写的排在最后。
func (qa *questionAnswer) text(q prompt.Question) string {
	var parts []string
	for _, o := range q.Options {
		if slices.Contains(qa.chosen, o.Label) && (q.MultiSelect || !qa.otherOn) {
			parts = append(parts, o.Label)
		}
	}
	if own := strings.TrimSpace(qa.other); qa.otherOn && own != "" {
		parts = append(parts, own)
	}
	return strings.Join(parts, ", ")
}

// promptCard 是 Claude Code 在等的第一个请求（工具确认、计划审批、提问、MCP 表单或网址）：照手机版（PromptSheet、
// ElicitationSheet）弹在窗口中间的 COUI 对话框，不点按钮关不掉。
func (a *App) promptCard(c *ui.Context, p *chatPane) {
	if len(p.state.Prompts) == 0 {
		return
	}
	pr := p.state.Prompts[0]
	f := &p.form
	if f.id != pr.RequestID {
		*f = promptForm{id: pr.RequestID, planMode: "acceptEdits", answers: make([]questionAnswer, len(pr.Questions)),
			values: map[string][]string{}, texts: map[string]*string{}, errors: map[string]string{}}
		for _, field := range pr.Fields {
			f.values[field.Key] = slices.Clone(field.Default)
		}
	}
	c = c.Key("prompt/" + pr.RequestID)
	switch {
	case pr.Kind == prompt.KindElicitation:
		a.elicitCard(c, p, pr)
	case len(pr.Questions) > 0:
		a.questionCard(c, p, pr)
	case pr.IsPlan:
		a.planCard(c, p, pr)
	default:
		a.toolCard(c, p, pr)
	}
}

// answer 交回对请求 pr 的回应；没成时原因显示出来，卡留着可以再点。
func (a *App) answer(p *chatPane, pr prompt.Prompt, reply prompt.Reply) {
	p.form.sending = true
	p.failed = ""
	id := pr.RequestID
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Answer(ctx, id, reply)
	}, func(err error) {
		p.form.sending = false
		p.fail(err)
	})
}

// detailBlock 是对话框里一段等宽的原文（命令、参数、预览，同手机版 DetailBlock）：淡灰底、12 的平滑圆角，太长时在框里滚动。
func detailBlock(c *ui.Context, text string) {
	if strings.TrimSpace(text) == "" {
		return
	}
	if r := []rune(text); len(r) > 4000 {
		text = string(r[:4000]) + "\n…"
	}
	k := coui.Of(c)
	ui.Column(c).FillWidth().Padding(0, coui.DialogPadding).Children(func() {
		box := coui.Surface{Shape: coui.ShapeBlock, Fill: k.DialogBlock}.Paint(ui.Scroll(c)).FillWidth().MaxHeight(260)
		box.Children(func() {
			ui.Text(c, text).FontSize(12).LineHeight(1.25).Font(coui.Mono).TextColor(k.Text.Alpha(0.8)).Padding(10, 12).Selectable()
		})
	})
}

// dialogButton 是对话框按钮栏里的一个按钮，和别的一样宽。
func dialogButton(c *ui.Context, label string, primary bool) ui.Element {
	style := coui.Plain
	if primary {
		style = coui.Recommend
	}
	return coui.DialogButton(c, label, style)
}

// toolCard 是工具确认（ToolSheet）：命令或参数；Claude Code 给了「以后不再问」的建议时可以勾「以后不再询问」；
// 拒绝时可以写理由让 Claude 换个做法。
func (a *App) toolCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	var input map[string]any
	_ = json.Unmarshal(pr.Input, &input)
	title, detail := chatview.DescribeTool(pr.ToolName, input)
	summary := []string{title}
	if pr.AgentID != "" {
		summary = append(summary, "来自子代理")
	}
	if pr.Reason != "" {
		summary = append(summary, pr.Reason)
	}
	f := &p.form
	coui.Sheet(c, "允许这次操作？", strings.Join(summary, "\n"), 460, func() {
		detailBlock(c, detail)
		if len(pr.SuggestionLines) > 0 {
			coui.Choice(c, "以后不再询问", strings.Join(pr.SuggestionLines, "\n"), coui.MarkCheck, f.remember).
				OnClick(func() { f.remember = !f.remember })
		}
		ui.Box(c).FillWidth().Padding(0, coui.DialogPadding).Children(func() {
			coui.LineInput(c.Key("feedback"), &f.feedback, 4).Placeholder("拒绝时告诉 Claude 换个做法（可不填）").Label("拒绝的理由")
		})
	}, func() {
		deny := "拒绝"
		if strings.TrimSpace(f.feedback) != "" {
			deny = "拒绝并告诉 Claude"
		}
		dialogButton(c, deny, false).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny, Feedback: strings.TrimSpace(f.feedback)})
		})
		dialogButton(c, "允许", true).Disabled(f.sending).OnClick(func() {
			kind := prompt.ReplyAllow
			if f.remember && len(pr.SuggestionLines) > 0 {
				kind = prompt.ReplyAllowAlways
			}
			a.answer(p, pr, prompt.Reply{Kind: kind})
		})
	})
}

// planMarkdown、planTheme 是计划审批里排计划用的 Markdown 设置（只在界面线程上用）：同 Compose 的 PlanSheet，
// 正文 13，标题和正文一样大、只加粗。
var (
	planMarkdown markdown.Options
	planTheme    markdown.Theme
)

func stylePlan(c *ui.Context) {
	k := coui.Of(c)
	planTheme = *markdown.DefaultTheme(c.Theme())
	planTheme.FontSize, planTheme.Text, planTheme.Strong = coui.SizeBodyS, k.Text, k.Text
	for i := range planTheme.Headings {
		planTheme.Headings[i] = markdown.HeadingStyle{Size: 1, Weight: 600, Top: 0.6, Bottom: 0.3}
	}
	planMarkdown.Theme = &planTheme
}

// planCard 是计划审批（PlanSheet）：计划正文；批准后自动接受改动或逐项确认；不批准就写下要改什么，Claude 接着完善计划。
func (a *App) planCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	var input struct {
		Plan string `json:"plan"`
	}
	_ = json.Unmarshal(pr.Input, &input)
	f := &p.form
	coui.Sheet(c, "按这个计划执行？", "", 520, func() {
		ui.Box(c).FillWidth().Padding(4, coui.DialogPadding).Children(func() {
			stylePlan(c)
			markdown.View(c, input.Plan, &planMarkdown).FillWidth()
		})
		for _, m := range []struct{ value, label string }{{"acceptEdits", "批准，自动接受改动"}, {"default", "批准，改动逐项确认"}} {
			coui.Choice(c.Key(m.value), m.label, "", coui.MarkRadio, f.planMode == m.value).OnClick(func() { f.planMode = m.value })
		}
		ui.Box(c).FillWidth().Padding(0, coui.DialogPadding).Children(func() {
			coui.LineInput(c.Key("feedback"), &f.feedback, 4).Placeholder("不批准：告诉 Claude 要改什么").Label("要改什么")
		})
	}, func() {
		dialogButton(c, "继续完善", false).Disabled(f.sending || strings.TrimSpace(f.feedback) == "").OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny, Feedback: strings.TrimSpace(f.feedback)})
		})
		dialogButton(c, "批准", true).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyApprovePlan, Mode: f.planMode})
		})
	})
}

// questionCard 是 AskUserQuestion（QuestionSheet）：每题选一个或几个，也可以自己写；带预览的单选题显示选中那项的预览、
// 能写备注；也可以先不答，和 Claude 聊聊这些问题。
func (a *App) questionCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	f := &p.form
	k := coui.Of(c)
	title := "需要你的选择"
	if len(pr.Questions) == 1 {
		title = pr.Questions[0].Question
	}
	complete := true
	for i, q := range pr.Questions {
		if f.answers[i].text(q) == "" {
			complete = false
		}
	}
	answers, notes := questionReplies(pr.Questions, f.answers)
	coui.Sheet(c, title, "", 520, func() {
		for i, q := range pr.Questions {
			qa := &f.answers[i]
			ui.Column(c.Key("question/" + strconv.Itoa(i))).FillWidth().Children(func() {
				if len(pr.Questions) > 1 {
					ui.Text(c, q.Question).FontSize(coui.SizeBodyM).TextColor(k.Secondary).Padding(12, coui.DialogPadding, 4, coui.DialogPadding)
				}
				questionOptions(c, q, qa)
			})
		}
		clarify := ui.Text(c, "先不回答，和 Claude 聊聊这些问题").FontSize(coui.SizeBodyS).TextColor(k.PrimaryText).
			Padding(12, coui.DialogPadding).Cursor(ui.CursorPointer).Role(ui.RoleButton)
		if !f.sending {
			clarify.OnClick(func() {
				a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyClarify, Answers: answers, Notes: notes})
			})
		}
	}, func() {
		dialogButton(c, "跳过", false).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny})
		})
		dialogButton(c, "确定", true).Disabled(f.sending || !complete).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyAnswer, Answers: answers, Notes: notes})
		})
	})
}

// questionOptions 是一个问题的选项：单选题点一项换成它，多选题各项开关；最后一项「其他」是自己写。
func questionOptions(c *ui.Context, q prompt.Question, qa *questionAnswer) {
	mark := coui.MarkRadio
	if q.MultiSelect {
		mark = coui.MarkCheck
	}
	for i, o := range q.Options {
		on := slices.Contains(qa.chosen, o.Label) && (q.MultiSelect || !qa.otherOn)
		coui.Choice(c.Key("option/"+strconv.Itoa(i)), o.Label, o.Description, mark, on).OnClick(func() {
			switch {
			case !q.MultiSelect:
				qa.chosen, qa.otherOn = []string{o.Label}, false
			case on:
				qa.chosen = slices.DeleteFunc(qa.chosen, func(s string) bool { return s == o.Label })
			default:
				qa.chosen = append(qa.chosen, o.Label)
			}
		})
	}
	coui.Choice(c.Key("other"), "其他", "", mark, qa.otherOn).OnClick(func() {
		qa.otherOn = !q.MultiSelect || !qa.otherOn
		if !q.MultiSelect {
			qa.chosen = nil
		}
	})
	field := func(key string, value *string, label, hint string, lines int) {
		ui.Box(c).FillWidth().Padding(0, coui.DialogPadding).Children(func() {
			coui.LineInput(c.Key(key), value, lines).Placeholder(hint).Label(label)
		})
	}
	if qa.otherOn {
		field("other-text", &qa.other, "自己写", "自己写", 4)
		return
	}
	if q.MultiSelect || len(qa.chosen) == 0 {
		return
	}
	i := slices.IndexFunc(q.Options, func(o prompt.Option) bool { return o.Label == qa.chosen[0] })
	if i >= 0 && q.Options[i].Preview != nil {
		detailBlock(c, *q.Options[i].Preview)
	}
	if q.TakesNotes {
		field("notes", &qa.notes, "备注", "备注（可不填）", 3)
	}
}

// questionReplies 是各题的回答和备注（问题 → 回答），没答的不列。
func questionReplies(questions []prompt.Question, answers []questionAnswer) (map[string]string, map[string]string) {
	replies, notes := map[string]string{}, map[string]string{}
	for i, q := range questions {
		if text := answers[i].text(q); text != "" {
			replies[q.Question] = text
		}
		if n := strings.TrimSpace(answers[i].notes); n != "" {
			notes[q.Question] = n
		}
	}
	return replies, notes
}

// elicitCard 是 MCP 服务器的表单（逐项填，核心核对，不对的原因写在那一项下面）或网址（去网页上做，点「打开网页」算同意），
// 照手机版的 ElicitationSheet。
func (a *App) elicitCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	k := coui.Of(c)
	f := &p.form
	title := pr.Server + " 需要你填写"
	if pr.URL != "" {
		title = pr.Server + " 请你去网页上操作"
	}
	coui.Sheet(c, title, pr.Message, 520, func() {
		if pr.URL != "" {
			ui.Text(c, pr.URL).FontSize(coui.SizeBodyS).TextColor(k.PrimaryText).Selectable().TextAlign(ui.Center).FillWidth().
				Padding(0, coui.DialogPadding)
		}
		for _, field := range pr.Fields {
			ui.Column(c.Key("field/" + field.Key)).FillWidth().Children(func() { elicitField(c, field, f) })
		}
	}, func() {
		dialogButton(c, "拒绝", false).Disabled(f.sending).OnClick(func() { a.elicit(p, pr, prompt.ElicitDecline, nil) })
		if pr.URL != "" {
			dialogButton(c, "打开网页", true).Disabled(f.sending).OnClick(func() {
				c.OpenURL(pr.URL)
				a.elicit(p, pr, prompt.ElicitAccept, nil)
			})
			return
		}
		dialogButton(c, "提交", true).Disabled(f.sending).OnClick(func() {
			a.elicit(p, pr, prompt.ElicitAccept, f.values)
		})
	})
}

// elicitField 是表单的一项：开关是开关行，单选、多选是选项行，别的是输入框；核心说不对时原因写在下面。
func elicitField(c *ui.Context, field prompt.Field, f *promptForm) {
	k := coui.Of(c)
	title := field.Title
	if title == "" {
		title = field.Key
	}
	if field.Required {
		title += " *"
	}
	value := f.values[field.Key]
	set := func(v []string) {
		f.values[field.Key] = v
		delete(f.errors, field.Key)
	}
	switch field.Kind {
	case prompt.FieldToggle:
		on := len(value) > 0 && value[0] == "true"
		coui.Choice(c.Key("toggle"), title, field.Description, coui.MarkSwitch, on).
			OnClick(func() { set([]string{strconv.FormatBool(!on)}) })
	case prompt.FieldSingle, prompt.FieldMulti:
		ui.Text(c, title).FontSize(coui.SizeBodyM).TextColor(k.Secondary).Padding(12, coui.DialogPadding, 4, coui.DialogPadding)
		mark := coui.MarkRadio
		if field.Kind == prompt.FieldMulti {
			mark = coui.MarkCheck
		}
		for i, ch := range field.Choices {
			on := slices.Contains(value, ch.Value)
			coui.Choice(c.Key("choice/"+strconv.Itoa(i)), ch.Label, "", mark, on).OnClick(func() {
				switch {
				case field.Kind == prompt.FieldSingle:
					set([]string{ch.Value})
				case on:
					set(slices.DeleteFunc(slices.Clone(value), func(s string) bool { return s == ch.Value }))
				default:
					set(append(slices.Clone(value), ch.Value))
				}
			})
		}
		if field.Description != "" {
			ui.Text(c, field.Description).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).Padding(0, coui.DialogPadding)
		}
	default:
		text := f.texts[field.Key]
		if text == nil {
			text = new(string)
			if len(value) > 0 {
				*text = value[0]
			}
			f.texts[field.Key] = text
		}
		ui.Column(c).FillWidth().Padding(4, coui.DialogPadding, 0, coui.DialogPadding).Children(func() {
			ui.Text(c, title).FontSize(coui.SizeBodyS).TextColor(k.Secondary)
			hint := field.Description
			if hint == "" {
				hint = title
			}
			input := coui.LineInput(c.Key("text"), text, 1).Placeholder(hint).Label(title)
			if input.Changed() {
				delete(f.errors, field.Key)
			}
		})
	}
	if why := f.errors[field.Key]; why != "" {
		ui.Text(c, why).FontSize(coui.SizeBodyXS).TextColor(k.Error).Padding(2, coui.DialogPadding, 4, coui.DialogPadding)
	}
}

// elicit 交回对 MCP 表单、网址的回应；表单有几项不对时把原因写到各项下面，表单留着。
func (a *App) elicit(p *chatPane, pr prompt.Prompt, action prompt.ElicitAction, values map[string][]string) {
	p.form.sending = true
	p.failed = ""
	id := pr.RequestID
	values = cloneValues(values)
	for k, text := range p.form.texts {
		if values != nil {
			values[k] = []string{*text}
		}
	}
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Elicit(ctx, id, action, values)
	}, func(err error) {
		p.form.sending = false
		var problems chat.FormError
		if errors.As(err, &problems) && p.form.id == id {
			p.form.errors = problems
			return
		}
		p.fail(err)
	})
}

func cloneValues(values map[string][]string) map[string][]string {
	if values == nil {
		return nil
	}
	out := make(map[string][]string, len(values))
	for k, v := range values {
		out[k] = slices.Clone(v)
	}
	return out
}
