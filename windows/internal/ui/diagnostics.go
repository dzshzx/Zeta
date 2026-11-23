package ui

import (
	"runtime"
	"strconv"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/diag"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// 设置里的「诊断」：Zeta 自己的运行日志和环境，各台设备上 Claude Code 那边的日志，导出诊断包（ui/diag）。

// startDiag 打开诊断页：建好、读一遍各台的日志清单。每次打开都重建，看的是最新的。
func (a *App) startDiag() {
	a.diag = diag.New(diag.Options{
		Devices: a.diagDevices,
		Run:     a.run,
		Post:    a.post,
		Save: func(name string, data []byte) (string, error) {
			return a.saveFile(locales.DiagExport(), name, []mygo.FileFilter{{Name: "ZIP", Extensions: []string{"zip"}}}, data)
		},
		Env: a.diagEnv,
		Dir: a.DiagDir,
		Log: a.RunLog,
		Now: a.now,
	})
	a.diag.Load()
}

// diagDevices 是诊断页列的设备：设置里的各台，「连接」关着的 Core 为 nil。
func (a *App) diagDevices() []diag.Device {
	var out []diag.Device
	for _, x := range a.hub.Hosts() {
		d := diag.Device{ID: x.ID, Name: x.Name()}
		if r := a.hub.Remote(x.ID); r != nil {
			d.Core = r.Core
		}
		out = append(out, d)
	}
	return out
}

// diagEnv 是诊断包里这台电脑上 Zeta 的环境：版本、系统（入口给的 Windows 版本）、界面缩放、各台设备怎么连（不含密钥，只有私钥的文件名）。
func (a *App) diagEnv() []diag.Line {
	v := a.About.Version
	lines := []diag.Line{
		{Name: locales.DiagEnvVersion(), Value: locales.DiagEnvVersionValue(v.Name, strconv.Itoa(v.Code))},
		{Name: locales.DiagEnvSystem(), Value: runtime.GOOS + "/" + runtime.GOARCH},
	}
	if a.DiagEnv != nil {
		lines = append(lines, a.DiagEnv()...)
	}
	lines = append(lines, diag.Line{Name: locales.DiagEnvZoom(), Value: strconv.Itoa(a.zoom) + "%"})
	for _, x := range a.hub.Hosts() {
		lines = append(lines, diag.Line{Name: locales.DiagEnvDevice(x.Name()), Value: hostLine(x)})
	}
	return lines
}

// hostLine 是一台设备在诊断包里的一行：地址、连法、私钥、连接开关、主机名和指纹。
func hostLine(x sshlink.Host) string {
	key := x.Key
	if key == "" {
		key = locales.DiagEnvKeyFromConfig()
	}
	conn := locales.DiagEnvOn()
	if x.Off {
		conn = locales.DiagEnvOff()
	}
	return locales.DiagEnvHost(x.Target, x.Via, key, conn, x.Hostname, x.Fingerprint(), x.DefaultCwd)
}

// diagPage 是诊断页。
func (a *App) diagPage(c *ui.Context) {
	if a.diag == nil {
		a.startDiag()
	}
	a.diag.View(c)
}
