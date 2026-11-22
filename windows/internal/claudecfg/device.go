package claudecfg

import (
	"maps"
	"slices"
	"strconv"
	"strings"
)

// Prefs 是客户端本地存的设置（%APPDATA%\Zeta 下的设置文件，格式见 settings 包），方法和 *settings.Store 的一样，
// 可以直接把它传进来。键和取值照 shared/…/core/Settings.kt。
type Prefs interface {
	// Bool 是布尔值，没设过时 ok 为 false。
	Bool(key string) (v bool, ok bool)
	// Int 是整数，没设过时 ok 为 false。
	Int(key string) (v int64, ok bool)
	// Strings 是字符串集合，没设过时 ok 为 false。
	Strings(key string) (v []string, ok bool)
	// SetStrings 整个换掉一个字符串集合，存到文件里。
	SetStrings(key string, v []string) error
}

// 用到的键（Settings.kt）：远程设备设过的项存在 remoteClaude（「设备编号/项=值」的集合）；没设过的照手机本机的
// autoCompact（默认 true）、autoCompactPercent（1…100，没设是 Claude Code 自己的）、autoTitle（默认 true）。
const (
	prefRemoteClaude   = "remoteClaude"
	prefAutoCompact    = "autoCompact"
	prefCompactPercent = "autoCompactPercent"
	prefAutoTitle      = "autoTitle"
	// 压缩时机明确设成 Claude Code 自己的默认点时存的值
	compactDefaultPoint = "default"
)

// CompactPercents 是压缩时机可选的几档（有效窗口的百分比）。
var CompactPercents = []int{50, 60, 70, 80, 90}

// Device 是一台远程设备上 Zeta 起的对话的自动压缩、压缩时机和自动标题，各台各设各的。只影响 Zeta 在那台上起的 Claude Code
// 进程（起进程时带上），不改 Claude Code 自己的配置；已经在跑的对话下次起进程时生效。
type Device struct {
	prefs   Prefs
	backend string
}

// DeviceSettings 是设备 backend（远程设备的编号）的这几项。
func DeviceSettings(p Prefs, backend string) Device { return Device{p, backend} }

func (d Device) remote() map[string]string {
	m := map[string]string{}
	entries, _ := d.prefs.Strings(prefRemoteClaude)
	for _, entry := range entries {
		if k, v, ok := strings.Cut(entry, "="); ok {
			m[k] = v
		}
	}
	return m
}

func (d Device) get(item string) (string, bool) {
	v, ok := d.remote()[d.backend+"/"+item]
	return v, ok
}

func (d Device) store(item, value string) error {
	m := d.remote()
	m[d.backend+"/"+item] = value
	var set []string
	for _, k := range slices.Sorted(maps.Keys(m)) {
		set = append(set, k+"="+m[k])
	}
	return d.prefs.SetStrings(prefRemoteClaude, set)
}

func (d Device) boolean(item string, def bool) bool {
	switch v, _ := d.get(item); v {
	case "true":
		return true
	case "false":
		return false
	}
	if v, ok := d.prefs.Bool(item); ok {
		return v
	}
	return def
}

// AutoCompact：让 Claude Code 自动压缩上下文。
func (d Device) AutoCompact() bool { return d.boolean(prefAutoCompact, true) }

// SetAutoCompact 打开或关掉自动压缩。
func (d Device) SetAutoCompact(on bool) error {
	return d.store(prefAutoCompact, strconv.FormatBool(on))
}

// CompactPercent 是自动压缩的触发点（有效窗口的百分比）；0 是 Claude Code 自己的（快满时）。
func (d Device) CompactPercent() int {
	if v, ok := d.get(prefCompactPercent); ok {
		if p, err := strconv.Atoi(v); err == nil && p >= 1 && p <= 100 {
			return p
		}
		return 0
	}
	if p, ok := d.prefs.Int(prefCompactPercent); ok && p >= 1 && p <= 100 {
		return int(p)
	}
	return 0
}

// SetCompactPercent 设压缩时机；0 是 Claude Code 自己的。
func (d Device) SetCompactPercent(p int) error {
	if p == 0 {
		return d.store(prefCompactPercent, compactDefaultPoint)
	}
	return d.store(prefCompactPercent, strconv.Itoa(p))
}

// AutoTitle：新对话第一轮回答后由 Claude Code 用它的快速模型起个简短标题；改过名的不动。
func (d Device) AutoTitle() bool { return d.boolean(prefAutoTitle, true) }

// SetAutoTitle 打开或关掉自动标题。
func (d Device) SetAutoTitle(on bool) error { return d.store(prefAutoTitle, strconv.FormatBool(on)) }

// CompactPercentLabel 是压缩时机的说法。
func CompactPercentLabel(p int) string {
	if p == 0 {
		return "默认（快满时）"
	}
	return "用到 " + strconv.Itoa(p) + "% 时"
}
