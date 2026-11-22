package ui

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// composer 是输入栏（同手机版 Composer.kt）：淡灰底的圆角面板，上面多行输入框（Enter 发送，Shift+Enter 换行；
// 回答中发的进排队条），下面一排小圆钮：权限模式、思考强度，右边是上下文用量的圆环、停止和发送。
func (a *App) composer(c *ui.Context, p *chatPane) {
	k := coui.Of(c)
	ready := p.src.Conv != nil && p.src.CwdProblem == ""
	hint := "交给 Claude 去完成"
	if p.state.Busy {
		hint = "Claude 正在工作，可以接着发"
	}
	ui.Column(c).FillWidth().Padding(8, 10).Gap(2).Radius(24).Background(k.Field).Border(1, k.Edge).
		Shadow(0, 2, 16, 0, composerShadow(k)).Children(func() {
		ui.TextAreaBase(c.Key("draft"), &p.draft).Placeholder(hint).PlaceholderColor(k.Tertiary).Label("消息").FillWidth().
			Lines(1, 6).Padding(5, 6).FontSize(coui.SizeBodyXL).LineHeight(1.2).Disabled(!ready).
			OnTextKey(func(tk ui.TextKey) bool {
				if tk.Key != ui.KeyEnter || tk.Mods != 0 {
					return false // Shift+Enter 换行
				}
				if a.send(tk.Text()) {
					tk.SetText("")
				}
				return true
			})
		ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
			if p.src.Conv != nil {
				a.modeMenu(c, p)
				a.effortMenu(c, p)
			}
			ui.Spacer(c)
			if p.src.Conv != nil {
				a.usageButton(c, p)
			}
			if p.state.Busy {
				coui.StopButton(c, "停止").OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Interrupt(ctx) })
				})
			}
			coui.SendButton(c, "发送", ready && strings.TrimSpace(p.draft) != "").OnClick(func() {
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
	icon, tint := modeIcon(coui.Of(c), current)
	coui.MenuButton(c.Key("mode"), func(t ui.Element) { coui.Chip(c, t, icon, modeLabel(current), tint) }, func(m *coui.Menu) {
		for _, mode := range permissionModes {
			if m.Choice(mode.label, mode.value == current) {
				value := mode.value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetMode(ctx, &value) })
			}
		}
	})
}

// modeIcon 是权限模式按钮上的盾牌和颜色（同手机版 PermissionModeChip）：逐项确认和没指定时是灰色的 M，
// 计划模式青色的 P，自动接受编辑紫色的 E，自动判断蓝色的 A，完全放行红色的感叹号。
func modeIcon(k *coui.Colors, mode string) (*ui.SVG, ui.Color) {
	switch mode {
	case "auto":
		return coui.IconShieldAuto, k.Blue
	case "plan":
		return coui.IconShieldPlan, k.Cyan
	case "acceptEdits":
		return coui.IconShieldEdits, k.Purple
	case "bypassPermissions":
		return coui.IconShieldBypass, k.Error
	}
	return coui.IconShieldDefault, ui.Color{}
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
	label := modelLabel(p.state, models)
	coui.MenuButton(c.Key("model"), func(t ui.Element) { modelTrigger(c, t, label) }, func(m *coui.Menu) {
		if m.Choice("默认", chosen == "" || chosen == chat.DefaultModel) {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, nil) })
		}
		for _, o := range models {
			if o.Value == chat.DefaultModel {
				continue
			}
			if m.Choice(optionName(o), o.Value == chosen) {
				value := o.Value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, &value) })
			}
		}
	})
}

// modelTrigger 是顶栏小字里的模型（同手机版顶栏副标题「设备 · 模型」）：12 的次要文字色，后面一个小箭头。
func modelTrigger(c *ui.Context, t ui.Element, label string) {
	k := coui.Of(c)
	t.Label(label).Gap(2).AlignItems(ui.Center).Padding(1, 4).Radius(8).Cursor(ui.CursorPointer)
	if t.Hovered() {
		t.Background(k.Hover)
	}
	t.Children(func() {
		ui.Text(c, label).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).SingleLine()
		ui.Icon(c, coui.IconChevronDown).Size(12, 12).TextColor(k.Tertiary)
	})
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
	level := effortLevel(chosen)
	if chosen == "" {
		level = effortLevel(p.state.Usage.AppliedEffort)
	}
	icon := coui.Gauge(level, len(effortLevels))
	coui.MenuButton(c.Key("effort"), func(t ui.Element) { coui.Chip(c, t, icon, label, ui.Color{}) }, func(m *coui.Menu) {
		if m.Choice("默认", chosen == "") {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, nil) })
		}
		for _, l := range effortLevels {
			if m.Choice(l.label, l.value == chosen) {
				value := l.value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, &value) })
			}
		}
	})
}

// effortLevel 是思考强度 v 在表盘上亮几格：低是 1，最高是 5，认不出的是 0。
func effortLevel(v string) int {
	for i, l := range effortLevels {
		if l.value == v {
			return i + 1
		}
	}
	return 0
}

// usageButton 是用量：按钮上是上下文用了多少，点开是上下文、本对话累计和压缩上下文。
func (a *App) usageButton(c *ui.Context, p *chatPane) {
	u := p.state.Usage
	k := coui.Of(c)
	label := "上下文"
	var fraction float32
	if u.Fraction != nil {
		label += fmt.Sprintf(" %d%%", int(*u.Fraction*100))
		fraction = float32(*u.Fraction)
	}
	color := k.Primary
	switch u.Level {
	case usage.LevelCritical:
		color = k.Error
	case usage.LevelWarning:
		color = k.Warning
	}
	button := coui.Ring(c, ui.ButtonBase(c.Key("usage")), label, fraction, color).OnClick(func() { p.usage = !p.usage })
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
		cost = formatCost(*u.TotalCost)
		if u.CostEstimated {
			cost = "≈" + cost
		}
	}
	statRow(c, "费用（按 API 标价）", cost)
	coui.SmallButton(c, "压缩上下文").Disabled(!p.connected || p.state.Busy).OnClick(func() {
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

// formatCost 是费用的写法：不到 1 美元留四位小数，再多留两位，没花钱写 $0。
func formatCost(usd float64) string {
	switch {
	case usd <= 0:
		return "$0"
	case usd < 1:
		return fmt.Sprintf("$%.4f", usd)
	}
	return fmt.Sprintf("$%.2f", usd)
}
