package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"testing"
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

// 终端里的（entrypoint cli）是 terminal；经 SSH 连进来的说那台的机器名（tailscale 对上的），对不上说地址，本机开的不说设备。
func TestOccupantSaysWhichTerminal(t *testing.T) {
	runs := fakeTailscale(t, tailnet)
	e := testEnv(t)
	for _, c := range []struct{ env, want string }{
		{"SSH_CONNECTION=100.64.0.4 51234 100.64.0.2 22", "work-pc"},
		{"SSH_CONNECTION=fd7a:115c:a1e0::4 51234 fd7a:115c:a1e0::2 22", "work-pc"},
		{"SSH_CONNECTION=192.168.1.7 51234 192.168.1.2 22", "192.168.1.7"},
		{"OTHER=1", ""},
	} {
		pid, _ := tui(t, false, c.env)
		register(t, e, pid, handoffID, "", `,"status":"idle","entrypoint":"cli"`)
		got := e.occupancy(handoffID)
		if got.App != "terminal" || got.Device != c.want {
			t.Fatalf("%s: app %q device %q", c.env, got.App, got.Device)
		}
		unregister(t, e, pid)
	}
	if *runs != 1 {
		t.Fatalf("tailscale 跑了 %d 次，该只跑一次（记着一分钟）", *runs)
	}
}

// 主机上没有 tailscale 时说地址，不报错。
func TestOccupantWithoutTailscaleSaysTheAddress(t *testing.T) {
	fakeTailscale(t, "")
	e := testEnv(t)
	pid, _ := tui(t, false, "SSH_CONNECTION=100.64.0.4 51234 100.64.0.2 22")
	register(t, e, pid, handoffID, "", `,"status":"busy","entrypoint":"cli"`)
	if got := e.occupancy(handoffID); got.App != "terminal" || got.Device != "100.64.0.4" {
		t.Fatalf("got %+v", got)
	}
}

// 另一个包托管的：app 是托管它的包名，device 是那个包的客户端记在托管目录里的；没记（旧客户端）时照 SSH 看。
func TestOccupantSaysWhichZetaOnWhichDevice(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	dev, _, _ := hosted(t, e, "zeta-dev", handoffID, turnDone...)
	writeFile(t, filepath.Join(dev, hostClientFile), "Xiaomi 17\n")
	if got := e.occupancy(handoffID); got.App != "zeta-dev" || got.Device != "Xiaomi 17" {
		t.Fatalf("seen from zeta: %+v", got)
	}
	other := newEnvAt(e.home, "zeta-dev")
	const second = "ffffffff-1111-4222-8333-444444444444"
	hosted(t, e, "zeta", second, `{"type":"system","subtype":"init","session_id":"`+second+`"}`)
	if got := other.occupancy(second); got.App != "zeta" || got.Device != "" {
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

// 托管目录里没记设备名（旧客户端不报）时，照进程环境里的 SSH_CONNECTION 说（经 Debian 的 OpenSSH 连来的手机、桌面版）。
func TestHostedDeviceFallsBackToSSH(t *testing.T) {
	fakeTailscale(t, tailnet)
	e := testEnv(t)
	dir := t.TempDir()
	pid, _ := tui(t, false, "SSH_CONNECTION=100.64.0.4 1 100.64.0.2 22")
	if got := e.deviceOf(registered{pid: pid, host: dir}); got != "work-pc" {
		t.Fatalf("got %q", got)
	}
	writeFile(t, filepath.Join(dir, hostClientFile), "DESKTOP-7\n")
	if got := e.deviceOf(registered{pid: pid, host: dir}); got != "DESKTOP-7" {
		t.Fatalf("got %q", got)
	}
}
