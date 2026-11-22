package settings

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// 设置文件里的键，同 shared 的 Settings.kt。
const (
	keyHosts        = "remoteHosts"    // 设备清单（JSON 数组的字符串，见 sshlink.EncodeHosts）
	keyBackend      = "backend"        // 侧栏正看着哪台设备
	keyPaneAll      = "paneAll"        // 侧栏列全部设备的会话
	keyPins         = "pinnedSessions" // 置顶的会话（PinKey）
	keyRemoteClaude = "remoteClaude"   // 各台设备的自动压缩、自动标题（「设备编号/项=值」）
	keyCommands     = "commands"       // 各台设备报过的斜杠命令（JSON 对象，键是设备编号）
)

// Local 是手机本机那台的编号；桌面版没有这一台，存着它时当作没选。
const Local = "local"

// Settings 是桌面版用到的各项设置。写不成（盘满、没权限）时返回错误，内存里的照样换成新值。
type Settings struct {
	store *Store
}

// Dir 是设置所在的目录：Windows 上是 %APPDATA%\Zeta（同 Compose 版），别的系统上是 $XDG_CONFIG_HOME/zeta-desktop。
func Dir() (string, error) {
	if runtime.GOOS == "windows" {
		if appData := os.Getenv("APPDATA"); appData != "" {
			return filepath.Join(appData, "Zeta"), nil
		}
	}
	config, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(config, "zeta-desktop"), nil
}

// Load 打开目录 dir 里的设置文件 zeta.json（同 Compose 版的 Settings.FILE）。读不成时给一份空的能用的设置，同时返回原因。
func Load(dir string) (*Settings, error) {
	store, err := Open(filepath.Join(dir, "zeta.json"))
	return &Settings{store: store}, err
}

// Hosts 是设备清单。
func (s *Settings) Hosts() []sshlink.Host {
	text, _ := s.store.String(keyHosts)
	return sshlink.DecodeHosts(text)
}

// SetHosts 存设备清单；删掉的设备在别的设置里的项（自动压缩、斜杠命令）一起去掉，同 Kotlin 版。
func (s *Settings) SetHosts(hosts []sshlink.Host) error {
	if err := s.store.SetString(keyHosts, sshlink.EncodeHosts(hosts)); err != nil {
		return err
	}
	kept := map[string]bool{Local: true}
	for _, h := range hosts {
		kept[h.ID] = true
	}
	return s.forgetHosts(kept)
}

// forgetHosts 去掉不在 kept 里的设备在各项里的记录。
func (s *Settings) forgetHosts(kept map[string]bool) error {
	if entries, ok := s.store.Strings(keyRemoteClaude); ok {
		var left []string
		for _, entry := range entries {
			name, _, _ := strings.Cut(entry, "=")
			if kept[hostOf(name)] {
				left = append(left, entry)
			}
		}
		if len(left) != len(entries) {
			if err := s.store.SetStrings(keyRemoteClaude, left); err != nil {
				return err
			}
		}
	}
	text, ok := s.store.String(keyCommands)
	var commands map[string]json.RawMessage
	if !ok || json.Unmarshal([]byte(text), &commands) != nil {
		return nil
	}
	changed := false
	for id := range commands {
		if !kept[id] {
			delete(commands, id)
			changed = true
		}
	}
	if !changed {
		return nil
	}
	data, err := marshal(commands)
	if err != nil {
		return err
	}
	return s.store.SetString(keyCommands, string(data))
}

// Backend 是侧栏正看着的设备编号；没存过是 Local。
func (s *Settings) Backend() string {
	if v, ok := s.store.String(keyBackend); ok {
		return v
	}
	return Local
}

// SetBackend 存侧栏正看着的设备。
func (s *Settings) SetBackend(id string) error { return s.store.SetString(keyBackend, id) }

// PaneAll 是侧栏列全部设备的会话（不只列 Backend 那台）。
func (s *Settings) PaneAll() bool {
	v, _ := s.store.Bool(keyPaneAll)
	return v
}

// SetPaneAll 存侧栏列不列全部设备。
func (s *Settings) SetPaneAll(all bool) error { return s.store.SetBool(keyPaneAll, all) }

// Pins 是置顶的会话（PinKey），按存的先后。
func (s *Settings) Pins() []string {
	pins, _ := s.store.Strings(keyPins)
	return pins
}

// SetPins 存置顶的会话。
func (s *Settings) SetPins(pins []string) error { return s.store.SetStrings(keyPins, pins) }

// PinKey 是置顶记的键：手机本机的就是会话号，远程设备的前面加设备编号和 /（同 Kotlin 的 HostKeys.key），各台的置顶分得开。
func PinKey(backend, sessionID string) string {
	if backend == Local {
		return sessionID
	}
	return backend + "/" + sessionID
}

// hostOf 是按设备记的键（PinKey 的写法）属于哪台设备。
func hostOf(key string) string {
	if id, _, ok := strings.Cut(key, "/"); ok {
		return id
	}
	return Local
}

// 更新用到的三项（update.Prefs），键同 Compose 版：手机、Compose 桌面版和这一版读写的是同一组。
const (
	keyAutoUpdateCheck = "autoUpdateCheck" // 每天最多自动检查一次新版本，默认开
	keyUpdateCheckedAt = "updateCheckedAt" // 上次检查的时间（Unix 毫秒，Kotlin 的 Long）
	keyUpdateNotified  = "updateNotified"  // 已经提示过的新版本（versionCode，Kotlin 的 Int）
)

// AutoUpdateCheck 是每天最多自动检查一次新版本，没设过是开着。
func (s *Settings) AutoUpdateCheck() bool {
	if v, ok := s.store.Bool(keyAutoUpdateCheck); ok {
		return v
	}
	return true
}

// SetAutoUpdateCheck 存自动检查的开关。
func (s *Settings) SetAutoUpdateCheck(on bool) { logSave(s.store.SetBool(keyAutoUpdateCheck, on)) }

// UpdateCheckedAt 是上次检查更新的时间（手动、自动都算）；没查过是零值。
func (s *Settings) UpdateCheckedAt() time.Time {
	ms, ok := s.store.Int(keyUpdateCheckedAt)
	if !ok || ms == 0 {
		return time.Time{}
	}
	return time.UnixMilli(ms)
}

// SetUpdateCheckedAt 存上次检查更新的时间。
func (s *Settings) SetUpdateCheckedAt(t time.Time) {
	logSave(s.store.SetLong(keyUpdateCheckedAt, t.UnixMilli()))
}

// UpdateNotified 是已经提示过的新版本；0 是还没提示过。
func (s *Settings) UpdateNotified() int {
	v, _ := s.store.Int(keyUpdateNotified)
	return int(v)
}

// SetUpdateNotified 存已经提示过的新版本。
func (s *Settings) SetUpdateNotified(versionCode int) {
	logSave(s.store.SetInt(keyUpdateNotified, versionCode))
}

// logSave 记下没存上的设置：这几项没存上只是下次多查一次、多提示一次，不打扰用户。
func logSave(err error) {
	if err != nil {
		log.Printf("zeta: 设置没存上：%v", err)
	}
}

// Store 是底下的设置文件：按键读写的部件（Claude Code 配置页的 claudecfg.Prefs）直接用它。
func (s *Settings) Store() *Store { return s.store }

// keyDark 是外观（同 Settings.kt 的 darkTheme）：true 深色、false 浅色，没有这个键是跟随系统。
const keyDark = "dark"

// Dark 是选的深浅色；跟随系统时 set 为 false。
func (s *Settings) Dark() (dark, set bool) { return s.store.Bool(keyDark) }

// SetDark 存深浅色：nil 是跟随系统。
func (s *Settings) SetDark(dark *bool) error {
	if dark == nil {
		return s.store.Delete(keyDark)
	}
	return s.store.SetBool(keyDark, *dark)
}
