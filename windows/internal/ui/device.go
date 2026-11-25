package ui

import (
	"errors"
	"path"
	"strings"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// editing 是一台设备的页面上的状态。
type editing struct {
	loaded     bool          // 已加载设备首页；直达子页时不读首页数据
	remote     *hub.Remote   // 配置页绑定的设备连接
	models     *modelSource  // 配置页显示的模型来源
	probed     *modelSource  // 本次打开首页已经向 Claude Code 要过清单的来源
	name       string        // 名称输入框
	cwd        string        // 默认工作目录输入框
	checking   bool          // 正在检查连接
	check      func() string // 上次检查的结果（显示时出字），没有时为 nil
	changing   bool          // 正在试着改用另一把私钥
	changedKey string        // 检查时发现对方现在的主机公钥（和记下的不一样），等用户决定认不认
	trusting   bool          // 正问着认不认新的指纹
	removing   bool          // 正问着删不删
	cutting    bool          // 正问着断不断开（那台上有对话在进行）
	naming     bool          // 正在改名（对话框开着）
	moving     bool          // 正在改默认工作目录（对话框开着）
	// 改默认工作目录时先到设备上核对（moveCwd）：verifying 是正在核对，cwdProblem 是 cwdChecked 那个目录进不去的原因；
	// 核对不了（连不上）时 unchecked 问用户仍然保存 pendingCwd 吗，uncheckedWhy 是连不上的原因。cwdRound 每开一次对话框、
	// 每核对一次加一，对不上的结果是过时的，不用。
	verifying    bool
	cwdChecked   string
	cwdProblem   string
	unchecked    bool
	pendingCwd   string
	uncheckedWhy string
	cwdRound     int
}

// startEditing 打开一台设备的页面：输入框里放上现在的值。
func (a *App) startEditing() {
	x, _ := a.hub.Host(a.device)
	a.edit = editing{name: x.CustomName, cwd: x.DefaultCwd}
	a.startDeviceConfig(x)
}

func (a *App) loadDeviceHome(x sshlink.Host) {
	if a.edit.loaded {
		return
	}
	a.edit.loaded = true
	if a.cfg != nil {
		a.cfg.Load()
	}
	a.async(func() func() {
		keys := a.hub.Keys()
		return func() { a.add.keys = keys }
	})
}

// devicePage 是一台设备：连接（名称、地址、SSH 密钥、指纹、家目录、默认工作目录）、检查连接，那台上的 Claude Code
// （新对话默认、自动压缩与标题、Claude Code 配置的入口，点进去换成那一页），删除。
func (a *App) devicePage(c *ui.Context) {
	x, ok := a.hub.Host(a.device)
	if !ok {
		a.open(pageSettings)
		return
	}
	if a.cfg != nil && a.cfg.Open() {
		if a.edit.loaded {
			a.syncDeviceModels(x)
		}
		a.cfg.SubPage(c)
		return
	}
	a.loadDeviceHome(x)
	a.syncDeviceModels(x)
	widget.Header(c, x.Name(), nil)
	widget.Body(c, "device", func() {
		a.connectionSection(c, x)
		widget.Group(c, "", locales.Now(a.edit.check), func(l *widget.List) {
			title := locales.DeviceCheck()
			if a.edit.checking {
				title = locales.CommonConnecting()
			}
			row := widget.Row{Title: title}
			if !a.edit.checking && !a.edit.changing {
				row.OnClick = func() { a.checkHost(x) }
			}
			l.Row(row)
		})
		if a.cfg != nil {
			a.cfg.Sections(c)
		}
		widget.Group(c, "", "", func(l *widget.List) {
			l.Row(widget.Row{Title: locales.DeviceRemove(), Danger: true, OnClick: func() { a.edit.removing = true }})
		})
	})
	a.deviceDialogs(c, x)
}

// connectionSection 是「连接」开关、名称、地址、私钥、指纹、家目录、默认工作目录。
func (a *App) connectionSection(c *ui.Context, x sshlink.Host) {
	k := widget.Of(c)
	widget.Group(c, locales.DeviceConnection(), "", func(l *widget.List) {
		on := !x.Off
		summary := locales.DeviceConnectSummary()
		if x.Off {
			summary = locales.DeviceOffSummary()
		}
		l.Row(widget.Row{Title: locales.DeviceConnect(), Summary: summary, End: func() {
			if widget.Switch(c.Key("connect"), &on).Label(locales.DeviceConnect()).Changed() {
				a.connect(x, on)
			}
		}})
		l.Row(widget.Row{Title: locales.DeviceCustomName(), Value: x.Name(), Arrow: true, OnClick: func() {
			a.edit.name, a.edit.naming = x.CustomName, true
		}})
		l.Row(widget.Row{Title: locales.DevicesAddress(), Value: sshlink.DisplayTarget(x.Target)})
		l.Row(widget.Row{Title: locales.DevicesSshKey(), End: func() {
			if a.edit.changing {
				ui.Text(c, locales.CommonConnecting()).FontSize(widget.SizeBodyS).TextColor(k.Secondary)
			}
			keyChoice(c, a.add.keys, x.Key, a.edit.changing || a.edit.checking, func(key string) { a.changeKey(x, key) })
		}})
		if fp := x.Fingerprint(); fp != "" {
			l.Row(widget.Row{Title: locales.DeviceFingerprint(), Summary: fp})
		}
		l.Row(widget.Row{Title: locales.DeviceHome(), Value: x.Home})
		l.Row(widget.Row{Title: locales.DeviceDefaultCwd(), Summary: x.DefaultCwd, Arrow: true, OnClick: func() {
			a.edit.cwd, a.edit.moving = x.DefaultCwd, true
			a.edit.verifying, a.edit.cwdProblem = false, ""
			a.edit.cwdRound++
		}})
	})
}

// connect 拨了「连接」开关：那台上有对话在进行时先问再断开。
func (a *App) connect(x sshlink.Host, on bool) {
	if !on && a.hub.Working(x.ID) {
		a.edit.cutting = true
		return
	}
	a.setConnected(x.ID, on)
}

// setConnected 照做，设备页上 Claude Code 那几组跟着换（断开时没有，连上时重新读）。
func (a *App) setConnected(id string, on bool) {
	if err := a.hub.SetConnected(id, on); err != nil {
		a.say(locales.CommonSaveFailed(), err.Error())
	}
	if x, ok := a.hub.Host(id); ok && a.page == pageDevice && a.device == id {
		a.startDeviceConfig(x)
	}
}

// original 是设备自己的名字（没起名字时显示的）。
func original(x sshlink.Host) string {
	x.CustomName = ""
	return x.Name()
}

// normalizeCwd 把输入的目录换成绝对路径：~ 换成家目录 home；不是绝对路径的不行。
func normalizeCwd(text, home string) (string, bool) {
	p := strings.TrimSpace(text)
	switch {
	case p == "~":
		p = home
	case strings.HasPrefix(p, "~/"):
		p = home + p[1:]
	case !strings.HasPrefix(p, "/"):
		return "", false
	}
	return path.Clean(p), true
}

// save 存改过的设备，存不上时说一声。
func (a *App) save(x sshlink.Host) {
	if err := a.hub.UpdateHost(x); err != nil {
		a.say(locales.CommonSaveFailed(), err.Error())
	}
}

// changeKey 改用私钥 key：先用它连一次，连上了才存。
func (a *App) changeKey(x sshlink.Host, key string) {
	a.edit.changing = true
	name := keyLabel(key)
	a.async(func() func() {
		f, err := a.hub.Probe(x.Target, x.HostKey, key)
		return func() {
			a.edit.changing = false
			if err != nil {
				a.say(locales.DeviceKeyChangeFailed(name), sshlink.Describe(err))
				return
			}
			current, ok := a.hub.Host(x.ID)
			if !ok {
				return
			}
			current.Key = key
			a.save(refreshed(current, f))
			a.say(locales.DeviceKeyChanged(name), f.Summary())
		}
	})
}

// checkHost 照记下的指纹和私钥连一次，结果写在按钮下面。指纹变了的问用户认不认；家目录、主机名变了的跟着改。
func (a *App) checkHost(x sshlink.Host) {
	a.edit.checking = true
	a.edit.check = nil
	a.async(func() func() {
		f, err := a.hub.Probe(x.Target, x.HostKey, x.Key)
		return func() {
			a.edit.checking = false
			var changed *sshlink.HostKeyChangedError
			switch {
			case errors.As(err, &changed):
				a.edit.check = locales.DeviceCheckMismatch
				a.edit.changedKey = changed.Seen
				a.edit.trusting = true
			case err != nil:
				a.edit.check = func() string { return locales.DeviceCheckFailed(sshlink.Describe(err)) }
			default:
				a.edit.check = func() string { return locales.DeviceCheckOk(f.Summary()) }
				if current, ok := a.hub.Host(x.ID); ok && (f.Home != current.Home || f.Hostname != current.Hostname) {
					a.save(refreshed(current, f))
				}
			}
		}
	})
}

// refreshed：连上时家目录或主机名变了就跟着改，默认工作目录原来是家目录的一起换。
func refreshed(x sshlink.Host, f host.Found) sshlink.Host {
	if x.DefaultCwd == x.Home {
		x.DefaultCwd = f.Home
	}
	x.Home = f.Home
	if f.Hostname != "" {
		x.Hostname = f.Hostname
	}
	return x
}

// deviceDialogs 是改名、改默认工作目录两个输入框，和认不认新指纹、删不删两个确认框。
func (a *App) deviceDialogs(c *ui.Context, x sshlink.Host) {
	summary := sshlink.NameProblem(a.edit.name, a.hub.OtherNames(x.ID))
	nameOK := summary == ""
	if nameOK {
		summary = locales.DeviceNameHint(original(x))
	}
	if widget.InputDialog(c, &a.edit.naming, locales.DeviceCustomName(), summary, &a.edit.name, original(x), locales.CommonOk(), nameOK) {
		x.CustomName = strings.TrimSpace(a.edit.name)
		a.save(x)
	}
	a.cwdDialogs(c, x)
	message := locales.DeviceFingerprintMessage(x.Fingerprint(), sshlink.Fingerprint(a.edit.changedKey))
	if widget.Alert(c, &a.edit.trusting, locales.DeviceFingerprintTitle(), message, widget.Action{Label: locales.CommonCancel()},
		widget.Action{Label: locales.DeviceTrustNew(), Style: widget.Recommend}) == 1 {
		x.HostKey = a.edit.changedKey
		a.save(x)
		a.edit.check = locales.DeviceTrusted
	}
	if !a.edit.trusting {
		a.edit.changedKey = ""
	}
	if widget.Alert(c, &a.edit.cutting, locales.DeviceDisconnectTitle(x.Name()),
		locales.DeviceDisconnectMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.DeviceDisconnect(), Style: widget.Recommend}) == 1 {
		a.setConnected(x.ID, false)
	}
	if widget.Alert(c, &a.edit.removing, locales.DeviceRemoveTitle(x.Name()), locales.DeviceRemoveMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.DeviceRemoveConfirm(), Style: widget.Destructive}) == 1 {
		if err := a.hub.RemoveHost(x.ID); err != nil {
			a.say(locales.CommonSaveFailed(), err.Error())
		}
		a.open(pageSettings)
	}
}

// cwdDialogs 是改默认工作目录的输入框（核对时说正在核对，进不去时说原因、留着让改），和核对不了时问仍然保存吗。
func (a *App) cwdDialogs(c *ui.Context, x sshlink.Host) {
	e := &a.edit
	cwd, cwdOK := normalizeCwd(e.cwd, x.Home)
	summary := locales.DeviceCwdSummary(x.Home)
	switch {
	case e.verifying:
		summary = locales.DeviceCwdVerifying(x.Name(), e.cwdChecked)
	case e.cwdProblem != "" && cwd == e.cwdChecked:
		summary = locales.DeviceCwdProblem(e.cwdProblem)
	}
	if widget.InputDialog(c, &e.moving, locales.DeviceDefaultCwd(), summary, &e.cwd, x.Home, locales.CommonOk(), cwdOK && !e.verifying) {
		a.moveCwd(x, cwd)
	}
	message := locales.DeviceCwdUncheckedMessage(x.Name(), e.pendingCwd, e.uncheckedWhy)
	if widget.Alert(c, &e.unchecked, locales.DeviceCwdUncheckedTitle(), message, widget.Action{Label: locales.CommonCancel()},
		widget.Action{Label: locales.CommonSaveAnyway(), Style: widget.Recommend}) == 1 {
		a.setCwd(x.ID, e.pendingCwd)
	}
}

// moveCwd 把默认工作目录改成 cwd（同手机版）：先到那台设备上看进不进得去，进得去才存；进不去时输入框留着，写明原因让改；
// 连不上、「连接」关着时问用户仍然保存还是取消。没改时直接关上。
func (a *App) moveCwd(x sshlink.Host, cwd string) {
	e := &a.edit
	if cwd == x.DefaultCwd {
		return
	}
	e.moving, e.verifying, e.cwdChecked, e.cwdProblem = true, true, cwd, ""
	e.cwdRound++
	round := e.cwdRound
	check := a.hub.DirChecker(x.ID)
	a.async(func() func() {
		problem, err := check(cwd)
		return func() {
			if round != e.cwdRound || !e.moving {
				return // 对话框关了或又开了一次：这次的结果不用
			}
			e.verifying = false
			switch {
			case err != nil:
				e.moving = false
				e.pendingCwd, e.uncheckedWhy, e.unchecked = cwd, sshlink.Describe(err), true
			case problem != "":
				e.cwdProblem = problem
			default:
				e.moving = false
				a.setCwd(x.ID, cwd)
			}
		}
	})
}

// setCwd 存设备 id 的默认工作目录（按此刻的设置改这一项）。
func (a *App) setCwd(id, cwd string) {
	if x, ok := a.hub.Host(id); ok {
		x.DefaultCwd = cwd
		a.save(x)
	}
}
