package ui

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	uiacct "github.com/dzshzx/Zeta/windows/internal/ui/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// composer 是输入栏（照 ChatGPT、Codex 客户端）：一圈细边的大圆角面板，上面一排待发的附件，再是多行输入框（Enter 发送，
// Shift+Enter 换行；回答中发的进排队条；粘贴图片、文件收成附件），下面一排：左边添加附件和权限模式，右边模型、
// 思考强度、上下文用量的圆环、停止和发送。权限、模型、思考强度是写着当前值的文字胶囊。
func (a *App) composer(c *ui.Context, p *chatPane) {
	k := widget.Of(c)
	ready := p.src.Conv != nil && p.src.CwdProblem == ""
	hint := locales.ComposerHint()
	if p.state.Busy {
		hint = locales.ComposerHintBusy()
	}
	// 固定的 Key：上面的状态条、排队条随对话忙闲来去，输入栏不跟着它们的个数换身份，不然正在组字的输入法会被打断
	widget.Surface{Shape: widget.ShapeComposer, Fill: k.Field, Edge: k.Edge}.Paint(ui.Column(c.Key("composer"))).FillWidth().Padding(8, 10).Gap(2).
		Shadow(0, 2, 16, 0, composerShadow(k)).Children(func() {
		a.slashList(c, p)
		a.composerAttachments(c, p)
		_, winH := c.Size()
		_, natural := c.MeasureText(0, ui.Span{Text: "M", Size: composerFontSize})
		in := ui.TextAreaBase(c.Key("draft"), &p.draft).Placeholder(hint).PlaceholderColor(k.Tertiary).Label(locales.ComposerMessage()).FillWidth().
			Lines(composerMinLines, composerMaxLines(winH, natural)).Padding(5, 6).FontSize(composerFontSize).LineHeight(composerLeading).Disabled(!ready).
			OnPaste(func(string) bool { return a.paste(c, p) }).
			OnTextKey(func(tk ui.TextKey) bool {
				if a.slashKey(p, tk) {
					return true
				}
				if tk.Key != ui.KeyEnter || tk.Mods != 0 {
					return false // Shift+Enter 换行
				}
				if a.send(tk.Text()) {
					tk.SetText("")
				}
				return true
			})
		insertPending(in, p)
		slashInsert(c, in, p)
		ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
			if ready {
				a.attachMenu(c)
			}
			if p.src.Conv != nil {
				a.modeMenu(c, p)
			}
			ui.Spacer(c)
			if p.src.Conv != nil {
				a.modelMenu(c, p)
				a.effortMenu(c, p)
				a.hostLoadChip(c, p)
				a.usageButton(c, p)
			}
			if p.state.Busy {
				widget.StopButton(c, locales.CommonStop()).OnClick(func() {
					a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Interrupt(ctx) })
				})
			}
			widget.SendButton(c, locales.ComposerSend(), ready && sendable(p, p.draft)).OnClick(func() {
				if a.send(p.draft) {
					p.draft = ""
				}
			})
		})
	})
}

// insertPending 把选的常用指令放进输入框：空着就整个换上，有字就插在光标处（替换选中的字）。
func insertPending(in ui.Element, p *chatPane) {
	if p.insert == "" {
		return
	}
	if strings.TrimSpace(p.draft) == "" {
		p.draft = p.insert
	} else {
		start, end := in.TextSelection()
		in.ReplaceText(start, end, p.insert)
	}
	p.insert = ""
}

// sendable：有东西可发（字、图或传好的文件），并且没有还在传的文件（同 Composer.kt 的 hasSomethingToSend）。
func sendable(p *chatPane, text string) bool {
	at := &p.att
	return !at.uploading() && (strings.TrimSpace(text) != "" || len(at.images) > 0 || len(at.entries()) > 0)
}

// attachMenu 是左下角的「+」（同 Composer.kt 的 attachMenu）：图片、文件、文件夹、输入路径一组，常用指令单独一组。
func (a *App) attachMenu(c *ui.Context) {
	widget.MenuButton(c.Key("attach"), func(t ui.Element) { widget.Chip(c, t, widget.IconAdd, locales.ComposerAddAttachment(), ui.Color{}) }, func(m *widget.Menu) {
		if m.Add(widget.Entry{Icon: widget.IconImage, Label: locales.ComposerAttachImages()}) {
			a.pickAttachments(true, false)
		}
		if m.Add(widget.Entry{Icon: widget.IconPaperclip, Label: locales.ComposerAttachFiles()}) {
			a.pickAttachments(false, false)
		}
		if m.Add(widget.Entry{Icon: widget.IconFolder, Label: locales.ComposerAttachFolder()}) {
			a.pickAttachments(false, true)
		}
		if m.Add(widget.Entry{Icon: widget.IconEdit, Label: locales.ComposerAttachPath()}) {
			a.openPathDialog()
		}
		m.Separator()
		if m.Add(widget.Entry{Icon: widget.IconFavorites, Label: locales.ComposerSavedPrompts()}) {
			a.prompts.open()
		}
	})
}

// composerAttachments 是输入框上方一排待发的附件（同 Composer.kt 的 ComposerAttachments）：文件（正在传的带进度，
// 没传成的注明）和图片缩略图，各带移除键，放不下时横着滚。
func (a *App) composerAttachments(c *ui.Context, p *chatPane) {
	at := &p.att
	if len(at.files) == 0 && len(at.images) == 0 && at.loading == 0 {
		return
	}
	ui.ScrollHorizontal(c.Key("attachments")).FillWidth().Children(func() {
		ui.Row(c).Padding(2, 4, 6, 4).Gap(8).AlignItems(ui.Center).Children(func() {
			for _, f := range at.files {
				if f.picture {
					attachedPicture(c.Key("file/"+strconv.Itoa(f.id)), f, func() { at.removeFile(f.id) })
				} else {
					attachedFile(c.Key("file/"+strconv.Itoa(f.id)), f, func() { at.removeFile(f.id) })
				}
			}
			for _, im := range at.images {
				attachedImage(c.Key("image/"+strconv.Itoa(im.id)), im, func() { at.removeImage(im.id) })
			}
			for i := range at.loading {
				ui.Box(c.Key("loading/"+strconv.Itoa(i))).Size(64, 64).Radius(12).Background(widget.Of(c).Chip).Center().
					Children(func() { ui.Spinner(c).Size(18, 18) })
			}
		})
	})
}

// attachedFile 是一个待发文件的小标签：图标、名字（最宽 240），右边移除。没传成的把原因放在悬停提示里。
func attachedFile(c *ui.Context, f *draftFile, remove func()) {
	k := widget.Of(c)
	icon := widget.IconFile
	if f.folder {
		icon = widget.IconFolder
	}
	// 同 Compose 的 FileChip：输入栏底色上一层淡淡的灰，主题色的文件图标
	chip := widget.Surface{Shape: widget.Rounded(12, 2.5), Fill: k.Text.Alpha(0.055)}.Paint(ui.Row(c)).Height(36).MaxWidth(240).Padding(0, 4, 0, 10).
		Gap(6).AlignItems(ui.Center)
	if f.failed != "" {
		chip.Tooltip(f.failed)
	}
	chip.Children(func() {
		color := k.PrimaryText
		if f.failed != "" {
			color = k.Error
		}
		ui.Icon(c, icon).Size(16, 16).TextColor(color).Shrink(0)
		ui.Text(c, f.label()).FontSize(widget.SizeBodyM).TextColor(k.Text).SingleLine().Ellipsis("…").Shrink(1).MinWidth(0)
		widget.FlatButton(c, widget.IconClose, locales.CommonRemove(), 28, 14).OnClick(remove)
	})
}

// attachedImage 是一张待发的内联图（旧消息、草稿里存着的）的缩略图，右上角是移除键。
func attachedImage(c *ui.Context, im *draftImage, remove func()) {
	thumbTile(c, im.thumb, locales.ComposerImageAttachment(), "", nil, remove)
}

// attachedPicture 是一张要传到设备上的图片：缩略图（缩小图还没做好时是灰块），正在传、正在做缩小图时上面转圈，没传成时标红、原因放在悬停提示里。
func attachedPicture(c *ui.Context, f *draftFile, remove func()) {
	var overlay func()
	switch {
	case f.failed != "":
		overlay = func() { ui.Icon(c, widget.IconClose).Size(18, 18).TextColor(widget.Of(c).Error) }
	case f.uploading() || f.encoding:
		overlay = func() { ui.Spinner(c).Size(18, 18) }
	}
	thumbTile(c, f.thumb, f.label(), f.failed, overlay, remove)
}

// thumbTile 是输入栏里的一块缩略图（64，12 的圆角）：thumb 是 nil 时是灰块，overlay 不是 nil 时盖一层半透明画上它，右上角是移除键。
func thumbTile(c *ui.Context, thumb *ui.Bitmap, label, tooltip string, overlay func(), remove func()) {
	k := widget.Of(c)
	ui.Box(c).Size(64, 64).Shrink(0).Children(func() {
		box := ui.Box(c).Size(64, 64).Radius(12).Clip().Background(k.Chip).Label(label)
		if tooltip != "" {
			box.Tooltip(tooltip)
		}
		box.Children(func() {
			if thumb != nil {
				ui.Image(c, thumb).Size(64, 64).Fit(ui.Cover)
			}
			if overlay != nil {
				ui.Box(c).Absolute().Top(0).Left(0).Size(64, 64).Center().Background(k.Chip.Alpha(0.7)).Children(overlay)
			}
		})
		x := ui.ButtonBase(c).Label(locales.CommonRemove()).Absolute().Top(3).Right(3).Size(20, 20).Radius(10).Center().Background(k.Mask).
			Cursor(ui.CursorPointer)
		x.Children(func() { ui.Icon(c, widget.IconClose).Size(13, 13).TextColor(ui.Hex("#FFFFFF")) })
		x.OnClick(remove)
	})
}

// send 发出输入框里的 text 和待发的附件：没东西可发、对话没打开或起不了进程时不发，返回 false（输入框不清）。
// 发出去的附件从输入栏拿掉；没发出去时原因显示在输入栏上方，字和附件放回输入栏。
func (a *App) send(text string) bool {
	p := a.pane
	if p == nil || p.src.Conv == nil || p.src.CwdProblem != "" || !sendable(p, text) {
		return false
	}
	if p.acts.editing != nil {
		return a.submitEdit(p, text)
	}
	m := coreapi.Message{Text: text, Images: p.att.pictures(), Files: p.att.entries()}
	p.att.clear()
	p.list.ScrollToEnd()
	p.failed = nil
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, coreapi.Outgoing{Text: m.Text, Images: m.Images, Files: m.Files})
	}, func(err error) {
		p.failed = locales.Wrap(err, locales.CommonSendFailed)
		p.putBack(m)
	})
	return true
}

// permissionModes 是权限模式的选项，和手机版一样的说法。
// label 按界面当前的语言出字，所以存取字函数。
var permissionModes = []struct {
	value string
	label func() string
}{
	{"default", locales.ClaudecfgModeDefault},
	{"acceptEdits", locales.ClaudecfgModeAcceptEdits},
	{"plan", locales.ClaudecfgModePlan},
	{"auto", locales.ClaudecfgModeAuto},
	{"bypassPermissions", locales.ComposerModeBypass},
}

func modeLabel(mode string) string {
	for _, m := range permissionModes {
		if m.value == mode {
			return m.label()
		}
	}
	if mode == "" {
		return locales.ComposerModeUnset()
	}
	return mode
}

// modeMenu 是权限模式：用户在这个对话里选的，没选过时是进程此刻用的。
func (a *App) modeMenu(c *ui.Context, p *chatPane) {
	current := p.state.Mode.User
	if current == "" {
		current = p.state.Mode.Process
	}
	icon, tint := modeIcon(widget.Of(c), current)
	widget.MenuButton(c.Key("mode"), func(t ui.Element) { widget.ToolPill(c, t, icon, modeLabel(current), tint) }, func(m *widget.Menu) {
		for _, mode := range permissionModes {
			if m.Choice(mode.label(), mode.value == current) {
				value := mode.value
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetMode(ctx, &value) })
			}
		}
	})
}

// modeIcon 是权限模式按钮上的盾牌和颜色（同手机版 PermissionModeChip）：逐项确认和没指定时是灰色的 M，
// 计划模式青色的 P，自动接受编辑紫色的 E，自动判断蓝色的 A，完全放行红色的感叹号。
func modeIcon(k *widget.Colors, mode string) (*ui.SVG, ui.Color) {
	switch mode {
	case "auto":
		return widget.IconShieldAuto, k.Blue
	case "plan":
		return widget.IconShieldPlan, k.Cyan
	case "acceptEdits":
		return widget.IconShieldEdits, k.Purple
	case "bypassPermissions":
		return widget.IconShieldBypass, k.Error
	}
	return widget.IconShieldDefault, ui.Color{}
}

// fallbackModels 是进程还没起、Claude Code 还没报模型清单时给的几个常用别名（它自己认）。
var fallbackModels = []chat.ModelOption{{Value: "opus", DisplayName: "Opus"}, {Value: "sonnet", DisplayName: "Sonnet"}, {Value: "haiku", DisplayName: "Haiku"}}

// modelMenu 是模型：「默认」（Claude Code 的设置）和 Claude Code 报的各系列最新的，旧版本收在「更多模型」的二级菜单里
// （chat.SplitModels，同设置里的默认模型和手机版），选中的是旧版本时写在那一项右边。
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
	widget.MenuButton(c.Key("model"), func(t ui.Element) { widget.ToolPill(c, t, nil, label, ui.Color{}) }, func(m *widget.Menu) {
		if m.Choice(locales.ComposerDefault(), chosen == "" || chosen == chat.DefaultModel) {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, nil) })
		}
		latest, older := chat.SplitModels(models)
		a.modelChoices(m, latest, chosen)
		if len(older) == 0 {
			return
		}
		hint := ""
		for _, o := range older {
			if o.Value == chosen {
				hint = optionName(o)
			}
		}
		m.Sub(locales.CommonMoreModels(), hint, func(m *widget.Menu) { a.modelChoices(m, older, chosen) })
	})
}

// modelChoices 把 models 列成模型菜单里的选项（「默认」不列，菜单自己有那一项），选了就换。
func (a *App) modelChoices(m *widget.Menu, models []chat.ModelOption, chosen string) {
	for _, o := range models {
		if o.Value == chat.DefaultModel {
			continue
		}
		if m.Choice(optionName(o), o.Value == chosen) {
			value := o.Value
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, &value) })
		}
	}
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
		return locales.ComposerModelDefault(readableModel(s.ReportedModel))
	}
	return locales.ComposerDefault()
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
var effortLevels = []struct {
	value string
	label func() string
}{
	{"low", locales.ClaudecfgEffortLow}, {"medium", locales.ClaudecfgEffortMedium}, {"high", locales.ClaudecfgEffortHigh},
	{"xhigh", locales.ClaudecfgEffortXHigh}, {"max", locales.ClaudecfgEffortMax},
}

func effortLabel(v string) string {
	for _, l := range effortLevels {
		if l.value == v {
			return l.label()
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
	label := locales.ComposerEffortDefault()
	switch {
	case chosen != "":
		label = locales.ComposerEffortChosen(effortLabel(chosen))
	case p.state.Usage.AppliedEffort != "":
		label = locales.ComposerEffortApplied(effortLabel(p.state.Usage.AppliedEffort))
	}
	level := effortLevel(chosen)
	if chosen == "" {
		level = effortLevel(p.state.Usage.AppliedEffort)
	}
	icon := widget.Gauge(level, len(effortLevels))
	widget.MenuButton(c.Key("effort"), func(t ui.Element) { widget.ToolPill(c, t, icon, label, ui.Color{}) }, func(m *widget.Menu) {
		if m.Choice(locales.ComposerDefault(), chosen == "") {
			a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, nil) })
		}
		for _, l := range effortLevels {
			if m.Choice(l.label(), l.value == chosen) {
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

// usageButton 是用量：按钮上是上下文用了多少，点开是这台的订阅额度（同手机版的用量弹框，打开时数不够新就问一次）、
// 上下文、本对话累计、各轮明细（usageTurns）和压缩上下文；弹出框限高，内容多了在里面滚动，右边给滚动条留出位置。
func (a *App) usageButton(c *ui.Context, p *chatPane) {
	u := p.state.Usage
	label := locales.ComposerContext()
	var fraction float32
	if u.Fraction != nil {
		label = locales.ComposerContextPercent(int(*u.Fraction * 100))
		fraction = float32(*u.Fraction)
	}
	button := widget.Ring(c, ui.ButtonBase(c.Key("usage")), label, fraction, contextColor(widget.Of(c), u.Level)).OnClick(func() {
		p.usage = !p.usage
		if r := a.hub.Remote(p.src.Backend); p.usage && r != nil {
			r.Quota.Refresh(false)
		}
	})
	ui.Popover(c.Key("usage-panel"), button, &p.usage, func() {
		_, winH := c.Size()
		ui.Column(c).Width(320).Padding(4).Gap(10).Children(func() {
			ui.Scroll(c.Key("usage-body")).FillWidth().MaxHeight(usagePanelHeight(winH)).Padding(0, widget.ScrollbarTrack, 0, 0).Label(locales.ComposerUsage()).Children(func() {
				ui.Column(c).FillWidth().Gap(10).Children(func() { a.usageDetails(c, p) })
			})
			widget.SmallButton(c, locales.ComposerCompact()).Disabled(!p.connected || p.state.Busy).OnClick(func() {
				p.usage = false
				a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.Compact(ctx) })
			})
		})
	})
}

// usagePanelHeight 是用量弹出框里能滚动的那一段最高多高：不超过 440，窗口矮时不超过窗口的一半，
// 额度、上下文、累计、各轮多了在里面滚动，「压缩上下文」留在底下不跟着滚走。
func usagePanelHeight(winH float32) float32 {
	return min(440, winH/2)
}

// contextColor 是上下文用量按程度的颜色：快满了红、该注意了黄、平时是主色。输入栏的圆环和用量弹出框里的进度条都用它。
func contextColor(k *widget.Colors, l usage.Level) ui.Color {
	switch l {
	case usage.LevelCritical:
		return k.Error
	case usage.LevelWarning:
		return k.Warning
	}
	return k.Primary
}

// usageDetails 是用量弹出框的内容。
func (a *App) usageDetails(c *ui.Context, p *chatPane) {
	if r := a.hub.Remote(p.src.Backend); r != nil {
		uiacct.QuotaDetails(c, r.Quota)
		ui.Divider(c)
	}
	u := p.state.Usage
	t := c.Theme()
	contextText := "—"
	if u.ContextTokens != nil {
		contextText = usage.FormatTokens(*u.ContextTokens)
		if u.ContextWindow != nil {
			contextText += " / " + usage.FormatTokens(*u.ContextWindow)
		}
		if u.Fraction != nil {
			contextText += fmt.Sprintf(" · %.1f%%", *u.Fraction*100)
		}
	}
	statRow(c, locales.ComposerContext(), contextText)
	if u.Fraction != nil {
		uiacct.UsageBar(c, min(max(*u.Fraction, 0), 1), contextColor(widget.Of(c), u.Level)).Label(locales.ComposerContextUsage())
	}
	ui.Text(c, strings.Join(contextNotes(u), "\n")).FontSize(12).TextColor(t.TextMuted)
	ui.Divider(c)
	ui.Text(c, locales.ComposerTotals(len(u.Turns))).FontSize(13).Bold()
	statRow(c, locales.ComposerInput(), usage.FormatTokens(u.Total.Input))
	statRow(c, locales.ComposerCacheRead(), usage.FormatTokens(u.Total.CacheRead))
	statRow(c, locales.ComposerCacheWrite(), usage.FormatTokens(u.Total.CacheWrite))
	statRow(c, locales.ComposerOutput(), usage.FormatTokens(u.Total.Output))
	cost := "—"
	if u.TotalCost != nil {
		cost = usage.FormatCost(*u.TotalCost)
		if u.CostEstimated {
			cost = "≈" + cost
		}
	}
	statRow(c, locales.ComposerCost(), cost)
	if note := totalsNote(u, p.state.Earlier); note != "" {
		ui.Text(c, note).FontSize(12).TextColor(t.TextMuted)
	}
	if len(u.Turns) > 0 {
		ui.Divider(c)
		usageTurns(c, u.Turns)
	}
}

func statRow(c *ui.Context, label, value string) {
	t := c.Theme()
	ui.Row(c).Gap(12).Children(func() {
		ui.Text(c, label).FontSize(13).TextColor(t.TextMuted)
		ui.Spacer(c)
		ui.Text(c, value).FontSize(13)
	})
}

// contextNotes 是用量弹出框里上下文一节的说明，一句一行（同手机版 UsageControls.kt 的 ContextSection）：按程度说现在怎样、
// 数是估的还是实测的，自动压缩点和它占窗口的比例，压缩过几次，最后是「模型 · 思考强度」。
func contextNotes(u usage.Usage) []string {
	off := u.AutoCompact != nil && !*u.AutoCompact
	var notes []string
	switch {
	case u.ContextTokens == nil:
		notes = append(notes, locales.ComposerContextNotYet())
	case off && u.Level == usage.LevelCritical:
		notes = append(notes, locales.ComposerContextFullOff())
	case off:
		notes = append(notes, locales.ComposerContextOff())
	case u.Level == usage.LevelCritical:
		notes = append(notes, locales.ComposerContextFull())
	case u.ContextEstimated:
		notes = append(notes, locales.ComposerContextEstimated())
	default:
		notes = append(notes, locales.ComposerContextMeasured())
	}
	if u.CompactAt != nil && !off {
		at := locales.ComposerCompactAt(usage.FormatTokens(*u.CompactAt))
		if u.CompactFraction != nil {
			at = locales.ComposerCompactAtPercent(usage.FormatTokens(*u.CompactAt), int(*u.CompactFraction*100))
		}
		notes = append(notes, at)
	}
	if u.Compactions > 0 {
		notes = append(notes, locales.ComposerCompactions(u.Compactions))
	}
	var meta []string
	if u.Model != "" {
		meta = append(meta, readableModel(u.Model))
	}
	if u.AppliedEffort != "" {
		meta = append(meta, locales.ComposerEffortMeta(effortLabel(u.AppliedEffort)))
	}
	if len(meta) > 0 {
		notes = append(notes, strings.Join(meta, " · "))
	}
	return notes
}

// 输入框的行数：空着时就有 composerMinLines 行高（桌面上地方够，一行高的框看着像单行输入、长消息也不好写），
// 随内容长高，到 composerMaxLines 后在框里滚动。
const (
	composerMinLines = 3
	composerFontSize = widget.SizeBodyXL
	composerLeading  = 1.2
)

// composerMaxLines 是窗口高 winH（DIP）时输入框最多长到几行：窗口高度的三分之一放得下的行数，留三分之二给对话；
// 不少于手机版的 6 行（小窗口里也照手机的上限）。mygo 的 Lines 按空文本一行的高度算框高：字号乘 composerLeading，
// 但不低于正文字体本身的行高，所以两者取大的。natural 要量正文字体（Windows 上是 Segoe UI），所以量拉丁字母：
// 中文字会落到后备字体（微软雅黑等）上，比正文字体高，拿它算会比三分之一少出一两行。
func composerMaxLines(winH, natural float32) int {
	return max(6, int(winH/3/max(natural, composerFontSize*composerLeading)))
}
