package tasks

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
)

// ansi 是终端的转义序列：CSI（颜色、光标）、OSC（以 BEL 或 ESC \ 结尾）和两个字符的那种。
var ansi = regexp.MustCompile(`\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[@-Z\\-_])`)

// PlainTerminalText 把终端输出转成能直接显示的纯文本：去掉颜色等转义序列；回车覆盖的进度行只留最后一版。
func PlainTerminalText(raw string) string {
	lines := strings.Split(ansi.ReplaceAllString(raw, ""), "\n")
	for i, line := range lines {
		line = strings.TrimSuffix(line, "\r")
		if j := strings.LastIndexByte(line, '\r'); j >= 0 {
			line = line[j+1:]
		}
		lines[i] = line
	}
	return strings.Join(lines, "\n")
}

// FormatElapsed：「3 秒」「2 分 05 秒」「1 小时 03 分」（源语言；按界面的语言用 FormatElapsedIn）。
func FormatElapsed(ms int64) string { return FormatElapsedIn("", ms) }

// FormatElapsedIn 同 FormatElapsed，用语言 lang 说。
func FormatElapsedIn(lang string, ms int64) string {
	seconds := max(ms/1000, 0)
	h, m, s := seconds/3600, seconds%3600/60, seconds%60
	switch {
	case h > 0:
		return locales.M("elapsedHours", "h", strconv.FormatInt(h, 10), "m", fmt.Sprintf("%02d", m)).In(lang)
	case m > 0:
		return locales.M("elapsedMinutes", "m", strconv.FormatInt(m, 10), "s", fmt.Sprintf("%02d", s)).In(lang)
	}
	return locales.M("elapsedSeconds", "s", strconv.FormatInt(s, 10)).In(lang)
}
