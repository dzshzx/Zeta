package ui

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/egoist/mygo/ui"
)

// composer 是输入栏：多行输入框（Enter 发送，Shift+Enter 换行；回答中发的进排队条），下面一行是权限模式、模型、
// 思考强度，右边是用量、停止和发送。
func (a *App) composer(c *ui.Context, p *chatPane) {
	t := c.Theme()
	ready := p.src.Conv != nil && p.src.CwdProblem == ""
	ui.Column(c).FillWidth().Padding(10, 12, 8, 12).Gap(6).Radius(14).Background(t.Surface).Border(1, t.Border).Children(func() {
		ui.TextAreaBase(c.Key("draft"), &p.draft).Placeholder("发消息").Label("消息").FillWidth().MinHeight(44).MaxHeight(220).
			Disabled(!ready).OnTextKey(func(k ui.TextKey) bool {
			if k.Key != ui.KeyEnter || k.Mods != 0 {
				return false // Shift+Enter 换行
			}
			if a.send(k.Text()) {
				k.SetText("")
			}
			return true
		})
		ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
			if p.src.Conv != nil {
				a.modeMenu(c, p)
				a.modelMenu(c, p)
				a.effortMenu(c, p)
			}
			ui.Spacer(c)
			if p.src.Conv != nil {
				a.usageButton(c, p)
			}
			if p.state.Busy {
				ui.Button(c, "停止").OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Interrupt(ctx) })
				})
			}
			send := ui.PrimaryButton(c, "发送").Disabled(!ready || strings.TrimSpace(p.draft) == "")
			send.OnClick(func() {
				if a.send(p.draft) {
					p.draft = ""
				}
			})
		})
	})
}

// send 发出输入框里的 text：空的、对话没打开或起不了进程时不发，返回 false（输入框不清）。没发出去时原因显示在输入栏上方，
// 字放回输入框。
func (a *App) send(text string) bool {
	p := a.pane
	if p == nil || p.src.Conv == nil || p.src.CwdProblem != "" || strings.TrimSpace(text) == "" {
		return false
	}
	p.list.ScrollToEnd()
	p.failed = ""
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, chat.Outgoing{Text: text})
	}, func(err error) {
		p.failed = "没发出去：" + err.Error()
		p.recall(text)
	})
	return true
}

// permissionModes 是权限模式的选项，和手机版一样的说法。
var permissionModes = []struct{ value, label string }{
	{"default", "逐项确认"},
	{"acceptEdits", "自动接受编辑"},
	{"plan", "计划模式"},
	{"auto", "自动判断"},
	{"bypassPermissions", "完全放行"},
}

func modeLabel(mode string) string {
	for _, m := range permissionModes {
		if m.value == mode {
			return m.label
		}
	}
	if mode == "" {
		return "默认权限"
	}
	return mode
}

// modeMenu 是权限模式：用户在这个对话里选的，没选过时是进程此刻用的。
func (a *App) modeMenu(c *ui.Context, p *chatPane) {
	current := p.state.Mode.User
	if current == "" {
		current = p.state.Mode.Process
	}
	ui.PopoverMenuButton(c.Key("mode"), modeLabel(current), func(m *ui.Menu) {
		for _, mode := range permissionModes {
			if m.Item(mode.label).Radio(mode.value == current).Chosen() {
				value := mode.value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetMode(ctx, &value) })
			}
		}
	}).Label("权限模式")
}

// fallbackModels 是进程还没起、Claude Code 还没报模型清单时给的几个常用别名（它自己认）。
var fallbackModels = []chat.ModelOption{{Value: "opus", DisplayName: "Opus"}, {Value: "sonnet", DisplayName: "Sonnet"}, {Value: "haiku", DisplayName: "Haiku"}}

// modelMenu 是模型：「默认」（Claude Code 的设置）和 Claude Code 报的各个模型。
func (a *App) modelMenu(c *ui.Context, p *chatPane) {
	models := p.state.Models
	if len(models) == 0 {
		models = fallbackModels
	}
	chosen := ""
	if p.state.Model != nil {
		chosen = *p.state.Model
	}
	ui.PopoverMenuButton(c.Key("model"), modelLabel(p.state, models), func(m *ui.Menu) {
		if m.Item("默认").Radio(chosen == "" || chosen == chat.DefaultModel).Chosen() {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, nil) })
		}
		for _, o := range models {
			if o.Value == chat.DefaultModel {
				continue
			}
			if m.Item(optionName(o)).Radio(o.Value == chosen).Chosen() {
				value := o.Value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, &value) })
			}
		}
	}).Label("模型")
}

func optionName(o chat.ModelOption) string {
	if o.DisplayName != "" {
		return o.DisplayName
	}
	return readableModel(o.Value)
}

// modelLabel 是模型按钮上的字：选了的写它的名字；没选时是「默认」，Claude Code 报了实际用的模型时写在括号里。
func modelLabel(s chat.State, models []chat.ModelOption) string {
	if s.Model != nil && *s.Model != "" && *s.Model != chat.DefaultModel {
		for _, o := range models {
			if o.Value == *s.Model {
				return optionName(o)
			}
		}
		return readableModel(*s.Model)
	}
	if s.ReportedModel != "" {
		return "默认（" + readableModel(s.ReportedModel) + "）"
	}
	return "默认"
}

var modelID = regexp.MustCompile(`^claude-([a-z]+)-(\d+(?:-\d{1,2})*)(?:-\d{8})?(\[1m\])?$`)

// readableModel 把完整的模型 ID 写成人看的名字（claude-opus-5-5 → Opus 5.5），认不出的原样。
func readableModel(id string) string {
	m := modelID.FindStringSubmatch(id)
	if m == nil {
		return id
	}
	name := strings.ToUpper(m[1][:1]) + m[1][1:] + " " + strings.ReplaceAll(m[2], "-", ".")
	if m[3] != "" {
		name += " (1M)"
	}
	return name
}

// effortLevels 是思考强度的几档（Claude Code 的 effort）。
var effortLevels = []struct{ value, label string }{
	{"low", "低"}, {"medium", "中"}, {"high", "高"}, {"xhigh", "超高"}, {"max", "最高"},
}

func effortLabel(v string) string {
	for _, l := range effortLevels {
		if l.value == v {
			return l.label
		}
	}
	return v
}

// effortMenu 是思考强度：「默认」（Claude Code 的设置，实际生效的写在括号里）和几档。
func (a *App) effortMenu(c *ui.Context, p *chatPane) {
	chosen := ""
	if p.state.Effort != nil {
		chosen = *p.state.Effort
	}
	label := "思考：默认"
	switch {
	case chosen != "":
		label = "思考：" + effortLabel(chosen)
	case p.state.Usage.AppliedEffort != "":
		label = "思考：默认（" + effortLabel(p.state.Usage.AppliedEffort) + "）"
	}
	ui.PopoverMenuButton(c.Key("effort"), label, func(m *ui.Menu) {
		if m.Item("默认").Radio(chosen == "").Chosen() {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, nil) })
		}
		for _, l := range effortLevels {
			if m.Item(l.label).Radio(l.value == chosen).Chosen() {
				value := l.value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, &value) })
			}
		}
	}).Label("思考强度")
}

// usageButton 是用量：按钮上是上下文用了多少，点开是上下文、本对话累计和压缩上下文。
func (a *App) usageButton(c *ui.Context, p *chatPane) {
	u := p.state.Usage
	t := c.Theme()
	label := "上下文"
	if u.Fraction != nil {
		label += fmt.Sprintf(" %d%%", int(*u.Fraction*100))
	}
	button := ui.Button(c.Key("usage"), label).OnClick(func() { p.usage = !p.usage })
	switch u.Level {
	case usage.LevelCritical:
		button.TextColor(t.Danger)
	case usage.LevelWarning:
		button.TextColor(t.Warning)
	}
	ui.Popover(c.Key("usage-panel"), button, &p.usage, func() {
		ui.Column(c).Width(320).Padding(4).Gap(10).Children(func() { a.usageDetails(c, p) })
	})
}

// usageDetails 是用量弹出框的内容。
func (a *App) usageDetails(c *ui.Context, p *chatPane) {
	u := p.state.Usage
	t := c.Theme()
	contextText := "发出第一条消息后显示"
	if u.ContextTokens != nil {
		contextText = tokens(*u.ContextTokens)
		if u.ContextWindow != nil {
			contextText += " / " + tokens(*u.ContextWindow)
		}
		if u.Fraction != nil {
			contextText += fmt.Sprintf(" · %.1f%%", *u.Fraction*100)
		}
	}
	statRow(c, "上下文", contextText)
	if u.Fraction != nil {
		ui.Progress(c, float64(*u.Fraction)).FillWidth()
	}
	var notes []string
	if u.CompactAt != nil && (u.AutoCompact == nil || *u.AutoCompact) {
		notes = append(notes, "自动压缩点 "+tokens(*u.CompactAt))
	}
	if u.AutoCompact != nil && !*u.AutoCompact {
		notes = append(notes, "自动压缩已关闭，快满时手动压缩")
	}
	if u.Compactions > 0 {
		notes = append(notes, fmt.Sprintf("已压缩 %d 次", u.Compactions))
	}
	if len(notes) > 0 {
		ui.Text(c, strings.Join(notes, "；")).FontSize(12).TextColor(t.TextMuted)
	}
	ui.Divider(c)
	ui.Text(c, fmt.Sprintf("本对话累计 · %d 轮", len(u.Turns))).FontSize(13).Bold()
	statRow(c, "输入", tokens(u.Total.Input))
	statRow(c, "缓存读取", tokens(u.Total.CacheRead))
	statRow(c, "缓存写入", tokens(u.Total.CacheWrite))
	statRow(c, "输出", tokens(u.Total.Output))
	cost := "—"
	if u.TotalCost != nil {
		cost = fmt.Sprintf("$%.4f", *u.TotalCost)
		if u.CostEstimated {
			cost = "≈" + cost
		}
	}
	statRow(c, "费用（按 API 标价）", cost)
	ui.Button(c, "压缩上下文").Disabled(!p.connected || p.state.Busy).OnClick(func() {
		p.usage = false
		a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Compact(ctx) })
	})
}

func statRow(c *ui.Context, label, value string) {
	t := c.Theme()
	ui.Row(c).Gap(12).Children(func() {
		ui.Text(c, label).FontSize(13).TextColor(t.TextMuted)
		ui.Spacer(c)
		ui.Text(c, value).FontSize(13)
	})
}

// tokens 把 token 数写短：1234 → 1.2K，123456 → 123K，1234567 → 1.23M。
func tokens(n int64) string {
	trim := func(s string) string {
		if strings.Contains(s, ".") {
			s = strings.TrimRight(strings.TrimRight(s, "0"), ".")
		}
		return s
	}
	switch {
	case n >= 999_500:
		return trim(fmt.Sprintf("%.2f", float64(n)/1e6)) + "M"
	case n >= 99_950:
		return trim(fmt.Sprintf("%.0f", float64(n)/1e3)) + "K"
	case n >= 1000:
		return trim(fmt.Sprintf("%.1f", float64(n)/1e3)) + "K"
	}
	return fmt.Sprint(max(n, 0))
}
