package ui

import (
	"errors"
	"path"
	"strings"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// editing 是一台设备的页面上的状态。
type editing struct {
	name       string // 名称输入框
	cwd        string // 默认工作目录输入框
	checking   bool   // 正在检查连接
	check      string // 上次检查的结果
	changing   bool   // 正在试着改用另一把私钥
	changedKey string // 检查时发现对方现在的主机公钥（和记下的不一样），等用户决定认不认
	trusting   bool   // 正问着认不认新的指纹
	removing   bool   // 正问着删不删
	cutting    bool   // 正问着断不断开（那台上有对话在进行）
	naming     bool   // 正在改名（对话框开着）
	moving     bool   // 正在改默认工作目录（对话框开着）
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
		a.cfg.SubPage(c)
		return
	}
	widget.Header(c, x.Name(), nil)
	widget.Body(c, "device", func() {
		a.connectionSection(c, x)
		widget.Group(c, "", a.edit.check, func(l *widget.List) {
			title := "检查连接"
			if a.edit.checking {
				title = "正在连接…"
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
			l.Row(widget.Row{Title: "删除设备", Danger: true, OnClick: func() { a.edit.removing = true }})
		})
	})
	a.deviceDialogs(c, x)
}

// connectionSection 是「连接」开关、名称、地址、私钥、指纹、家目录、默认工作目录。
func (a *App) connectionSection(c *ui.Context, x sshlink.Host) {
	k := widget.Of(c)
	widget.Group(c, "连接", "", func(l *widget.List) {
		on := !x.Off
		summary := "用到这台设备时连上，断了自动重连"
		if x.Off {
			summary = "已断开：" + offNote
		}
		l.Row(widget.Row{Title: "连接", Summary: summary, End: func() {
			if widget.Switch(c.Key("connect"), &on).Label("连接").Changed() {
				a.connect(x, on)
			}
		}})
		l.Row(widget.Row{Title: "自定义设备名", Value: x.Name(), Arrow: true, OnClick: func() {
			a.edit.name, a.edit.naming = x.CustomName, true
		}})
		l.Row(widget.Row{Title: "地址", Value: sshlink.DisplayTarget(x.Target)})
		l.Row(widget.Row{Title: "SSH 密钥", End: func() {
			if a.edit.changing {
				ui.Text(c, "正在连接…").FontSize(widget.SizeBodyS).TextColor(k.Secondary)
			}
			keyChoice(c, a.add.keys, x.Key, a.edit.changing || a.edit.checking, func(key string) { a.changeKey(x, key) })
		}})
		if fp := x.Fingerprint(); fp != "" {
			l.Row(widget.Row{Title: "主机指纹", Summary: fp})
		}
		l.Row(widget.Row{Title: "家目录", Value: x.Home})
		l.Row(widget.Row{Title: "默认工作目录", Summary: x.DefaultCwd, Arrow: true, OnClick: func() {
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
		a.say("没存上", err.Error())
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
		a.say("没存上", err.Error())
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
				a.say("无法改为 "+name, sshlink.Describe(err))
				return
			}
			current, ok := a.hub.Host(x.ID)
			if !ok {
				return
			}
			current.Key = key
			a.save(refreshed(current, f))
			a.say("SSH 密钥已改为 "+name, f.Summary())
		}
	})
}

// checkHost 照记下的指纹和私钥连一次，结果写在按钮下面。指纹变了的问用户认不认；家目录、主机名变了的跟着改。
func (a *App) checkHost(x sshlink.Host) {
	a.edit.checking = true
	a.edit.check = ""
	a.async(func() func() {
		f, err := a.hub.Probe(x.Target, x.HostKey, x.Key)
		return func() {
			a.edit.checking = false
			var changed *sshlink.HostKeyChangedError
			switch {
			case errors.As(err, &changed):
				a.edit.check = "没连上：主机指纹和记下的不一样"
				a.edit.changedKey = changed.Seen
				a.edit.trusting = true
			case err != nil:
				a.edit.check = "没连上：" + sshlink.Describe(err)
			default:
				a.edit.check = "连上了：" + f.Summary()
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
		summary = "留空就用设备自己的名字「" + original(x) + "」"
	}
	if widget.InputDialog(c, &a.edit.naming, "自定义设备名", summary, &a.edit.name, original(x), "确定", nameOK) {
		x.CustomName = strings.TrimSpace(a.edit.name)
		a.save(x)
	}
	a.cwdDialogs(c, x)
	message := "记下的：" + x.Fingerprint() + "\n现在的：" + sshlink.Fingerprint(a.edit.changedKey) +
		"\n确认是那台设备换过密钥（比如重装过系统）再改认新的；不确定的话可能连到了别的机器上。"
	if widget.Alert(c, &a.edit.trusting, "主机指纹变了", message, widget.Action{Label: "取消"},
		widget.Action{Label: "改认新的", Style: widget.Recommend}) == 1 {
		x.HostKey = a.edit.changedKey
		a.save(x)
		a.edit.check = "已改认新的主机指纹，再检查一次连接"
	}
	if !a.edit.trusting {
		a.edit.changedKey = ""
	}
	if widget.Alert(c, &a.edit.cutting, "断开「"+x.Name()+"」？",
		"这台设备上有对话在进行（在回答、等你确认或有后台任务）。断开后它们在那边接着跑完，打开连接后接着看；等你确认的要连上后才能答。",
		widget.Action{Label: "取消"}, widget.Action{Label: "断开", Style: widget.Recommend}) == 1 {
		a.setConnected(x.ID, false)
	}
	if widget.Alert(c, &a.edit.removing, "删除「"+x.Name()+"」？", "只是 Zeta 不再连它。那台设备上的会话记录、Claude Code 的配置都不动。",
		widget.Action{Label: "取消"}, widget.Action{Label: "删除", Style: widget.Destructive}) == 1 {
		if err := a.hub.RemoveHost(x.ID); err != nil {
			a.say("没存上", err.Error())
		}
		a.open(pageSettings)
	}
}

// cwdDialogs 是改默认工作目录的输入框（核对时说正在核对，进不去时说原因、留着让改），和核对不了时问仍然保存吗。
func (a *App) cwdDialogs(c *ui.Context, x sshlink.Host) {
	e := &a.edit
	cwd, cwdOK := normalizeCwd(e.cwd, x.Home)
	summary := "在这台设备上开新对话时的工作目录，~ 是 " + x.Home
	switch {
	case e.verifying:
		summary = "正在到「" + x.Name() + "」上看 " + e.cwdChecked + " 能不能进去…"
	case e.cwdProblem != "" && cwd == e.cwdChecked:
		summary = e.cwdProblem + "。换一个目录，或先在那边建好。"
	}
	if widget.InputDialog(c, &e.moving, "默认工作目录", summary, &e.cwd, x.Home, "确定", cwdOK && !e.verifying) {
		a.moveCwd(x, cwd)
	}
	message := "没连上「" + x.Name() + "」，不知道 " + e.pendingCwd + " 在那边能不能进去：" + e.uncheckedWhy +
		"。仍然保存的话，进不去时开新对话会再说。"
	if widget.Alert(c, &e.unchecked, "无法核对这个目录", message, widget.Action{Label: "取消"},
		widget.Action{Label: "仍然保存", Style: widget.Recommend}) == 1 {
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
