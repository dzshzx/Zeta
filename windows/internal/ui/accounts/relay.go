package accounts

import (
	"github.com/dzshzx/Zeta/helper/coreapi"
	acct "github.com/dzshzx/Zeta/hub/accounts"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 中转：账号页的「中转」一组（点一行换过去，行尾「编辑」），和添加、编辑中转的编辑页。编辑页占整页（换掉账号页的几组，
// 标题由 Title 给设置页），不用居中对话框：字段多（名称、地址、密钥、默认模型），模型列表可能有几十上百项，
// 对话框放不下；照设置的规矩不带返回键，底下「取消」或 Esc（Back）回到账号页。逻辑（草稿、拉模型列表、保存删除）在
// hub/accounts 的 relays.go。

const (
	// filterFrom：模型多于这么多个时列表上面给筛选框。
	filterFrom = 8
	// shownModels：模型列表最多列这么多行，多的说还有几个、请筛选。
	shownModels = 40
)

// relaySummary 是中转一行的小字：地址，设了默认模型时附上。
func relaySummary(r coreapi.Relay) string {
	if r.Model == "" {
		return r.BaseURL
	}
	return locales.AccountsRelaySummaryModel(r.BaseURL, r.Model)
}

// Title 是编辑页的标题（添加中转、编辑中转 名字）；没在编辑时为空，设置页用它自己的标题。
func (p *Page) Title() string {
	switch {
	case p.edit == nil:
		return ""
	case p.edit.Create:
		return locales.AccountsRelayAdd()
	}
	return locales.AccountsRelayEditTitle(p.edit.Name)
}

// Back 从编辑页回到账号页（草稿丢掉）；没在编辑时返回假（设置页拿 Esc 的时候用）。
func (p *Page) Back() bool {
	if p.edit == nil {
		return false
	}
	p.edit = nil
	p.Accounts.DismissProblem()
	return true
}

// openEditor 打开草稿 d 的编辑页。
func (p *Page) openEditor(d *acct.RelayDraft) {
	p.edit, p.modelFilter, p.showKey = d, "", false
	p.Accounts.DismissProblem()
}

// relays 是「中转」一组：每个中转一行（名字，地址和默认模型），在用的右边写「当前」，别的点一行换过去；行尾「编辑」；
// 最后一行「添加中转」。
func (p *Page) relays(c *ui.Context) {
	svc := p.Accounts
	st, loaded := svc.State()
	if !loaded {
		return
	}
	idle := svc.Working() == ""
	widget.Group(c, locales.AccountsRelaysTitle(), locales.AccountsRelaysFooter(), func(l *widget.List) {
		for _, r := range st.Relays {
			row := widget.Row{Key: "relay:" + r.Name, Title: r.Name, Summary: relaySummary(r), End: func() {
				widget.SmallButton(c.Key("edit:"+r.Name), locales.CommonEdit()).Disabled(!idle).
					OnClick(func() { p.openEditor(acct.EditRelayDraft(r)) })
			}}
			switch {
			case r.Current:
				row.Value = locales.AccountsCurrent()
			case idle:
				row.OnClick = func() { p.switchingRelay, p.askSwitchRelay = r, true }
			}
			l.Row(row)
		}
		add := widget.Row{Key: "relay-add", Icon: widget.IconAdd, Title: locales.AccountsRelayAdd()}
		if idle {
			add.OnClick = func() { p.openEditor(acct.NewRelayDraft()) }
		}
		l.Row(add)
	})
}

// relayAlerts 是中转的两个确认：换过去、删除。
func (p *Page) relayAlerts(c *ui.Context) {
	if widget.Alert(c, &p.askSwitchRelay, locales.AccountsRelaySwitchTitle(p.switchingRelay.Name), locales.AccountsRelaySwitchMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.AccountsSwitch(), Style: widget.Recommend}) == 1 {
		p.Accounts.SwitchRelay(p.switchingRelay)
	}
	if widget.Alert(c, &p.askRemoveRelay, locales.AccountsRelayRemoveTitle(p.removingRelay), locales.AccountsRelayRemoveMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.CommonDelete(), Style: widget.Destructive}) == 1 {
		d := p.edit
		p.Accounts.RemoveRelay(p.removingRelay, func() {
			if p.edit == d {
				p.edit = nil
			}
		})
	}
}

// editor 是添加、编辑中转的编辑页：连接一组（名称、地址、密钥），默认模型一组（手填或从拉来的列表里选），
// 底下删除（编辑时）、取消、保存。
func (p *Page) editor(c *ui.Context, d *acct.RelayDraft) {
	widget.Group(c, "", "", func(l *widget.List) {
		if d.Create {
			field(l, locales.AccountsRelayName(), "", func() {
				widget.LineInput(c.Key("relay-name"), &d.Name, 1).Placeholder(locales.AccountsRelayNameHint()).
					Label(locales.AccountsRelayName()).AutoFocus()
			})
		} else {
			l.Row(widget.Row{Title: locales.AccountsRelayName(), Summary: locales.AccountsRelayNameFixed(), Value: d.Name})
		}
		field(l, locales.AccountsRelayUrl(), locales.AccountsRelayUrlNote(), func() {
			widget.LineInput(c.Key("relay-url"), &d.BaseURL, 1).Placeholder("https://").Label(locales.AccountsRelayUrl())
		})
		p.keyField(l, d)
	})
	p.modelGroup(c, d)
	p.editorFooter(c, d)
}

// editorFooter 是编辑页底下：在用的说明、正在做的事、出错原因，删除（编辑时）、取消、保存。
func (p *Page) editorFooter(c *ui.Context, d *acct.RelayDraft) {
	svc := p.Accounts
	idle := svc.Working() == ""
	k := widget.Of(c)
	ui.Column(c).Padding(0, 16, 16, 16).Gap(12).Children(func() {
		if d.Current {
			widget.Notice(c, locales.AccountsRelayCurrentNote(), widget.Info)
		}
		if w := svc.Working(); w != "" {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Spinner(c).Size(16, 16).Label(w)
				ui.Text(c, w).FontSize(widget.SizeBodyM).TextColor(k.Text)
			})
		}
		if problem := svc.Problem(); problem != "" {
			widget.Notice(c, problem, widget.Failure)
		}
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			if !d.Create {
				widget.SmallDangerButton(c.Key("relay-delete"), locales.CommonDelete()).Disabled(!idle || d.Current).OnClick(func() {
					p.removingRelay, p.askRemoveRelay = d.Name, true
				})
			}
			ui.Spacer(c)
			widget.SmallButton(c.Key("relay-cancel"), locales.CommonCancel()).OnClick(func() { p.Back() })
			widget.SmallPrimaryButton(c.Key("relay-save"), locales.CommonSave()).Disabled(!idle || !d.Ready()).OnClick(func() {
				svc.SaveRelay(d, func() {
					if p.edit == d {
						p.edit = nil
					}
				})
			})
		})
	})
}

// keyField 是密钥一行：遮着的输入框，右边「显示」「隐藏」；编辑时空着是不改；Zeta 改不了的灰着、说明为什么。
func (p *Page) keyField(l *widget.List, d *acct.RelayDraft) {
	c := l.Context()
	note := ""
	if !d.KeyEditable {
		note = locales.AccountsRelayKeyReadOnly()
	}
	field(l, locales.AccountsRelayKey(), note, func() {
		ui.Row(c).FillWidth().Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Box(c).Grow(1).MinWidth(0).Children(func() {
				in := widget.LineInput(c.Key("relay-key"), &d.Key, 1).Label(locales.AccountsRelayKey()).Disabled(!d.KeyEditable)
				if !d.Create && d.HasKey {
					in.Placeholder(locales.AccountsRelayKeyKeep())
				}
				if !p.showKey {
					in.Password()
				}
			})
			label := locales.AccountsRelayShowKey()
			if p.showKey {
				label = locales.AccountsRelayHideKey()
			}
			widget.SmallButton(c.Key("relay-show-key"), label).Disabled(!d.KeyEditable).OnClick(func() { p.showKey = !p.showKey })
		})
	})
}

// modelGroup 是默认模型一组：输入框（手填，可以清空）和「获取模型列表」，下面是拉的状态、出错原因和拉来的列表（显示名和
// id，点一项填进输入框；多时可以筛选）。
func (p *Page) modelGroup(c *ui.Context, d *acct.RelayDraft) {
	svc := p.Accounts
	k := widget.Of(c)
	widget.Group(c, locales.AccountsRelayModel(), locales.AccountsRelayModelFooter(), func(l *widget.List) {
		l.Custom(func() {
			ui.Box(c).Grow(1).MinWidth(0).Children(func() {
				widget.LineInput(c.Key("relay-model"), &d.Model, 1).Placeholder(locales.AccountsRelayModelNone()).
					Label(locales.AccountsRelayModel())
			})
			widget.SmallButton(c.Key("relay-model-clear"), locales.AccountsRelayClearModel()).Disabled(d.Model == "").
				OnClick(func() { d.Model = "" })
			widget.SmallButton(c.Key("relay-fetch"), locales.AccountsRelayFetchModels()).Disabled(!d.CanFetch()).
				OnClick(func() { svc.FetchRelayModels(d) })
		})
		switch {
		case d.Loading():
			l.Custom(func() {
				ui.Spinner(c).Size(16, 16).Label(locales.AccountsRelayFetching())
				ui.Text(c, locales.AccountsRelayFetching()).FontSize(widget.SizeBodyM).TextColor(k.Text).Grow(1)
			})
		case d.ModelsProblem() != "":
			l.Custom(func() { widget.Notice(c, d.ModelsProblem(), widget.Failure).Grow(1) })
		case d.Create && !d.CanFetch():
			l.Message(locales.AccountsRelayFetchNeeds())
		}
		p.modelList(l, d)
	})
}

// modelList 是拉来的模型：一项一行，标题是显示名（没有时是 id），小字是 id；和输入框一样的那项右边写「已选」。
func (p *Page) modelList(l *widget.List, d *acct.RelayDraft) {
	c := l.Context()
	models, fetched := d.Models()
	if !fetched {
		return
	}
	if len(models) == 0 {
		l.Message(locales.AccountsRelayModelsEmpty())
		return
	}
	if len(models) > filterFrom {
		l.Custom(func() {
			widget.LineInput(c.Key("relay-model-filter"), &p.modelFilter, 1).Placeholder(locales.AccountsRelayModelsFilter()).
				Label(locales.AccountsRelayModelsFilter())
		})
	}
	shown := acct.FilterModels(models, p.modelFilter)
	if len(shown) == 0 {
		l.Message(locales.AccountsRelayModelsNoMatch())
		return
	}
	for _, m := range shown[:min(len(shown), shownModels)] {
		row := widget.Row{Key: "model:" + m.ID, Title: m.ID, OnClick: func() { d.Model = m.ID }}
		if m.DisplayName != "" && m.DisplayName != m.ID {
			row.Title, row.Summary = m.DisplayName, m.ID
		}
		if m.ID == d.Model {
			row.Value = locales.AccountsRelayModelChosen()
		}
		l.Row(row)
	}
	if more := len(shown) - shownModels; more > 0 {
		l.Message(locales.AccountsRelayModelsMore(more))
	}
}

// field 是编辑页的一行：上面是名字，下面是输入（fn 画），再下面一句说明（可以空）。
func field(l *widget.List, label, note string, fn func()) {
	c := l.Context()
	k := widget.Of(c)
	l.Custom(func() {
		ui.Column(c).Grow(1).MinWidth(0).Gap(6).Padding(4, 0).Children(func() {
			ui.Text(c, label).FontSize(widget.SizeBodyM).TextColor(k.Text)
			fn()
			if note != "" {
				ui.Text(c, note).FontSize(widget.SizeBodyS).LineHeight(1.35).TextColor(k.Secondary)
			}
		})
	})
}
