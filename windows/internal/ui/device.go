package ui

import (
	"errors"
	"path"
	"strings"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
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
}

// startEditing 打开一台设备的页面：输入框里放上现在的值。
func (a *App) startEditing() {
	x, _ := a.hub.Host(a.device)
	a.edit = editing{name: x.CustomName, cwd: x.DefaultCwd}
	a.async(func() func() {
		keys := a.hub.Keys()
		return func() { a.add.keys = keys }
	})
}

// devicePage 是一台设备：名称、地址、SSH 密钥、指纹、家目录、默认工作目录，检查连接，删除。
func (a *App) devicePage(c *ui.Context) {
	x, ok := a.hub.Host(a.device)
	if !ok {
		a.open(pageDevices)
		return
	}
	coui.Header(c, x.Name(), func() { a.open(pageDevices) })
	coui.Body(c, "device", func() {
		a.connectionSection(c, x)
		coui.Group(c, "", a.edit.check, func(l *coui.List) {
			title := "检查连接"
			if a.edit.checking {
				title = "正在连接…"
			}
			row := coui.Row{Title: title}
			if !a.edit.checking && !a.edit.changing {
				row.OnClick = func() { a.checkHost(x) }
			}
			l.Row(row)
		})
		coui.Group(c, "", "", func(l *coui.List) {
			l.Row(coui.Row{Title: "删除设备", Danger: true, OnClick: func() { a.edit.removing = true }})
		})
	})
	a.deviceDialogs(c, x)
}

// connectionSection 是名称、地址、私钥、指纹、家目录、默认工作目录。
func (a *App) connectionSection(c *ui.Context, x sshlink.Host) {
	k := coui.Of(c)
	nameProblem := sshlink.NameProblem(a.edit.name, a.hub.OtherNames(x.ID))
	coui.Group(c, "连接", nameProblem, func(l *coui.List) {
		l.Row(coui.Row{Title: "名称", End: func() {
			coui.Input(c.Key("name"), &a.edit.name).Placeholder(original(x)).Label("名称").Width(220)
			changed := strings.TrimSpace(a.edit.name) != x.CustomName
			coui.SmallButton(c, "保存").Disabled(!changed || nameProblem != "").OnClick(func() {
				x.CustomName = strings.TrimSpace(a.edit.name)
				a.save(x)
			})
		}})
		l.Row(coui.Row{Title: "地址", Value: sshlink.DisplayTarget(x.Target)})
		l.Row(coui.Row{Title: "SSH 密钥", End: func() {
			if a.edit.changing {
				ui.Text(c, "正在连接…").FontSize(coui.SizeBodyS).TextColor(k.Secondary)
			}
			keyChoice(c, a.add.keys, x.Key, a.edit.changing || a.edit.checking, func(key string) { a.changeKey(x, key) })
		}})
		if fp := x.Fingerprint(); fp != "" {
			l.Row(coui.Row{Title: "主机指纹", Summary: fp})
		}
		l.Row(coui.Row{Title: "家目录", Value: x.Home})
		cwd, cwdOK := normalizeCwd(a.edit.cwd, x.Home)
		l.Row(coui.Row{Title: "默认工作目录", End: func() {
			coui.Input(c.Key("cwd"), &a.edit.cwd).Placeholder(x.Home).Label("默认工作目录").Width(220)
			coui.SmallButton(c.Key("save-cwd"), "保存").Disabled(!cwdOK || cwd == x.DefaultCwd).OnClick(func() {
				x.DefaultCwd = cwd
				a.edit.cwd = cwd
				a.save(x)
			})
		}})
	})
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

// deviceDialogs 是认不认新指纹、删不删两个确认框。
func (a *App) deviceDialogs(c *ui.Context, x sshlink.Host) {
	message := "记下的：" + x.Fingerprint() + "\n现在的：" + sshlink.Fingerprint(a.edit.changedKey) +
		"\n确认是那台设备换过密钥（比如重装过系统）再改认新的；不确定的话可能连到了别的机器上。"
	if coui.Alert(c, &a.edit.trusting, "主机指纹变了", message, coui.Action{Label: "取消"},
		coui.Action{Label: "改认新的", Style: coui.Recommend}) == 1 {
		x.HostKey = a.edit.changedKey
		a.save(x)
		a.edit.check = "已改认新的主机指纹，再检查一次连接"
	}
	if !a.edit.trusting {
		a.edit.changedKey = ""
	}
	if coui.Alert(c, &a.edit.removing, "删除「"+x.Name()+"」？", "只是 Zeta 不再连它。那台设备上的会话记录、Claude Code 的配置都不动。",
		coui.Action{Label: "取消"}, coui.Action{Label: "删除", Style: coui.Destructive}) == 1 {
		if err := a.hub.RemoveHost(x.ID); err != nil {
			a.say("没存上", err.Error())
		}
		a.open(pageDevices)
	}
}
