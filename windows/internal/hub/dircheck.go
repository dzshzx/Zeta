package hub

// DirChecker 是到设备 id 上看进不进得去目录的办法（同新对话起进程前的核对，设备页改默认工作目录时用）：返回的函数在后台
// goroutine 上调用，进不去时是给人看的原因，进得去是空；连不上、「连接」关着时是错误。在主线程上取，用的是此刻这台的设置。
func (h *Hub) DirChecker(id string) func(path string) (string, error) {
	x, _ := h.Host(id)
	devices := h.devices
	return func(path string) (string, error) { return devices.CheckDir(x, path) }
}
