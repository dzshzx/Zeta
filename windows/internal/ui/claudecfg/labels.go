package claudecfg

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo"
)

// 界面上的说法：模型、思考强度、权限模式、权限规则的表、闲置超时、token 数、文件大小。

// fallbackModels 是还没拿到 Claude Code 的模型清单时，默认模型下拉里的几个别名。
var fallbackModels = func() []Model {
	var models []Model
	for _, m := range chat.FallbackModels {
		models = append(models, Model{Value: m.Value, Label: m.DisplayName})
	}
	return models
}()

// option 是下拉里的一项：value 写进配置，label 是按界面当前的语言给人看的说法。
type option struct {
	value string
	label func() string
}

// effortLevels 是思考强度的几档。
var effortLevels = []option{
	{"low", locales.ClaudecfgEffortLow}, {"medium", locales.ClaudecfgEffortMedium}, {"high", locales.ClaudecfgEffortHigh},
	{"xhigh", locales.ClaudecfgEffortXHigh}, {"max", locales.ClaudecfgEffortMax},
}

func effortLabel(v string) string {
	for _, l := range effortLevels {
		if l.value == v {
			return l.label()
		}
	}
	return v
}

// permissionModes 是能设成默认的权限模式。
var permissionModes = []option{
	{"default", locales.ClaudecfgModeDefault}, {"acceptEdits", locales.ClaudecfgModeAcceptEdits},
	{"plan", locales.ClaudecfgModePlan}, {"auto", locales.ClaudecfgModeAuto},
	{"bypassPermissions", locales.ComposerModeBypass},
}

func permissionModeLabel(v string) string {
	for _, m := range permissionModes {
		if m.value == v {
			return m.label()
		}
	}
	return v
}

// compactWindows 是上下文窗口下拉里的几档（token 数）。
var compactWindows = []int64{200_000, 300_000, 400_000, 500_000, 600_000, 800_000, 1_000_000}

var modelID = regexp.MustCompile(`^claude-([a-z]+)-(\d+(?:-\d{1,2})*)(?:-\d{8})?(\[1m\])?$`)

// readableModel 把完整模型 ID（claude-opus-5-5、claude-haiku-4-5-20251001、claude-opus-5-5[1m]）写成人看的名字；认不出的原样。
func readableModel(id string) string {
	m := modelID.FindStringSubmatch(id)
	if m == nil {
		return id
	}
	name := strings.ToUpper(m[1][:1]) + m[1][1:] + " " + strings.ReplaceAll(m[2], "-", ".")
	if m[3] != "" {
		name += " (1M)"
	}
	return name
}

// formatTokens：1234 → 1.2K，400000 → 400K，1000000 → 1M。
func formatTokens(v int64) string {
	trim := func(s string) string {
		if strings.Contains(s, ".") {
			s = strings.TrimSuffix(strings.TrimRight(s, "0"), ".")
		}
		return s
	}
	switch {
	case v >= 999_500:
		return trim(strconv.FormatFloat(float64(v)/1e6, 'f', 2, 64)) + "M"
	case v >= 99_950:
		return trim(strconv.FormatFloat(float64(v)/1e3, 'f', 0, 64)) + "K"
	case v >= 1_000:
		return trim(strconv.FormatFloat(float64(v)/1e3, 'f', 1, 64)) + "K"
	}
	return strconv.FormatInt(max(v, 0), 10)
}

// sizeText 是文件大小。
func sizeText(n int64) string {
	switch {
	case n < 1024:
		return fmt.Sprintf("%d B", n)
	case n < 1<<20:
		return fmt.Sprintf("%.1f KiB", float64(n)/1024)
	}
	return fmt.Sprintf("%.2f MiB", float64(n)/(1<<20))
}

// clipLine 把多行压成一行、截到 n 个字符。
func clipLine(s string, n int) string {
	s = strings.Join(strings.Fields(s), " ")
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

// openZipDialog 用系统的文件对话框选一个 ZIP。
func openZipDialog() (string, []byte, error) {
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{Title: locales.ClaudecfgZipDialogTitle(), Filters: []mygo.FileFilter{{Name: "ZIP", Extensions: []string{"zip"}}}})
	if err != nil || len(paths) == 0 {
		return "", nil, err
	}
	st, err := os.Stat(paths[0])
	if err != nil {
		return "", nil, errors.New(locales.ClaudecfgZipUnreadable())
	}
	if st.Size() > 45<<20 {
		return "", nil, errors.New(locales.ClaudecfgZipTooLarge())
	}
	data, err := os.ReadFile(paths[0])
	if err != nil {
		return "", nil, errors.New(locales.ClaudecfgZipUnreadable())
	}
	return filepath.Base(paths[0]), data, nil
}

// behaviors 是权限下拉里的三张表，按页面上的顺序。
var behaviors = []coreapi.Behavior{coreapi.Allow, coreapi.Ask, coreapi.Deny}

// behaviorLabel 是规则所在的表的叫法；空是没设。
func behaviorLabel(b coreapi.Behavior) string {
	switch b {
	case coreapi.Allow:
		return locales.ClaudecfgBehaviorAllow()
	case coreapi.Ask:
		return locales.ClaudecfgBehaviorAsk()
	case coreapi.Deny:
		return locales.ClaudecfgBehaviorDeny()
	}
	return locales.ClaudecfgBehaviorNone()
}

// idleChoices 是闲置超时可选的几档（分钟），0 是不自动结束；和手机上的设置页一样。
var idleChoices = []int{5, 10, 30, 60, 120, 0}

// idleLabel 是闲置超时的说法：「不自动结束」「30 分钟」「2 小时」。
func idleLabel(minutes int) string {
	switch {
	case minutes == 0:
		return locales.ClaudecfgIdleNever()
	case minutes%1440 == 0:
		return locales.ClaudecfgIdleDays(minutes / 1440)
	case minutes%60 == 0:
		return locales.ClaudecfgIdleHours(minutes / 60)
	}
	return locales.ClaudecfgIdleMinutes(minutes)
}

var mcpNamePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// validMcpName：字母、数字、下划线和连字符，最长 64（添加前在表单里先查一遍，核心还会再查）。
func validMcpName(name string) bool { return mcpNamePattern.MatchString(name) }

// compactPercentLabel 是压缩时机的说法：0 是默认（快满时）。
func compactPercentLabel(p int) string {
	if p == 0 {
		return locales.ClaudecfgCompactAtDefault()
	}
	return locales.ClaudecfgCompactAtPercent(p)
}
