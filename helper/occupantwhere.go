package main

import (
	"cmp"
	"context"
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/conv"
	"github.com/dzshzx/Zeta/helper/core"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 占着会话的进程是什么应用、用户在哪台设备上用它（sessionproto.Occupancy 的 app、device），「别处运行」卡片的标题照它说「在哪」。

// hostClientFile 是托管目录里记着「最近一次是哪台设备的客户端在这个对话里发消息、起进程」的文件（conv.Device 的 JSON），
// 托管它的那个包的核心写（helper/conv 的 keepDevice），另一个包的核心查别处占用时读。
const hostClientFile = "client"

// appOf 是登记的进程 r 是什么应用：Zeta 托管的是托管它的包名（zeta、zeta-dev），终端界面（entrypoint cli）是 terminal，
// 别的是登记的 entrypoint 原样，登记里没有时为空。
func appOf(r registered) string {
	if r.host != "" {
		return filepath.Base(filepath.Dir(filepath.Dir(r.host)))
	}
	if r.entrypoint == "cli" {
		return sessionproto.AppTerminal
	}
	return r.entrypoint
}

// deviceOf 是用户在哪台设备上用进程 r：
//   - Zeta 托管的：托管目录里的 client（托管它的核心按那个客户端的 core.client 定的，placeOf），没有时不知道。
//     不看进程环境里的 SSH_CONNECTION：核心是第一个连它的客户端经 SSH 拉起的，之后它起的进程都带着那一条，换了客户端也不变。
//   - 别的：进程环境里的 SSH_CONNECTION 照 placeOf 定。tmux 里起的进程带的是开 tmux 那次连进来的 SSH_CONNECTION，
//     后来从别的机器 attach 上来时这里说的还是开 tmux 的那台：不准，但没有更好的来源，接受。
func (e *env) deviceOf(r registered) conv.Device {
	if r.host != "" {
		var d conv.Device
		if data, err := os.ReadFile(joinPath(r.host, hostClientFile)); err == nil {
			_ = json.Unmarshal(data, &d)
		}
		return d
	}
	conn, _ := procEnv(r.pid, "SSH_CONNECTION")
	return placeOf(conn, "")
}

// placeOf 按连进来的 SSH 连接（SSH_CONNECTION 的值，不经 SSH 时为空）和客户端自己报的设备名定是哪台设备，终端和 Zeta 的客户端一个规矩：
// 没有 SSH、来源是回环地址（Windows 上的桌面版连本机的 WSL 就是）的是本机；来源地址在 tailscale 里对得上的用那里的机器名，
// 同一台机器在终端和 Zeta 里叫法一样；对不上时用报的设备名，没报（终端）用地址。
func placeOf(sshConnection, reported string) conv.Device {
	ip, _, _ := strings.Cut(strings.TrimSpace(sshConnection), " ")
	if ip == "" {
		return conv.Device{Local: true}
	}
	if addr := net.ParseIP(ip); addr != nil && addr.IsLoopback() {
		return conv.Device{Local: true}
	}
	return conv.Device{Name: cmp.Or(tailscaleName(ip), reported, ip)}
}

// tailscaleStatus 跑 tailscale status --json（最多 tailscaleTimeout）；测试里换掉。主机没装 tailscale 时出错。
var tailscaleStatus = func(ctx context.Context) ([]byte, error) {
	return exec.CommandContext(ctx, "tailscale", "status", "--json").Output()
}

const (
	tailscaleTimeout = 2 * time.Second
	// tailscaleFresh：查到的对照表（或查不成）记这么久。别处运行时核心每 3 秒查一次占用，不每次都跑 tailscale
	tailscaleFresh = time.Minute
)

var tailscaleCache struct {
	mu    sync.Mutex
	at    time.Time
	names map[string]string // tailnet 地址 → 机器名
}

// tailscaleName 是 tailnet 里地址 ip 的机器名（tailscale status 里 Self、Peer 的 HostName）；查不成、对不上时为空，不报错。
func tailscaleName(ip string) string {
	c := &tailscaleCache
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.names == nil || time.Since(c.at) >= tailscaleFresh {
		c.names, c.at = readTailscaleNames(), time.Now()
	}
	return c.names[ip]
}

// readTailscaleNames 跑一次 tailscale status --json，读成地址 → 机器名；查不成时是空表（也记着，免得每次都等它失败）。
func readTailscaleNames() map[string]string {
	names := map[string]string{}
	ctx, cancel := context.WithTimeout(context.Background(), tailscaleTimeout)
	defer cancel()
	out, err := tailscaleStatus(ctx)
	if err != nil {
		return names
	}
	type node struct {
		HostName     string
		TailscaleIPs []string
	}
	var status struct {
		Self *node
		Peer map[string]*node
	}
	if json.Unmarshal(out, &status) != nil {
		return names
	}
	nodes := []*node{status.Self}
	for _, p := range status.Peer {
		nodes = append(nodes, p)
	}
	for _, n := range nodes {
		if n == nil || n.HostName == "" {
			continue
		}
		for _, ip := range n.TailscaleIPs {
			names[ip] = n.HostName
		}
	}
	return names
}

// keepHostClient 把设备 d 记进本包托管进程 key 的托管目录（hostClientFile），另一个包查别处占用时读。
func (e *env) keepHostClient(key string, d conv.Device) error {
	data, err := json.Marshal(d)
	if err != nil {
		return err
	}
	return e.writeHostFile(key, hostClientFile, string(data))
}

// clientDevice 是客户端 c 是哪台设备（按它的 core.client 照 placeOf 定）；没报过（旧客户端）时不知道。
func clientDevice(c *core.Client) conv.Device { return introducedDevice(c.Introduced()) }

// introducedDevice 是报了 core.client 的客户端是哪台设备（参数同 core.Client.Introduced）：没报过（ok 为假）时不知道，不当本机。
func introducedDevice(device, ssh string, ok bool) conv.Device {
	if !ok {
		return conv.Device{}
	}
	return placeOf(ssh, device)
}
