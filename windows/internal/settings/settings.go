package settings

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/remote"
)

// 设置文件里的键，同 shared 的 Settings.kt。
const (
	keyHosts          = "remoteHosts"    // 设备清单（JSON 数组的字符串，见 remote.EncodeHosts）
	keyBackend        = "backend"        // 侧栏正看着哪台设备
	keyPaneAll        = "paneAll"        // 侧栏列全部设备的会话
	keyPins           = "pinnedSessions" // 置顶的会话（PinKey）
	keyRemoteClaude   = "remoteClaude"   // 各台设备的自动压缩、自动标题（「设备编号/项=值」）
	keyContextWindows = "contextWindows" // 各台设备报过的模型上下文窗口（「设备编号/模型=值」）
	keyCommands       = "commands"       // 各台设备报过的斜杠命令（JSON 对象，键是设备编号）
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
func (s *Settings) Hosts() []remote.Host {
	text, _ := s.store.String(keyHosts)
	return remote.DecodeHosts(text)
}

// SetHosts 存设备清单；删掉的设备在别的设置里的项（自动压缩、上下文窗口、斜杠命令）一起去掉，同 Kotlin 版。
func (s *Settings) SetHosts(hosts []remote.Host) error {
	if err := s.store.SetString(keyHosts, remote.EncodeHosts(hosts)); err != nil {
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
	for _, key := range []string{keyRemoteClaude, keyContextWindows} {
		entries, ok := s.store.Strings(key)
		if !ok {
			continue
		}
		var left []string
		for _, entry := range entries {
			name, _, _ := strings.Cut(entry, "=")
			if kept[hostOf(name)] {
				left = append(left, entry)
			}
		}
		if len(left) != len(entries) {
			if err := s.store.SetStrings(key, left); err != nil {
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
