package ui

import (
	"cmp"
	"strconv"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub"
	acct "github.com/dzshzx/Zeta/hub/accounts"
	"github.com/dzshzx/Zeta/hub/appversion"
	cfg "github.com/dzshzx/Zeta/hub/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/about"
	uiacct "github.com/dzshzx/Zeta/windows/internal/ui/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// themeLabels 是外观的三档，下标对应 themeOf 的结果；按界面当前的语言取字。
var themeLabels = []func() string{locales.SettingsThemeSystem, locales.SettingsThemeLight, locales.SettingsThemeDark}

// startSettings 打开设置页：各台还没读过账号的读一遍（账号一行的副标题），额度没有时取一次存着的；「连接」关着的不读。
func (a *App) startSettings() {
	for _, x := range a.hub.Hosts() {
		r := a.hub.Remote(x.ID)
		if r == nil {
			continue
		}
		dh := a.hub.DefaultHarness(x.ID)
		acctSvc, q := r.AccountsFor(dh), r.QuotaFor(dh)
		if _, ok := acctSvc.State(); !ok && acctSvc.Working() == "" {
			acctSvc.Refresh()
		}
		if _, ok := q.Snapshot(); !ok && !q.Refreshing() {
			q.Refresh(false)
		}
	}
}

// settings 是设置（布局照 ChatGPT 的 Windows 客户端）：侧栏换成设置的分类（settingsNav），右边是选中的那一类，
// 没有返回键；侧栏顶上的「返回应用」或 Esc 回到对话。分类：常规（外观、快捷键）、各台设备、添加设备、诊断、关于；账号与额度是设备页里的一页。

// inSettings：在看设置的某一页（不是对话）。
func (a *App) inSettings() bool { return a.page != pageHome && a.page != pageTasks }

// leaveSettings 回到对话。
func (a *App) leaveSettings() { a.page = pageHome }

// escapeSettings 是设置里按 Esc：页里点开着一层时先退回那一层（escBack），别的时候回到对话。
func (a *App) escapeSettings() {
	if back := a.escBack(); back != nil {
		back()
		return
	}
	a.leaveSettings()
}

// escBack 是这时按 Esc 先退回的那一层：设备页里开着账号与额度、诊断页点开着日志；没有时是 nil，Esc 回到对话，
// 「返回应用」旁边也只在这时标 Esc。
func (a *App) escBack() func() {
	switch {
	case a.page == pageDevice && a.acct != nil:
		return a.accountsBack
	case a.page == pageDiag && a.diag != nil && a.diag.Nested():
		return a.diag.Back
	}
	return nil
}

// settingsNav 是在设置里时的侧栏：顶上一行「返回应用」同时是标题栏，下面一类一行，选中的那类是白底。
func (a *App) settingsNav(c *ui.Context) {
	k := widget.Of(c)
	c.OnWindowShortcut(0, ui.KeyEscape, a.escapeSettings)
	esc := ""
	if a.escBack() == nil {
		esc = "Esc"
	}
	ui.Column(c).Fill().Background(k.Sidebar).Children(func() {
		ui.Row(c).Height(widget.TitleBarHeight).Padding(0, 10, 0, 10).AlignItems(ui.Center).Shrink(0).DragWindow().Children(func() {
			widget.NavItem(c, widget.IconBack, locales.SettingsBackToApp(), esc, false).OnClick(a.leaveSettings).Width(0).Grow(1)
		})
		ui.Scroll(c.Key("settings-nav")).Grow(1).MinHeight(0).Children(func() {
			ui.Column(c).Padding(4, 10, 12, 10).Gap(2).Children(func() { a.settingsCategories(c) })
		})
	})
}

// settingsCategories 是设置的各类。
func (a *App) settingsCategories(c *ui.Context) {
	widget.NavItem(c, widget.IconSettings, locales.SettingsGeneral(), "", a.page == pageSettings).OnClick(func() { a.open(pageSettings) })
	navTitle(c, locales.SettingsDevices())
	for _, x := range a.hub.Hosts() {
		widget.NavItem(c.Key("device/"+x.ID), widget.IconServer, x.Name(), a.accountsHint(c, x), a.page == pageDevice && a.device == x.ID).
			OnClick(func() {
				a.device = x.ID
				a.open(pageDevice)
			})
	}
	widget.NavItem(c, widget.IconAdd, locales.SettingsAddDevice(), "", a.page == pageDevices).OnClick(func() { a.open(pageDevices) })
	navTitle(c, "")
	widget.NavItem(c, widget.IconWrench, locales.SettingsDiagnostics(), "", a.page == pageDiag).OnClick(func() { a.open(pageDiag) })
	hint := ""
	if st := a.About.Updates.Status(); st.Phase == update.Available {
		hint = locales.SettingsUpdateHint(st.Manifest.VersionName)
	}
	widget.NavItem(c, widget.IconInfo, locales.SettingsAbout(), hint, a.page == pageAbout).OnClick(func() { a.open(pageAbout) })
}

// accountsHint 是一台设备在设置侧栏右边写的：按该设备默认工具显示额度比例或中转名，「连接」关着时是已断开。
func (a *App) accountsHint(c *ui.Context, x sshlink.Host) string {
	return a.accountsHintFor(c, x, a.hub.DefaultHarness(x.ID))
}

func (a *App) accountsHintFor(c *ui.Context, x sshlink.Host, tool string) string {
	r := a.hub.Remote(x.ID)
	if r == nil {
		return offLabel(x)
	}
	q := r.QuotaFor(tool)
	if relay := q.Relay(); relay != "" {
		return locales.SettingsAccountsRelay(relay)
	}
	if h, ok := headline(q, c.Now()); ok {
		return locales.SettingsQuotaUsed(h.Percent())
	}
	return ""
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
	a.subPage(c, locales.SettingsGeneral(), "settings", nil, func() {
		a.appearanceSection(c)
		a.chatSection(c)
		shortcutsSection(c)
	})
}

// offLabel 是「连接」关着的设备在设置侧栏和侧栏设备菜单里注明的字；开着时是空。
func offLabel(x sshlink.Host) string {
	if x.Off {
		return locales.SettingsOff()
	}
	return ""
}

// headline 是额度里最要紧的那个窗口；没有数、这种登录没有订阅额度、在用中转时 ok 为 false。
func headline(q *acct.Quota, now time.Time) (coreapi.QuotaWindow, bool) {
	s, ok := q.Snapshot()
	if !ok || !q.Shown() {
		return coreapi.QuotaWindow{}, false
	}
	s = s.At(now)
	if s.Unavailable {
		return coreapi.QuotaWindow{}, false
	}
	return s.Headline()
}

func percentText(n int) string { return strconv.Itoa(n) + "%" }

// appearanceSection 是外观：主题（跟随系统、浅色、深色，存在设置里，键同手机版）、半透明、界面语言和界面缩放（zoom.go）。
func (a *App) appearanceSection(c *ui.Context) {
	s := a.hub.Settings()
	widget.Group(c, locales.SettingsAppearance(), "", func(l *widget.List) {
		l.Row(widget.Row{Icon: widget.IconTheme, Title: locales.SettingsTheme(), End: func() {
			widget.MenuButton(c.Key("theme"), func(t ui.Element) {
				widget.Dropdown(c, t, themeLabels[themeOf(s.Dark())]())
				t.Label(locales.SettingsTheme())
			}, func(m *widget.Menu) {
				current := themeOf(s.Dark())
				for i, label := range themeLabels {
					if m.Choice(label(), i == current) && i != current {
						a.setTheme(i)
					}
				}
			})
		}})
		// 半透明：毛玻璃（看大图的遮罩、聊天顶栏）透不透出底下的界面
		a.switchRow(c, l, "translucent", widget.Row{Icon: widget.IconSparkle, Title: locales.SettingsTranslucent(),
			Summary: locales.SettingsTranslucentSummary()}, s.Translucent(), s.SetTranslucent)
		a.languageRow(c, l)
		a.zoomRow(l)
	})
}

// switchRow 是设置里存一个开关的一行：on 是现在的值，改了经 set 存下，没存上弹框说原因。
func (a *App) switchRow(c *ui.Context, l *widget.List, key string, row widget.Row, on bool, set func(bool) error) {
	row.End = func() {
		if widget.Switch(c.Key(key), &on).Label(row.Title).Changed() {
			if err := set(on); err != nil {
				a.say(locales.CommonSaveFailed(), err.Error())
			}
		}
	}
	l.Row(row)
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
		a.say(locales.CommonSaveFailed(), err.Error())
	}
}

// dark 是界面用不用深色：设置里选了就照选的，跟随系统时照 system。
func (a *App) dark(system bool) bool {
	if dark, set := a.hub.Settings().Dark(); set {
		return dark
	}
	return system
}

// subPage 是设置的一页：顶上标题（同时是标题栏；back 不为空时带返回键），下面是 fn 画的、能滚动的卡片。
func (a *App) subPage(c *ui.Context, title, key string, back, fn func()) {
	widget.Header(c, title, back)
	widget.Body(c, key, fn)
}

// openAccounts 在设备页里打开 a.device 那台当前工具的账号与额度；「连接」关着（没有 serve 连接）时不开。
func (a *App) openAccounts() {
	if r := a.hub.Remote(a.device); r != nil {
		tool := a.editTool(a.device)
		a.acct = &uiacct.Page{Accounts: r.AccountsFor(tool), Quota: r.QuotaFor(tool), Harness: tool}
	}
}

// accountsPage 是一台的账号与额度，设备页里的一层（a.acct 不为空时设备页画它）：返回键和 Esc 回到设备页，
// 在编辑中转时先回到账号页。几台设备时标题带设备名。
func (a *App) accountsPage(c *ui.Context, x sshlink.Host) {
	title := locales.SettingsAccounts()
	switch {
	case a.acct.Title() != "": // 在编辑中转
		title = a.acct.Title()
	case len(a.hub.Hosts()) > 1:
		title = locales.SettingsAccountsOnDevice(x.Name())
	}
	a.subPage(c, title, "accounts", a.accountsBack, func() { a.acct.View(c) })
}

// accountsBack 是账号页的返回：在编辑中转时回到账号页，不然回到设备页（设备页的状态还在，不重新读）。
func (a *App) accountsBack() {
	if !a.acct.Back() {
		a.acct = nil
	}
}

// aboutPage 是关于：版本、检查更新、新版本的下载安装、开源许可。
func (a *App) aboutPage(c *ui.Context) {
	a.subPage(c, locales.SettingsAbout(), "about", nil, func() {
		a.About.View(c)
		ui.Text(c, locales.SettingsUnofficial()).FontSize(widget.SizeBodyXS).LineHeight(1.4).TextColor(widget.Of(c).Secondary).Padding(0, 32)
	})
}

// openChatDeviceSettings 从对话打开它所在设备与对应工具的设置，不用侧栏所选的设备。
func (a *App) openChatDeviceSettings(p *chatPane, permissions bool) bool {
	x, ok := a.hub.Host(p.src.Backend)
	if !ok || (permissions && x.Off) {
		return false
	}
	a.device = x.ID
	a.open(pageDevice)
	a.selectDeviceTool(x, p.tool())
	if permissions && a.cfg != nil {
		a.cfg.OpenPermissions(cmp.Or(a.chatCwd(p), x.DefaultCwd))
	}
	return true
}

// startDeviceConfig 打开设备页时建那台的 Claude Code 与 Antigravity 配置部分，数据等显示对应页面时再读。
func (a *App) startDeviceConfig(x sshlink.Host) {
	a.edit.loaded, a.edit.loadedClaude, a.edit.loadedAgy = false, false, false
	a.edit.models, a.edit.probed, a.edit.fed = nil, nil, nil
	a.edit.modelsAgy, a.edit.probedAgy, a.edit.fedAgy = nil, nil, nil
	r := a.hub.Remote(x.ID)
	a.edit.remote = r
	if r == nil {
		delete(a.modelLists, x.ID)
		delete(a.modelLists, modelListKey(x.ID, sessionproto.HarnessAgy))
		a.edit.cfgClaude, a.edit.cfgAgy, a.cfg = nil, nil, nil
		return
	}
	makeCfg := func(tool string) *claudecfg.Page {
		listKey := modelListKey(x.ID, tool)
		return claudecfg.New(claudecfg.Options{
			Core:    r.CoreFor(tool),
			Harness: tool,
			Cwd:     x.DefaultCwd,
			Device:  cfg.DeviceSettings(a.hub.Settings().Store(), x.ID),
			Run:     a.run,
			Post:    a.post,
			OnDefaultModel: func(model string) {
				if s := a.modelLists[listKey]; s.current(r) {
					s.catalog.Model = model
				}
			},
		})
	}
	a.edit.cfgClaude = makeCfg(sessionproto.HarnessClaude)
	a.edit.cfgAgy = makeCfg(sessionproto.HarnessAgy)
	if a.editTool(x.ID) == sessionproto.HarnessAgy {
		a.cfg = a.edit.cfgAgy
	} else {
		a.cfg = a.edit.cfgClaude
	}
}

// defaultAbout 是没给关于页时用的：本程序的版本，不查更新（测试里；窗口由入口换成接了自动更新的）。
func defaultAbout(h *hub.Hub) *about.Page {
	v := appversion.Current()
	return &about.Page{Version: v, Updates: &update.Updater{Version: v, Prefs: h.Settings()}, Prefs: h.Settings()}
}

// ShowUpdate 在窗口底部提示一句：自动检查查到了新版本 version（版本名），点「查看」进关于页。主线程上调用。
func (a *App) ShowUpdate(version string) { a.updateVersion = version }

// updateBar 是窗口底部的提示条：一句话、查看、关掉。
func (a *App) updateBar(c *ui.Context) {
	if a.updateVersion == "" {
		return
	}
	k := widget.Of(c)
	bar := ui.Row(c.Key("update-bar")).Margin(0, 16, 12, 16).Padding(8, 8, 8, 20).Gap(10).AlignItems(ui.Center).Shrink(0).
		AlignSelf(ui.Center).MaxWidth(widget.PageWidth - 32)
	widget.Shadow(widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(bar), k)
	bar.Children(func() {
		ui.Box(c).Size(6, 6).Radius(3).Background(k.Error).Shrink(0)
		ui.Text(c, locales.UpdateAvailableNotice(a.updateVersion)).FontSize(widget.SizeBodyM).TextColor(k.Text).Grow(1).MinWidth(0)
		widget.SmallPrimaryButton(c, locales.SettingsUpdateView()).OnClick(func() {
			a.updateVersion = ""
			a.open(pageAbout)
		})
		widget.FlatButton(c, widget.IconClose, locales.SettingsUpdateDismiss(), 32, 18).OnClick(func() { a.updateVersion = "" })
	})
}

// chatSection 是对话一组：自动加载网络图片（键同手机版设置首页的同名开关，聊天列表每帧照它）。
func (a *App) chatSection(c *ui.Context) {
	s := a.hub.Settings()
	widget.Group(c, locales.SettingsChat(), "", func(l *widget.List) {
		a.switchRow(c, l, "auto-load-images", widget.Row{Icon: widget.IconImage, Title: locales.SettingsAutoLoadImages(),
			Summary: locales.SettingsAutoLoadImagesSummary()}, s.AutoLoadRemoteImages(), s.SetAutoLoadRemoteImages)
	})
}
