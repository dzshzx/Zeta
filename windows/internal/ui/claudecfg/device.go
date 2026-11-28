package claudecfg

import (
	"context"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/chat"
	cfg "github.com/dzshzx/Zeta/hub/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// Sections 是设备页上的三组：新对话默认、CLI 运行与版本更新、配置的入口。
func (p *Page) Sections(c *ui.Context) {
	p.defaultsSection(c)
	if p.isAgy() {
		p.agySection(c)
	} else {
		p.claudeSection(c)
	}
	title, footer, memLabel := locales.ClaudecfgConfigTitle(), locales.ClaudecfgConfigFooter(), locales.ClaudecfgMemory()
	if p.isAgy() {
		title, footer, memLabel = locales.ClaudecfgAgyConfigTitle(), locales.ClaudecfgAgyConfigFooter(), locales.ClaudecfgAgyRules()
	}
	widget.Group(c, title, footer, func(l *widget.List) {
		for _, e := range []struct {
			key, label string
			v          view
			open       func()
		}{
			{"memory", memLabel, viewMemory, p.openMemory},
			{"skills", "Skills", viewSkills, p.openSkills},
			{"mcp", locales.ClaudecfgMcpServers(), viewMcp, p.openMcp},
			{"agents", locales.ClaudecfgSubagents(), viewAgents, p.openAgents},
		} {
			entry(l, e.key, e.label, "", "", true, func() {
				p.push(e.v)
				e.open()
			})
		}
		entry(l, "permissions", locales.ClaudecfgPermissions(), "", "", true, func() { p.OpenPermissions(p.opts.Cwd) })
	})
	p.noticeDialog(c)
}

// setDefaults 在后台改一项默认值，写成了换上新的，没写成说一声。
func (p *Page) setDefaults(change func(ctx context.Context) (coreapi.Defaults, error)) {
	if p.working || p.defaults == nil {
		return
	}
	p.working = true
	round := p.defaultsRound
	p.async(func(ctx context.Context) func() {
		d, err := change(ctx)
		return func() {
			p.working = false
			if p.defaultsRound != round {
				return
			}
			if err != nil {
				p.failed(locales.ClaudecfgChangeFailed(), err)
				return
			}
			p.defaults = &d
			if p.opts.OnDefaultModel != nil {
				p.opts.OnDefaultModel(d.Model)
			}
		}
	})
}

func (p *Page) defaultsSection(c *ui.Context) {
	d := p.defaults
	footer := locales.ClaudecfgDefaultsFooter()
	if p.defaultsErr != "" {
		footer = locales.ClaudecfgLoadFailed(p.defaultsErr) + "\n\n" + footer
	}
	if p.modelsPending {
		footer = locales.ClaudecfgModelsPending() + "\n\n" + footer
	}
	widget.Group(c, locales.ClaudecfgDefaultsTitle(), footer, func(l *widget.List) {
		if d == nil {
			emptyRow(l, locales.ClaudecfgNotRead(), "")
			return
		}
		disabled := p.working
		item(l, locales.ClaudecfgDefaultModel(), "", func() { p.modelChoice(c, *d, disabled || p.modelSource == ModelSourceUnknown) })
		if !p.isAgy() {
			entry(l, "default-effort", locales.ClaudecfgDefaultEffort(), effortSummary(*d), "", true, func() { p.push(viewEffort) })
		}
		p.permissionModeRow(l, *d, disabled)
		if !p.isAgy() {
			p.compactWindowRow(l, *d, disabled)
		}
	})
}

// modelChoice 是默认模型的下拉（同手机版的 DefaultModelPreference）：第一项是不设（Claude Code 内置的默认，default 也算不设），
// 接着是各系列最新的；旧版本收在最后一项「更多模型」的二级菜单里，选中的是旧版本时写在那一项右边。设着的不在清单里（别处
// 设的、下线的）时也列在上层。
func (p *Page) modelChoice(c *ui.Context, d coreapi.Defaults, disabled bool) {
	current := d.Model
	if current == "default" {
		current = ""
	}
	var top, older []Model
	for _, m := range p.models {
		if m.Older {
			older = append(older, m)
		} else {
			top = append(top, m)
		}
	}
	all := append(slices.Clone(top), older...)
	if current != "" && !slices.ContainsFunc(all, func(m Model) bool { return m.Value == current }) {
		unknown := Model{Value: current, Label: chat.ReadableModel(current)}
		top, all = append(top, unknown), append(all, unknown)
	}
	defaultLabel := locales.ClaudecfgModelBuiltin()
	if p.modelSource != ModelSourceHarness {
		defaultLabel = locales.ComposerDefault()
	}
	shown, olderHint := defaultLabel, ""
	for _, m := range all {
		if m.Value == current {
			shown = m.Label
			if m.Older {
				olderHint = m.Label
			}
		}
	}
	set := func(value string) {
		if !disabled {
			p.setDefaults(func(ctx context.Context) (coreapi.Defaults, error) { return p.opts.Core.SetDefaultModel(ctx, value) })
		}
	}
	choices := func(m *widget.Menu, list []Model) {
		for _, mo := range list {
			if m.Choice(mo.Label, mo.Value == current) && mo.Value != current {
				set(mo.Value)
			}
		}
	}
	widget.MenuButton(c.Key("default-model"), func(t ui.Element) {
		widget.Dropdown(c, t, shown)
		t.Label(locales.ClaudecfgDefaultModel()).Disabled(disabled)
	}, func(m *widget.Menu) {
		if m.Choice(defaultLabel, current == "") && current != "" {
			set("")
		}
		choices(m, top)
		if len(older) > 0 {
			m.Sub(locales.CommonMoreModels(), olderHint, func(m *widget.Menu) { choices(m, older) })
		}
	})
}

func (p *Page) permissionModeRow(l *widget.List, d coreapi.Defaults, disabled bool) {
	c := l.Context()
	var labels []string
	selected := -1
	for i, m := range chat.PermissionModes {
		labels = append(labels, m.Label())
		if m.Value == d.PermissionMode {
			selected = i
		}
	}
	if d.PermissionMode == "" {
		selected = 0
	} else if selected < 0 {
		labels = append(labels, d.PermissionMode)
		selected = len(labels) - 1
	}
	item(l, locales.ClaudecfgDefaultMode(), "", func() {
		choice(c, "default-mode", locales.ClaudecfgDefaultMode(), labels, selected, disabled, func(i int) {
			if i >= len(chat.PermissionModes) {
				return
			}
			mode := chat.PermissionModes[i].Value
			p.setDefaults(func(ctx context.Context) (coreapi.Defaults, error) {
				return p.opts.Core.SetDefaultPermissionMode(ctx, mode)
			})
		})
	})
}

func (p *Page) compactWindowRow(l *widget.List, d coreapi.Defaults, disabled bool) {
	c := l.Context()
	windows := slices.Clone(compactWindows)
	if d.CompactWindow != 0 && !slices.Contains(windows, d.CompactWindow) {
		windows = append(windows, d.CompactWindow)
	}
	labels := []string{locales.ClaudecfgContextWindowModel()}
	selected := 0
	for i, w := range windows {
		labels = append(labels, formatTokens(w))
		if w == d.CompactWindow {
			selected = i + 1
		}
	}
	item(l, locales.ClaudecfgContextWindow(), "", func() {
		choice(c, "context-window", locales.ClaudecfgContextWindow(), labels, selected, disabled, func(i int) {
			var tokens int64
			if i > 0 {
				tokens = windows[i-1]
			}
			p.setDefaults(func(ctx context.Context) (coreapi.Defaults, error) { return p.opts.Core.SetCompactWindow(ctx, tokens) })
		})
	})
}

// effortSummary：「Opus 5.5 超高，其他模型 高」；都没设时说由 Claude Code 定。
func effortSummary(d coreapi.Defaults) string {
	var parts []string
	for _, e := range d.Efforts {
		parts = append(parts, locales.ClaudecfgEffortModelLevel(chat.ReadableModel(e.Model), effortLabel(e.Level)))
	}
	switch {
	case len(parts) == 0 && d.Effort == "":
		return locales.ClaudecfgEffortUnset()
	case len(parts) == 0:
		return effortLabel(d.Effort)
	case d.Effort != "":
		parts = append(parts, locales.ClaudecfgEffortModelLevel(locales.ClaudecfgEffortOtherModels(), effortLabel(d.Effort)))
	}
	return strings.Join(parts, locales.ClaudecfgCommaSeparator())
}

// claudeSection 是自动压缩、压缩时机、自动标题（存在这台电脑上，每台设备各设各的）和闲置超时（存在那台主机上）。
func (p *Page) claudeSection(c *ui.Context) {
	dev := p.opts.Device
	widget.Group(c, "Claude", locales.ClaudecfgClaudeFooter(), func(l *widget.List) {
		on := dev.AutoCompact()
		item(l, locales.ClaudecfgAutoCompact(), "", func() {
			if widget.Switch(c.Key("auto-compact"), &on).Label(locales.ClaudecfgAutoCompact()).Changed() {
				p.saveDevice(dev.SetAutoCompact(on))
			}
		})
		percent := dev.CompactPercent()
		points := append([]int{0}, cfg.CompactPercents...)
		if !slices.Contains(points, percent) {
			points = append(points, percent)
		}
		var labels []string
		for _, pt := range points {
			labels = append(labels, compactPercentLabel(pt))
		}
		item(l, locales.ClaudecfgCompactAt(), "", func() {
			choice(c, "compact-at", locales.ClaudecfgCompactAt(), labels, slices.Index(points, percent), !on, func(i int) {
				p.saveDevice(dev.SetCompactPercent(points[i]))
			})
		})
		title := dev.AutoTitle()
		item(l, locales.ClaudecfgAutoTitle(), locales.ClaudecfgAutoTitleSummary(), func() {
			if widget.Switch(c.Key("auto-title"), &title).Label(locales.ClaudecfgAutoTitle()).Changed() {
				p.saveDevice(dev.SetAutoTitle(title))
			}
		})
		p.idleRow(l)
		p.claudeRow(l)
	})
}

func (p *Page) agySection(c *ui.Context) {
	widget.Group(c, "Antigravity", locales.ClaudecfgAgyFooter(), func(l *widget.List) {
		p.idleRow(l)
		p.claudeRow(l)
	})
}

// idleRow 是「闲置超时」：存着的值不在几档里时（在别的客户端上设的）也列出来；还没读到时灰着。
func (p *Page) idleRow(l *widget.List) {
	c := l.Context()
	points := slices.Clone(idleChoices)
	selected := -1
	if p.idle != nil {
		if !slices.Contains(points, *p.idle) {
			points = append(points, *p.idle)
		}
		selected = slices.Index(points, *p.idle)
	}
	var labels []string
	for _, m := range points {
		labels = append(labels, idleLabel(m))
	}
	item(l, locales.ClaudecfgIdleTimeout(), "", func() {
		choice(c, "idle-timeout", locales.ClaudecfgIdleTimeout(), labels, selected, p.idle == nil, func(i int) {
			minutes := points[i]
			p.async(func(ctx context.Context) func() {
				m, err := p.opts.Core.SetIdleMinutes(ctx, minutes)
				return func() {
					if err != nil {
						p.failed(locales.ClaudecfgChangeFailed(), err)
						return
					}
					p.idle = &m
				}
			})
		})
	})
}

func (p *Page) saveDevice(err error) {
	if err != nil {
		p.failed(locales.CommonSaveFailed(), err)
	}
}

// effortRow 是默认思考强度页的一行：model 空是没单独设的模型共用的那一档。
type effortRow struct {
	model, title, value string
}

// effortRows：设过的模型、模型清单里的模型（按 Model.ID，同手机版 effortRows），同一个 ID 只列一次，最后是「其他模型」。
func (p *Page) effortRows(d coreapi.Defaults) []effortRow {
	var rows []effortRow
	// 中转的 ID 没有统一写法，用它报的名字；Claude Code 的完整 ID 按 ID 写（别名和 [1m] 可能对应同一个 ID，它们的名字代表不了这个 ID）。
	name := func(id string) string {
		if p.modelSource != ModelSourceHarness || p.isAgy() {
			if i := slices.IndexFunc(p.models, func(m Model) bool { return m.ID == id }); i >= 0 {
				return p.models[i].Label
			}
		}
		return chat.ReadableModel(id)
	}
	listed := map[string]bool{}
	for _, e := range d.Efforts {
		rows = append(rows, effortRow{e.Model, name(e.Model), e.Level})
		listed[e.Model] = true
	}
	for _, m := range p.models {
		if m.ID != "" && !listed[m.ID] {
			rows = append(rows, effortRow{m.ID, name(m.ID), ""})
			listed[m.ID] = true
		}
	}
	return append(rows, effortRow{"", locales.ClaudecfgEffortOtherModels(), d.Effort})
}

// effortPage 是默认思考强度：每个模型一行，写进 modelSettings.<模型>.effortLevel，「其他模型」是顶层的 effortLevel。
func (p *Page) effortPage(c *ui.Context) {
	pageTitle(c, locales.ClaudecfgDefaultEffort(), p.back, nil)
	body(c, "effort", func() {
		widget.Group(c, "", locales.ClaudecfgEffortFooter(), func(l *widget.List) {
			if p.defaults == nil {
				emptyRow(l, locales.ClaudecfgNotRead(), p.defaultsErr)
				return
			}
			labels := []string{locales.ClaudecfgEffortFollow()}
			for _, l := range effortLevels {
				labels = append(labels, l.label())
			}
			for _, row := range p.effortRows(*p.defaults) {
				selected := 0
				for i, l := range effortLevels {
					if l.value == row.value {
						selected = i + 1
					}
				}
				item(l, row.title, "", func() {
					choice(c, "effort-"+row.model, row.title, labels, selected, p.working, func(i int) {
						level := ""
						if i > 0 {
							level = effortLevels[i-1].value
						}
						p.setDefaults(func(ctx context.Context) (coreapi.Defaults, error) {
							return p.opts.Core.SetDefaultEffort(ctx, row.model, level)
						})
					})
				})
			}
		})
	})
}
