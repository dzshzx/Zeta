package ui

import (
	"context"
	"strconv"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

type artifactsState struct {
	open     bool
	selected string
	loading  bool
	content  *coreapi.ArtifactContent
	errText  string
}

func (a *App) artifactsButton(c *ui.Context, p *chatPane) {
	if len(p.state.Artifacts) == 0 {
		return
	}
	label := locales.ChatArtifactsCount(len(p.state.Artifacts))
	b := ui.ButtonBase(c.Key("agy-artifacts")).Cursor(ui.CursorPointer).Label(label)
	widget.ToolPill(c, b, widget.IconFile, label, ui.Color{})
	b.OnClick(func() {
		p.artifacts.open = true
		if p.artifacts.selected == "" && len(p.state.Artifacts) > 0 {
			a.loadArtifact(p, p.state.Artifacts[0].Name)
		}
	})
	a.artifactsDialog(c, p)
}

func (a *App) loadArtifact(p *chatPane, name string) {
	if p.src == nil || p.src.Conv == nil {
		return
	}
	p.artifacts.selected = name
	p.artifacts.loading = true
	p.artifacts.errText = ""
	conv := p.src.Conv
	a.run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*1e9)
		got, err := conv.ReadArtifact(ctx, name)
		cancel()
		a.post(func() {
			if p.artifacts.selected != name {
				return
			}
			p.artifacts.loading = false
			if err != nil {
				p.artifacts.errText = err.Error()
				return
			}
			p.artifacts.content = &got
		})
	})
}

func (a *App) artifactsDialog(c *ui.Context, p *chatPane) {
	s := &p.artifacts
	if !s.open {
		return
	}
	widget.Dialog(c, &s.open, func() {
		k := widget.Of(c)
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 8, widget.DialogPadding).Gap(10).Children(func() {
			ui.Text(c, locales.ChatArtifactsTitle()).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
			ui.Row(c).FillWidth().Gap(6).Wrap().Children(func() {
				for _, art := range p.state.Artifacts {
					name := art.Name
					btn := widget.SmallButton(c.Key("art-"+name), name)
					if s.selected == name {
						btn = widget.SmallPrimaryButton(c.Key("art-"+name), name)
					}
					btn.OnClick(func() { a.loadArtifact(p, name) })
				}
			})
			switch {
			case s.loading:
				ui.Spinner(c).Size(18, 18)
			case s.errText != "":
				ui.Text(c, s.errText).FontSize(widget.SizeBodyS).TextColor(k.Error)
			case s.content != nil:
				if s.content.Summary != "" {
					ui.Text(c, s.content.Summary).FontSize(widget.SizeBodyXS).TextColor(k.Secondary)
				}
				if s.content.Truncated {
					ui.Text(c, locales.ChatArtifactTruncated(strconv.FormatInt(s.content.Size, 10))).FontSize(widget.SizeBodyXS).TextColor(k.Secondary)
				}
				ui.Scroll(c.Key("art-scroll")).FillWidth().MaxHeight(360).Children(func() {
					ui.Text(c, s.content.Content).FontSize(widget.SizeBodyS).LineHeight(1.5).TextColor(k.Text)
				})
			}
		})
		widget.ButtonBar(c, func() {
			widget.DialogButton(c, locales.CommonClose(), widget.Recommend).OnClick(func() { s.open = false })
		})
	})
}
