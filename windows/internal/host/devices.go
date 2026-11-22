package host

import (
	"context"
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

// SessionWatch 是会话列表订阅的回调，都在后台 goroutine 上调用：Update 是推来的列表，Activity 是核心报的各会话忙闲
// （conv.activity，每次整份），Problem 是连上（空）、断开或连不上（原因）。
type SessionWatch struct {
	Update   func(hostlink.SessionsUpdate)
	Activity func(map[string]hostlink.Activity)
	Problem  func(string)
}

// WatchSessions 订阅设备 h 的会话列表和各会话的忙闲（同一条连接，同 Kotlin 版的 SessionList）。
func (d *Devices) WatchSessions(h sshlink.Host, w SessionWatch) io.Closer {
	shell := hostlink.NewShell(d.Links, h, LinuxName)
	return hostlink.StartServe(hostlink.ServeOptions{
		Open:          func() (*sshlink.Process, error) { return hostlink.OpenServe(shell, d.Helper) },
		Subscriptions: []string{"sessions.watch", "conv.activity"},
		Message: func(kind string, line []byte) {
			switch kind {
			case "zeta_sessions":
				u, err := hostlink.ParseSessions(line, h.ID)
				if err != nil {
					log.Printf("zeta: 会话列表的推送解不开：%v", err)
					return
				}
				w.Update(u)
			case "conv.activity":
				a, err := hostlink.ParseActivity(line)
				if err != nil {
					log.Printf("zeta: 会话忙闲的推送解不开：%v", err)
					return
				}
				w.Activity(a)
			}
		},
		Problem: w.Problem,
	})
}

// Search 在设备 h 上的对话内容里找 query（zeta-helper search），边找边把命中交给 hit；返回是不是全部搜完了。阻塞，ctx 取消时停。
func (d *Devices) Search(ctx context.Context, h sshlink.Host, query string, hit func(hostlink.SearchHit)) (bool, error) {
	return hostlink.NewShell(d.Links, h, LinuxName).Search(ctx, d.Helper, query, hit)
}

// ConfigHosts 是这台电脑 ~/.ssh/config 里能选的主机。
func (d *Devices) ConfigHosts() ([]string, error) { return d.Config.ConfigHosts() }

// Keys 是这台电脑 ~/.ssh 里能选的私钥。
func (d *Devices) Keys() []string { return d.Config.Keys() }

// Retain 只留下 ids 这几台的连接。
func (d *Devices) Retain(ids []string) { d.Links.Retain(ids) }
