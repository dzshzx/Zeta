// Package about 是设置里的「关于」页：版本、检查更新、有新版本时的更新与更新说明、开源许可。
package about

import (
	"fmt"

	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// Updates 是关于页用到的更新功能，*update.Updater 就是。
type Updates interface {
	Enabled() bool
	Status() update.Status
	Check()
	Update()
	Cancel()
}

// Page 是「关于」页，调用方嵌进设置窗口，每帧调 View：它只画一列内容，不带滚动，放进设置页的滚动区。
// Updates 的状态是后台 goroutine 改的，变了要重画（Updater.Changed 里调窗口的 Update）。字段在第一次 View 之前填好。
type Page struct {
	Version update.Version
	Updates Updates
	Prefs   update.Prefs // 「自动检查更新」开关读写的设置

	notes      markdown.Options
	notesTheme markdown.Theme
	open       map[string]bool // 展开着全文的许可证
	licenses   []License
	loadErr    error
	loaded     bool
}

// View 画整页：一组组白色圆角卡片，同手机版设置里的「关于」。
func (p *Page) View(c *ui.Context) ui.Element {
	return ui.Column(c).FillWidth().Children(func() {
		p.aboutSection(c)
		if st := p.Updates.Status(); st.Phase == update.Available {
			p.releaseSection(c, st)
		}
		p.licenseSection(c)
	})
}

func (p *Page) aboutSection(c *ui.Context) {
	coui.Group(c, "关于", "", func(l *coui.List) {
		version := p.Version.Name
		if !p.Version.Release() {
			version = "开发版 " + version
		}
		l.Row(coui.Row{Title: "版本", Value: version})
		if !p.Updates.Enabled() {
			return
		}
		st := p.Updates.Status()
		p.checkRow(l, st)
		l.Row(coui.Row{Title: "自动检查更新", End: func() {
			on := p.Prefs.AutoUpdateCheck()
			coui.Switch(c, &on).Label("自动检查更新")
			if on != p.Prefs.AutoUpdateCheck() {
				p.Prefs.SetAutoUpdateCheck(on)
			}
		}})
	})
}

// checkRow 是检查更新一行：右边是结果，点它再查一次；正在查、已有新版本时点不了。没查成时原因写在标题下面。
func (p *Page) checkRow(l *coui.List, st update.Status) {
	c := l.Context()
	row := coui.Row{Title: "检查更新", End: func() { checkResult(c, st) }}
	if st.Phase == update.CheckFailed {
		row.Summary, row.SummaryColor = st.Reason, coui.Of(c).Error
	}
	if st.Phase != update.Checking && st.Phase != update.Available {
		row.OnClick = p.Updates.Check
	}
	l.Row(row)
}

// checkResult 是检查更新那一行右边的结果（14，同 COUI 行尾的值）。
func checkResult(c *ui.Context, st update.Status) {
	k := coui.Of(c)
	value := func(s string, color ui.Color) { ui.Text(c, s).FontSize(coui.SizeBodyM).TextColor(color).SingleLine() }
	switch st.Phase {
	case update.Checking:
		ui.Spinner(c).Size(14, 14)
		value("正在检查…", k.Tertiary)
	case update.UpToDate:
		value("已是最新版本", k.Tertiary)
	case update.CheckFailed:
		value("检查失败", k.Error)
	case update.Available:
		value("有新版本 "+st.Manifest.VersionName, k.PrimaryText)
	}
}

// releaseSection 是新版本一节：版本号、大小或进度，按钮管下载和安装；下面是更新说明。
func (p *Page) releaseSection(c *ui.Context, st update.Status) {
	m := st.Manifest
	coui.Group(c, "新版本", "安装时 Zeta 会关闭，完成后自动重新打开。", func(l *coui.List) {
		row := coui.Row{Title: "Zeta " + m.VersionName, Summary: releaseSummary(st), End: func() { p.releaseButton(c, st.Step) }}
		if st.Step == update.Failed {
			row.SummaryColor = coui.Of(c).Error
		}
		l.Row(row)
		if st.Step == update.Downloading {
			l.Custom(func() {
				ui.Progress(c, float64(st.Done)/float64(m.MSI.Size)).Grow(1).Label("下载进度")
			})
		}
	})
	coui.Group(c, "更新说明", "", func(l *coui.List) {
		notes := m.Notes
		if notes == "" {
			notes = "- 没有更新说明"
		}
		k := coui.Of(c)
		p.notesTheme = markdown.Theme{FontSize: coui.SizeBodyL, Text: k.Text, Muted: k.Tertiary, Strong: k.Text, Link: k.PrimaryText}
		p.notes.Theme = &p.notesTheme
		l.Custom(func() { markdown.View(c, notes, &p.notes).Grow(1).Padding(4, 0) })
	})
}

func (p *Page) releaseButton(c *ui.Context, step update.Step) {
	switch step {
	case update.Ready:
		if coui.SmallPrimaryButton(c, "更新").Clicked() {
			p.Updates.Update()
		}
	case update.Failed:
		if coui.SmallPrimaryButton(c, "重试").Clicked() {
			p.Updates.Update()
		}
	case update.Downloading:
		if coui.SmallButton(c, "取消").Clicked() {
			p.Updates.Cancel()
		}
	case update.Verifying, update.Installing:
		ui.Spinner(c).Size(16, 16)
	}
}

func releaseSummary(st update.Status) string {
	m := st.Manifest
	size := formatBytes(m.MSI.Size)
	switch st.Step {
	case update.Downloading:
		return "正在下载 " + formatBytes(st.Done) + " / " + size
	case update.Verifying:
		return "正在校验"
	case update.Installing:
		return "正在安装"
	case update.Failed:
		return st.Reason
	}
	if len(m.PublishedAt) >= 10 {
		return size + " · " + m.PublishedAt[:10] + " 发布"
	}
	return size
}

// licenseSection 是开源许可：Zeta 自己的，再是 Windows 程序和主机上的程序带着的第三方组件，各一组，点一行展开全文。
func (p *Page) licenseSection(c *ui.Context) {
	if !p.loaded {
		p.licenses, p.loadErr = licenses()
		p.open, p.loaded = map[string]bool{}, true
	}
	coui.Group(c, "开源许可", "Zeta 按 GPL-3.0 授权，Copyright © 2026 dzshzx。程序里还带着下面这些第三方组件，各自按它们的许可证分发。",
		func(l *coui.List) { p.license(l, "Zeta", "Zeta（GPL-3.0）", zetaLicense) })
	if p.loadErr != nil {
		ui.Column(c).Padding(0, 16, 16, 16).Children(func() {
			coui.Notice(c, "读不出许可证清单："+p.loadErr.Error(), coui.Failure)
		})
		return
	}
	for _, group := range []struct{ where, title string }{
		{"app", "Windows 程序链接的"},
		{"host", "带到主机上的 zeta-helper、claude-switch 链接的"},
	} {
		coui.Group(c, group.title, "", func(l *coui.List) {
			for _, li := range p.licenses {
				if li.Where == group.where {
					p.license(l, li.Where+" "+li.Module, li.Title(), li.Text)
				}
			}
		})
	}
}

// license 是一项许可：一行标题，右边的箭头展开时朝下，展开后下面是全文。key 在整页里不重复。
func (p *Page) license(l *coui.List, key, title string, text func() string) {
	c := l.Context()
	k := coui.Of(c)
	open := p.open[key]
	l.Custom(func() {
		ui.Column(c.Key(key)).Grow(1).MinWidth(0).Children(func() {
			parts := ui.CollapsibleBase(c, &open)
			parts.Trigger.FillWidth().MinHeight(32).Gap(8).AlignItems(ui.Center).Cursor(ui.CursorPointer).Label(title).
				Children(func() {
					ui.Text(c, title).FontSize(coui.SizeHeadlineXS).TextColor(k.Text).Grow(1).MinWidth(0).SingleLine().Ellipsis("…")
					ui.Icon(c, coui.IconForward).Size(16, 16).TextColor(k.Tertiary).Rotate(90 * parts.Progress())
				})
			panel := parts.Panel(func() {
				ui.Text(c, text()).FontSize(coui.SizeBodyXS).LineHeight(1.4).TextColor(k.Secondary).Selectable()
			})
			if panel.Valid() {
				panel.FillWidth().Padding(4, 0, 8, 0)
			}
		})
	})
	p.open[key] = open
}

// formatBytes 把字节数写成 12.3 MB 这样。
func formatBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	value, suffix := float64(n)/unit, "KB"
	for _, s := range []string{"MB", "GB"} {
		if value < unit {
			break
		}
		value, suffix = value/unit, s
	}
	return fmt.Sprintf("%.1f %s", value, suffix)
}
