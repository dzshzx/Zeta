package ui

import (
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/egoist/mygo/ui"
)

// desktopFooter 是添加设备下面的说明（同 Compose 版的 DesktopFooter）。
const desktopFooter = "使用本机的 SSH 配置和密钥连接。设备需运行 Linux（arm64 或 x86-64）并已安装 Claude Code；对话在设备上运行，关闭 Zeta 不会中断。"

// autoKey 是「SSH 密钥」里照 ~/.ssh/config 的那一项。
const autoKey = "自动选择"

// adding 是设备页上添加设备的状态。
type adding struct {
	keys       []string // 这台电脑 ~/.ssh 里能选的私钥
	key        string   // 接下来加的设备用哪把，空是自动选择
	config     []string // SSH 配置里还没加过的主机
	configErr  string   // 读不到 SSH 配置的原因
	configRead bool     // 读完了（读成没读成都算）
	draft      string   // 手动添加的地址
	probing    string   // 正在连的地址；同一时间只连一个
}

// startAdding 打开设备页时读一遍这台电脑的 SSH 配置和私钥。
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

// devicesPage 是设备页：加好的各台（点进去改），和添加设备。
func (a *App) devicesPage(c *ui.Context) {
	pageTitle(c, "设备", nil)
	ui.Scroll(c.Key("devices")).Grow(1).Children(func() {
		ui.Column(c).Padding(0, 24, 24, 24).Gap(20).MaxWidth(640).Children(func() {
			if hosts := a.hub.Hosts(); len(hosts) > 0 {
				section(c, "已添加", "", func() {
					for _, x := range hosts {
						a.hostRow(c, x)
					}
				})
			}
			a.addSection(c)
		})
	})
}

// hostRow 是一台加好的设备，点开改它。
func (a *App) hostRow(c *ui.Context, x sshlink.Host) {
	t := c.Theme()
	row := ui.Row(c.Key(x.ID)).MinHeight(48).Padding(6, 12).Gap(12).Radius(8).AlignItems(ui.Center).Focusable().Label(x.Name())
	if row.Hovered() {
		row.Background(t.SurfaceHover)
	}
	row.OnClick(func() {
		a.device = x.ID
		a.open(pageDevice)
	})
	row.Children(func() {
		ui.Column(c).Grow(1).MinWidth(0).Children(func() {
			ui.Text(c, x.Name()).SingleLine()
			ui.Text(c, sshlink.DisplayTarget(x.Target)).FontSize(12).TextColor(t.TextMuted).SingleLine()
		})
		ui.Text(c, "›").TextColor(t.TextMuted)
	})
}

// addSection 是添加设备：私钥、从 SSH 配置导入、手动添加。
func (a *App) addSection(c *ui.Context) {
	t := c.Theme()
	busy := a.add.probing != ""
	section(c, "添加设备", desktopFooter, func() {
		item(c, "SSH 密钥", func() {
			keyChoice(c, a.add.keys, a.add.key, busy, func(key string) { a.add.key = key })
		})
	})
	section(c, "从 SSH 配置导入", "已添加的设备不再列出。", func() {
		switch {
		case !a.add.configRead:
			paneMessage(c, "正在读取 SSH 配置…")
		case a.add.configErr != "":
			paneMessage(c, "无法读取 SSH 配置："+a.add.configErr)
		case len(a.add.config) == 0:
			paneMessage(c, "SSH 配置中没有可添加的设备")
		}
		for _, alias := range a.add.config {
			item(c, alias, func() {
				if a.add.probing == alias {
					ui.Text(c, "正在连接…").TextColor(t.TextMuted)
					return
				}
				ui.Button(c.Key("add-"+alias), "添加").Disabled(busy).OnClick(func() { a.addHost(alias) })
			})
		}
	})
	target, valid := sshlink.TargetFor(a.add.draft)
	submit := func() {
		if t, ok := sshlink.TargetFor(a.add.draft); ok {
			a.addHost(t)
		}
	}
	section(c, "手动添加", sshlink.ConfigHint, func() {
		ui.Row(c).Padding(6, 8).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.TextInput(c.Key("draft"), &a.add.draft).Placeholder("user@host 或主机名").Label("地址").Grow(1).OnSubmit(submit)
			label := "添加"
			if busy && a.add.probing == target {
				label = "正在连接…"
			}
			ui.PrimaryButton(c, label).Disabled(!valid || busy).OnClick(submit)
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
				a.say("没有添加「"+shown+"」", sshlink.Describe(err))
				return
			}
			x, err := a.hub.AddHost(target, key, f)
			if err != nil {
				a.say("「"+x.Name()+"」没存上", err.Error())
				return
			}
			a.add.draft = ""
			a.add.config = without(a.add.config, target)
			a.say("已添加「"+x.Name()+"」", addedSummary(f, key))
		}
	})
}

// addedSummary 是加上以后给人看的：Claude Code 版本、系统、私钥、主机指纹。
func addedSummary(f host.Found, key string) string {
	text := f.Summary()
	if key != "" {
		text += " · SSH 密钥 " + sshlink.KeyName(key)
	}
	if fp := sshlink.Fingerprint(f.HostKey); fp != "" {
		text += "\n主机指纹 " + fp
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
	labels := make([]string, len(options))
	for i, k := range options {
		labels[i] = keyLabel(k)
	}
	selected := keyLabel(chosen)
	ui.Select(c.Key("key"), &selected, labels).Label("SSH 密钥").Disabled(disabled).OnChange(func() {
		for i, l := range labels {
			if l == selected && options[i] != chosen {
				onChange(options[i])
			}
		}
	})
}

// keyLabel 是私钥 key 给人看的名字；空是自动选择。
func keyLabel(key string) string {
	if key == "" {
		return autoKey
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
