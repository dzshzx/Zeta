package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/dzshzx/Zeta/helper/conv"
)

// fakeTailscale 换掉 tailscale status --json：out 为空时当主机没装 tailscale。清掉缓存，返回跑过几次。
func fakeTailscale(t *testing.T, out string) *int {
	t.Helper()
	runs := 0
	old := tailscaleStatus
	tailscaleStatus = func(context.Context) ([]byte, error) {
		runs++
		if out == "" {
			return nil, errors.New("tailscale: not found")
		}
		return []byte(out), nil
	}
	reset := func() {
		tailscaleCache.mu.Lock()
		tailscaleCache.names = nil
		tailscaleCache.mu.Unlock()
	}
	reset()
	t.Cleanup(func() { tailscaleStatus = old; reset() })
	return &runs
}

const tailnet = `{"Self":{"HostName":"build-box","TailscaleIPs":["100.64.0.2","fd7a:115c:a1e0::2"]},
"Peer":{"nodekey:a":{"HostName":"work-pc","TailscaleIPs":["100.64.0.4","fd7a:115c:a1e0::4"]}}}`

// 终端里的（entrypoint cli）是 terminal；经 SSH 连进来的说那台的机器名（tailscale 对上的），对不上说地址；
// 没有 SSH、从回环地址连来的是本机。
func TestOccupantSaysWhichTerminal(t *testing.T) {
	runs := fakeTailscale(t, tailnet)
	e := testEnv(t)
	for _, c := range []struct {
		env, name string
		local     bool
	}{
		{"SSH_CONNECTION=100.64.0.4 51234 100.64.0.2 22", "work-pc", false},
		{"SSH_CONNECTION=fd7a:115c:a1e0::4 51234 fd7a:115c:a1e0::2 22", "work-pc", false},
		{"SSH_CONNECTION=192.168.1.7 51234 192.168.1.2 22", "192.168.1.7", false},
		{"SSH_CONNECTION=127.0.0.1 51234 127.0.0.1 22", "", true},
		{"SSH_CONNECTION=::1 51234 ::1 22", "", true},
		{"OTHER=1", "", true},
	} {
		pid, _ := tui(t, false, c.env)
		register(t, e, pid, handoffID, "", `,"status":"idle","entrypoint":"cli"`)
		got := e.occupancy(handoffID)
		if got.App != "terminal" || got.Device != c.name || got.Local != c.local {
			t.Fatalf("%s: %+v", c.env, got)
		}
		unregister(t, e, pid)
	}
	if *runs != 1 {
		t.Fatalf("tailscale 跑了 %d 次，该只跑一次（记着一分钟）", *runs)
	}
}

// Zeta 的客户端和终端一个取名规矩；对不上 tailscale 时用客户端自己报的名字，没报才用地址。
func TestPlaceOfAZetaClient(t *testing.T) {
	fakeTailscale(t, tailnet)
	for _, c := range []struct {
		ssh, reported string
		want          conv.Device
	}{
		{"100.64.0.4 1 100.64.0.2 22", "DESKTOP-7", conv.Device{Name: "work-pc"}},
		{"192.168.1.7 1 192.168.1.2 22", "DESKTOP-7", conv.Device{Name: "DESKTOP-7"}},
		{"192.168.1.7 1 192.168.1.2 22", "", conv.Device{Name: "192.168.1.7"}},
		{"127.0.0.1 1 127.0.0.1 22", "DESKTOP-7", conv.Device{Local: true}},
		{"", "Xiaomi 17", conv.Device{Local: true}},
	} {
		if got := placeOf(c.ssh, c.reported); got != c.want {
			t.Errorf("%q %q: %+v", c.ssh, c.reported, got)
		}
	}
}

// 主机上没有 tailscale 时说地址，不报错。
func TestOccupantWithoutTailscaleSaysTheAddress(t *testing.T) {
	fakeTailscale(t, "")
	e := testEnv(t)
	pid, _ := tui(t, false, "SSH_CONNECTION=100.64.0.4 51234 100.64.0.2 22")
	register(t, e, pid, handoffID, "", `,"status":"busy","entrypoint":"cli"`)
	if got := e.occupancy(handoffID); got.App != "terminal" || got.Device != "100.64.0.4" || got.Local {
		t.Fatalf("got %+v", got)
	}
}

// 另一个包托管的：app 是托管它的包名，在哪是那个包的核心记在托管目录 client 里的；没记时不知道。
func TestOccupantSaysWhichZetaOnWhichDevice(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	dev, _, _ := hosted(t, e, "zeta-dev", handoffID, turnDone...)
	if err := e.keepHostClientIn(dev, conv.Device{Name: "work-pc"}); err != nil {
		t.Fatal(err)
	}
	if got := e.occupancy(handoffID); got.App != "zeta-dev" || got.Device != "work-pc" || got.Local {
		t.Fatalf("seen from zeta: %+v", got)
	}
	if err := e.keepHostClientIn(dev, conv.Device{Local: true}); err != nil {
		t.Fatal(err)
	}
	if got := e.occupancy(handoffID); got.Device != "" || !got.Local {
		t.Fatalf("a local client: %+v", got)
	}
	other := newEnvAt(e.home, "zeta-dev")
	const second = "ffffffff-1111-4222-8333-444444444444"
	hosted(t, e, "zeta", second, `{"type":"system","subtype":"init","session_id":"`+second+`"}`)
	if got := other.occupancy(second); got.App != "zeta" || got.Device != "" || got.Local {
		t.Fatalf("seen from zeta-dev, nothing recorded: %+v", got)
	}
}

// 别的入口（VS Code 插件、SDK）原样给 entrypoint；旧版登记里没有 entrypoint 时不给 app。
func TestOccupantPassesOtherEntrypointsThrough(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	pid, _ := tui(t, false)
	register(t, e, pid, handoffID, "", `,"status":"idle","entrypoint":"claude-vscode"`)
	if got := e.occupancy(handoffID); got.App != "claude-vscode" || got.Device != "" {
		t.Fatalf("got %+v", got)
	}
	unregister(t, e, pid)
	pid, _ = tui(t, false)
	register(t, e, pid, handoffID, "", `,"status":"idle"`)
	if got := e.occupancy(handoffID); got.App != "" {
		t.Fatalf("old Claude Code: %+v", got)
	}
}

// 几个进程占着时说状态最要紧的那个是什么、在哪。
func TestOccupantDescribesTheMostPressingHolder(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	idle, _ := tui(t, false)
	register(t, e, idle, handoffID, "", `,"status":"idle","entrypoint":"claude-vscode"`)
	busy, _ := tui(t, false, "SSH_CONNECTION=100.64.0.4 1 100.64.0.2 22")
	register(t, e, busy, handoffID, "", `,"status":"busy","entrypoint":"cli"`)
	if got := e.occupancy(handoffID); got.App != "terminal" || got.Device != "work-pc" || *got.Status != "busy" {
		t.Fatalf("got %+v", got)
	}
}

// unregister 删掉进程 pid 的登记（像它退出了一样，进程留给测试收尾时结束）。
func unregister(t *testing.T, e *env, pid int) {
	t.Helper()
	if err := os.Remove(filepath.Join(e.sessions, strconv.Itoa(pid)+".json")); err != nil {
		t.Fatal(err)
	}
}

// 没报过 core.client 的旧客户端是不知道，不算本机；报过、不经 SSH 的是本机。
func TestIntroducedDevice(t *testing.T) {
	fakeTailscale(t, tailnet)
	if got := introducedDevice("", "", false); got != (conv.Device{}) {
		t.Fatalf("old client: %+v", got)
	}
	if got := introducedDevice("Xiaomi 17", "", true); got != (conv.Device{Local: true}) {
		t.Fatalf("local client: %+v", got)
	}
}

// Zeta 托管的不看进程环境里的 SSH_CONNECTION：那是拉起核心的第一个客户端的，换了客户端也不变。
func TestHostedDeviceIgnoresSSH(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	dir := t.TempDir()
	pid, _ := tui(t, false, "SSH_CONNECTION=100.64.0.4 1 100.64.0.2 22")
	if got := e.deviceOf(registered{pid: pid, host: dir}); got != (conv.Device{}) {
		t.Fatalf("got %+v", got)
	}
}

// serve 给 core.client 补上自己的 SSH_CONNECTION，客户端自己写的换掉；不经 SSH 时去掉。
func TestServeAddsTheSSHSource(t *testing.T) {
	got := string(withSSHSource([]byte(`{"id":1,"method":"core.client","device":"d","ssh":"伪造"}`), "100.64.0.4 1 100.64.0.2 22"))
	if got != `{"device":"d","id":1,"method":"core.client","ssh":"100.64.0.4 1 100.64.0.2 22"}` {
		t.Fatalf("got %s", got)
	}
	if got := string(withSSHSource([]byte(`{"id":1,"method":"core.client","ssh":"伪造"}`), "")); got != `{"id":1,"method":"core.client"}` {
		t.Fatalf("got %s", got)
	}
}

// keepHostClientIn 照 keepHostClient 把设备写进托管目录 dir。
func (e *env) keepHostClientIn(dir string, d conv.Device) error {
	return newEnvAt(e.home, filepath.Base(filepath.Dir(filepath.Dir(dir)))).keepHostClient(filepath.Base(dir), d)
}
