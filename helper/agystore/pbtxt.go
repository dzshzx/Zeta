// Package agystore 读写 Antigravity CLI（agy）保存在 ~/.gemini/antigravity-cli 下的会话记录、摘要、批注与子代理日志，
// 并按 /proc/<pid>/fd 里的 presence/<id>.lock 识别正在运行的 agy 进程。
package agystore

import (
	"bytes"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Annotation 是 ~/.gemini/antigravity-cli/annotations/<id>.pbtxt 里 Zeta 用到的用户批注字段。
type Annotation struct {
	Title    string
	Archived bool
	Pinned   bool
}

// AnnotationPath 是会话 id 的 pbtxt 批注文件路径。
func AnnotationPath(agyDir, id string) string {
	return filepath.Join(agyDir, "annotations", id+".pbtxt")
}

// ReadAnnotation 解析 annotations/<id>.pbtxt 里的 title、archived、pinned；文件不在或读不了时返回零值。
func ReadAnnotation(agyDir, id string) Annotation {
	data, err := os.ReadFile(AnnotationPath(agyDir, id))
	if err != nil {
		return Annotation{}
	}
	return parseAnnotation(string(data))
}

func parseAnnotation(text string) Annotation {
	var a Annotation
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		switch {
		case strings.HasPrefix(line, "title:"):
			if v, ok := parsePbtxtString(strings.TrimSpace(strings.TrimPrefix(line, "title:"))); ok {
				a.Title = v
			}
		case line == "archived: true":
			a.Archived = true
		case line == "pinned: true":
			a.Pinned = true
		}
	}
	return a
}

// SetAnnotationTitle 在 annotations/<id>.pbtxt 里写入或替换 title，保留文件里的其余字段（tags、pinned 等）。
func SetAnnotationTitle(agyDir, id, title string) error {
	dir := filepath.Join(agyDir, "annotations")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	path := filepath.Join(dir, id+".pbtxt")
	existing, _ := os.ReadFile(path)
	updated := replacePbtxtField(string(existing), "title", quotePbtxt(title))
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(updated), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func replacePbtxtField(content, key, valueLiteral string) string {
	prefix := key + ":"
	var kept []string
	replaced := false
	for _, line := range strings.Split(content, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, prefix) {
			if !replaced && valueLiteral != "" {
				kept = append(kept, key+": "+valueLiteral)
				replaced = true
			}
			continue
		}
		if trimmed != "" {
			kept = append(kept, line)
		}
	}
	if !replaced && valueLiteral != "" {
		kept = append([]string{key + ": " + valueLiteral}, kept...)
	}
	if len(kept) == 0 {
		return ""
	}
	return strings.Join(kept, "\n") + "\n"
}

func quotePbtxt(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '"':
			b.WriteString(`\"`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}

func parsePbtxtString(s string) (string, bool) {
	if len(s) < 2 || (s[0] != '"' && s[0] != '\'') || s[len(s)-1] != s[0] {
		return "", false
	}
	body := s[1 : len(s)-1]
	var out bytes.Buffer
	for i := 0; i < len(body); {
		if body[i] != '\\' || i+1 >= len(body) {
			out.WriteByte(body[i])
			i++
			continue
		}
		next, step := decodePbtxtEscape(body[i+1:])
		out.Write(next)
		i += 1 + step
	}
	res := out.String()
	if !utf8.ValidString(res) {
		res = strings.ToValidUTF8(res, "\uFFFD")
	}
	return res, true
}

var simplePbtxtEscapes = map[byte]byte{
	'n': '\n', 'r': '\r', 't': '\t', '\\': '\\', '"': '"', '\'': '\'',
}

func decodePbtxtEscape(rest string) ([]byte, int) {
	c := rest[0]
	if c >= '0' && c <= '7' {
		return decodePbtxtOctal(rest)
	}
	if b, ok := simplePbtxtEscapes[c]; ok {
		return []byte{b}, 1
	}
	if c == 'x' || c == 'u' {
		return decodePbtxtHex(rest)
	}
	return []byte{c}, 1
}

func decodePbtxtOctal(rest string) ([]byte, int) {
	end := min(3, len(rest))
	j := 1
	for j < end && rest[j] >= '0' && rest[j] <= '7' {
		j++
	}
	v, _ := strconv.ParseUint(rest[:j], 8, 8)
	return []byte{byte(v)}, j
}

func decodePbtxtHex(rest string) ([]byte, int) {
	if rest[0] == 'x' && len(rest) >= 3 {
		if v, err := strconv.ParseUint(rest[1:3], 16, 8); err == nil {
			return []byte{byte(v)}, 3
		}
	}
	if rest[0] == 'u' && len(rest) >= 5 {
		if v, err := strconv.ParseUint(rest[1:5], 16, 32); err == nil {
			return utf8.AppendRune(nil, rune(v)), 5
		}
	}
	return []byte{rest[0]}, 1
}
