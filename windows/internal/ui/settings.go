package ui

import (
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	uiacct "github.com/dzshzx/Zeta/windows/internal/ui/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/ui"
)

// unofficial 是关于一组下面的说明（同 ZetaInfo.UNOFFICIAL）。
const unofficial = "Claude 是 Anthropic 的商标。Zeta 不是 Anthropic 的产品，与 Anthropic 没有关联。"

// themeLabels 是外观的三档，下标对应 themeOf 的结果。
var themeLabels = []string{"跟随系统", "浅色", "深色"}

// startSettings 打开设置页：各台还没读过账号的读一遍（账号一行的副标题），额度没有时取一次存着的。
func (a *App) startSettings() {
	for _, x := range a.hub.Hosts() {
		r := a.hub.Remote(x.ID)
		if _, ok := r.Accounts.State(); !ok && r.Accounts.Working() == "" {
			r.Accounts.Refresh()
		}
		if _, ok := r.Quota.Snapshot(); !ok && !r.Quota.Refreshing() {
			r.Quota.Refresh(false)
		}
	}
}

// settingsPage 是设置（照手机版的设置首页）：设备、Claude、外观、关于几组。
func (a *App) settingsPage(c *ui.Context) {
	coui.Header(c, "设置", nil)
	coui.Body(c, "settings", func() {
		a.devicesSection(c)
		a.claudeSection(c)
		a.appearanceSection(c)
		a.aboutSection(c)
	})
}

// devicesSection 是各台远程设备（点进设备页）和添加远程设备。
func (a *App) devicesSection(c *ui.Context) {
	k := coui.Of(c)
	coui.Group(c, "设备", "", func(l *coui.List) {
		for _, x := range a.hub.Hosts() {
			a.hostRow(l, x)
		}
		l.Row(coui.Row{Icon: coui.IconAdd, IconColor: k.Orange, Title: "添加远程设备", Arrow: true,
			OnClick: func() { a.open(pageDevices) }})
	})
}

// hostRow 是一台加好的设备，点开设备页。
func (a *App) hostRow(l *coui.List, x sshlink.Host) {
	l.Row(coui.Row{Key: x.ID, Icon: coui.IconServer, IconColor: coui.Of(l.Context()).Orange, Title: x.Name(),
		Summary: sshlink.DisplayTarget(x.Target), Arrow: true, OnClick: func() {
			a.device = x.ID
			a.open(pageDevice)
		}})
}

// claudeSection 是各台的账号与额度：副标题是那台在用的账号，右边是最要紧的额度窗口用了多少。几台设备时标题前写设备名。
func (a *App) claudeSection(c *ui.Context) {
	hosts := a.hub.Hosts()
	if len(hosts) == 0 {
		return
	}
	k := coui.Of(c)
	coui.Group(c, "Claude", "", func(l *coui.List) {
		for _, x := range hosts {
			r := a.hub.Remote(x.ID)
			row := coui.Row{Key: x.ID, Icon: coui.IconContacts, IconColor: k.Blue, Title: "账号与额度", Arrow: true,
				OnClick: func() {
					a.device = x.ID
					a.open(pageAccounts)
				}}
			if len(hosts) > 1 {
				row.Title = x.Name() + " · 账号与额度"
			}
			row.Summary, row.SummaryColor = accountSummary(r.Accounts, k)
			if h, ok := headline(r.Quota, c.Now()); ok {
				row.End = func() {
					ui.Text(c, "已用 "+percentText(h.Percent())).FontSize(coui.SizeBodyM).FontFeatures("tnum").
						TextColor(uiacct.Color(c.Theme(), h.Severity, k.Tertiary)).SingleLine()
				}
			}
			l.Row(row)
		}
	})
}

// accountSummary 是账号一行的副标题：在用的账号，还没读到时「正在读取」，读不了时是原因（红字）。
func accountSummary(s *acct.Service, k *coui.Colors) (string, ui.Color) {
	st, ok := s.State()
	switch {
	case !ok && s.Problem() != "":
		return s.Problem(), k.Error
	case !ok:
		return "正在读取", ui.Color{}
	case st.Current == nil:
		return "未登录", ui.Color{}
	}
	return st.Current.Title(), ui.Color{}
}

// headline 是额度里最要紧的那个窗口；没有数、这种登录没有订阅额度时 ok 为 false。
func headline(q *acct.Quota, now time.Time) (acct.Window, bool) {
	s, ok := q.Snapshot()
	if !ok {
		return acct.Window{}, false
	}
	s = s.At(now)
	if s.Unavailable {
		return acct.Window{}, false
	}
	return s.Headline()
}

func percentText(n int) string { return strconv.Itoa(n) + "%" }

// appearanceSection 是外观：跟随系统、浅色、深色，存在设置里（键同手机版）。
func (a *App) appearanceSection(c *ui.Context) {
	k := coui.Of(c)
	s := a.hub.Settings()
	coui.Group(c, "外观", "", func(l *coui.List) {
		l.Row(coui.Row{Icon: coui.IconTheme, IconColor: k.Green, Title: "主题模式", End: func() {
			coui.MenuButton(c.Key("theme"), func(t ui.Element) {
				coui.Dropdown(c, t, themeLabels[themeOf(s.Dark())])
				t.Label("主题模式")
			}, func(m *coui.Menu) {
				current := themeOf(s.Dark())
				for i, label := range themeLabels {
					if m.Choice(label, i == current) && i != current {
						a.setTheme(i)
					}
				}
			})
		}})
	})
}

// themeOf 是存着的深浅色在 themeLabels 里是第几档。
func themeOf(dark, set bool) int {
	switch {
	case !set:
		return 0
	case dark:
		return 2
	}
	return 1
}

func (a *App) setTheme(i int) {
	var dark *bool
	if i > 0 {
		v := i == 2
		dark = &v
	}
	if err := a.hub.Settings().SetDark(dark); err != nil {
		a.say("没存上", err.Error())
	}
}

// dark 是界面用不用深色：设置里选了就照选的，跟随系统时照 system。
func (a *App) dark(system bool) bool {
	if dark, set := a.hub.Settings().Dark(); set {
		return dark
	}
	return system
}

// aboutSection 是关于：版本，有新版本时标出来；点进关于页（检查、下载安装更新，开源许可）。
func (a *App) aboutSection(c *ui.Context) {
	k := coui.Of(c)
	coui.Group(c, "关于", unofficial, func(l *coui.List) {
		version := a.About.Version.Name
		if !a.About.Version.Release() {
			version = "开发版 " + version
		}
		row := coui.Row{Icon: coui.IconInfo, IconColor: k.Green, Title: "版本", Value: version, Arrow: true,
			OnClick: func() { a.open(pageAbout) }}
		if st := a.About.Updates.Status(); st.Phase == update.Available {
			row.Value = ""
			row.End = func() { newVersionBadge(c, st.Manifest.VersionName) }
		}
		l.Row(row).Label("关于")
	})
}

// newVersionBadge 是版本一行右边的「有新版本」：主题色的字，前面一个红点（同 COUI 的提醒点）。
func newVersionBadge(c *ui.Context, name string) {
	k := coui.Of(c)
	ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
		ui.Box(c).Size(6, 6).Radius(3).Background(k.Error)
		ui.Text(c, "有新版本 "+name).FontSize(coui.SizeBodyM).TextColor(k.PrimaryText).SingleLine()
	})
}

// subPage 是设置往下一层的页：顶上标题和返回（回到设置），下面是 fn 画的、能滚动的卡片。
func (a *App) subPage(c *ui.Context, title, key string, fn func()) {
	coui.Header(c, title, func() { a.open(pageSettings) })
	coui.Body(c, key, fn)
}

// startAccounts 打开 a.device 那台的账号与额度页。
func (a *App) startAccounts() {
	r := a.hub.Remote(a.device)
	if r == nil {
		a.acct = nil
		return
	}
	a.acct = &uiacct.Page{Accounts: r.Accounts, Quota: r.Quota}
}

// accountsPage 是一台的账号与额度。几台设备时标题带设备名。
func (a *App) accountsPage(c *ui.Context) {
	x, ok := a.hub.Host(a.device)
	if !ok || a.acct == nil {
		a.open(pageSettings)
		return
	}
	title := "账号与额度"
	if len(a.hub.Hosts()) > 1 {
		title = x.Name() + " · " + title
	}
	a.subPage(c, title, "accounts", func() { a.acct.View(c) })
}

// aboutPage 是关于：版本、检查更新、新版本的下载安装、开源许可。
func (a *App) aboutPage(c *ui.Context) {
	a.subPage(c, "关于", "about", func() { a.About.View(c) })
}

// startDeviceConfig 打开设备页时建那台的 Claude Code 配置部分，读一遍新对话默认。
func (a *App) startDeviceConfig(x sshlink.Host) {
	r := a.hub.Remote(x.ID)
	if r == nil {
		a.cfg = nil
		return
	}
	r.Config.Cwd = x.DefaultCwd
	a.cfg = claudecfg.New(claudecfg.Options{
		Client: r.Config,
		Device: cfg.DeviceSettings(a.hub.Settings().Store(), x.ID),
		Run:    a.run,
		Post:   a.post,
		Models: a.models(x.ID),
		Loaded: func() { a.hub.FillHostname(x.ID) },
	})
	a.cfg.Load()
}

// models 是设备 id 上 Claude Code 报过的模型（聊天窗格开着那台的对话时有），给默认模型下拉用；没有时 claudecfg 列常用的别名。
func (a *App) models(id string) []claudecfg.Model {
	p := a.pane
	if p == nil || p.src.Backend != id {
		return nil
	}
	var out []claudecfg.Model
	for _, m := range p.state.Models {
		out = append(out, claudecfg.Model{Value: m.Value, Label: m.DisplayName})
	}
	return out
}

// defaultAbout 是没给关于页时用的：本程序的版本，不查更新（测试里；窗口由入口换成接了自动更新的）。
func defaultAbout(h *hub.Hub) *about.Page {
	v := update.Current()
	return &about.Page{Version: v, Updates: &update.Updater{Version: v, Prefs: h.Settings()}, Prefs: h.Settings()}
}

// ShowUpdate 在窗口底部提示一句（自动检查查到了新版本），点「查看」进关于页。主线程上调用。
func (a *App) ShowUpdate(text string) { a.updateNote = text }

// updateBar 是窗口底部的提示条：一句话、查看、关掉。
func (a *App) updateBar(c *ui.Context) {
	if a.updateNote == "" {
		return
	}
	k := coui.Of(c)
	bar := ui.Row(c.Key("update-bar")).Margin(0, 16, 12, 16).Padding(8, 8, 8, 20).Gap(10).AlignItems(ui.Center).Shrink(0).
		AlignSelf(ui.Center).MaxWidth(coui.PageWidth - 32)
	coui.Shadow(coui.Surface{Shape: coui.ShapeCard, Fill: k.Card}.Paint(bar), k)
	bar.Children(func() {
		ui.Box(c).Size(6, 6).Radius(3).Background(k.Error).Shrink(0)
		ui.Text(c, a.updateNote).FontSize(coui.SizeBodyM).TextColor(k.Text).Grow(1).MinWidth(0)
		coui.SmallPrimaryButton(c, "查看").OnClick(func() {
			a.updateNote = ""
			a.open(pageAbout)
		})
		coui.FlatButton(c, coui.IconClose, "关闭提示", 32, 18).OnClick(func() { a.updateNote = "" })
	})
}
