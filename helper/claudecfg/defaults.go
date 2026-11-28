package claudecfg

import (
	"encoding/json"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 新对话的默认值：settings.json 里 Claude Code 的 /model、/effort 存为默认时写的那几项（2.1.329，用户级，所有项目生效）。
// 对话里“只用于这个会话”的不写文件。

// Defaults 是一台设备上新对话的默认值。
type Defaults struct {
	Model          string        `json:"model"`          // model；空是没设，用 Claude Code 内置的默认模型
	PermissionMode string        `json:"permissionMode"` // permissions.defaultMode；空是没设（逐项确认）
	Efforts        []ModelEffort `json:"efforts"`        // modelSettings.<模型>.effortLevel，按文件里的顺序
	Effort         string        `json:"effort"`         // 顶层的 effortLevel：模型没有单独设时用它
	CompactWindow  int64         `json:"compactWindow"`  // autoCompactWindow（token 数）；0 是没设，按模型本身的窗口
}

// ModelEffort 是按模型存的一档思考强度。
type ModelEffort struct {
	Model string `json:"model"`
	Level string `json:"level"`
}

// MinCompactWindow 是 Claude Code 肯认的最小上下文窗口（2.1.330 的设置校验），更小的值它当没设。
const MinCompactWindow = 100_000

func defaultsOf(s *object) Defaults {
	d := Defaults{Model: nonBlank(s, "model"), Effort: nonBlank(s, "effortLevel"), Efforts: []ModelEffort{}}
	d.PermissionMode = nonBlank(s.obj("permissions"), "defaultMode")
	if ms := s.obj("modelSettings"); ms != nil {
		for _, m := range ms.keys {
			if level := nonBlank(ms.obj(m), "effortLevel"); level != "" {
				d.Efforts = append(d.Efforts, ModelEffort{m, level})
			}
		}
	}
	if n, ok := s.vals["autoCompactWindow"].(json.Number); ok {
		if v, err := strconv.ParseInt(n.String(), 10, 64); err == nil && v >= MinCompactWindow {
			d.CompactWindow = v
		}
	}
	return d
}

// Defaults 读新对话的默认值。
func (st *Store) Defaults() (Defaults, error) {
	s, err := st.readSettings()
	if err != nil {
		return Defaults{}, err
	}
	if st.IsAgy() {
		return agyDefaultsOf(s), nil
	}
	return defaultsOf(s), nil
}

func (st *Store) editDefaults(edit func(*object) error) (Defaults, error) {
	s, err := st.updateSettings(edit)
	if err != nil {
		return Defaults{}, err
	}
	if st.IsAgy() {
		return agyDefaultsOf(s), nil
	}
	return defaultsOf(s), nil
}

// setOrDelete 把 key 设成 v，v 为 nil 时删掉。
func setOrDelete(o *object, key string, v any) {
	if v == nil {
		o.del(key)
	} else {
		o.set(key, v)
	}
}

// SetDefaultModel 设默认模型；空是删掉，回到 Claude Code 内置的默认。
func (st *Store) SetDefaultModel(model string) (Defaults, error) {
	return st.editDefaults(func(s *object) error {
		setOrDelete(s, "model", optional(model))
		return nil
	})
}

// SetCompactWindow 设上下文窗口（token 数）；0 是删掉，按模型本身的窗口。
func (st *Store) SetCompactWindow(tokens int64) (Defaults, error) {
	if st.IsAgy() {
		return Defaults{}, locales.Err("errAgyConfigUnsupported")
	}
	if tokens != 0 && tokens < MinCompactWindow {
		return Defaults{}, locales.Err("cfgCompactWindowMin", "min", FormatTokens(MinCompactWindow))
	}
	return st.editDefaults(func(s *object) error {
		var v any
		if tokens != 0 {
			v = json.Number(strconv.FormatInt(tokens, 10))
		}
		setOrDelete(s, "autoCompactWindow", v)
		return nil
	})
}

// SetDefaultPermissionMode 设默认权限模式（permissions.defaultMode 或 agy 的 agentMode/toolPermission），其余键原样留着。
func (st *Store) SetDefaultPermissionMode(mode string) (Defaults, error) {
	if st.IsAgy() {
		return st.editDefaults(func(s *object) error {
			applyAgyDefaultMode(s, mode)
			return nil
		})
	}
	return st.editDefaults(func(s *object) error {
		p, err := childObject(s, "permissions", errInvalidPermissions)
		if err != nil {
			return err
		}
		p.set("defaultMode", mode)
		return nil
	})
}

// SetDefaultEffort 设默认思考强度（level 为空是删掉）。model 是完整模型 ID 时改 modelSettings.<model>.effortLevel，和 /effort
// 存为默认一样，这个模型下别的键原样留着，删空了连这个模型一起删，modelSettings 空了也删；model 为空时改顶层的 effortLevel。
func (st *Store) SetDefaultEffort(model, level string) (Defaults, error) {
	if st.IsAgy() {
		return Defaults{}, locales.Err("errAgyConfigUnsupported")
	}
	return st.editDefaults(func(s *object) error {
		if model == "" {
			setOrDelete(s, "effortLevel", optional(level))
			return nil
		}
		bad := locales.Err("cfgModelSettingsInvalid")
		all, err := childObject(s, "modelSettings", bad)
		if err != nil {
			return err
		}
		entry, err := childObject(all, model, bad)
		if err != nil {
			return err
		}
		setOrDelete(entry, "effortLevel", optional(level))
		if entry.len() == 0 {
			all.del(model)
		}
		if all.len() == 0 {
			s.del("modelSettings")
		}
		return nil
	})
}

// optional 把空串当没有。
func optional(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// FormatTokens：1234 → 1.2K，123456 → 123K，1234567 → 1.23M。
func FormatTokens(v int64) string {
	v = max(v, 0)
	switch {
	case v >= 999_500:
		return trimZeros(strconv.FormatFloat(float64(v)/1e6, 'f', 2, 64)) + "M"
	case v >= 99_950:
		return trimZeros(strconv.FormatFloat(float64(v)/1e3, 'f', 0, 64)) + "K"
	case v >= 1_000:
		return trimZeros(strconv.FormatFloat(float64(v)/1e3, 'f', 1, 64)) + "K"
	}
	return strconv.FormatInt(v, 10)
}

func trimZeros(s string) string {
	if !strings.Contains(s, ".") {
		return s
	}
	return strings.TrimSuffix(strings.TrimRight(s, "0"), ".")
}
