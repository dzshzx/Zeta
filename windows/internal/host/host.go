// Package host 是桌面版碰远程设备的几样事：中枢用的 Devices（会话列表的订阅、对话的连接、查目录、取图）、加设备时的探测，
// 和桌面版自己定的两样：Zeta 在那边的目录名 LinuxName、编进程序的 zeta-helper（LoadProgram）。在主机上跑命令、装程序、
// serve 连接这些客户端共用的在 client/hostlink，SSH 在 client/sshlink。
package host

import (
	"fmt"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/assets"
	"github.com/dzshzx/Zeta/windows/internal/update"
)

// LinuxName 是 Zeta 在那边的目录名（ZETA_NAME）：正式版是 zeta，和手机正式版是同一个包；别的构建（测试版、go run、测试）是
// zeta-dev，和手机测试版是同一个包。同一个包在一台主机上只用一份 zeta-helper 和一个核心，几个客户端读的是同一份状态。
var LinuxName = linuxName(update.Current().Release())

func linuxName(release bool) string {
	if release {
		return "zeta"
	}
	return "zeta-dev"
}

// LoadProgram 从编进程序的 assets 里取程序 name（hostlink.ZetaHelper、hostlink.ClaudeSwitch，也是 assets 里的目录名）的两份。
func LoadProgram(name string) (*hostlink.Program, error) {
	binaries := map[string][]byte{}
	for _, arch := range hostlink.Arches {
		data, err := assets.Read(name + "/linux-" + arch)
		if err != nil {
			return nil, fmt.Errorf("桌面版里没有 %s：%w", name, err)
		}
		binaries[arch] = data
	}
	return hostlink.NewProgram(name, binaries), nil
}
