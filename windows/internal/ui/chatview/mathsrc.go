package chatview

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// mathSource 把回答里的公式写法换成 mygo 的 Markdown 认的，哪些算公式照手机版
// （shared 的 TexMathDelimiters 和 MarkdownMathParser 的 ChatMathSequentialParser）：
//
//   - 单个 $ 包的行内公式：左边的 $ 后面不是空白、前面不是英文字母、数字、_ 或 $；右边的 $ 前面不是空白、后面不是英文字母、数字、_ 或 $；
//     中间不全是空白；而且只和紧接着的下一个 $ 配，那个配不上（像「$4 和 $x$」里 4 后面那个）就放弃这个开头，
//     免得金额吞掉后面真正的公式。配上的换成 \( \)；mygo 不开单个 $ 的公式（ExtDollarMath），配不上的 $ 就是字。
//     mygo 自己的判定和这不同：$ 后面紧跟数字的（$2x$）它不算，配不上时它会接着往后找。
//   - \[ 只有在行首、并且配得上行尾的 \] 时才是块公式；别处的 \[（比如 arr\[0\]）换成 &#91;，显示成方括号
//     （mygo 会把行中间的 \[…\] 也当公式）。\( \) 和 $$ 照 mygo 的。
//   - 代码围栏、行内代码、链接（[文字](地址)、[文字][引用]）里的不动，反斜杠转义的 \$ 是字。
//     $ 只在同一块里配：空行、围栏、列表项、标题、引用、表格的行和格子之间不配。
//
// 不像手机版那样等长替换：mygo 的行内公式只有 \( \) 一种写法，所以配上的公式比原文长两个字。
func mathSource(src string) string {
	if !strings.Contains(src, "$") && !strings.Contains(src, `\[`) {
		return src
	}
	s := &mathScan{src: src}
	s.scan()
	s.pair()
	if len(s.edits) == 0 {
		return src
	}
	var b strings.Builder
	b.Grow(len(src) + 2*len(s.edits))
	at := 0
	for _, e := range s.edits {
		b.WriteString(src[at:e.at])
		b.WriteString(e.text)
		at = e.at + e.n
	}
	b.WriteString(src[at:])
	return b.String()
}

// dollar 是一个 $ 记号（n 是 1 或 2；\( 和 \) 也记成 2，同手机版先把它们换成 $$），block 是它所在的块。
type dollar struct {
	at, n, block int
}

type edit struct {
	at, n int
	text  string
}

type mathScan struct {
	src     string
	dollars []dollar
	edits   []edit // 按位置排好
	block   int
	bracket []int // 不算块公式、要换掉的 \[ 的位置
}

// scan 逐行走一遍：跳过围栏，块的边界处换块号，行里记下 $、\(、\) 和要换掉的 \[。
func (s *mathScan) scan() {
	var fence string
	for start := 0; start < len(s.src); {
		end := strings.IndexByte(s.src[start:], '\n')
		if end < 0 {
			end = len(s.src)
		} else {
			end += start
		}
		line := s.src[start:end]
		open := fence
		fence = fenceAfter(open, line)
		switch {
		case open != "" || fence != "":
			s.block++ // 围栏的开头、结尾和里面整行跳过
		case strings.TrimSpace(line) == "":
			s.block++
		default:
			if startsBlock(line) {
				s.block++
			}
			start = s.line(start, end)
		}
		start = max(start, end) + 1
	}
}

// line 走一行（start 到 end），返回走到哪里（\[…\] 块公式会跨过几行）。
func (s *mathScan) line(start, end int) int {
	src := s.src
	table := strings.HasPrefix(strings.TrimLeft(src[start:end], " "), "|")
	for i := start; i < end; {
		switch c := src[i]; {
		case c == '`':
			run := runOf(src, i, '`')
			if e := codeSpanEnd(src, i+run, run); e >= 0 {
				i = e
				end = max(end, lineEnd(src, i)) // 行内代码可以跨行
			} else {
				i += run
			}
		case c == '\\' && i+1 < len(src) && src[i+1] == '(':
			if e := closeOf(src, i+2, ')', false); e >= 0 {
				s.dollars = append(s.dollars, dollar{i, 2, s.block}, dollar{e, 2, s.block})
				i = e + 2
				end = max(end, lineEnd(src, i))
			} else {
				i += 2
			}
		case c == '\\' && i+1 < len(src) && src[i+1] == '[':
			if strings.TrimSpace(src[start:i]) == "" {
				if e := closeOf(src, i+2, ']', true); e >= 0 && strings.TrimSpace(src[e+2:lineEnd(src, e+2)]) == "" {
					s.dollars = append(s.dollars, dollar{i, 2, s.block}, dollar{e, 2, s.block})
					s.block++
					return lineEnd(src, e+2)
				}
			}
			s.bracket = append(s.bracket, i)
			i += 2
		case c == '\\':
			i += 2 // 其余转义（含 \\、\$）整个跳过
		case c == '[':
			if e := linkEnd(src, i, end); e > i {
				i = e
			} else {
				i++
			}
		case c == '|' && table:
			s.block++
			i++
		case c == '$':
			run := runOf(src, i, '$')
			if run <= 2 {
				s.dollars = append(s.dollars, dollar{i, run, s.block})
			}
			i += run
		default:
			i++
		}
	}
	return end
}

// pair 照手机版的 matchDollarPairs 从左到右配对，配上的单个 $ 记成要换的；再把要换的 \[ 加进去，按位置排好。
func (s *mathScan) pair() {
	ds := s.dollars
	var pairs []edit
	for i := 0; i < len(ds); {
		op := ds[i]
		if op.n == 2 { // $$：配下一个 $$（中间的单个 $ 不算），配上的整段跳过
			j := i + 1
			for j < len(ds) && ds[j].n != 2 {
				j++
			}
			if j < len(ds) && ds[j].block == op.block && strings.TrimSpace(s.src[op.at+2:ds[j].at]) != "" {
				i = j + 1
			} else {
				i++
			}
			continue
		}
		if j := i + 1; j < len(ds) && s.canOpen(op) && ds[j].n == 1 && ds[j].block == op.block && s.canClose(ds[j]) &&
			strings.TrimSpace(s.src[op.at+1:ds[j].at]) != "" {
			pairs = append(pairs, edit{op.at, 1, `\(`}, edit{ds[j].at, 1, `\)`})
			i = j + 1
			continue
		}
		i++
	}
	// 两张表各自按位置排好，并起来；被配上的公式盖住的 \[ 是公式里的字，不换
	b := 0
	for _, p := range pairs {
		for b < len(s.bracket) && s.bracket[b] < p.at {
			if !s.inside(pairs, s.bracket[b]) {
				s.edits = append(s.edits, edit{s.bracket[b], 2, "&#91;"})
			}
			b++
		}
		s.edits = append(s.edits, p)
	}
	for ; b < len(s.bracket); b++ {
		if !s.inside(pairs, s.bracket[b]) {
			s.edits = append(s.edits, edit{s.bracket[b], 2, "&#91;"})
		}
	}
}

// inside 是 at 在不在配上的一对 $ 之间。
func (s *mathScan) inside(pairs []edit, at int) bool {
	for k := 0; k+1 < len(pairs); k += 2 {
		if pairs[k].at < at && at < pairs[k+1].at {
			return true
		}
	}
	return false
}

func (s *mathScan) canOpen(d dollar) bool {
	next, _ := utf8.DecodeRuneInString(s.src[d.at+1:])
	if d.at+1 >= len(s.src) || unicode.IsSpace(next) {
		return false
	}
	prev, _ := utf8.DecodeLastRuneInString(s.src[:d.at])
	return d.at == 0 || !asciiWordOrDollar(prev)
}

func (s *mathScan) canClose(d dollar) bool {
	prev, _ := utf8.DecodeLastRuneInString(s.src[:d.at])
	if d.at == 0 || unicode.IsSpace(prev) {
		return false
	}
	next, _ := utf8.DecodeRuneInString(s.src[d.at+1:])
	return d.at+1 >= len(s.src) || !asciiWordOrDollar(next)
}

func asciiWordOrDollar(r rune) bool {
	return r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '$'
}

// startsBlock 是这一行开始一个新块：标题、引用、列表项、表格的行。
func startsBlock(line string) bool {
	t := strings.TrimLeft(line, " ")
	if len(line)-len(t) > 3 && !strings.HasPrefix(t, "-") && !strings.HasPrefix(t, "*") && !strings.HasPrefix(t, "+") {
		return false // 缩进的接续行
	}
	switch {
	case t == "":
		return false
	case t[0] == '#' || t[0] == '>' || t[0] == '|':
		return true
	case (t[0] == '-' || t[0] == '*' || t[0] == '+') && (len(t) == 1 || t[1] == ' '):
		return true
	}
	n := 0
	for n < len(t) && n < 9 && t[n] >= '0' && t[n] <= '9' {
		n++
	}
	return n > 0 && n < len(t) && (t[n] == '.' || t[n] == ')') && (n+1 == len(t) || t[n+1] == ' ')
}

// fenceAfter 是看完这一行后开着的代码围栏（同手机版 TexMathDelimiters.fenceAfterLine）。
func fenceAfter(open, line string) string {
	marker := fenceMarker(line)
	if open == "" {
		return marker
	}
	if marker != "" && marker[0] == open[0] && len(marker) >= len(open) && strings.TrimSpace(line) == marker {
		return ""
	}
	return open
}

func fenceMarker(line string) string {
	t := strings.TrimLeft(line, " ")
	if len(line)-len(t) > 3 || t == "" || t[0] != '`' && t[0] != '~' {
		return ""
	}
	if run := runOf(t, 0, t[0]); run >= 3 {
		return t[:run]
	}
	return ""
}

func runOf(s string, i int, c byte) int {
	n := 0
	for i+n < len(s) && s[i+n] == c {
		n++
	}
	return n
}

func lineEnd(s string, i int) int {
	if e := strings.IndexByte(s[i:], '\n'); e >= 0 {
		return i + e
	}
	return len(s)
}

// blankAfter 是 s[nl] 这个换行之后是不是空行。
func blankAfter(s string, nl int) bool {
	return strings.TrimSpace(s[nl+1:lineEnd(s, nl+1)]) == ""
}

// codeSpanEnd 是从 from 起和开头同样长的反引号串之后的位置；不跨空行，找不到时 -1（开头的反引号只是字）。
func codeSpanEnd(s string, from, run int) int {
	for i := from; i < len(s); {
		switch {
		case s[i] == '\n' && blankAfter(s, i):
			return -1
		case s[i] == '`':
			n := runOf(s, i, '`')
			if n == run {
				return i + n
			}
			i += n
		default:
			i++
		}
	}
	return -1
}

// closeOf 找没转义的 \ + closer（同手机版 TexMathDelimiters.findClose）：里面不能全是空白；
// 行内的不跨空行，块的可以；都不跨进代码围栏。找不到时 -1。
func closeOf(s string, from int, closer byte, blankLines bool) int {
	for i := from; i < len(s)-1; {
		switch {
		case s[i] == '\n':
			if !blankLines && blankAfter(s, i) || fenceMarker(s[i+1:lineEnd(s, i+1)]) != "" {
				return -1
			}
			i++
		case s[i] == '\\' && s[i+1] == closer:
			if strings.TrimSpace(s[from:i]) == "" {
				return -1
			}
			return i
		case s[i] == '\\':
			i += 2
		default:
			i++
		}
	}
	return -1
}

// linkEnd 是从 [ 起的链接（[文字](地址) 或 [文字][引用]）之后的位置，不是链接时返回 at；只在这一行里找（end 之前）。
func linkEnd(s string, at, end int) int {
	text := matching(s, at, end, '[', ']')
	if text < 0 || text+1 >= end {
		return at
	}
	switch s[text+1] {
	case '(':
		if e := matching(s, text+1, end, '(', ')'); e >= 0 {
			return e + 1
		}
	case '[':
		if e := matching(s, text+1, end, '[', ']'); e >= 0 {
			return e + 1
		}
	}
	return at
}

// matching 是 s[at] 这个 open 配对的 close 的位置（算嵌套，跳过转义），找不到时 -1。
func matching(s string, at, end int, open, close byte) int {
	depth := 0
	for i := at; i < end; i++ {
		switch s[i] {
		case '\\':
			i++
		case open:
			depth++
		case close:
			if depth--; depth == 0 {
				return i
			}
		}
	}
	return -1
}
