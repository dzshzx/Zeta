package usage

import (
	"strconv"
	"strings"
)

// CompactNotice 是 compact_boundary 事件（一整行）在聊天里显示的提示。只写压缩前的大小：它是实测数；
// post_tokens 不含系统提示和工具，写成「→ 7.6K」会让人以为上下文只剩这么多。压缩后的占用看用量弹窗。
func CompactNotice(line []byte) string {
	var e event
	decode(line, &e)
	label := "上下文已压缩"
	m := e.CompactMetadata
	if m != nil && m.Trigger == "auto" {
		label = "上下文已自动压缩"
	}
	if m == nil || m.PreTokens == nil {
		return label
	}
	return label + "（压缩前 " + FormatTokens(*m.PreTokens) + "）"
}

// FormatTokens：1234 → 1.2K，26618 → 26.6K，123456 → 123K，1234567 → 1.23M。十万以内留一位小数，压缩前后差几百也看得出来。
func FormatTokens(value int64) string {
	v := max(value, 0)
	switch {
	case v >= 999_500:
		return trimZeros(fixed(float64(v)/1_000_000, 2)) + "M"
	case v >= 99_950:
		return trimZeros(fixed(float64(v)/1_000, 0)) + "K"
	case v >= 1_000:
		return trimZeros(fixed(float64(v)/1_000, 1)) + "K"
	}
	return strconv.FormatInt(v, 10)
}

func trimZeros(text string) string {
	if !strings.Contains(text, ".") {
		return text
	}
	return strings.TrimRight(strings.TrimRight(text, "0"), ".")
}

// FormatExactTokens 是精确数，千位分隔。
func FormatExactTokens(value int64) string {
	digits := strconv.FormatInt(max(value, 0), 10)
	var b strings.Builder
	for i, c := range digits {
		if i > 0 && (len(digits)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(c)
	}
	return b.String()
}

// FormatCost 是美元数：不到 1 美元留四位小数，否则两位。
func FormatCost(usd float64) string {
	switch {
	case usd <= 0:
		return "$0"
	case usd < 1:
		return "$" + fixed(usd, 4)
	}
	return "$" + fixed(usd, 2)
}

// fixed 把不小于 0 的 x 写成 places 位小数，进位和 Kotlin（Java）的 String.format 一样：取能还原 x 的最短十进制写法，
// 再在那一位四舍五入（1.25 → 1.3）。strconv 按二进制的精确值、逢五取偶，个别数会差一位。
func fixed(x float64, places int) string {
	whole, frac, _ := strings.Cut(strconv.FormatFloat(x, 'f', -1, 64), ".")
	if len(frac) <= places {
		frac += strings.Repeat("0", places-len(frac))
		return join(whole, frac)
	}
	up := frac[places] >= '5'
	digits := []byte(whole + frac[:places])
	for i := len(digits) - 1; up && i >= 0; i-- {
		if digits[i] == '9' {
			digits[i] = '0'
			continue
		}
		digits[i]++
		up = false
	}
	s := string(digits)
	if up {
		s = "1" + s
	}
	return join(s[:len(s)-places], s[len(s)-places:])
}

func join(whole, frac string) string {
	if frac == "" {
		return whole
	}
	return whole + "." + frac
}
