package host

import (
	"io"
	"log"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
)

// Devices 是中枢碰设备的几样事的真实现：探测、会话列表的订阅，和这台电脑的 SSH 配置。
type Devices struct {
	Links  *sshlink.Links
	Config *sshlink.OpenSSH
	Helper *hostlink.Program
}

// Probe 见 host.Probe。
func (d *Devices) Probe(target, expected, key string) (Found, error) {
	return Probe(d.Links, target, expected, key)
}

// WatchSessions 订阅设备 h 的会话列表：每推来一份交给 update，连上（空）、断开或连不上（原因）交给 problem，都在后台 goroutine 上调用。
func (d *Devices) WatchSessions(h sshlink.Host, update func(hostlink.SessionsUpdate), problem func(string)) io.Closer {
	shell := hostlink.NewShell(d.Links, h, LinuxName)
	return hostlink.StartServe(hostlink.ServeOptions{
		Open:          func() (*sshlink.Process, error) { return hostlink.OpenServe(shell, d.Helper) },
		Subscriptions: []string{"sessions.watch"},
		Message: func(kind string, line []byte) {
			if kind != "zeta_sessions" {
				return
			}
			u, err := hostlink.ParseSessions(line, h.ID)
			if err != nil {
				log.Printf("zeta: 会话列表的推送解不开：%v", err)
				return
			}
			update(u)
		},
		Problem: problem,
	})
}

// ConfigHosts 是这台电脑 ~/.ssh/config 里能选的主机。
func (d *Devices) ConfigHosts() ([]string, error) { return d.Config.ConfigHosts() }

// Keys 是这台电脑 ~/.ssh 里能选的私钥。
func (d *Devices) Keys() []string { return d.Config.Keys() }

// Retain 只留下 ids 这几台的连接。
func (d *Devices) Retain(ids []string) { d.Links.Retain(ids) }
