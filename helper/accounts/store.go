package accounts

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"time"
)

// QuotaStore 是一台主机上各账号最近一次的额度，存在 ~/.cache/zeta-quota/<账号>.json：登录状态本来就是这台上各个 Zeta 包、
// 终端里的 claude 共用的，所以额度也共用，各次 serve、各个客户端读到的是同一份。按账号分开存：换了号读到的是新号的
// （还没有就是没有），换回来还看得到上次的数。账号按 ~/.claude.json 里的登录编号认，文件名是它的哈希。
//
// 数是对话核心交来的：get_usage 的应答（Usage）和对话里的 rate_limit_event（Event）。读写都是小文件，可以并发调用：
// 写先写临时文件再改名，两个同时写时后写的那份留下。
type QuotaStore struct {
	Home string
}

// quotaDir 是存额度的目录（相对家目录）。
const quotaDir = ".cache/zeta-quota"

// account 是在用的登录编号（~/.claude.json 的 oauthAccount.accountUuid）；没登录时为空。只解码这一项。
func (s QuotaStore) account() (string, error) {
	data, err := os.ReadFile(filepath.Join(s.Home, ".claude.json"))
	if errors.Is(err, fs.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	var config struct {
		OAuth *struct {
			UUID string `json:"accountUuid"`
		} `json:"oauthAccount"`
	}
	if err := json.Unmarshal(data, &config); err != nil {
		return "", errors.New("读不懂 ~/.claude.json")
	}
	if config.OAuth == nil {
		return "", nil
	}
	return config.OAuth.UUID, nil
}

func (s QuotaStore) path(account string) string {
	sum := sha256.Sum256([]byte(account))
	return filepath.Join(s.Home, quotaDir, hex.EncodeToString(sum[:8])+".json")
}

// Get 是在用的账号最近一次的额度；没登录、还没有数时为 nil。
func (s QuotaStore) Get() (*Snapshot, error) {
	account, err := s.account()
	if err != nil || account == "" {
		return nil, err
	}
	return s.load(account)
}

func (s QuotaStore) load(account string) (*Snapshot, error) {
	data, err := os.ReadFile(s.path(account))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var snap Snapshot
	if json.Unmarshal(data, &snap) != nil || snap.UpdatedAt.IsZero() {
		return nil, nil // 坏了的当作没有，下次有数时覆盖
	}
	return &snap, nil
}

func (s QuotaStore) save(account string, snap Snapshot) error {
	data, err := json.Marshal(snap)
	if err != nil {
		return err
	}
	path := s.path(account)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".quota-*")
	if err != nil {
		return err
	}
	_, werr := tmp.Write(data)
	cerr := tmp.Close()
	if err := errors.Join(werr, cerr); err != nil {
		_ = os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// Usage 收下一次 get_usage 的应答（control_response 的 response.response）。Claude Code 这次没取到服务器的数（rate_limits
// 为 null）而已有窗口时，已有的留着，fresh 为 false；被拒的状态只有事件里有，取到新数时沿用已有的。返回现在的额度。
func (s QuotaStore) Usage(raw []byte, now time.Time) (snap Snapshot, fresh bool, err error) {
	parsed, err := ParseUsage(raw, now)
	if err != nil {
		return Snapshot{}, false, err
	}
	account, err := s.account()
	if err != nil || account == "" {
		return parsed, true, err
	}
	prev, err := s.load(account)
	if err != nil {
		return Snapshot{}, false, err
	}
	if prev != nil {
		if !parsed.Unavailable && len(parsed.Windows) == 0 && len(prev.Windows) > 0 {
			return *prev, false, nil
		}
		parsed.Blocked, parsed.BlockedUntil = prev.Blocked, prev.BlockedUntil
	}
	parsed = parsed.At(now)
	return parsed, true, s.save(account, parsed)
}

// Event 收下对话里一条 rate_limit_event 的 rate_limit_info，并进已有的额度。没有变化时 changed 为 false。
func (s QuotaStore) Event(info []byte, now time.Time) (snap Snapshot, changed bool, err error) {
	account, err := s.account()
	if err != nil {
		return Snapshot{}, false, err
	}
	var prev *Snapshot
	if account != "" {
		if prev, err = s.load(account); err != nil {
			return Snapshot{}, false, err
		}
	}
	snap, changed, err = ApplyEvent(prev, info, now)
	if err != nil || !changed || account == "" {
		return snap, changed, err
	}
	return snap, true, s.save(account, snap)
}
