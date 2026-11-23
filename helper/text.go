package main

import (
	"bufio"
	"bytes"
	"io"
	"regexp"
	"strings"
	"unicode/utf8"
)

// fixUTF8 把不是合法 UTF-8 的字节换成替代字符 U+FFFD，按 Unicode 推荐的做法：每段“最长的非法子序列”换一个。
func fixUTF8(b []byte) []byte {
	out := make([]byte, 0, len(b)+16)
	for i := 0; i < len(b); {
		if b[i] < utf8.RuneSelf {
			out = append(out, b[i])
			i++
			continue
		}
		n, ok := utf8Sequence(b[i:])
		if ok {
			out = append(out, b[i:i+n]...)
		} else {
			out = utf8.AppendRune(out, utf8.RuneError)
		}
		i += n
	}
	return out
}

// utf8Sequence 看 b 开头的一个多字节字符：合法时返回它的长度和 true；不合法时返回最长的非法子序列的长度（至少 1）。
func utf8Sequence(b []byte) (int, bool) {
	c := b[0]
	lo, hi := byte(0x80), byte(0xBF)
	var need int
	switch {
	case c >= 0xC2 && c <= 0xDF:
		need = 1
	case c == 0xE0:
		need, lo = 2, 0xA0
	case c == 0xED:
		need, hi = 2, 0x9F
	case c >= 0xE1 && c <= 0xEF:
		need = 2
	case c == 0xF0:
		need, lo = 3, 0x90
	case c == 0xF4:
		need, hi = 3, 0x8F
	case c >= 0xF1 && c <= 0xF3:
		need = 3
	default:
		return 1, false
	}
	for k := 1; k <= need; k++ {
		if k >= len(b) || b[k] < lo || b[k] > hi {
			return k, false
		}
		lo, hi = 0x80, 0xBF
	}
	return need + 1, true
}

// firstInvalidUTF8 是第一个非法序列开始的位置；全部合法时为 -1。
func firstInvalidUTF8(b []byte) int {
	for i := 0; i < len(b); {
		if b[i] < utf8.RuneSelf {
			i++
			continue
		}
		n, ok := utf8Sequence(b[i:])
		if !ok {
			return i
		}
		i += n
	}
	return -1
}

func decodeString(b []byte) string {
	if utf8.Valid(b) {
		return string(b)
	}
	return string(fixUTF8(b))
}

func runeLen(s string) int { return utf8.RuneCountInString(s) }

// runePrefix 是前 n 个字符。
func runePrefix(s string, n int) string {
	if len(s) <= n {
		return s
	}
	count := 0
	for i := range s {
		if count == n {
			return s[:i]
		}
		count++
	}
	return s
}

// dropRunes 去掉最后 n 个字符；不够 n 个时是空串。
func dropRunes(s string, n int) string {
	end := len(s)
	for k := 0; k < n; k++ {
		if end == 0 {
			return ""
		}
		_, size := utf8.DecodeLastRuneInString(s[:end])
		end -= size
	}
	return s[:end]
}

// squash 把连续的空白（含换行）折成一个空格，去掉首尾空白。
func squash(s string) string { return strings.Join(strings.Fields(s), " ") }

// oneLine 折成一行，超过 limit 个字符时截断加“…”。
func oneLine(s string, limit int) string {
	text := squash(s)
	if runeLen(text) <= limit {
		return text
	}
	return runePrefix(text, limit) + "…"
}

// space 是 Unicode 的空白，与 strings.TrimSpace、strings.Fields 同一口径。
const space = `[\t-\r \x{85}\pZ]`

var (
	commandName = regexp.MustCompile(`(?s)<command-name>` + space + `*(.*?)` + space + `*</command-name>`)
	commandArgs = regexp.MustCompile(`(?s)<command-args>` + space + `*(.*?)` + space + `*</command-args>`)
	commandOut  = regexp.MustCompile(`(?s)<local-command-(?:stdout|stderr)>(.*?)</local-command-(?:stdout|stderr)>`)
	// 终端里 /model 的输出（2.1.330）：Set model to `Fable 5.2` for this session only，后面的说法随存不存成默认、带不带思考强度而变
	modelSet   = regexp.MustCompile("^Set model to `([^`]+)`")
	bashInput  = regexp.MustCompile(`(?s)<bash-input>(.*?)</bash-input>`)
	ansiEscape = regexp.MustCompile(`\x1b\[[0-9;?]*[A-Za-z]`)
	// 消息开头的附件行（见 Kotlin 的 AttachedPaths）：复制进来的文件 [Attached file: 路径]，
	// 引用的路径 [Attached file: @"路径"] 和 [Attached folder: @"路径"]
	attachedLine = regexp.MustCompile(`^\[Attached (?:file|folder): (?:@"(.+)"|(.+))\]$`)
)

// injectedPrefixes 开头的用户消息是系统注入的，不是用户亲手输入的。
var injectedPrefixes = []string{"<command-name>", "<local-command", "<system-reminder>", "<task-notification>", "Caveat:", "[Request interrupted", "<bash-stdout>", "<bash-stderr>"}

// userText 是用户亲手输入的文字（斜杠命令还原成 /名字 参数，! 命令还原成 ! 命令）；工具结果、系统注入的内容、! 命令的输出是空串。
func userText(content any) string {
	text := rawUserText(content)
	if strings.HasPrefix(text, "<bash-input>") {
		if m := bashInput.FindStringSubmatch(text); m != nil {
			return "! " + strings.TrimSpace(m[1])
		}
		return ""
	}
	if strings.HasPrefix(text, "<command-name>") {
		name := commandName.FindStringSubmatch(text)
		if name == nil {
			return ""
		}
		command := name[1]
		if !strings.HasPrefix(command, "/") {
			command = "/" + command
		}
		args := ""
		if m := commandArgs.FindStringSubmatch(text); m != nil {
			args = m[1]
		}
		return strings.TrimSpace(command + " " + args)
	}
	for _, p := range injectedPrefixes {
		if strings.HasPrefix(text, p) {
			return ""
		}
	}
	return text
}

// peerMessage 是另一个 Claude Code 会话发来的消息（会话互通）：Claude Code 把它记成 queued_command 附件，
// prompt 是 <cross-session-message from=… from-name=… from-mode=…>正文</cross-session-message>，
// origin 里有结构化的 kind（peer）、name（发信会话的名字）和 body（正文，较早的版本没有，从 prompt 里剥）。
// 不是这种附件时 ok 为假。
func peerMessage(attachment obj) (name, body string, ok bool) {
	origin := asObj(attachment["origin"])
	if attachment["type"] != "queued_command" || origin["kind"] != "peer" {
		return "", "", false
	}
	body = asString(origin["body"])
	if body == "" {
		body = asString(attachment["prompt"])
		if m := peerWrapper.FindStringSubmatch(body); m != nil {
			body = m[1]
		}
	}
	return asString(origin["name"]), strings.TrimSpace(body), true
}

// peerWrapper 是会话互通消息在 prompt 里的外壳，取出里面的正文。
var peerWrapper = regexp.MustCompile(`(?s)^\s*<cross-session-message[^>]*>(.*?)</cross-session-message>\s*$`)

// rawUserText 是用户消息里的文字块连起来、去掉首尾空白，不做别的处理。
func rawUserText(content any) string {
	var text string
	switch c := content.(type) {
	case string:
		text = c
	case []any:
		var parts []string
		for _, b := range c {
			if m, ok := b.(obj); ok && m["type"] == "text" {
				parts = append(parts, asString(m["text"]))
			}
		}
		text = strings.Join(parts, "\n")
	}
	return strings.TrimSpace(text)
}

// shellText 是 ! 命令的那两行（<bash-input>…、<bash-stdout>…），原样交给 Zeta 画成命令步骤；别的是空串。
func shellText(content any) string {
	text := rawUserText(content)
	if strings.HasPrefix(text, "<bash-input>") || strings.HasPrefix(text, "<bash-stdout>") || strings.HasPrefix(text, "<bash-stderr>") {
		return text
	}
	return ""
}

// attachedPath 是附件行里的路径；不是附件行时 ok 为假。
func attachedPath(line string) (string, bool) {
	m := attachedLine.FindStringSubmatch(line)
	if m == nil {
		return "", false
	}
	if m[1] != "" {
		return m[1], true
	}
	return m[2], true
}

// attachedName 是附件行里路径的最后一段（根目录是 "/"）；不是附件行时 ok 为假。
func attachedName(line string) (string, bool) {
	p, ok := attachedPath(line)
	if !ok {
		return "", false
	}
	if name := baseName(strings.TrimRight(p, "/")); name != "" {
		return name, true
	}
	return "/", true
}

// imageCount 是消息内容里的图片块数。
func imageCount(content any) int {
	n := 0
	for _, b := range asList(content) {
		if m, ok := b.(obj); ok && m["type"] == "image" {
			n++
		}
	}
	return n
}

// hasToolResult：内容是数组且其中有工具结果块。
func hasToolResult(content any) bool {
	for _, b := range asList(content) {
		if m, ok := b.(obj); ok && m["type"] == "tool_result" {
			return true
		}
	}
	return false
}

// lineReader 一行一行地读，每行带着末尾的换行（最后一行可能没有）。行多长都行。
type lineReader struct {
	r   *bufio.Reader
	buf []byte
	err error
}

func newLineReader(r io.Reader) *lineReader { return newLineReaderSize(r, 256*1024) }

// newLineReaderSize：缓冲 size 字节（一次从 r 读这么多；比它长的行照样读得出）。
func newLineReaderSize(r io.Reader, size int) *lineReader {
	return &lineReader{r: bufio.NewReaderSize(r, size)}
}

// next 返回下一行；读完或出错时为 false，出错的原因在 err（读完不算出错）。返回的切片下次调用后失效。
func (l *lineReader) next() ([]byte, bool) {
	chunk, err := l.r.ReadSlice('\n')
	if err == nil {
		return chunk, true
	}
	l.buf = append(l.buf[:0], chunk...)
	for err == bufio.ErrBufferFull {
		chunk, err = l.r.ReadSlice('\n')
		l.buf = append(l.buf, chunk...)
	}
	if err != nil && err != io.EOF {
		l.err = err
		return nil, false
	}
	return l.buf, len(l.buf) > 0
}

// has 在一行的原始字节里找一段 ASCII 文字（非法 UTF-8 换成替代字符不会影响 ASCII 子串在不在）。
func has(line []byte, s string) bool { return bytes.Contains(line, []byte(s)) }

func hasAny(line []byte, subs ...string) bool {
	for _, s := range subs {
		if has(line, s) {
			return true
		}
	}
	return false
}
