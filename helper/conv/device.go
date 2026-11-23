package conv

// 哪台设备在用这个对话：最近一个发消息（或要起进程）的客户端是哪台（Deps.ClientDevice），记在托管进程的托管目录里
// （Deps.KeepDevice，托管目录的 client），另一个包的核心查别处占用时读它，「别处运行」卡片的标题说「在哪台设备的 Zeta 里」。

// Device 是一台设备：Local 为真是这台主机本机（客户端不经 SSH 连来，或从回环地址连来）；不是本机时 Name 是它的名字，
// 不知道时为空。都是零值是不知道（旧客户端不报）。托管目录的 client 就是它的 JSON。
type Device struct {
	Name  string `json:"name,omitempty"`
	Local bool   `json:"local,omitempty"`
}

// startedBy：客户端 client 发了消息或要起进程，起进程用它的设置，也记下它是哪台设备。旧客户端不报设备时记成不知道，
// 不留上一台的：说不准是哪台时宁可不说。
func (c *Conversation) startedBy(client int) {
	c.starter = client
	if c.deps.ClientDevice != nil {
		c.device = c.deps.ClientDevice(client)
	}
}

// keepDevice 把设备记到接着的托管进程上，变了才写（进程换了、接上了本包现成的进程也算变）；没写成的下次推送时再写。
func (c *Conversation) keepDevice() {
	if c.proc == nil || c.deps.KeepDevice == nil {
		return
	}
	key := c.proc.link.Key()
	if c.keptDev.key == key && c.keptDev.device == c.device {
		return
	}
	if c.deps.KeepDevice(key, c.device) == nil {
		c.keptDev.key, c.keptDev.device = key, c.device
	}
}
