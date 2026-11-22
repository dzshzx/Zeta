package claudecfg

import (
	"bytes"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
)

// ~/.claude/settings.json：只动要改的那一个键，别的键和顺序原样保留；写回的格式和 Claude Code 自己写的一样
// （两格缩进、末尾换行）。文件不是有效的 JSON 时不碰它。

var errInvalidSettings = fail("~/.claude/settings.json 不是有效的 JSON，先在终端里修好它")

// parseSettings：不存在或是空的给空对象；不是 JSON 对象时报错。
func parseSettings(text []byte, exists bool) (*object, error) {
	if !exists || len(bytes.TrimSpace(text)) == 0 {
		return newObject(), nil
	}
	o, err := parseObject(text)
	if err != nil {
		return nil, errInvalidSettings
	}
	return o, nil
}

// lenientSettings 读的时候宽松一些：坏了也只当没设。
func (s *Store) lenientSettings() *object {
	data, _, ok := readHead(settingsPath(s.Home()), MaxText)
	o, err := parseSettings(data, ok)
	if err != nil {
		return newObject()
	}
	return o
}

func (s *Store) readSettings() (*object, error) {
	doc, err := load(settingsPath(s.Home()), MaxText)
	if err != nil {
		return nil, err
	}
	return parseSettings([]byte(doc.Content), doc.Exists)
}

var settingsMu sync.Mutex

// updateSettings 改 settings.json：读出来、交给 edit 改、按读到时的校验值写回去；中途被别人改了就在新内容上重做一遍。
// edit 报错时什么都不写。返回写回去的（没变化时是读到的）内容。
func (s *Store) updateSettings(edit func(*object) error) (*object, error) {
	// 本进程里的改动一个一个来（serve 并发处理请求），撞车只会来自 Claude Code 这类别的进程
	settingsMu.Lock()
	defer settingsMu.Unlock()
	path := settingsPath(s.Home())
	for range 3 {
		doc, err := load(path, MaxText)
		if err != nil {
			return nil, err
		}
		settings, err := parseSettings([]byte(doc.Content), doc.Exists)
		if err != nil {
			return nil, err
		}
		before := compactJSON(settings)
		if err := edit(settings); err != nil {
			return nil, err
		}
		if compactJSON(settings) == before {
			return settings, nil
		}
		expected := doc.SHA
		if !doc.Exists {
			expected = "-"
		}
		_, err = store(path, []byte(renderJSON(settings)), expected, "")
		if err == nil {
			return settings, nil
		}
		if err != ErrConflict {
			return nil, err
		}
	}
	return nil, fail("settings.json 一直在被改动，稍后再试")
}

// childObject 是 parent 里 key 处的对象，没有时新建一个放进去；不是对象时报 bad。
func childObject(parent *object, key string, bad error) (*object, error) {
	v, ok := parent.get(key)
	if !ok {
		o := newObject()
		parent.set(key, o)
		return o, nil
	}
	o, isObj := v.(*object)
	if !isObj {
		return nil, bad
	}
	return o, nil
}

// nonBlank 是 key 处不全是空白的字符串，没有时为空。
func nonBlank(o *object, key string) string {
	if o == nil {
		return ""
	}
	s, _ := o.str(key)
	if strings.TrimSpace(s) == "" {
		return ""
	}
	return s
}

// memorySettings 是 settings.json 里记忆页关心的几项。
type memorySettings struct {
	autoMemory bool
	forcedOff  bool   // env 里设了 CLAUDE_CODE_DISABLE_AUTO_MEMORY，开关不起作用
	customDir  string // autoMemoryDirectory 原样的值
}

func memorySettingsOf(s *object) memorySettings {
	m := memorySettings{autoMemory: true}
	if b, ok := s.vals["autoMemoryEnabled"].(bool); ok {
		m.autoMemory = b
	}
	if env := s.obj("env"); env != nil {
		v, _ := env.str("CLAUDE_CODE_DISABLE_AUTO_MEMORY")
		switch strings.ToLower(strings.TrimSpace(v)) {
		case "1", "true", "yes", "on":
			m.forcedOff = true
		}
	}
	m.customDir, _ = s.str("autoMemoryDirectory")
	return m
}

// skillOverride 是 skillOverrides 里 names 中第一个有值的；Claude Code 停用 Skill 时写 "off"。
func skillOverride(s *object, names ...string) string {
	overrides := s.obj("skillOverrides")
	if overrides == nil {
		return ""
	}
	for _, n := range names {
		if n == "" {
			continue
		}
		if v, ok := overrides.vals[n]; ok {
			return scalarText(v)
		}
	}
	return ""
}

// scalarText 是 JSON 标量的文字（字符串原样，数字、布尔照写）；对象、数组、null 是空。
func scalarText(v any) string {
	switch v := v.(type) {
	case string:
		return v
	case bool:
		return strconv.FormatBool(v)
	case json.Number:
		return v.String()
	}
	return ""
}
