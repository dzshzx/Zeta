// Package settings 读写桌面版的设置：%APPDATA%\Zeta 下和 Compose 版共用的 JSON 文件。
//
// 文件格式同 desktop 的 FilePrefStore.kt：一个 JSON 对象，每项是 {"t":类型,"v":值}，类型是 s（字符串）、i、l（整数）、
// f（小数）、b（真假）、set（字符串数组）。键和取值同 shared 的 Settings.kt，所以从旧版升上来，设备、置顶这些都还在。
package settings

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
)

// 每项的类型记号。
const (
	typeString = "s"
	typeInt    = "i"
	typeLong   = "l"
	typeBool   = "b"
	typeSet    = "set"
)

// entry 是文件里的一项。
type entry struct {
	T string          `json:"t"`
	V json.RawMessage `json:"v"`
}

// Store 是一个设置文件：整份在内存里，改一项就整份重写（先写临时文件再换名，写到一半断电也不坏）。
// 认不得的项原样留着，Compose 版存的、这一版还不用的设置不丢。哪个 goroutine 上读写都行。
type Store struct {
	path  string
	mu    sync.Mutex
	items map[string]entry
}

// Open 打开设置文件 path。文件不在是空的设置；读不了或坏了也给一份空的能用的 Store，同时返回原因（下次写会盖掉坏的那份，同 Compose 版）。
func Open(path string) (*Store, error) {
	s := &Store{path: path, items: map[string]entry{}}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return s, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return s, fmt.Errorf("设置文件 %s 坏了：%w", path, err)
	}
	for key, value := range raw {
		var e entry
		// 不是 {"t":…,"v":…} 的项 Compose 版也读不出来，跳过
		if json.Unmarshal(value, &e) == nil && e.T != "" && e.V != nil {
			s.items[key] = e
		}
	}
	return s, nil
}

// value 把 key 那一项（类型是 types 之一）解码进 v；没有、类型不对、解不出来时是 false。
func (s *Store) value(key string, v any, types ...string) bool {
	s.mu.Lock()
	e, ok := s.items[key]
	s.mu.Unlock()
	if !ok {
		return false
	}
	for _, t := range types {
		if e.T == t {
			return json.Unmarshal(e.V, v) == nil
		}
	}
	return false
}

// String 是字符串项 key；没有时 ok 为 false。
func (s *Store) String(key string) (v string, ok bool) {
	ok = s.value(key, &v, typeString)
	return v, ok
}

// Bool 是真假项 key。
func (s *Store) Bool(key string) (v bool, ok bool) {
	ok = s.value(key, &v, typeBool)
	return v, ok
}

// Int 是整数项 key（Compose 版的 Int 和 Long 都算）。
func (s *Store) Int(key string) (v int64, ok bool) {
	ok = s.value(key, &v, typeInt, typeLong)
	return v, ok
}

// Strings 是字符串集合项 key，按文件里的先后。
func (s *Store) Strings(key string) (v []string, ok bool) {
	ok = s.value(key, &v, typeSet)
	return v, ok
}

// SetString 存字符串项。
func (s *Store) SetString(key, v string) error { return s.put(key, typeString, v) }

// SetBool 存真假项。
func (s *Store) SetBool(key string, v bool) error { return s.put(key, typeBool, v) }

// SetInt 存整数项（Compose 版的 Int）。
func (s *Store) SetInt(key string, v int) error { return s.put(key, typeInt, v) }

// SetLong 存整数项（Compose 版的 Long）。
func (s *Store) SetLong(key string, v int64) error { return s.put(key, typeLong, v) }

// SetStrings 存字符串集合项；nil 存成空集合。
func (s *Store) SetStrings(key string, v []string) error {
	if v == nil {
		v = []string{}
	}
	return s.put(key, typeSet, v)
}

// Delete 删掉一项；本来就没有时什么也不写。
func (s *Store) Delete(key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.items[key]; !ok {
		return nil
	}
	delete(s.items, key)
	return s.save()
}

// put 换掉一项并写盘；值没变时不写。
func (s *Store) put(key, t string, v any) error {
	data, err := marshal(v)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if old, ok := s.items[key]; ok && old.T == t && bytes.Equal(old.V, data) {
		return nil
	}
	s.items[key] = entry{T: t, V: data}
	return s.save()
}

// save 整份写进文件：先写同目录下的临时文件再换名。拿着锁时调用。
func (s *Store) save() error {
	data, err := marshal(s.items)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	temp := s.path + ".tmp"
	if err := os.WriteFile(temp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(temp, s.path)
}

// marshal 同 json.Marshal，只是不把 <、>、& 写成 \u 转义（Compose 版也不转），末尾不带换行。
func marshal(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(b.Bytes(), "\n"), nil
}
