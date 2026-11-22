package ui

import (
	"crypto/rand"
	"encoding/hex"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 常用指令（同 Compose 版的 SavedPromptsUi.kt）：附件菜单里点开列表，点一条填进输入框（空着就整个换上，有字就插在光标处），
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
		summary = "还没有常用指令。点「新建」写一条。点一条就把它填进输入框，可以改了再发。"
	}
	coui.Sheet(c, "常用指令", summary, 420, func() {
		for i, pr := range list {
			a.promptRow(c.Key("prompt/"+pr.ID), p, pr, i == 0, i == len(list)-1)
		}
	}, func() {
		if coui.DialogButton(c, "关闭", coui.Plain).Grow(1).Basis(0).Clicked() {
			u.listOpen = false
		}
		if coui.DialogButton(c, "新建", coui.Recommend).Grow(1).Basis(0).Disabled(len(list) >= settings.MaxPrompts).Clicked() {
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
	k := coui.Of(c)
	u := &a.prompts
	ui.Row(c).FillWidth().Padding(0, 8, 0, 0).AlignItems(ui.Center).Children(func() {
		pick := ui.ButtonBase(c).Label(pr.DisplayName()).Grow(1).MinWidth(0).Padding(8, 4, 8, coui.DialogPadding).AlignItems(ui.Center).
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
				ui.Text(c, title).FontSize(14.5).LineHeight(1.38).FontWeight(coui.Medium).TextColor(k.Text).MaxLines(lines).Ellipsis("…")
				if named {
					ui.Text(c, strings.TrimSpace(pr.Text)).FontSize(12.5).LineHeight(1.36).TextColor(k.Secondary).MaxLines(2).Ellipsis("…")
				}
			})
		})
		coui.MenuButton(c, func(t ui.Element) { coui.FlatButtonOf(c, t, coui.IconMore, "更多操作", 36, 20) }, func(m *coui.Menu) {
			a.promptActions(m, pr, first, last)
		})
	})
}

func (a *App) promptActions(m *coui.Menu, pr settings.SavedPrompt, first, last bool) {
	u := &a.prompts
	if m.Add(coui.Entry{Icon: coui.IconEdit, Label: "编辑"}) {
		u.listOpen, u.editing = false, &promptDraft{open: true, id: pr.ID, name: pr.Name, text: pr.Text}
	}
	if m.Add(coui.Entry{Icon: coui.IconArrowUp, Label: "上移", Disabled: first}) {
		a.savePrompts(settings.MovePrompt(a.hub.Prompts(), pr.ID, -1))
	}
	if m.Add(coui.Entry{Icon: coui.IconArrowDown, Label: "下移", Disabled: last}) {
		a.savePrompts(settings.MovePrompt(a.hub.Prompts(), pr.ID, 1))
	}
	m.Separator()
	if m.Add(coui.Entry{Icon: coui.IconDelete, Label: "删除", Danger: true}) {
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
	k := coui.Of(c)
	problem := settings.PromptProblem(a.hub.Prompts(), d.name, d.text, d.id)
	title := "编辑常用指令"
	if d.id == "" {
		title = "新建常用指令"
	}
	coui.Dialog(c, &d.open, func() {
		ui.Column(c).FillWidth().Padding(coui.DialogPadding, coui.DialogPadding, 4, coui.DialogPadding).Gap(6).Children(func() {
			ui.Text(c, title).FontSize(coui.SizeHeadlineS).FontWeight(600).TextColor(k.Text).TextAlign(ui.Center).FillWidth()
			coui.LineInput(c.Key("name"), &d.name, 1).Placeholder("名字（可不填）").Label("名字").AutoFocus()
			coui.LineInput(c.Key("text"), &d.text, 8).Placeholder("内容").Label("内容").MinHeight(96)
			// 空着时不报错，「保存」变灰就够了
			if problem != "" && d.text != "" {
				ui.Text(c, problem).FontSize(coui.SizeBodyM).TextColor(k.Error).Padding(8, 0, 0, 0)
			}
		})
		coui.ButtonBar(c, func() {
			if coui.DialogButton(c, "取消", coui.Plain).Grow(1).Basis(0).Clicked() {
				d.open = false
			}
			if coui.DialogButton(c, "保存", coui.Recommend).Grow(1).Basis(0).Disabled(problem != "").Clicked() {
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
	if coui.Alert(c, &open, "删除这条常用指令？", "「"+pr.DisplayName()+"」删除后找不回来。",
		coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
		a.savePrompts(settings.RemovePrompt(a.hub.Prompts(), pr.ID))
	}
	if !open {
		u.deleting, u.listOpen = nil, true
	}
}

// savePrompts 存清单，存不上时说一声。
func (a *App) savePrompts(list []settings.SavedPrompt) {
	if err := a.hub.SetPrompts(list); err != nil {
		a.say("常用指令没存上", err.Error())
	}
}

// newPromptID 是新的一条的编号（Kotlin 版是 UUID；只要求同一份清单里不重复）。
func newPromptID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // crypto/rand 的 Read 不会失败
	return hex.EncodeToString(b)
}
