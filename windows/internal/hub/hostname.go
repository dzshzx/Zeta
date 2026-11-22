package hub

import "github.com/dzshzx/Zeta/client/hostlink"

// greeted：设备 id 上会话列表的订阅连上了（收到 zeta_hello）。拿招呼里的主机名补上设备的主机名，
// 再把本机设置里这台的置顶迁到核心（pins.go）。对话连接的招呼只补主机名（setHostname）。
func (h *Hub) greeted(id string, hello hostlink.Hello) {
	h.setHostname(id, hello.Hostname)
	h.migratePins(id)
}

// setHostname 记下设备 id 报的主机名 name（没起名字的设备在侧栏、设置里就叫它）。name 是空的（那边查不到）或和记着的一样时不动。
// 加设备时的检查（host.Probe）在装 zeta-helper 之前，照旧自己查主机名。
func (h *Hub) setHostname(id, name string) {
	x, ok := h.Host(id)
	if name == "" || !ok || x.Hostname == name {
		return
	}
	x.Hostname = name
	logSave(h.UpdateHost(x))
}
