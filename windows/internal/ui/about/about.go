// Package about 是设置里的「关于」页：版本、检查更新、有新版本时的更新与更新说明、开源许可。
package about

import (
	"fmt"

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

	notes    markdown.Options
	open     map[string]bool // 展开着全文的许可证
	licenses []License
	loadErr  error
	loaded   bool
}

// View 画整页。
func (p *Page) View(c *ui.Context) ui.Element {
	return ui.Column(c).FillWidth().Gap(24).Children(func() {
		p.aboutSection(c)
		if st := p.Updates.Status(); st.Phase == update.Available {
			p.releaseSection(c, st)
		}
		p.licenseSection(c)
	})
}

func (p *Page) aboutSection(c *ui.Context) {
	section(c, "关于", func() {
		row(c, func() {
			ui.Text(c, "版本")
			ui.Spacer(c)
			if !p.Version.Release() {
				muted(c, "开发版")
			}
			ui.Text(c, p.Version.Name).Selectable()
		})
		if !p.Updates.Enabled() {
			return
		}
		st := p.Updates.Status()
		p.checkRow(c, st)
		if st.Phase == update.CheckFailed {
			ui.Text(c, st.Reason).TextColor(c.Theme().Danger)
		}
		row(c, func() {
			ui.Text(c, "自动检查更新")
			ui.Spacer(c)
			on := p.Prefs.AutoUpdateCheck()
			ui.Switch(c, &on).Label("自动检查更新")
			if on != p.Prefs.AutoUpdateCheck() {
				p.Prefs.SetAutoUpdateCheck(on)
			}
		})
	})
}

// checkRow 是检查更新一行：右边是结果，点它再查一次；正在查、已有新版本时点不了。
func (p *Page) checkRow(c *ui.Context, st update.Status) {
	busy := st.Phase == update.Checking || st.Phase == update.Available
	b := ui.ButtonBase(c).FillWidth().Height(36).Padding(0, 8, 0, 8).Margin(0, -8, 0, -8).Radius(6).Gap(8).
		AlignItems(ui.Center).Disabled(busy).DimDisabled(false).Label("检查更新")
	if b.Hovered() && !busy {
		b.Background(c.Theme().SurfaceHover)
	}
	b.Children(func() {
		ui.Text(c, "检查更新")
		ui.Spacer(c)
		checkResult(c, st)
	})
	if b.Clicked() {
		p.Updates.Check()
	}
}

// checkResult 是检查更新那一行右边的结果。
func checkResult(c *ui.Context, st update.Status) {
	switch st.Phase {
	case update.Checking:
		ui.Spinner(c)
		muted(c, "正在检查…")
	case update.UpToDate:
		muted(c, "已是最新版本")
	case update.CheckFailed:
		ui.Text(c, "检查失败").TextColor(c.Theme().Danger)
	case update.Available:
		ui.Text(c, "有新版本 "+st.Manifest.VersionName).TextColor(c.Theme().Accent)
	}
}

// releaseSection 是新版本一节：版本号、大小或进度，按钮管下载和安装；下面是更新说明。
func (p *Page) releaseSection(c *ui.Context, st update.Status) {
	m := st.Manifest
	section(c, "新版本", func() {
		row(c, func() {
			ui.Column(c).Grow(1).Gap(4).Children(func() {
				ui.Text(c, "Zeta "+m.VersionName).Bold()
				summary := ui.Text(c, releaseSummary(st))
				if st.Step == update.Failed {
					summary.TextColor(c.Theme().Danger)
				} else {
					summary.TextColor(c.Theme().TextMuted)
				}
			})
			p.releaseButton(c, st.Step)
		})
		if st.Step == update.Downloading {
			ui.Progress(c, float64(st.Done)/float64(m.MSI.Size)).FillWidth().Label("下载进度")
		}
		muted(c, "安装时 Zeta 会关闭，完成后自动重新打开。")
	})
	section(c, "更新说明", func() {
		notes := m.Notes
		if notes == "" {
			notes = "- 没有更新说明"
		}
		markdown.View(c, notes, &p.notes)
	})
}

func (p *Page) releaseButton(c *ui.Context, step update.Step) {
	switch step {
	case update.Ready:
		if ui.PrimaryButton(c, "更新").Clicked() {
			p.Updates.Update()
		}
	case update.Failed:
		if ui.Button(c, "重试").Clicked() {
			p.Updates.Update()
		}
	case update.Downloading:
		if ui.Button(c, "取消").Clicked() {
			p.Updates.Cancel()
		}
	case update.Verifying, update.Installing:
		ui.Spinner(c)
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

// licenseSection 是开源许可：Zeta 自己的，再是 Windows 程序和主机上的程序带着的第三方组件，点开看全文。
func (p *Page) licenseSection(c *ui.Context) {
	if !p.loaded {
		p.licenses, p.loadErr = licenses()
		p.open, p.loaded = map[string]bool{}, true
	}
	section(c, "开源许可", func() {
		muted(c, "Zeta 按 GPL-3.0 授权，Copyright © 2026 dzshzx。程序里还带着下面这些第三方组件，各自按它们的许可证分发。")
		p.license(c, "Zeta", "Zeta（GPL-3.0）", zetaLicense)
		if p.loadErr != nil {
			ui.Text(c, "读不出许可证清单："+p.loadErr.Error()).TextColor(c.Theme().Danger)
			return
		}
		for _, group := range []struct{ where, title string }{
			{"app", "Windows 程序链接的"},
			{"host", "带到主机上的 zeta-helper、claude-switch 链接的"},
		} {
			ui.Text(c, group.title).Bold().Margin(8, 0, 0, 0)
			for _, l := range p.licenses {
				if l.Where == group.where {
					p.license(c, l.Where+" "+l.Module, l.Title(), l.Text)
				}
			}
		}
	})
}

// license 是一项许可：标题点开显示全文。key 在整页里不重复。
func (p *Page) license(c *ui.Context, key, title string, text func() string) {
	open := p.open[key]
	ui.Collapsible(c.Key(key), title, &open, func() {
		ui.Text(c, text()).FontSize(12).TextColor(c.Theme().TextMuted).Selectable()
	})
	p.open[key] = open
}

func section(c *ui.Context, title string, body func()) {
	ui.Column(c).FillWidth().Gap(10).Children(func() {
		ui.Text(c, title).FontSize(13).Bold().TextColor(c.Theme().TextMuted)
		body()
	})
}

func row(c *ui.Context, body func()) {
	ui.Row(c).FillWidth().Gap(8).AlignItems(ui.Center).Children(body)
}

func muted(c *ui.Context, s string) ui.Element { return ui.Text(c, s).TextColor(c.Theme().TextMuted) }

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
