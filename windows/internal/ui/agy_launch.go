package ui

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

type agyLaunchState struct {
	open     bool
	agent    string
	dirsRaw  string
	sandbox  bool
	working  bool
	errorMsg string
}

func (a *App) agyLaunchButton(c *ui.Context, p *chatPane) {
	if p.tool() != sessionproto.HarnessAgy || !p.canSwitchHarness() {
		return
	}
	label := locales.ComposerAgyLaunch()
	if p.state.Sandbox || (p.state.Agent != nil && *p.state.Agent != "") || len(p.state.ExtraDirs) > 0 {
		label += " ●"
	}
	b := ui.ButtonBase(c.Key("agy-launch")).Cursor(ui.CursorPointer).Label(locales.ComposerAgyLaunch())
	widget.ToolPill(c, b, nil, label, ui.Color{})
	b.OnClick(func() {
		p.agyLaunch.open = true
		p.agyLaunch.agent = ""
		if p.state.Agent != nil {
			p.agyLaunch.agent = *p.state.Agent
		}
		p.agyLaunch.dirsRaw = strings.Join(p.state.ExtraDirs, "\n")
		p.agyLaunch.sandbox = p.state.Sandbox
		p.agyLaunch.errorMsg = ""
	})
	a.agyLaunchDialog(c, p)
}

func (a *App) agyLaunchDialog(c *ui.Context, p *chatPane) {
	s := &p.agyLaunch
	if !s.open {
		return
	}
	widget.Dialog(c, &s.open, func() {
		k := widget.Of(c)
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 8, widget.DialogPadding).Gap(12).Children(func() {
			ui.Text(c, locales.ComposerAgyLaunchTitle()).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
			ui.Row(c).FillWidth().AlignItems(ui.Center).Gap(12).Children(func() {
				ui.Text(c, locales.ClaudecfgAgySandbox()).FontSize(widget.SizeBodyM).TextColor(k.Text).Grow(1)
				widget.Switch(c.Key("launch-sandbox"), &s.sandbox).Disabled(s.working)
			})
			ui.Text(c, "--agent").FontSize(widget.SizeBodyXS).TextColor(k.Secondary)
			widget.Input(c.Key("launch-agent"), &s.agent).Placeholder(locales.ComposerAgyAgentPlaceholder()).Disabled(s.working).FillWidth()
			ui.Text(c, "--add-dir").FontSize(widget.SizeBodyXS).TextColor(k.Secondary)
			ui.TextAreaBase(c.Key("launch-dirs"), &s.dirsRaw).Lines(3, 6).Padding(8, 12).FontSize(widget.SizeBodyM).FillWidth()
			if s.errorMsg != "" {
				ui.Text(c, s.errorMsg).FontSize(widget.SizeBodyXS).TextColor(k.Error)
			}
		})
		widget.ButtonBar(c, func() {
			widget.DialogButton(c, locales.CommonCancel(), widget.Plain).Disabled(s.working).OnClick(func() { s.open = false })
			widget.DialogButton(c, locales.CommonSave(), widget.Recommend).Disabled(s.working).OnClick(func() {
				a.applyAgyLaunch(p)
			})
		})
	})
}

func (a *App) applyAgyLaunch(p *chatPane) {
	s := &p.agyLaunch
	agent := strings.TrimSpace(s.agent)
	sandbox := s.sandbox
	var dirs []string
	for line := range strings.SplitSeq(s.dirsRaw, "\n") {
		if d := strings.TrimSpace(line); d != "" {
			dirs = append(dirs, d)
		}
	}
	s.open = false
	a.act(func(ctx context.Context, conv *chat.Conversation) error {
		if err := conv.SetAgent(ctx, agent); err != nil {
			return err
		}
		if err := conv.SetExtraDirs(ctx, dirs); err != nil {
			return err
		}
		return conv.SetSandbox(ctx, sandbox)
	})
}
