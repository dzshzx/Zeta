package claudecfg

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

type pluginsState struct {
	list      []coreapi.AgyPlugin
	loaded    bool
	working   bool
	addOpen   bool
	addTarget string
	removing  *coreapi.AgyPlugin
	removeAsk bool
}

func (p *Page) openPlugins() {
	p.plugins = pluginsState{}
	p.reloadPlugins()
}

func (p *Page) reloadPlugins() {
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.Plugins(ctx)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgAgyPluginsLoadFailed(), err)
				return
			}
			p.plugins.list, p.plugins.loaded = list, true
		}
	})
}

func (p *Page) pluginsPage(c *ui.Context) {
	s := &p.plugins
	pageTitle(c, locales.ClaudecfgAgyPlugins(), p.back, func() {
		widget.SmallPrimaryButton(c, locales.ClaudecfgAgyPluginInstall()).Disabled(s.working).OnClick(func() {
			s.addOpen, s.addTarget = true, ""
		})
	})
	body(c, "agy-plugins", func() {
		widget.Group(c, locales.ClaudecfgAgyPlugins(), locales.ClaudecfgAgyPluginsFooter(), func(l *widget.List) {
			switch {
			case !s.loaded:
				loadingRow(l, locales.CommonLoading())
			case len(s.list) == 0:
				emptyRow(l, locales.ClaudecfgAgyPluginsEmpty(), "")
			default:
				for _, pl := range s.list {
					p.pluginRow(c, l, pl)
				}
			}
		})
	})
	p.pluginDialogs(c)
}

func (p *Page) pluginRow(c *ui.Context, l *widget.List, pl coreapi.AgyPlugin) {
	s := &p.plugins
	summary := pl.Description
	if pl.Version != "" {
		if summary != "" {
			summary = pl.Version + " · " + summary
		} else {
			summary = pl.Version
		}
	}
	item(l, pl.Name, summary, func() {
		on := pl.Enabled
		if widget.Switch(c.Key("pl-"+pl.Name), &on).Disabled(s.working).Changed() {
			p.togglePlugin(pl.Name, on)
		}
		widget.SmallButton(c.Key("rm-"+pl.Name), locales.CommonDelete()).Disabled(s.working).OnClick(func() {
			cp := pl
			s.removing, s.removeAsk = &cp, true
		})
	})
}

func (p *Page) togglePlugin(name string, enabled bool) {
	p.plugins.working = true
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.SetPluginEnabled(ctx, name, enabled)
		return func() {
			p.plugins.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.plugins.list, p.plugins.loaded = list, true
		}
	})
}

func (p *Page) installPlugin() {
	target := strings.TrimSpace(p.plugins.addTarget)
	if target == "" {
		return
	}
	p.plugins.addOpen, p.plugins.working = false, true
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.InstallPlugin(ctx, target)
		return func() {
			p.plugins.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.plugins.list, p.plugins.loaded = list, true
		}
	})
}

func (p *Page) uninstallPlugin(name string) {
	p.plugins.working = true
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.UninstallPlugin(ctx, name)
		return func() {
			p.plugins.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.plugins.list, p.plugins.loaded = list, true
		}
	})
}

func (p *Page) pluginDialogs(c *ui.Context) {
	s := &p.plugins
	if s.addOpen {
		dialog(c, &s.addOpen, locales.ClaudecfgAgyPluginInstall(), func() {
			widget.Input(c.Key("pl-target"), &s.addTarget).Placeholder("plugin-name / path / URL").FillWidth()
		}, func() {
			widget.DialogButton(c, locales.CommonCancel(), widget.Plain).OnClick(func() { s.addOpen = false })
			widget.DialogButton(c, locales.ClaudecfgAgyPluginInstall(), widget.Recommend).
				Disabled(strings.TrimSpace(s.addTarget) == "").OnClick(p.installPlugin)
		})
	}
	if s.removing != nil {
		if alert(c, &s.removeAsk, locales.ClaudecfgAgyPluginUninstall(), s.removing.Name, widget.Destructive, locales.CommonCancel(), locales.CommonDelete()) == 1 {
			name := s.removing.Name
			s.removing = nil
			p.uninstallPlugin(name)
		} else if !s.removeAsk {
			s.removing = nil
		}
	}
}
