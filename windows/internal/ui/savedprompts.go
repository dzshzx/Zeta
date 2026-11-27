package ui

import (
	"crypto/rand"
	"encoding/hex"
	"strings"

	"github.com/dzshzx/Zeta/hub/settings"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 常用指令（同手机版 shared 的 SavedPromptsUi）：自己发过的消息右键「收藏为常用指令」存进来（msgactions.go 的 saveMessage）；
// 附件菜单里点开列表，点一条填进输入框（空着就整个换上，有字就插在光标处），
// 每条的「更多操作」里编辑、上移、下移、删除；「新建」时输入框里正好有字就先填上。清单存在设置里（settings.SavedPrompt）。

// promptsUI 是常用指令几个对话框的状态。
type promptsUI struct {
	listOpen bool
	editing  *promptDraft
	deleting *settings.SavedPrompt
}

// promptDraft 是正在新建（id 空着）或编辑的一条。
type promptDraft struct {
	open           bool
	id, name, text string
}

func (u *promptsUI) open() { u.listOpen = true }

// savedPrompts 画常用指令的几个对话框。
func (a *App) savedPrompts(c *ui.Context, p *chatPane) {
	a.promptList(c, p)
	a.promptEditor(c)
	a.promptDelete(c)
}

func (a *App) promptList(c *ui.Context, p *chatPane) {
	u := &a.prompts
	if !u.listOpen {
		return
	}
	list := a.hub.Prompts()
	summary := ""
	if len(list) == 0 {
		summary = locales.SavedPromptsEmpty()
	}
	widget.Sheet(c, locales.SavedPromptsTitle(), summary, 420, func() {
		for i, pr := range list {
			a.promptRow(c.Key("prompt/"+pr.ID), p, pr, i == 0, i == len(list)-1)
		}
	}, func() {
		if widget.DialogButton(c, locales.CommonClose(), widget.Plain).Clicked() {
			u.listOpen = false
		}
		if widget.DialogButton(c, locales.CommonNew(), widget.Recommend).Disabled(len(list) >= settings.MaxPrompts).Clicked() {
			text := ""
			if strings.TrimSpace(p.draft) != "" {
				text = p.draft
			}
			u.listOpen, u.editing = false, &promptDraft{open: true, text: text}
		}
	})
}

// promptRow 是列表里的一条：起了名字的是名字加两行内容，没起名字的直接是内容（两行），点了填进输入框；右边「更多操作」。
func (a *App) promptRow(c *ui.Context, p *chatPane, pr settings.SavedPrompt, first, last bool) {
	k := widget.Of(c)
	u := &a.prompts
	ui.Row(c).FillWidth().Padding(0, 8, 0, 0).AlignItems(ui.Center).Children(func() {
		pick := ui.ButtonBase(c).Label(pr.DisplayName()).Grow(1).MinWidth(0).Padding(8, 4, 8, widget.DialogPadding).AlignItems(ui.Center).
			Radius(12).Cursor(ui.CursorPointer).FocusRingOffset(-2)
		if pick.Hovered() {
			pick.Background(k.Hover)
		}
		pick.OnClick(func() {
			u.listOpen = false
			p.insert = pr.Text
		})
		pick.Children(func() {
			title, lines := strings.TrimSpace(pr.Text), 2
			named := strings.TrimSpace(pr.Name) != ""
			if named {
				title, lines = strings.TrimSpace(pr.Name), 1
			}
			ui.Column(c).Grow(1).MinWidth(0).Gap(2).Children(func() {
				ui.Text(c, title).FontSize(14.5).LineHeight(1.38).FontWeight(widget.Medium).TextColor(k.Text).MaxLines(lines).Ellipsis("…")
				if named {
					ui.Text(c, strings.TrimSpace(pr.Text)).FontSize(12.5).LineHeight(1.36).TextColor(k.Secondary).MaxLines(2).Ellipsis("…")
				}
			})
		})
		widget.MenuButton(c, func(t ui.Element) { widget.FlatButtonOf(c, t, widget.IconMore, locales.SavedPromptsMore(), 36, 20) }, func(m *widget.Menu) {
			a.promptActions(m, pr, first, last)
		})
	})
}

func (a *App) promptActions(m *widget.Menu, pr settings.SavedPrompt, first, last bool) {
	u := &a.prompts
	if m.Add(widget.Entry{Icon: widget.IconEdit, Label: locales.CommonEdit()}) {
		u.listOpen, u.editing = false, &promptDraft{open: true, id: pr.ID, name: pr.Name, text: pr.Text}
	}
	if m.Add(widget.Entry{Icon: widget.IconArrowUp, Label: locales.SavedPromptsMoveUp(), Disabled: first}) {
		a.savePrompts(settings.MovePrompt(a.hub.Prompts(), pr.ID, -1))
	}
	if m.Add(widget.Entry{Icon: widget.IconArrowDown, Label: locales.SavedPromptsMoveDown(), Disabled: last}) {
		a.savePrompts(settings.MovePrompt(a.hub.Prompts(), pr.ID, 1))
	}
	m.Separator()
	if m.Add(widget.Entry{Icon: widget.IconDelete, Label: locales.CommonDelete(), Danger: true}) {
		chosen := pr
		u.listOpen, u.deleting = false, &chosen
	}
}

func (a *App) promptEditor(c *ui.Context) {
	u := &a.prompts
	d := u.editing
	if d == nil {
		return
	}
	k := widget.Of(c)
	problem := settings.PromptProblem(a.hub.Prompts(), d.name, d.text, d.id)
	title := locales.SavedPromptsEditTitle()
	if d.id == "" {
		title = locales.SavedPromptsNewTitle()
	}
	widget.Dialog(c, &d.open, func() {
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 4, widget.DialogPadding).Gap(6).Children(func() {
			ui.Text(c, title).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
			widget.LineInput(c.Key("name"), &d.name, 1).Placeholder(locales.SavedPromptsNamePlaceholder()).Label(locales.SavedPromptsName()).AutoFocus()
			widget.LineInput(c.Key("text"), &d.text, 8).Placeholder(locales.SavedPromptsText()).Label(locales.SavedPromptsText()).MinHeight(96)
			// 空着时不报错，「保存」变灰就够了
			if problem != "" && d.text != "" {
				ui.Text(c, problem).FontSize(widget.SizeBodyM).TextColor(k.Error).Padding(8, 0, 0, 0)
			}
		})
		widget.ButtonBar(c, func() {
			if widget.DialogButton(c, locales.CommonCancel(), widget.Plain).Clicked() {
				d.open = false
			}
			if widget.DialogButton(c, locales.CommonSave(), widget.Recommend).Disabled(problem != "").Clicked() {
				a.saveDraft(d)
				d.open = false
			}
		})
	})
	if !d.open { // 存了、取消了都回到列表
		u.editing, u.listOpen = nil, true
	}
}

// saveDraft 存新建或编辑的这条：新建的放最前面。
func (a *App) saveDraft(d *promptDraft) {
	list := a.hub.Prompts()
	if d.id == "" {
		a.savePrompts(settings.AddPrompt(list, newPromptID(), d.name, d.text))
		return
	}
	a.savePrompts(settings.UpdatePrompt(list, d.id, d.name, d.text))
}

func (a *App) promptDelete(c *ui.Context) {
	u := &a.prompts
	pr := u.deleting
	if pr == nil {
		return
	}
	open := true
	if widget.Alert(c, &open, locales.SavedPromptsDeleteTitle(), locales.SavedPromptsDeleteMessage(pr.DisplayName()),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.CommonDelete(), Style: widget.Destructive}) == 1 {
		a.savePrompts(settings.RemovePrompt(a.hub.Prompts(), pr.ID))
	}
	if !open {
		u.deleting, u.listOpen = nil, true
	}
}

// savePrompts 存清单，存不上时说一声。
func (a *App) savePrompts(list []settings.SavedPrompt) {
	if err := a.hub.SetPrompts(list); err != nil {
		a.say(locales.SavedPromptsSaveFailed(), err.Error())
	}
}

// newPromptID 是新的一条的编号（Kotlin 版是 UUID；只要求同一份清单里不重复）。
func newPromptID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // crypto/rand 的 Read 不会失败
	return hex.EncodeToString(b)
}
