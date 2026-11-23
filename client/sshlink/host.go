// Package sshlink 是远程设备和到它们的 SSH：设备清单的格式（写法同手机版的设置）、照这台电脑的
// OpenSSH 配置认证、Zeta 自带的 SSH 直连（golang.org/x/crypto/ssh）和主机指纹。客户端共用，不碰界面（hostlink 在它上面跑命令）。
package sshlink

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"unicode/utf16"

	"golang.org/x/crypto/ssh"
)

// 连法（设置里 via 的值）。桌面版只直连；手机上经 Debian 加的设备留在清单里，桌面版连不了。
const (
	ViaDirect = "direct"
	ViaDebian = "debian"
)

// Host 是一台远程设备，上面装着 Claude Code。
type Host struct {
	ID         string // r- 开头的编号
	CustomName string // 用户起的名字，没起是空
	Target     string // 用户@主机[:端口]，或这台电脑 ~/.ssh/config 里的别名
	Home       string // 连上时查到的家目录
	DefaultCwd string // 在它上面开新对话的默认工作目录
	Via        string // ViaDirect 或 ViaDebian
	HostKey    string // 加设备时记下的主机公钥（SSH 线上格式的 base64），以后只认它
	Hostname   string // 它报的主机名（uname -n），还没记下是空
	Key        string // 登录用的私钥（~/.ssh/名字），空是照 ~/.ssh/config
	Off        bool   // 设备页的「连接」关着：Zeta 不连它，它上面的对话在那边照样跑
}

// Name 是界面上的名字：用户起的，没起时是它报的主机名，还没记下时是地址。
func (h Host) Name() string {
	switch {
	case h.CustomName != "":
		return h.CustomName
	case h.Hostname != "":
		return h.Hostname
	}
	return DisplayTarget(h.Target)
}

// Fingerprint 是主机指纹（SHA256:…）；没记下或坏了是空。
func (h Host) Fingerprint() string { return Fingerprint(h.HostKey) }

// hostJSON 是设置里 remoteHosts 数组的一项，键和先后同 shared 的 RemoteHosts.encode。
type hostJSON struct {
	ID       string `json:"id"`
	Name     string `json:"name,omitempty"`
	Hostname string `json:"hostname,omitempty"`
	Target   string `json:"target"`
	Home     string `json:"home"`
	Cwd      string `json:"cwd"`
	Via      string `json:"via"`
	HostKey  string `json:"hostKey,omitempty"`
	Off      bool   `json:"off,omitempty"`
	Key      string `json:"key,omitempty"`
}

// EncodeHosts 把设备清单写成设置里存的 JSON 数组。
func EncodeHosts(hosts []Host) string {
	items := make([]hostJSON, 0, len(hosts))
	for _, h := range hosts {
		items = append(items, hostJSON{
			ID: h.ID, Name: h.CustomName, Hostname: h.Hostname, Target: h.Target, Home: h.Home,
			Cwd: h.DefaultCwd, Via: h.Via, HostKey: h.HostKey, Off: h.Off, Key: h.Key,
		})
	}
	data, _ := json.Marshal(items) // 只有字符串和布尔值，不会失败
	return string(data)
}

// DecodeHosts 读设置里的设备清单。坏掉的项（缺字段、地址不合规矩、直连的没有主机指纹、编号重复）跳过，整个坏了是空清单，同 Kotlin 版。
func DecodeHosts(text string) []Host {
	var items []json.RawMessage
	if json.Unmarshal([]byte(text), &items) != nil {
		return nil
	}
	var hosts []Host
	seen := map[string]bool{}
	for _, raw := range items {
		var item hostJSON
		if json.Unmarshal(raw, &item) != nil {
			continue
		}
		h, ok := item.host()
		if !ok || seen[h.ID] {
			continue
		}
		seen[h.ID] = true
		hosts = append(hosts, h)
	}
	return hosts
}

// host 核对一项，合规矩的换成 Host。
func (item hostJSON) host() (Host, bool) {
	via := ViaDebian
	if item.Via == ViaDirect {
		via = ViaDirect
	}
	keyOK := item.HostKey != "" && ParseHostKey(item.HostKey) != nil
	switch {
	case !strings.HasPrefix(item.ID, "r-"), !validTarget(item.Target, via), !strings.HasPrefix(item.Home, "/"):
		return Host{}, false
	case via == ViaDirect && !keyOK:
		return Host{}, false
	}
	h := Host{ID: item.ID, Target: item.Target, Home: item.Home, DefaultCwd: item.Home, Via: via,
		Hostname: strings.TrimSpace(item.Hostname), Off: item.Off}
	// 早先没起名字时存的是地址：那不算用户起的
	if name := strings.TrimSpace(item.Name); name != item.Target && name != DisplayTarget(item.Target) {
		h.CustomName = name
	}
	if strings.HasPrefix(item.Cwd, "/") {
		h.DefaultCwd = item.Cwd
	}
	if via == ViaDirect {
		h.HostKey = item.HostKey
		h.Key = item.Key
	}
	return h, true
}

// validTarget：直连的是写明用户的地址或别名，经 Debian 的交给那边的 OpenSSH。
func validTarget(target, via string) bool {
	if via == ViaDirect {
		_, parsed := ParseTarget(target)
		_, alias := Alias(target)
		return parsed || alias
	}
	return validOpenSSH(target)
}

// NewID 是新设备的编号：r- 加 8 位十六进制，和手机本机的 local 撞不上。
func NewID() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return "r-" + hex.EncodeToString(b)
}

// NameProblem 说给设备起的名字有什么问题：太长、和别的设备（others 是它们现在的名字，不含它自己）重名；没问题是空。空名字是不起名字，没问题。
func NameProblem(name string, others []string) string {
	clean := strings.TrimSpace(name)
	if len(utf16.Encode([]rune(clean))) > 40 {
		return "名字太长了"
	}
	for _, other := range others {
		if clean != "" && clean == other {
			return "已经有叫「" + clean + "」的设备了"
		}
	}
	return ""
}

// ParseHostKey 解出记下的主机公钥（base64）；坏的、认不得的是 nil。
func ParseHostKey(text string) ssh.PublicKey {
	blob, err := base64.StdEncoding.DecodeString(text)
	if err != nil {
		return nil
	}
	key, err := ssh.ParsePublicKey(blob)
	if err != nil {
		return nil
	}
	return key
}

// EncodeHostKey 是存进设置的主机公钥写法。
func EncodeHostKey(key ssh.PublicKey) string { return base64.StdEncoding.EncodeToString(key.Marshal()) }

// Fingerprint 是主机公钥（base64）的指纹，写法同 OpenSSH（SHA256:…）；坏的是空。
func Fingerprint(hostKey string) string {
	key := ParseHostKey(hostKey)
	if key == nil {
		return ""
	}
	return ssh.FingerprintSHA256(key)
}
