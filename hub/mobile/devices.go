package mobile

import (
	"path"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/hub/locales"
)

// 设置首页的设备一组、设备页（改名、默认工作目录）和「常规」（见 docs/ios.md「设置」）。连接开关和删除是 mobile.go 的
// connect、remove。

// DeviceInfo 是设置里的一台设备：设备页要的全部，和首页那一行右边写的（额度已用比例或在用的中转）。
type DeviceInfo struct {
	ID string `json:"id"`
	// Name 是界面上的名字；CustomName 是用户起的（没起是空），Original 是没起名字时的名字（改名框的占位）。
	Name       string `json:"name"`
	CustomName string `json:"custom_name"`
	Original   string `json:"original"`
	Target     string `json:"target"`
	Home       string `json:"home"`
	DefaultCwd string `json:"default_cwd"`
	Via        string `json:"via"`
	Hostname   string `json:"hostname"`
	// Fingerprint 是记下的主机指纹。
	Fingerprint    string `json:"fingerprint"`
	Off            bool   `json:"off"`
	DefaultHarness string `json:"default_harness"`
	// Relay 是在用的中转名（这时不写额度）；Used 是最要紧的额度窗口用了百分之几，没有数、不显示额度时是 -1。
	Relay string `json:"relay"`
	Used  int    `json:"used"`
}

func (c *Core) deviceInfo(x sshlink.Host, now time.Time) DeviceInfo {
	original := x
	original.CustomName = ""
	dh := c.hub.DefaultHarness(x.ID)
	d := DeviceInfo{ID: x.ID, Name: x.Name(), CustomName: x.CustomName, Original: original.Name(), Target: x.Target,
		Home: x.Home, DefaultCwd: x.DefaultCwd, Via: x.Via, Hostname: x.Hostname, Fingerprint: x.Fingerprint(), Off: x.Off,
		DefaultHarness: dh, Used: -1}
	r := c.hub.Remote(x.ID)
	if r == nil {
		return d
	}
	q := r.QuotaFor(dh)
	d.Relay = q.Relay()
	if s, ok := q.Snapshot(); ok && d.Relay == "" && q.Shown() {
		if s = s.At(now); !s.Unavailable {
			if w, ok := s.Headline(); ok {
				d.Used = w.Percent()
			}
		}
	}
	return d
}

type (
	deviceRenameParams struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	cwdParams struct {
		ID   string `json:"id"`
		Path string `json:"path"`
	}
	prefsParams struct {
		AutoLoadRemoteImages bool `json:"auto_load_remote_images"`
	}
)

// CwdCheck 是 device.checkcwd 的结果。Path 是换成绝对路径的目录；Problem 是进不去的原因（输入框留着让改）；
// Unchecked 是核对不了的原因（连不上），Off 是「连接」关着：这两种界面问仍然保存还是取消。都没有时进得去，已经存好。
type CwdCheck struct {
	Path      string `json:"path"`
	Problem   string `json:"problem"`
	Unchecked string `json:"unchecked"`
	Off       bool   `json:"off"`
}

// Prefs 是「常规」里存在设置文件里的几项。
type Prefs struct {
	AutoLoadRemoteImages bool `json:"auto_load_remote_images"`
}

func init() {
	// devices 是设置里的各台（只读）；settings.open 在打开设置时让各台还没读过的账号、额度读一遍（首页那一行右边要写）。
	reading("devices", func(c *Core, _ none) (any, error) {
		out := []DeviceInfo{}
		now := time.Now()
		for _, x := range c.hub.Hosts() {
			out = append(out, c.deviceInfo(x, now))
		}
		return out, nil
	})
	onLoop("settings.open", func(c *Core, _ none) (any, error) {
		for _, x := range c.hub.Hosts() {
			c.refreshDeviceDefault(x.ID)
		}
		return ok, nil
	})
	onLoop("device.setharness", func(c *Core, p deviceParams) (any, error) {
		if _, found := c.hub.Host(p.ID); !found {
			return nil, locales.Err(locales.MobileDeviceGone)
		}
		if err := c.hub.SetDefaultHarness(p.ID, p.Harness); err != nil {
			return nil, err
		}
		c.refreshDeviceDefault(p.ID)
		return ok, nil
	})
	onLoop("device.rename", func(c *Core, p deviceRenameParams) (any, error) {
		x, found := c.hub.Host(p.ID)
		if !found {
			return nil, locales.Err(locales.MobileDeviceGone)
		}
		if problem := sshlink.NameProblem(p.Name, c.hub.OtherNames(p.ID)); problem != "" {
			return nil, locales.Err(func() string { return problem })
		}
		x.CustomName = strings.TrimSpace(p.Name)
		return ok, c.hub.UpdateHost(x)
	})
	blocking("device.checkcwd", func(c *Core, p cwdParams) (any, error) { return c.checkCwd(p) })
	onLoop("device.setcwd", func(c *Core, p cwdParams) (any, error) {
		x, found := c.hub.Host(p.ID)
		if !found {
			return nil, locales.Err(locales.MobileDeviceGone)
		}
		cwd, valid := normalizeCwd(p.Path, x.Home)
		if !valid {
			return nil, locales.Err(locales.MobileCwdNotAbsolute)
		}
		x.DefaultCwd = cwd
		return ok, c.hub.UpdateHost(x)
	})
	reading("prefs", func(c *Core, _ none) (any, error) {
		return Prefs{AutoLoadRemoteImages: c.hub.Settings().AutoLoadRemoteImages()}, nil
	})
	onLoop("prefs.set", func(c *Core, p prefsParams) (any, error) {
		c.hub.Settings().SetAutoLoadRemoteImages(p.AutoLoadRemoteImages)
		return ok, nil
	})
}

func (c *Core) refreshDeviceDefault(id string) {
	r := c.hub.Remote(id)
	if r == nil {
		return
	}
	dh := c.hub.DefaultHarness(id)
	acct, q := r.AccountsFor(dh), r.QuotaFor(dh)
	if _, ok := acct.State(); !ok && acct.Working() == "" {
		acct.Refresh()
	}
	if _, ok := q.Snapshot(); !ok && !q.Refreshing() {
		q.Refresh(false)
	}
}

// checkCwd 把默认工作目录改成 p.Path（同桌面版）：先到那台设备上看进不进得去，进得去才存；进不去时答原因、不存；
// 连不上或「连接」关着时答核对不了，不存，由界面问仍然保存（device.setcwd）还是取消。没改时直接答进得去。
func (c *Core) checkCwd(p cwdParams) (CwdCheck, error) {
	var x sshlink.Host
	var found bool
	var check func(string) (string, error)
	c.Do(func() {
		x, found = c.hub.Host(p.ID)
		check = c.hub.DirChecker(p.ID)
	})
	if !found {
		return CwdCheck{}, locales.Err(locales.MobileDeviceGone)
	}
	cwd, valid := normalizeCwd(p.Path, x.Home)
	if !valid {
		return CwdCheck{}, locales.Err(locales.MobileCwdNotAbsolute)
	}
	out := CwdCheck{Path: cwd}
	if cwd == x.DefaultCwd {
		return out, nil
	}
	if x.Off {
		out.Off = true
		return out, nil
	}
	problem, err := check(cwd)
	switch {
	case err != nil:
		out.Unchecked = sshlink.Describe(err)
	case problem != "":
		out.Problem = problem
	default:
		var saveErr error
		c.Do(func() {
			if cur, ok := c.hub.Host(p.ID); ok {
				cur.DefaultCwd = cwd
				saveErr = c.hub.UpdateHost(cur)
			}
		})
		return out, saveErr
	}
	return out, nil
}

// normalizeCwd 把输入的目录换成绝对路径：~ 换成家目录 home；不是绝对路径的不行（同桌面版）。
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
