package claudecfg

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/egoist/mygo"
)

// 界面上的说法：模型、思考强度、权限模式、token 数、文件大小。

// fallbackModels 是还没拿到 Claude Code 的模型清单时，默认模型下拉里的几个别名。
var fallbackModels = []Model{{"opus", "Opus"}, {"sonnet", "Sonnet"}, {"haiku", "Haiku"}, {"fable", "Fable"}}

// effortLevels 是思考强度的几档。
var effortLevels = []struct{ value, label string }{
	{"low", "低"}, {"medium", "中"}, {"high", "高"}, {"xhigh", "超高"}, {"max", "最高"},
}

func effortLabel(v string) string {
	for _, l := range effortLevels {
		if l.value == v {
			return l.label
		}
	}
	return v
}

// permissionModes 是能设成默认的权限模式（完全放行 Claude Code 不肯存成默认，不列）。
var permissionModes = []struct{ value, label string }{
	{"default", "逐项确认"}, {"acceptEdits", "自动接受编辑"}, {"plan", "计划模式"}, {"auto", "自动判断"},
}

func permissionModeLabel(v string) string {
	for _, m := range permissionModes {
		if m.value == v {
			return m.label
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
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{Title: "选择 Skill 包", Filters: []mygo.FileFilter{{Name: "ZIP", Extensions: []string{"zip"}}}})
	if err != nil || len(paths) == 0 {
		return "", nil, err
	}
	st, err := os.Stat(paths[0])
	if err != nil {
		return "", nil, fmt.Errorf("读不了这个文件")
	}
	if st.Size() > 45<<20 {
		return "", nil, fmt.Errorf("文件超过 45 MB，不像是一个 Skill 包")
	}
	data, err := os.ReadFile(paths[0])
	if err != nil {
		return "", nil, fmt.Errorf("读不了这个文件")
	}
	return filepath.Base(paths[0]), data, nil
}

func itoa(n int) string { return strconv.Itoa(n) }
