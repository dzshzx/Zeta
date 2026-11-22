package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/host"
)

// 没记主机名的设备：连上时 serve 的招呼里带着主机名，记下来，侧栏、设备页的名字跟着换。
func TestHelloFillsAMissingHostname(t *testing.T) {
	f := newSettingsFixture(t)
	x, err := f.hub.AddHost("10.0.0.5", "", host.Found{Home: "/home/zeta", HostKey: f.devices.HostKey()})
	if err != nil {
		t.Fatal(err)
	}
	if x.Name() != "10.0.0.5" {
		t.Fatalf("名字 %q", x.Name())
	}
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.devices.Chat(x.ID).Greet("lab") // 设备页读配置走的对话连接
	f.until(t, "补上主机名", func() bool { y, _ := f.hub.Host(x.ID); return y.Hostname == "lab" })
	f.until(t, "名字换成主机名", func() bool { return f.shows("lab") })
	if saved := f.hub.Settings().Hosts(); len(saved) != 1 || saved[0].Hostname != "lab" {
		t.Errorf("存下的 %+v", saved)
	}
}
