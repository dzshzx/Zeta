package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/host"
)

// 早先加的设备没记主机名：设备页读到新对话默认时顺带问一句补上，名字跟着换；记过的不再问。
func TestDevicePageFillsAMissingHostname(t *testing.T) {
	f := newSettingsFixture(t)
	x, err := f.hub.AddHost("10.0.0.5", "", host.Found{Home: "/home/zeta", HostKey: f.devices.HostKey()})
	if err != nil {
		t.Fatal(err)
	}
	if x.Name() != "10.0.0.5" {
		t.Fatalf("名字 %q", x.Name())
	}
	f.devices.Hostnames = map[string]string{x.ID: "lab"}
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.until(t, "补上主机名", func() bool { y, _ := f.hub.Host(x.ID); return y.Hostname == "lab" })
	f.until(t, "名字换成主机名", func() bool { return f.shows("lab") })
	if saved := f.hub.Settings().Hosts(); len(saved) != 1 || saved[0].Hostname != "lab" {
		t.Errorf("存下的 %+v", saved)
	}
}
