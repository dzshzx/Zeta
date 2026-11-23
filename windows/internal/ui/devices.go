package ui

import (
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// adding 是添加远程设备页的状态。
type adding struct {
	keys       []string // 这台电脑 ~/.ssh 里能选的私钥
	key        string   // 接下来加的设备用哪把，空是自动选择
	config     []string // SSH 配置里还没加过的主机
	configErr  string   // 读不到 SSH 配置的原因
	configRead bool     // 读完了（读成没读成都算）
	draft      string   // 手动添加的地址
	probing    string   // 正在连的地址；同一时间只连一个
}

// startAdding 打开添加远程设备页时读一遍这台电脑的 SSH 配置和私钥。
func (a *App) startAdding() {
	known := make([]string, 0, len(a.hub.Hosts()))
	for _, x := range a.hub.Hosts() {
		known = append(known, x.Target)
	}
	a.add.configRead = false
	a.async(func() func() {
		keys := a.hub.Keys()
		config, err := a.hub.ConfigHosts(known)
		return func() {
			a.add.keys, a.add.config, a.add.configRead = keys, config, true
			a.add.configErr = ""
			if err != nil {
				a.add.configErr = err.Error()
			}
		}
	})
}

// devicesPage 是添加远程设备：私钥、从 SSH 配置导入、手动添加。加好的各台列在设置页上。
func (a *App) devicesPage(c *ui.Context) {
	a.subPage(c, locales.DevicesTitle(), "devices", func() { a.addSection(c) })
}

// addSection 是添加设备：私钥、从 SSH 配置导入、手动添加。
func (a *App) addSection(c *ui.Context) {
	busy := a.add.probing != ""
	widget.Group(c, locales.SettingsAddDevice(), locales.DevicesFooter(), func(l *widget.List) {
		l.Row(widget.Row{Title: locales.DevicesSshKey(), End: func() {
			keyChoice(c, a.add.keys, a.add.key, busy, func(key string) { a.add.key = key })
		}})
	})
	widget.Group(c, locales.DevicesImport(), locales.DevicesImportFooter(), func(l *widget.List) {
		switch {
		case !a.add.configRead:
			l.Message(locales.DevicesReadingConfig())
		case a.add.configErr != "":
			l.Message(locales.DevicesConfigError(a.add.configErr))
		case len(a.add.config) == 0:
			l.Message(locales.DevicesConfigEmpty())
		}
		for _, alias := range a.add.config {
			l.Row(widget.Row{Key: alias, Title: alias, End: func() {
				if a.add.probing == alias {
					ui.Text(c, locales.CommonConnecting()).FontSize(widget.SizeBodyS).TextColor(widget.Of(c).Secondary)
					return
				}
				widget.SmallButton(c.Key("add-"+alias), locales.CommonAdd()).Disabled(busy).OnClick(func() { a.addHost(alias) })
			}})
		}
	})
	target, valid := sshlink.TargetFor(a.add.draft)
	submit := func() {
		if t, ok := sshlink.TargetFor(a.add.draft); ok {
			a.addHost(t)
		}
	}
	widget.Group(c, locales.DevicesManual(), sshlink.ConfigHint, func(l *widget.List) {
		l.Custom(func() {
			widget.Input(c.Key("draft"), &a.add.draft).Placeholder(locales.DevicesAddressPlaceholder()).Label(locales.DevicesAddress()).Grow(1).OnSubmit(submit)
			label := locales.CommonAdd()
			if busy && a.add.probing == target {
				label = locales.CommonConnecting()
			}
			widget.SmallPrimaryButton(c, label).Disabled(!valid || busy).OnClick(submit)
		})
	})
}

// addHost 先连一次 target，连上了才加。
func (a *App) addHost(target string) {
	if a.add.probing != "" {
		return
	}
	key := a.add.key
	a.add.probing = target
	a.async(func() func() {
		f, err := a.hub.Probe(target, "", key)
		return func() {
			a.add.probing = ""
			shown := sshlink.DisplayTarget(target)
			if err != nil {
				a.say(locales.DevicesNotAdded(shown), sshlink.Describe(err))
				return
			}
			x, err := a.hub.AddHost(target, key, f)
			if err != nil {
				a.say(locales.DevicesNotSaved(x.Name()), err.Error())
				return
			}
			a.add.draft = ""
			a.add.config = without(a.add.config, target)
			a.say(locales.DevicesAdded(x.Name()), addedSummary(f, key))
		}
	})
}

// addedSummary 是加上以后给人看的：Claude Code 版本、系统、私钥、主机指纹。
func addedSummary(f host.Found, key string) string {
	text := f.Summary()
	if key != "" {
		text += " · " + locales.DevicesSummaryKey(sshlink.KeyName(key))
	}
	if fp := sshlink.Fingerprint(f.HostKey); fp != "" {
		text += "\n" + locales.DevicesSummaryFingerprint(fp)
	}
	return text
}

// keyChoice 是「SSH 密钥」下拉：自动选择（照 ~/.ssh/config），或 keys 里的一把。选着的那把已经不在 ~/.ssh 里了也列出来。
func keyChoice(c *ui.Context, keys []string, chosen string, disabled bool, onChange func(string)) {
	options := []string{""}
	options = append(options, keys...)
	if chosen != "" && !contains(keys, chosen) {
		options = append(options, chosen)
	}
	widget.MenuButton(c.Key("key"), func(t ui.Element) {
		widget.Dropdown(c, t, keyLabel(chosen))
		t.Description(locales.DevicesSshKey()).Disabled(disabled)
	}, func(m *widget.Menu) {
		for _, key := range options {
			if m.Choice(keyLabel(key), key == chosen) && key != chosen {
				onChange(key)
			}
		}
	})
}

// keyLabel 是私钥 key 给人看的名字；空是自动选择。
func keyLabel(key string) string {
	if key == "" {
		return locales.DevicesAutoKey()
	}
	return sshlink.KeyName(key)
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func without(list []string, s string) []string {
	var out []string
	for _, x := range list {
		if x != s {
			out = append(out, x)
		}
	}
	return out
}
