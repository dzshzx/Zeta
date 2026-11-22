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

// promptForm 是输入栏上方那张确认卡里填的东西，跟着一个请求（id），换了请求就重来。
// 只有明确点按钮才算回应，换会话、点别处都不替用户做决定（同手机版）。
type promptForm struct {
	id       string
	feedback string
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

// promptCard 是输入栏上方的确认卡：Claude Code 在等的第一个请求（工具确认、计划审批、提问、MCP 表单或网址）。
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
	promptPanel(c.Key("prompt/"+pr.RequestID), func() {
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
	})
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

// promptPanel 是确认卡的样子：照 COUI 的对话框（DialogContentLayout），白底（深色是深灰）、28 的圆角、柔和的阴影，
// 四周留 20、24；手机上它是弹出的对话框，桌面上放在输入栏上方，不挡着对话。
func promptPanel(c *ui.Context, fn func()) {
	k := coui.Of(c)
	ui.Column(c).FillWidth().Padding(20, 20, 16, 20).Gap(10).Radius(28).Background(k.Dialog).Border(1, k.Edge).
		Shadow(0, 6, 28, 0, k.Shadow).Children(fn)
}

// cardTitle 是确认卡的标题：居中、18、加粗（同 COUI 对话框的标题）。
func cardTitle(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(coui.SizeHeadlineS).FontWeight(600).TextColor(coui.Of(c).Text).TextAlign(ui.Center).FillWidth()
}

// cardNote 是标题下面居中的一句说明（13、次要文字色）。
func cardNote(c *ui.Context, text string) {
	ui.Text(c, text).FontSize(coui.SizeBodyS).TextColor(coui.Of(c).Secondary).TextAlign(ui.Center).FillWidth()
}

// detailBlock 是一段等宽的原文（命令、参数、预览），太长时在框里滚动。
func detailBlock(c *ui.Context, text string) {
	if strings.TrimSpace(text) == "" {
		return
	}
	if r := []rune(text); len(r) > 4000 {
		text = string(r[:4000]) + "\n…"
	}
	k := coui.Of(c)
	ui.Scroll(c).FillWidth().MaxHeight(220).Radius(12).Background(k.CodeBody).Children(func() {
		ui.Text(c, text).FontSize(12).LineHeight(1.25).Font(coui.Mono).TextColor(k.Text.Alpha(0.8)).Padding(10, 12).Selectable()
	})
}

// toolCard 是工具确认：允许、总是允许（Claude Code 给了「以后不再问」的建议时）、拒绝（可以写理由让 Claude 换个做法）。
func (a *App) toolCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	t := c.Theme()
	var input map[string]any
	_ = json.Unmarshal(pr.Input, &input)
	title, detail := chatview.DescribeTool(pr.ToolName, input)
	ui.Column(c).FillWidth().Gap(4).Children(func() {
		cardTitle(c, "允许这次操作？")
		cardNote(c, title)
		if pr.AgentID != "" {
			cardNote(c, "来自子代理")
		}
		if pr.Reason != "" {
			cardNote(c, pr.Reason)
		}
	})
	detailBlock(c, detail)
	if len(pr.SuggestionLines) > 0 {
		ui.Text(c, "总是允许："+strings.Join(pr.SuggestionLines, "；")).FontSize(coui.SizeBodyS).TextColor(t.TextMuted).Padding(0, 4)
	}
	f := &p.form
	coui.Input(c.Key("feedback"), &f.feedback).Placeholder("拒绝时告诉 Claude 换个做法（可不填）").Label("拒绝的理由").FillWidth()
	ui.Row(c).Gap(8).Children(func() {
		deny := "拒绝"
		if strings.TrimSpace(f.feedback) != "" {
			deny = "拒绝并告诉 Claude"
		}
		coui.Button(c, deny).Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny, Feedback: strings.TrimSpace(f.feedback)})
		})
		if len(pr.SuggestionLines) > 0 {
			coui.Button(c, "总是允许").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
				a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyAllowAlways})
			})
		}
		coui.PrimaryButton(c, "允许").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyAllow})
		})
	})
}

// planMarkdown 是计划审批里排计划用的 Markdown 设置（只在界面线程上用）。
var planMarkdown markdown.Options

// planCard 是计划审批：批准后自动接受改动或逐项确认；不批准就写下要改什么，Claude 接着完善计划。
func (a *App) planCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	var input struct {
		Plan string `json:"plan"`
	}
	_ = json.Unmarshal(pr.Input, &input)
	f := &p.form
	cardTitle(c, "按这个计划执行？")
	ui.Scroll(c.Key("plan")).FillWidth().MaxHeight(320).Children(func() {
		markdown.View(c, input.Plan, &planMarkdown).FillWidth()
	})
	ui.RadioGroup(c.Key("plan-mode"), func() {
		ui.Radio(c, &f.planMode, "acceptEdits", "批准，自动接受改动")
		ui.Radio(c, &f.planMode, "default", "批准，改动逐项确认")
	}).Label("批准以后")
	coui.Input(c.Key("feedback"), &f.feedback).Placeholder("不批准：告诉 Claude 要改什么").Label("要改什么").FillWidth()
	ui.Row(c).Gap(8).Children(func() {
		coui.Button(c, "继续完善").Grow(1).Basis(0).Disabled(f.sending || strings.TrimSpace(f.feedback) == "").OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny, Feedback: strings.TrimSpace(f.feedback)})
		})
		coui.PrimaryButton(c, "批准").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyApprovePlan, Mode: f.planMode})
		})
	})
}

// questionCard 是 AskUserQuestion：每题选一个或几个，也可以自己写；带预览的单选题显示选中那项的预览、能写备注；
// 也可以先不答，和 Claude 聊聊这些问题。
func (a *App) questionCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	f := &p.form
	t := c.Theme()
	if len(pr.Questions) == 1 {
		cardTitle(c, pr.Questions[0].Question)
	} else {
		cardTitle(c, "需要你的选择")
	}
	complete := true
	for i, q := range pr.Questions {
		qa := &f.answers[i]
		if qa.text(q) == "" {
			complete = false
		}
		ui.Column(c.Key("question/" + strconv.Itoa(i))).Gap(4).Children(func() {
			if len(pr.Questions) > 1 {
				ui.Text(c, q.Question).FontSize(13).TextColor(t.TextMuted)
			}
			questionOptions(c, q, qa)
		})
	}
	ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
		answers, notes := questionReplies(pr.Questions, f.answers)
		coui.Button(c, "先和 Claude 聊聊").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyClarify, Answers: answers, Notes: notes})
		})
		coui.Button(c, "跳过").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyDeny})
		})
		coui.PrimaryButton(c, "确定").Grow(1).Basis(0).Disabled(f.sending || !complete).OnClick(func() {
			a.answer(p, pr, prompt.Reply{Kind: prompt.ReplyAnswer, Answers: answers, Notes: notes})
		})
	})
}

// questionOptions 是一个问题的选项：单选题点一项换成它，多选题各项开关；最后一项「其他」是自己写。
func questionOptions(c *ui.Context, q prompt.Question, qa *questionAnswer) {
	t := c.Theme()
	for i, o := range q.Options {
		on := slices.Contains(qa.chosen, o.Label) && (q.MultiSelect || !qa.otherOn)
		label := o.Label
		if o.Description != "" {
			label += " —— " + o.Description
		}
		choiceRow(c.Key("option/"+strconv.Itoa(i)), label, on, q.MultiSelect, func() {
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
	choiceRow(c.Key("other"), "其他", qa.otherOn, q.MultiSelect, func() {
		qa.otherOn = !q.MultiSelect || !qa.otherOn
		if !q.MultiSelect {
			qa.chosen = nil
		}
	})
	if qa.otherOn {
		coui.Input(c.Key("other-text"), &qa.other).Placeholder("自己写").Label("自己写").FillWidth()
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
		coui.Input(c.Key("notes"), &qa.notes).Placeholder("备注（可不填）").Label("备注").FillWidth()
	}
	_ = t
}

// choiceRow 是一个可以点选的选项（COUI 的单选、复选框）：前面一个标记，单选是圆的，多选和开关（square）是圆角方块；
// 选中时填上主题色、里面一个白色对勾。
func choiceRow(c *ui.Context, label string, on, square bool, toggle func()) {
	k := coui.Of(c)
	row := ui.Row(c).Gap(10).Padding(7, 10).Radius(12).AlignItems(ui.Center).Cursor(ui.CursorPointer).Role(ui.RoleButton).
		Label(label).Focusable()
	if row.Hovered() {
		row.Background(k.Hover)
	}
	row.OnClick(toggle)
	row.Children(func() {
		mark := ui.Box(c).Size(18, 18).Shrink(0).Radius(9).Border(1.5, k.Tertiary).Center()
		if square {
			mark.Radius(5)
		}
		if on {
			mark.Background(k.Primary).Border(1.5, k.Primary).Children(func() {
				ui.Icon(c, coui.IconOK).Size(12, 12).TextColor(k.OnPrimary)
			})
		}
		ui.Text(c, label).FontSize(coui.SizeBodyM).TextColor(k.Text).MaxLines(2)
	})
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

// elicitCard 是 MCP 服务器的表单（逐项填，核心核对，不对的原因写在那一项下面）或网址（去网页上做，点「打开网页」算同意）。
func (a *App) elicitCard(c *ui.Context, p *chatPane, pr prompt.Prompt) {
	t := c.Theme()
	f := &p.form
	if pr.URL != "" {
		cardTitle(c, pr.Server+" 请你去网页上操作")
	} else {
		cardTitle(c, pr.Server+" 需要你填写")
	}
	if pr.Message != "" {
		ui.Text(c, pr.Message).FontSize(13)
	}
	if pr.URL != "" {
		ui.Text(c, pr.URL).FontSize(12).TextColor(t.TextMuted).Selectable()
	}
	for _, field := range pr.Fields {
		ui.Column(c.Key("field/" + field.Key)).Gap(4).Children(func() { elicitField(c, field, f) })
	}
	ui.Row(c).Gap(8).Children(func() {
		coui.Button(c, "拒绝").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() { a.elicit(p, pr, prompt.ElicitDecline, nil) })
		if pr.URL != "" {
			coui.PrimaryButton(c, "打开网页").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
				c.OpenURL(pr.URL)
				a.elicit(p, pr, prompt.ElicitAccept, nil)
			})
			return
		}
		coui.PrimaryButton(c, "提交").Grow(1).Basis(0).Disabled(f.sending).OnClick(func() {
			a.elicit(p, pr, prompt.ElicitAccept, f.values)
		})
	})
}

// elicitField 是表单的一项：开关、单选、多选各有各的样子，别的是输入框；核心说不对时原因写在下面。
func elicitField(c *ui.Context, field prompt.Field, f *promptForm) {
	t := c.Theme()
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
		choiceRow(c.Key("toggle"), title, on, true, func() { set([]string{strconv.FormatBool(!on)}) })
	case prompt.FieldSingle, prompt.FieldMulti:
		ui.Text(c, title).FontSize(13)
		for i, ch := range field.Choices {
			on := slices.Contains(value, ch.Value)
			choiceRow(c.Key("choice/"+strconv.Itoa(i)), ch.Label, on, field.Kind == prompt.FieldMulti, func() {
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
	default:
		text := f.texts[field.Key]
		if text == nil {
			text = new(string)
			if len(value) > 0 {
				*text = value[0]
			}
			f.texts[field.Key] = text
		}
		ui.Text(c, title).FontSize(13)
		input := coui.Input(c.Key("text"), text).Placeholder(field.Description).Label(title).FillWidth()
		if input.Changed() {
			delete(f.errors, field.Key)
		}
	}
	if field.Description != "" && (field.Kind == prompt.FieldToggle || field.Kind == prompt.FieldSingle || field.Kind == prompt.FieldMulti) {
		ui.Text(c, field.Description).FontSize(12).TextColor(t.TextMuted)
	}
	if why := f.errors[field.Key]; why != "" {
		ui.Text(c, why).FontSize(12).TextColor(t.Danger)
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
