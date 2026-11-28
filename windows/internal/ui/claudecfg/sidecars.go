package claudecfg

import (
	"context"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

type sidecarsState struct {
	list    []coreapi.AgySidecar
	loaded  bool
	working bool
}

func (p *Page) openSidecars() {
	p.sidecars = sidecarsState{}
	p.reloadSidecars()
}

func (p *Page) reloadSidecars() {
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.Sidecars(ctx)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgAgySidecarsLoadFailed(), err)
				return
			}
			p.sidecars.list, p.sidecars.loaded = list, true
		}
	})
}

func (p *Page) sidecarsPage(c *ui.Context) {
	s := &p.sidecars
	pageTitle(c, "Sidecars", p.back, nil)
	body(c, "agy-sidecars", func() {
		widget.Group(c, "Sidecars", locales.ClaudecfgAgySidecarsFooter(), func(l *widget.List) {
			switch {
			case !s.loaded:
				loadingRow(l, locales.CommonLoading())
			case len(s.list) == 0:
				emptyRow(l, locales.ClaudecfgAgySidecarsEmpty(), "")
			default:
				for _, sc := range s.list {
					summary := sc.Description
					if summary == "" {
						summary = sc.ID
					}
					item(l, sc.DisplayName, summary, func() {
						on := sc.Enabled
						if widget.Switch(c.Key("sc-"+sc.ID), &on).Disabled(s.working).Changed() {
							p.toggleSidecar(sc.ID, on)
						}
					})
				}
			}
		})
	})
}

func (p *Page) toggleSidecar(id string, enabled bool) {
	p.sidecars.working = true
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.SetSidecarEnabled(ctx, id, enabled, nil)
		return func() {
			p.sidecars.working = false
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.sidecars.list, p.sidecars.loaded = list, true
		}
	})
}
