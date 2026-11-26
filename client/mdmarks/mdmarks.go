// Package mdmarks 扫出输入框里的 Markdown 写法，给输入框加显示样式用：标记符号原样留在文字里、颜色调淡，
// 标记包住的内容按 Markdown 显示。只管显示，发出去的还是原文。认的是常用的一小部分，按行扫描，
// 不求和回答里的 Markdown 渲染完全一致。
//
// 手机版在 Kotlin 里有同一套算法（shared 的 ui/chat/ComposerMarkdown.kt），两边都跑 testdata/cases.json，
// 改规则时两边一起改、用例加在那份文件里。
package mdmarks

import (
	"regexp"
	"strings"
	"unicode"
)

// Kind 是一段文字的样式。
type Kind string

// 各种样式。Marker 是标记符号本身（调淡），其余是被包住的内容或整行。
const (
	Marker    Kind = "marker"
	Bold      Kind = "bold"
	Italic    Kind = "italic"
	Strike    Kind = "strike"
	Code      Kind = "code"
	CodeBlock Kind = "codeBlock"
	Heading   Kind = "heading"
	Quote     Kind = "quote"
	Bullet    Kind = "bullet"
)

// Span 是一段要加样式的文字，Start、End 按 Unicode 码位数（rune）算，左闭右开。
type Span struct {
	Kind       Kind
	Start, End int
}

var (
	headingPrefix = regexp.MustCompile(`^ {0,3}#{1,6}( |$)`)
	quotePrefix   = regexp.MustCompile(`^ {0,3}>`)
	bulletPrefix  = regexp.MustCompile(`^\s*([-*+]|\d{1,9}[.)]) `)
)

// delims 是强调的写法，按这个顺序找：加粗在斜体前，`**a *b* c**` 里的单星号才认成里面的斜体。
var delims = []struct {
	mark string
	kind Kind
}{
	{"**", Bold}, {"__", Bold}, {"~~", Strike}, {"*", Italic}, {"_", Italic},
}

// Scan 扫出整段文字里要加样式的区间（不排序）。区间可以重叠（加粗里套斜体、
// 标记符号在加粗区间里面）：显示时先叠内容的样式，最后叠 Marker。
func Scan(text string) []Span {
	var out []Span
	fence := ""
	offset := 0
	for _, line := range strings.Split(text, "\n") {
		runes := []rune(line)
		end := offset + len(runes)
		// 代码块整行算，开、关围栏的那两行整行调淡；还没写闭合围栏时一直算到末尾（正在输入）
		next := FenceAfter(fence, line)
		switch {
		case next != fence:
			out = append(out, Span{CodeBlock, offset, end}, Span{Marker, offset, end})
		case fence != "":
			out = append(out, Span{CodeBlock, offset, end})
		default:
			out = lineSpans(runes, line, offset, out)
		}
		fence = next
		offset = end + 1
	}
	return out
}

// lineSpans 是代码块外的一行：行首的标题、引用、列表记号，再是行内代码和强调。
func lineSpans(runes []rune, line string, offset int, out []Span) []Span {
	n := len(runes)
	if m := headingPrefix.FindStringSubmatchIndex(line); m != nil {
		out = append(out, Span{Heading, offset, offset + n}, Span{Marker, offset, offset + runeLen(line[:m[2]])})
	}
	if m := quotePrefix.FindStringIndex(line); m != nil {
		out = append(out, Span{Quote, offset, offset + n}, Span{Marker, offset, offset + runeLen(line[:m[1]])})
	}
	if m := bulletPrefix.FindStringSubmatchIndex(line); m != nil {
		out = append(out, Span{Bullet, offset + runeLen(line[:m[2]]), offset + runeLen(line[:m[3]])})
	}
	busy := make([]bool, n) // 行内代码占着的字：代码里的星号、下划线是原文
	for _, c := range codeSpans(runes) {
		s, e, run := c[0], c[1], c[2]
		for i := s; i < e; i++ {
			busy[i] = true
		}
		out = append(out, Span{Code, offset + s, offset + e}, Span{Marker, offset + s, offset + s + run}, Span{Marker, offset + e - run, offset + e})
	}
	for _, d := range delims {
		w := len(d.mark) // 标记都是 ASCII
		for _, r := range emphasis(runes, busy, []rune(d.mark)) {
			out = append(out, Span{d.kind, offset + r[0], offset + r[1]},
				Span{Marker, offset + r[0], offset + r[0] + w}, Span{Marker, offset + r[1] - w, offset + r[1]})
		}
	}
	return out
}

// emphasis 找一种强调写法 mark 包住的各段（含两头的标记），返回 [起点, 终点)。
// 开头的标记后面紧跟非空白，结尾的标记前面紧跟非空白；标记前后不能再是同一个符号（`**` 不当成两个 `*`），
// 也不能是反斜杠转义；下划线两边贴着字母数字的不算（snake_case）。碰到行内代码的不算。
func emphasis(runes []rune, busy []bool, mark []rune) [][2]int {
	var found [][2]int
	w := len(mark)
	for i := 0; i+w < len(runes); i++ {
		if !opens(runes, busy, mark, i) {
			continue
		}
		j := i + w + 1
		for j+w <= len(runes) && !busy[j] && !closes(runes, mark, j) {
			j++
		}
		if j+w <= len(runes) && !busy[j] {
			found = append(found, [2]int{i, j + w})
			j += w
		}
		// 没找到结尾时，i 到 j 之间的开头往后找的范围都在这一段里，也找不到：直接跳过去，整行只扫一遍
		i = j - 1
	}
	return found
}

func opens(runes []rune, busy []bool, mark []rune, i int) bool {
	w := len(mark)
	if !at(runes, mark, i) || busy[i] {
		return false
	}
	c := mark[0]
	if i > 0 && (runes[i-1] == c || runes[i-1] == '\\' || c == '_' && wordRune(runes[i-1])) {
		return false
	}
	next := runes[i+w]
	return !unicode.IsSpace(next) && next != c
}

func closes(runes []rune, mark []rune, j int) bool {
	w := len(mark)
	if !at(runes, mark, j) {
		return false
	}
	c := mark[0]
	prev := runes[j-1]
	if unicode.IsSpace(prev) || prev == c || prev == '\\' {
		return false
	}
	return j+w == len(runes) || runes[j+w] != c && !(c == '_' && wordRune(runes[j+w]))
}

func at(runes []rune, mark []rune, i int) bool {
	if i+len(mark) > len(runes) {
		return false
	}
	for k, r := range mark {
		if runes[i+k] != r {
			return false
		}
	}
	return true
}

func wordRune(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) }

func runeLen(s string) int { return len([]rune(s)) }

// codeSpans 找一行里的行内代码（同回答的渲染）：以等长的反引号串闭合，返回 [起点, 终点, 反引号个数]；
// 找不到闭合时开头的反引号只是字。
func codeSpans(runes []rune) [][3]int {
	var found [][3]int
	for i := 0; i < len(runes); {
		if runes[i] != '`' {
			i++
			continue
		}
		run := runOf(runes, i, '`')
		j := i + run
		for j < len(runes) {
			n := runOf(runes, j, '`')
			if n == run {
				break
			}
			j += max(n, 1)
		}
		if j < len(runes) {
			found = append(found, [3]int{i, j + run, run})
			j += run
		} else {
			j = i + run
		}
		i = j
	}
	return found
}

// FenceAfter 是看完 line 这一行后开着的代码围栏（没开着是 ""）：没开着时这一行可能开启一个；开着时只有同种、
// 不短于开头、后面没别的字的记号才闭合它。回答的渲染（windows 的 chatview）和输入框都用它，同手机版
// TexMathDelimiters.fenceAfterLine。
func FenceAfter(open, line string) string {
	marker := FenceMarker(line)
	if open == "" {
		return marker
	}
	if marker != "" && marker[0] == open[0] && len(marker) >= len(open) && strings.TrimSpace(line) == marker {
		return ""
	}
	return open
}

// FenceMarker 是 line 开头的代码围栏记号（三个以上的 ` 或 ~，前面最多三个空格），不是围栏时 ""。
func FenceMarker(line string) string {
	t := strings.TrimLeft(line, " ")
	if len(line)-len(t) > 3 || t == "" || t[0] != '`' && t[0] != '~' {
		return ""
	}
	n := 0
	for n < len(t) && t[n] == t[0] {
		n++
	}
	if n >= 3 {
		return t[:n]
	}
	return ""
}

func runOf(runes []rune, i int, c rune) int {
	n := 0
	for i+n < len(runes) && runes[i+n] == c {
		n++
	}
	return n
}
