package claudecfg

import (
	"context"
	"slices"
	"strings"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

const (
	defaultsFooter = "在这台设备上开新对话时用。存在这台设备上 Claude Code 自己的设置里，和那边终端里 /model、/effort 存为默认的是同一份；" +
		"对话里选的只管那个对话。思考强度按模型分别存。上下文窗口是会话最多用多少 token，自动压缩按它算，比模型本身的窗口大时按模型的。"
	claudeFooter = "只管 Zeta 在这台设备上起的对话。闲置超时马上生效，其余的下次起进程时生效。"
	configFooter = "改的是这台设备上 Claude Code 自己的配置（~/.claude），那边终端里的 claude 同样生效。"
	notRead      = "还没读到 Claude Code 的设置"
)

// Sections 是设备页上的三组：新对话默认、Claude（自动压缩、压缩时机、自动标题、闲置超时）、Claude Code 配置的入口。
func (p *Page) Sections(c *ui.Context) {
	p.defaultsSection(c)
	p.claudeSection(c)
	coui.Group(c, "Claude Code 配置", configFooter, func(l *coui.List) {
		for _, e := range []struct {
			label string
			v     view
			open  func()
		}{
			{"记忆", viewMemory, p.openMemory},
			{"Skills", viewSkills, p.openSkills},
			{"MCP 服务器", viewMcp, p.openMcp},
			{"Subagent", viewAgents, p.openAgents},
			{"工具权限", viewPermissions, p.openPermissions},
		} {
			entry(l, e.label, "", "", true, func() {
				p.push(e.v)
				e.open()
			})
		}
	})
	p.noticeDialog(c)
}

// setDefaults 在后台改一项默认值，写成了换上新的，没写成说一声。
func (p *Page) setDefaults(change func(ctx context.Context) (cfg.Defaults, error)) {
	p.working = true
	p.async(func(ctx context.Context) func() {
		d, err := change(ctx)
		return func() {
			p.working = false
			if err != nil {
				p.failed("没有改成", err)
				return
			}
			p.defaults = &d
		}
	})
}

func (p *Page) models() []Model {
	if len(p.opts.Models) > 0 {
		return p.opts.Models
	}
	return fallbackModels
}

func (p *Page) defaultsSection(c *ui.Context) {
	d := p.defaults
	footer := defaultsFooter
	if p.defaultsErr != "" {
		footer = "读取失败：" + p.defaultsErr + "\n\n" + footer
	}
	coui.Group(c, "新对话默认", footer, func(l *coui.List) {
		if d == nil {
			emptyRow(l, notRead, "")
			return
		}
		disabled := p.working
		// 默认模型：第一项是不设（Claude Code 内置的默认）；设着的不在清单里时也列出来
		models := slices.Clone(p.models())
		if d.Model != "" && !slices.ContainsFunc(models, func(m Model) bool { return m.Value == d.Model }) {
			models = append(models, Model{d.Model, readableModel(d.Model)})
		}
		labels := []string{"Claude Code 内置的默认"}
		selected := 0
		for i, m := range models {
			labels = append(labels, m.Label)
			if m.Value == d.Model {
				selected = i + 1
			}
		}
		item(l, "默认模型", "", func() {
			choice(c, "默认模型", labels, selected, disabled, func(i int) {
				value := ""
				if i > 0 {
					value = models[i-1].Value
				}
				p.setDefaults(func(ctx context.Context) (cfg.Defaults, error) { return p.opts.Client.SetDefaultModel(ctx, value) })
			})
		})
		entry(l, "默认思考强度", effortSummary(*d), "", true, func() { p.push(viewEffort) })
		p.permissionModeRow(l, *d, disabled)
		p.compactWindowRow(l, *d, disabled)
	})
}

func (p *Page) permissionModeRow(l *coui.List, d cfg.Defaults, disabled bool) {
	c := l.Context()
	var labels []string
	selected := 0
	for i, m := range permissionModes {
		labels = append(labels, m.label)
		if m.value == d.PermissionMode {
			selected = i
		}
	}
	if d.PermissionMode != "" && !slices.ContainsFunc(permissionModes, func(m struct{ value, label string }) bool { return m.value == d.PermissionMode }) {
		labels = append(labels, permissionModeLabel(d.PermissionMode))
		selected = len(labels) - 1
	}
	item(l, "默认权限模式", "", func() {
		choice(c, "默认权限模式", labels, selected, disabled, func(i int) {
			if i >= len(permissionModes) {
				return
			}
			mode := permissionModes[i].value
			p.setDefaults(func(ctx context.Context) (cfg.Defaults, error) {
				return p.opts.Client.SetDefaultPermissionMode(ctx, mode)
			})
		})
	})
}

func (p *Page) compactWindowRow(l *coui.List, d cfg.Defaults, disabled bool) {
	c := l.Context()
	windows := slices.Clone(compactWindows)
	if d.CompactWindow != 0 && !slices.Contains(windows, d.CompactWindow) {
		windows = append(windows, d.CompactWindow)
	}
	labels := []string{"按模型的窗口"}
	selected := 0
	for i, w := range windows {
		labels = append(labels, formatTokens(w))
		if w == d.CompactWindow {
			selected = i + 1
		}
	}
	item(l, "上下文窗口", "", func() {
		choice(c, "上下文窗口", labels, selected, disabled, func(i int) {
			var tokens int64
			if i > 0 {
				tokens = windows[i-1]
			}
			p.setDefaults(func(ctx context.Context) (cfg.Defaults, error) { return p.opts.Client.SetCompactWindow(ctx, tokens) })
		})
	})
}

// effortSummary：「Opus 5.5 超高，其他模型 高」；都没设时说由 Claude Code 定。
func effortSummary(d cfg.Defaults) string {
	var parts []string
	for _, e := range d.Efforts {
		parts = append(parts, readableModel(e.Model)+" "+effortLabel(e.Level))
	}
	switch {
	case len(parts) == 0 && d.Effort == "":
		return "没有设，按 Claude Code 的默认"
	case len(parts) == 0:
		return effortLabel(d.Effort)
	case d.Effort != "":
		parts = append(parts, "其他模型 "+effortLabel(d.Effort))
	}
	return strings.Join(parts, "，")
}

// claudeSection 是自动压缩、压缩时机、自动标题（存在这台电脑上，每台设备各设各的）和闲置超时（存在那台主机上）。
func (p *Page) claudeSection(c *ui.Context) {
	dev := p.opts.Device
	coui.Group(c, "Claude", claudeFooter, func(l *coui.List) {
		on := dev.AutoCompact()
		item(l, "自动压缩上下文", "", func() {
			if coui.Switch(c.Key("auto-compact"), &on).Label("自动压缩上下文").Changed() {
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
			labels = append(labels, cfg.CompactPercentLabel(pt))
		}
		item(l, "压缩时机", "", func() {
			choice(c, "压缩时机", labels, slices.Index(points, percent), !on, func(i int) {
				p.saveDevice(dev.SetCompactPercent(points[i]))
			})
		})
		title := dev.AutoTitle()
		item(l, "自动生成对话标题", "第一轮回答后由 Claude Code 起个简短标题，改过名的不动", func() {
			if coui.Switch(c.Key("auto-title"), &title).Label("自动生成对话标题").Changed() {
				p.saveDevice(dev.SetAutoTitle(title))
			}
		})
		p.idleRow(l)
	})
}

// idleRow 是「闲置超时」：存着的值不在几档里时（在别的客户端上设的）也列出来；还没读到时灰着。
func (p *Page) idleRow(l *coui.List) {
	c := l.Context()
	points := slices.Clone(cfg.IdleChoices)
	selected := -1
	if p.idle != nil {
		if !slices.Contains(points, *p.idle) {
			points = append(points, *p.idle)
		}
		selected = slices.Index(points, *p.idle)
	}
	var labels []string
	for _, m := range points {
		labels = append(labels, cfg.IdleLabel(m))
	}
	item(l, "闲置超时", "", func() {
		choice(c, "闲置超时", labels, selected, p.idle == nil, func(i int) {
			minutes := points[i]
			p.async(func(ctx context.Context) func() {
				m, err := p.opts.Client.SetIdleMinutes(ctx, minutes)
				return func() {
					if err != nil {
						p.failed("没有改成", err)
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
		p.failed("没存上", err)
	}
}

// effortRow 是默认思考强度页的一行：model 空是没单独设的模型共用的那一档。
type effortRow struct {
	model, title, value string
}

// effortRows：设过的模型、模型清单里的完整 ID，最后是「其他模型」。
func (p *Page) effortRows(d cfg.Defaults) []effortRow {
	var rows []effortRow
	for _, e := range d.Efforts {
		rows = append(rows, effortRow{e.Model, readableModel(e.Model), e.Level})
	}
	for _, m := range p.opts.Models {
		if strings.HasPrefix(m.Value, "claude-") && d.EffortOf(m.Value) == "" {
			rows = append(rows, effortRow{m.Value, readableModel(m.Value), ""})
		}
	}
	return append(rows, effortRow{"", "其他模型", d.Effort})
}

// effortPage 是默认思考强度：每个模型一行，写进 modelSettings.<模型>.effortLevel，「其他模型」是顶层的 effortLevel。
func (p *Page) effortPage(c *ui.Context) {
	pageTitle(c, "默认思考强度", p.back, nil)
	body(c, "effort", func() {
		coui.Group(c, "", "这台设备上的新对话按所用的模型取这里的设置，和那边终端里 /effort 存为默认的是同一份；没单独设的模型用「其他模型」那一档。", func(l *coui.List) {
			if p.defaults == nil {
				emptyRow(l, notRead, p.defaultsErr)
				return
			}
			labels := []string{"跟随 Claude Code"}
			for _, l := range effortLevels {
				labels = append(labels, l.label)
			}
			for _, row := range p.effortRows(*p.defaults) {
				selected := 0
				for i, l := range effortLevels {
					if l.value == row.value {
						selected = i + 1
					}
				}
				item(l, row.title, "", func() {
					choice(c, row.title, labels, selected, p.working, func(i int) {
						level := ""
						if i > 0 {
							level = effortLevels[i-1].value
						}
						p.setDefaults(func(ctx context.Context) (cfg.Defaults, error) {
							return p.opts.Client.SetDefaultEffort(ctx, row.model, level)
						})
					})
				})
			}
		})
	})
}
