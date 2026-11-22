package hub

import (
	"log"
	"slices"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// FillHostname：早先加的设备没记下主机名（加的时候还不查它）时，问一句（uname -n）补上，侧栏、设置里的名字跟着换
// （同 Kotlin 版 ConversationHub.refreshClaudeDefaults 读到默认值后顺带问的那句）。设备页读到新对话默认时调；问不到就照旧。
func (h *Hub) FillHostname(id string) {
	x, ok := h.Host(id)
	if !ok || x.Hostname != "" || h.naming[id] {
		return
	}
	h.naming[id] = true
	h.run(func() {
		name, err := h.devices.Hostname(x)
		h.post(func() {
			delete(h.naming, id)
			if err != nil {
				log.Printf("zeta: 没问到「%s」的主机名：%v", x.Name(), err)
				return
			}
			i := slices.IndexFunc(h.hosts, func(o sshlink.Host) bool { return o.ID == id })
			if i < 0 || h.hosts[i].Hostname != "" {
				return
			}
			hosts := slices.Clone(h.hosts)
			hosts[i].Hostname = name
			logSave(h.setHosts(hosts))
		})
	})
}
