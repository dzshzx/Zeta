package claudecfg

import (
	"context"
	"slices"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

var agyHookEvents = []string{"SessionStart", "PreInvocation", "PreToolUse", "PostToolUse", "PostInvocation", "Stop"}

type hooksState struct {
	view      *coreapi.AgyHooksView
	working   bool
	editOpen  bool
	draft     coreapi.AgyHookDraft
	timeoutIn string
	deleting  *coreapi.AgyHookEntry
	deleteAsk bool
}

func (p *Page) openHooks() {
	p.hooks = hooksState{}
	p.reloadHooks()
}

func (p *Page) reloadHooks() {
	p.async(func(ctx context.Context) func() {
		v, err := p.opts.Core.Hooks(ctx, p.opts.Cwd)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgAgyHooksLoadFailed(), err)
				return
			}
			p.hooks.view = &v
		}
	})
}

func (p *Page) hooksPage(c *ui.Context) {
	s := &p.hooks
	pageTitle(c, locales.ClaudecfgAgyHooks(), p.back, func() {
		widget.SmallPrimaryButton(c, locales.ClaudecfgAgyHookAdd()).Disabled(s.working).OnClick(func() {
			s.draft = coreapi.AgyHookDraft{Scope: "user", Enabled: true, Event: "PreToolUse"}
			s.timeoutIn = ""
			s.editOpen = true
		})
	})
	body(c, "agy-hooks", func() {
		widget.Group(c, locales.ClaudecfgAgyHooks(), locales.ClaudecfgAgyHooksFooter(), func(l *widget.List) {
			switch {
			case s.view == nil:
				loadingRow(l, locales.CommonLoading())
			case len(s.view.Hooks) == 0:
				emptyRow(l, locales.ClaudecfgAgyHooksEmpty(), "")
			default:
				for _, h := range s.view.Hooks {
					p.hookRow(c, l, h)
				}
			}
		})
	})
	p.hookDialogs(c)
}

func (p *Page) hookRow(c *ui.Context, l *widget.List, h coreapi.AgyHookEntry) {
	s := &p.hooks
	summary := h.Scope
	if h.Plugin != "" {
		summary += " (" + h.Plugin + ")"
	}
	if len(h.Events) > 0 {
		summary += " · " + strings.Join(h.Events, ", ")
	}
	if h.Command != "" {
		summary += " · " + h.Command
	}
	item(l, h.Name, summary, func() {
		if h.ReadOnly {
			ui.Text(c, locales.ClaudecfgAgyHookReadOnly()).FontSize(widget.SizeBodyS).TextColor(widget.Of(c).Tertiary)
			return
		}
		on := h.Enabled
		if widget.Switch(c.Key("hk-"+h.Scope+"-"+h.Name), &on).Disabled(s.working).Changed() {
			p.setHookEnabled(h.Scope, h.Name, on)
		}
		widget.SmallButton(c.Key("hke-"+h.Scope+"-"+h.Name), locales.CommonEdit()).Disabled(s.working).OnClick(func() {
			s.draft = coreapi.AgyHookDraft{
				OriginalName: h.Name, Name: h.Name, Scope: h.Scope, Enabled: h.Enabled,
				Event: h.Event, Matcher: h.Matcher, Command: h.Command, Timeout: h.Timeout,
			}
			if s.draft.Event == "" {
				s.draft.Event = "PreToolUse"
			}
			s.timeoutIn = ""
			if h.Timeout > 0 {
				s.timeoutIn = strconv.Itoa(h.Timeout)
			}
			s.editOpen = true
		})
		widget.SmallButton(c.Key("hkd-"+h.Scope+"-"+h.Name), locales.CommonDelete()).Disabled(s.working).OnClick(func() {
			cp := h
			s.deleting, s.deleteAsk = &cp, true
		})
	})
}

func (p *Page) setHookEnabled(scope, name string, enabled bool) {
	p.hooks.working = true
	p.async(func(ctx context.Context) func() {
		v, err := p.opts.Core.SetHookEnabled(ctx, p.opts.Cwd, scope, name, enabled)
		return func() {
			p.hooks.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.hooks.view = &v
		}
	})
}

func (p *Page) saveHook() {
	s := &p.hooks
	d := s.draft
	if t, err := strconv.Atoi(strings.TrimSpace(s.timeoutIn)); err == nil && t > 0 {
		d.Timeout = t
	} else {
		d.Timeout = 0
	}
	s.editOpen, s.working = false, true
	p.async(func(ctx context.Context) func() {
		v, err := p.opts.Core.SaveHook(ctx, p.opts.Cwd, d)
		return func() {
			p.hooks.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.hooks.view = &v
		}
	})
}

func (p *Page) deleteHook(scope, name string) {
	p.hooks.working = true
	p.async(func(ctx context.Context) func() {
		v, err := p.opts.Core.DeleteHook(ctx, p.opts.Cwd, scope, name)
		return func() {
			p.hooks.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.hooks.view = &v
		}
	})
}

func (p *Page) hookDialogs(c *ui.Context) {
	s := &p.hooks
	if s.editOpen {
		dialog(c, &s.editOpen, locales.ClaudecfgAgyHooks(), func() {
			widget.Input(c.Key("hk-name"), &s.draft.Name).Placeholder("hook-name").FillWidth()
			labeled(c, "Scope", func() {
				scopes := []string{"user", "project"}
				idx := max(0, slices.Index(scopes, s.draft.Scope))
				choice(c, "hk-scope", "Scope", scopes, idx, false, func(i int) { s.draft.Scope = scopes[i] })
			})
			labeled(c, "Event", func() {
				idx := max(0, slices.Index(agyHookEvents, s.draft.Event))
				choice(c, "hk-event", "Event", agyHookEvents, idx, false, func(i int) { s.draft.Event = agyHookEvents[i] })
			})
			if s.draft.Event == "PreToolUse" || s.draft.Event == "PostToolUse" {
				widget.Input(c.Key("hk-matcher"), &s.draft.Matcher).Placeholder("matcher (optional)").FillWidth()
			}
			widget.Input(c.Key("hk-cmd"), &s.draft.Command).Placeholder("command").FillWidth()
			widget.Input(c.Key("hk-timeout"), &s.timeoutIn).Placeholder("timeout seconds (optional)").FillWidth()
		}, func() {
			widget.DialogButton(c, locales.CommonCancel(), widget.Plain).OnClick(func() { s.editOpen = false })
			valid := strings.TrimSpace(s.draft.Name) != "" && strings.TrimSpace(s.draft.Command) != ""
			widget.DialogButton(c, locales.CommonSave(), widget.Recommend).Disabled(!valid).OnClick(p.saveHook)
		})
	}
	if s.deleting != nil {
		if alert(c, &s.deleteAsk, locales.CommonDelete(), s.deleting.Name, widget.Destructive, locales.CommonCancel(), locales.CommonDelete()) == 1 {
			sc, nm := s.deleting.Scope, s.deleting.Name
			s.deleting = nil
			p.deleteHook(sc, nm)
		} else if !s.deleteAsk {
			s.deleting = nil
		}
	}
}
