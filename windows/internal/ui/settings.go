package ui

import (
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	uiacct "github.com/dzshzx/Zeta/windows/internal/ui/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// unofficial 是关于一组下面的说明（同 ZetaInfo.UNOFFICIAL）。
const unofficial = "Claude 是 Anthropic 的商标。Zeta 不是 Anthropic 的产品，与 Anthropic 没有关联。"

// themeLabels 是外观的三档，下标对应 themeOf 的结果。
var themeLabels = []string{"跟随系统", "浅色", "深色"}

// startSettings 打开设置页：各台还没读过账号的读一遍（账号一行的副标题），额度没有时取一次存着的；「连接」关着的不读。
func (a *App) startSettings() {
	for _, x := range a.hub.Hosts() {
		r := a.hub.Remote(x.ID)
		if r == nil {
			continue
		}
		if _, ok := r.Accounts.State(); !ok && r.Accounts.Working() == "" {
			r.Accounts.Refresh()
		}
		if _, ok := r.Quota.Snapshot(); !ok && !r.Quota.Refreshing() {
			r.Quota.Refresh(false)
		}
	}
}

// settings 是设置（布局照 ChatGPT 的 Windows 客户端）：侧栏换成设置的分类（settingsNav），右边是选中的那一类，
// 没有返回键；侧栏顶上的「返回应用」或 Esc 回到对话。分类：常规（外观、快捷键）、各台设备、添加设备、各台的账号与额度、诊断、关于。

// inSettings：在看设置的某一页（不是对话）。
func (a *App) inSettings() bool { return a.page != pageHome && a.page != pageTasks }

// leaveSettings 回到对话。
func (a *App) leaveSettings() { a.page = pageHome }

// escapeSettings 是设置里按 Esc：诊断页点开着一个日志文件时先回到清单，别的时候回到对话。
func (a *App) escapeSettings() {
	if a.page == pageDiag && a.diag != nil && a.diag.Back() {
		return
	}
	a.leaveSettings()
}

// settingsNav 是在设置里时的侧栏：顶上一行「返回应用」同时是标题栏，下面一类一行，选中的那类是白底。
func (a *App) settingsNav(c *ui.Context) {
	k := widget.Of(c)
	c.OnWindowShortcut(0, ui.KeyEscape, a.escapeSettings)
	ui.Column(c).Fill().Background(k.Sidebar).Children(func() {
		ui.Row(c).Height(widget.TitleBarHeight).Padding(0, 10, 0, 10).AlignItems(ui.Center).Shrink(0).DragWindow().Children(func() {
			widget.NavItem(c, widget.IconBack, "返回应用", "Esc", false).OnClick(a.leaveSettings).Width(0).Grow(1)
		})
		ui.Scroll(c.Key("settings-nav")).Grow(1).MinHeight(0).Children(func() {
			ui.Column(c).Padding(4, 10, 12, 10).Gap(2).Children(func() { a.settingsCategories(c) })
		})
	})
}

// settingsCategories 是设置的各类。
func (a *App) settingsCategories(c *ui.Context) {
	widget.NavItem(c, widget.IconSettings, "常规", "", a.page == pageSettings).OnClick(func() { a.open(pageSettings) })
	navTitle(c, "设备")
	for _, x := range a.hub.Hosts() {
		widget.NavItem(c.Key("device/"+x.ID), widget.IconServer, x.Name(), offLabel(x), a.page == pageDevice && a.device == x.ID).
			OnClick(func() {
				a.device = x.ID
				a.open(pageDevice)
			})
	}
	widget.NavItem(c, widget.IconAdd, "添加设备", "", a.page == pageDevices).OnClick(func() { a.open(pageDevices) })
	if hosts := a.hub.Hosts(); len(hosts) > 0 {
		navTitle(c, "账号与额度")
		for _, x := range hosts {
			a.accountsItem(c, x)
		}
	}
	navTitle(c, "")
	widget.NavItem(c, widget.IconWrench, "诊断", "", a.page == pageDiag).OnClick(func() { a.open(pageDiag) })
	hint := ""
	if st := a.About.Updates.Status(); st.Phase == update.Available {
		hint = "有新版本 " + st.Manifest.VersionName
	}
	widget.NavItem(c, widget.IconInfo, "关于", hint, a.page == pageAbout).OnClick(func() { a.open(pageAbout) })
}

// accountsItem 是一台设备的账号与额度：名字是设备名，右边是最要紧的额度窗口用了多少；「连接」关着的那台写已断开，点不进去。
func (a *App) accountsItem(c *ui.Context, x sshlink.Host) {
	r := a.hub.Remote(x.ID)
	hint := offLabel(x)
	if r != nil {
		if h, ok := headline(r.Quota, c.Now()); ok {
			hint = "已用 " + percentText(h.Percent())
		}
	}
	item := widget.NavItem(c.Key("accounts/"+x.ID), widget.IconContacts, x.Name(), hint, a.page == pageAccounts && a.device == x.ID)
	if r == nil {
		item.Disabled(true)
		return
	}
	item.OnClick(func() {
		a.device = x.ID
		a.open(pageAccounts)
	})
}

// navTitle 是设置侧栏里一组的小标题（空时只留一段空白）。
func navTitle(c *ui.Context, title string) {
	if title == "" {
		ui.Box(c).Height(10)
		return
	}
	ui.Text(c, title).FontSize(widget.SizeBodyXS).FontWeight(widget.Medium).TextColor(widget.Of(c).Secondary).Padding(14, 10, 4, 10)
}

// settingsPage 是设置的「常规」：外观、对话、快捷键。
func (a *App) settingsPage(c *ui.Context) {
	a.subPage(c, "常规", "settings", func() {
		a.appearanceSection(c)
		a.chatSection(c)
		shortcutsSection(c)
	})
}

// offNote 是「连接」关着的设备怎样（设备页的开关下面、聊天窗格的已断开说明），同手机版的 RemoteHosts.OFF_NOTE。
const offNote = "Zeta 不连这台设备，它上面的对话在那边照样跑"

// offLabel 是「连接」关着的设备在设置侧栏和侧栏设备菜单里注明的字；开着时是空。
func offLabel(x sshlink.Host) string {
	if x.Off {
		return "已断开"
	}
	return ""
}

// headline 是额度里最要紧的那个窗口；没有数、这种登录没有订阅额度时 ok 为 false。
func headline(q *acct.Quota, now time.Time) (coreapi.QuotaWindow, bool) {
	s, ok := q.Snapshot()
	if !ok {
		return coreapi.QuotaWindow{}, false
	}
	s = s.At(now)
	if s.Unavailable {
		return coreapi.QuotaWindow{}, false
	}
	return s.Headline()
}

func percentText(n int) string { return strconv.Itoa(n) + "%" }

// appearanceSection 是外观：主题（跟随系统、浅色、深色，存在设置里，键同手机版）、界面语言和界面缩放（zoom.go）。
func (a *App) appearanceSection(c *ui.Context) {
	s := a.hub.Settings()
	widget.Group(c, "外观", "", func(l *widget.List) {
		l.Row(widget.Row{Icon: widget.IconTheme, Title: "主题模式", End: func() {
			widget.MenuButton(c.Key("theme"), func(t ui.Element) {
				widget.Dropdown(c, t, themeLabels[themeOf(s.Dark())])
				t.Label("主题模式")
			}, func(m *widget.Menu) {
				current := themeOf(s.Dark())
				for i, label := range themeLabels {
					if m.Choice(label, i == current) && i != current {
						a.setTheme(i)
					}
				}
			})
		}})
		a.languageRow(c, l)
		a.zoomRow(l)
	})
}

// languageRow 是界面语言：跟随系统，或文字表里有的几种（各用自己的写法，「简体中文」「English」）。选了马上换，下次启动还是它
// （mygo 记在用户数据目录里）；手机上的语言各管各的。换了以后开着的连接把新语言报给核心（入口的 OnLocaleChanged）。
func (a *App) languageRow(c *ui.Context, l *widget.List) {
	chosen := a.Language.Chosen()
	label := locales.SettingsLanguageSystem()
	if chosen != "" {
		label = i18n.Autonym(chosen)
	}
	l.Row(widget.Row{Icon: widget.IconGlobe, Title: locales.SettingsLanguage(), End: func() {
		widget.MenuButton(c.Key("language"), func(t ui.Element) {
			widget.Dropdown(c, t, label)
			t.Label(locales.SettingsLanguage())
		}, func(m *widget.Menu) {
			if m.Choice(locales.SettingsLanguageSystem(), chosen == "") && chosen != "" {
				a.Language.Choose("")
			}
			for _, tag := range i18n.Languages() {
				if m.Choice(i18n.Autonym(tag), chosen == tag) && chosen != tag {
					a.Language.Choose(tag)
				}
			}
		})
	}})
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

// subPage 是设置的一页：顶上标题（同时是标题栏），下面是 fn 画的、能滚动的卡片。
func (a *App) subPage(c *ui.Context, title, key string, fn func()) {
	widget.Header(c, title, nil)
	widget.Body(c, key, fn)
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
	a.subPage(c, "关于", "about", func() {
		a.About.View(c)
		ui.Text(c, unofficial).FontSize(widget.SizeBodyXS).LineHeight(1.4).TextColor(widget.Of(c).Secondary).Padding(0, 32)
	})
}

// startDeviceConfig 打开设备页时建那台的 Claude Code 配置部分，读一遍新对话默认。
func (a *App) startDeviceConfig(x sshlink.Host) {
	r := a.hub.Remote(x.ID)
	if r == nil {
		a.cfg = nil
		return
	}
	models := a.models(x.ID)
	a.cfg = claudecfg.New(claudecfg.Options{
		Core:   r.Core,
		Cwd:    x.DefaultCwd,
		Device: cfg.DeviceSettings(a.hub.Settings().Store(), x.ID),
		Run:    a.run,
		Post:   a.post,
		Models: models,
		// 还没有清单时去要一次，要到了换上（models.go）
		ModelsPending: len(models) == 0,
	})
	a.cfg.Load()
	a.askModels(x, r)
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
	k := widget.Of(c)
	bar := ui.Row(c.Key("update-bar")).Margin(0, 16, 12, 16).Padding(8, 8, 8, 20).Gap(10).AlignItems(ui.Center).Shrink(0).
		AlignSelf(ui.Center).MaxWidth(widget.PageWidth - 32)
	widget.Shadow(widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(bar), k)
	bar.Children(func() {
		ui.Box(c).Size(6, 6).Radius(3).Background(k.Error).Shrink(0)
		ui.Text(c, a.updateNote).FontSize(widget.SizeBodyM).TextColor(k.Text).Grow(1).MinWidth(0)
		widget.SmallPrimaryButton(c, "查看").OnClick(func() {
			a.updateNote = ""
			a.open(pageAbout)
		})
		widget.FlatButton(c, widget.IconClose, "关闭提示", 32, 18).OnClick(func() { a.updateNote = "" })
	})
}

// chatSection 是对话一组：自动加载网络图片（键同手机版设置首页的同名开关，聊天列表每帧照它）。
func (a *App) chatSection(c *ui.Context) {
	s := a.hub.Settings()
	widget.Group(c, "对话", "", func(l *widget.List) {
		on := s.AutoLoadRemoteImages()
		l.Row(widget.Row{Icon: widget.IconImage, Title: "自动加载网络图片",
			Summary: "关着时回答里的网络图片先显示「加载图片」，点了才去取：取图会把这台电脑的 IP 交给图片所在的网站",
			End: func() {
				if widget.Switch(c.Key("auto-load-images"), &on).Label("自动加载网络图片").Changed() {
					if err := s.SetAutoLoadRemoteImages(on); err != nil {
						a.say("没存上", err.Error())
					}
				}
			}})
	})
}
