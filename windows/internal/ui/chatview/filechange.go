package chatview

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui/editor"
)

// changeKind 是一次改文件的方式。
type changeKind int

const (
	editChange      changeKind = iota // 改了其中几处
	createChange                      // 新建（或还不知道是不是新建的 Write）
	overwriteChange                   // 整份重写已有的文件
)

// fileChange 是一次改文件的工具调用改了什么。
type fileChange struct {
	path  string
	kind  changeKind
	hunks []items.Hunk
	// numbered：行号是文件里的真实行号（Claude Code 给了 structuredPatch）；只按新旧字符串对比时不知道在第几行，不显示行号。
	numbered bool
	// truncated：zeta-helper 截断过过长的参数，对比只到截断处。
	truncated bool
	// replaceAll：Edit 的 replace_all，文件里所有相同的片段都换了。
	replaceAll bool
}

// counts 是增、删的行数和对比的总行数。
func (f *fileChange) counts() (added, removed, total int) {
	for _, h := range f.hunks {
		for _, l := range h.Lines {
			switch l.Kind {
			case items.Added:
				added++
			case items.Removed:
				removed++
			}
		}
		total += len(h.Lines)
	}
	return added, removed, total
}

// label 是改动方式的说明；普通的改动没有。
func (f *fileChange) label() string {
	switch {
	case f.kind == createChange:
		return locales.ChatNewFile()
	case f.kind == overwriteChange:
		return locales.ChatRewritten()
	case f.replaceAll:
		return locales.ChatReplaceAll()
	}
	return ""
}

// clipMarker 是 zeta-helper 截断过长字符串时加的尾巴（不分语言）；只在步骤标着 Clipped 时去掉它，不靠它认截断。
const clipMarker = "\n…"

// contextLines：自己对比新旧字符串时，改动前后留几行没变的。
const contextLines = 3

// isFileEdit 报告工具会改文件、能画成对比。
func isFileEdit(name string) bool {
	switch name {
	case "Edit", "MultiEdit", "Write", "edit_file", "write_file", "replace_file_content", "multi_replace_file_content", "write_to_file":
		return true
	}
	return false
}

func isWriteTool(name string) bool {
	return name == "Write" || name == "write_file" || name == "write_to_file"
}

func filePathOf(s items.Step) string {
	return firstNonBlank(s.Str("file_path"), s.Str("Path"), s.Str("TargetFile"), s.Str("path"))
}

func fileContentOf(s items.Step) (string, bool) {
	for _, k := range []string{"content", "Content", "CodeContent"} {
		if v, ok := s.Input[k].(string); ok {
			return v, true
		}
	}
	return "", false
}

func editPairOf(s items.Step) (old, nw string, ok bool) {
	for _, pair := range [][2]string{{"old_string", "new_string"}, {"Target", "Replacement"}, {"TargetContent", "ReplacementContent"}} {
		if old, okOld := s.Input[pair[0]].(string); okOld {
			if nw, okNew := s.Input[pair[1]].(string); okNew {
				return old, nw, true
			}
		}
	}
	return "", "", false
}

func replaceAllOf(s items.Step) bool {
	return s.Bool("replace_all") || s.Bool("ReplaceAll") || s.Bool("AllowMultiple")
}

// changeOf 从一次工具调用算出文件改动；不是改文件的工具、参数还没到、或者没改成（失败了照普通步骤显示报错）时返回 nil。
// 有核心给的改动就照它画（带真实行号），否则按参数里的新旧字符串自己对比。
func changeOf(s items.Step) *fileChange {
	path := filePathOf(s)
	if !isFileEdit(s.Name) || s.Status == items.ToolFailed || path == "" {
		return nil
	}
	if p := s.Patch; p != nil && (len(p.Hunks) > 0 || p.Created) {
		return patchFileChange(path, s, p)
	}
	switch {
	case isWriteTool(s.Name):
		// 还不知道是不是新建：先当作整份新内容显示，结果到了再换成真实对比
		return newFileChange(path, s)
	case s.Name == "Edit" || s.Name == "edit_file" || s.Name == "replace_file_content":
		old, nw, ok := editPairOf(s)
		if !ok {
			return nil
		}
		f := &fileChange{path: path, kind: editChange, replaceAll: replaceAllOf(s)}
		f.addEdit(old, nw, s.Clipped)
		return f
	default: // MultiEdit / multi_replace_file_content
		return multiEditChange(path, s)
	}
}

func patchFileChange(path string, s items.Step, p *items.Patch) *fileChange {
	switch {
	case isWriteTool(s.Name) && p.Created:
		if fc := newFileChange(path, s); fc != nil {
			return fc
		}
		return &fileChange{path: path, kind: createChange, hunks: p.Hunks, numbered: true}
	case isWriteTool(s.Name):
		return &fileChange{path: path, kind: overwriteChange, hunks: p.Hunks, numbered: true}
	default:
		return &fileChange{path: path, kind: editChange, hunks: p.Hunks, numbered: true, replaceAll: replaceAllOf(s)}
	}
}

func multiEditChange(path string, s items.Step) *fileChange {
	edits, _ := s.Input["edits"].([]any)
	if len(edits) == 0 {
		edits, _ = s.Input["ReplacementChunks"].([]any)
	}
	f := &fileChange{path: path, kind: editChange}
	for _, e := range edits {
		m, _ := e.(map[string]any)
		old, _ := m["old_string"].(string)
		nw, _ := m["new_string"].(string)
		if old == "" && nw == "" {
			old, _ = m["TargetContent"].(string)
			nw, _ = m["ReplacementContent"].(string)
		}
		f.addEdit(old, nw, s.Clipped)
	}
	if len(f.hunks) == 0 {
		return nil
	}
	return f
}

// addEdit 把一处新旧字符串的对比接到改动后面；clipped 是核心截断过参数（items.Step 的 Clipped）。
func (f *fileChange) addEdit(old, nw string, clipped bool) {
	old, clippedOld := unclip(old, clipped)
	nw, clippedNew := unclip(nw, clipped)
	f.truncated = f.truncated || clippedOld || clippedNew
	f.hunks = append(f.hunks, compareLines(old, nw)...)
}

func newFileChange(path string, s items.Step) *fileChange {
	content, ok := fileContentOf(s)
	if !ok {
		return nil
	}
	content, truncated := unclip(content, s.Clipped)
	f := &fileChange{path: path, kind: createChange, numbered: true, truncated: truncated}
	if lines := splitLines(content); len(lines) > 0 {
		h := items.Hunk{Lines: make([]items.Line, len(lines))}
		for i, text := range lines {
			h.Lines[i] = items.Line{Kind: items.Added, Text: text, New: i + 1}
		}
		f.hunks = []items.Hunk{h}
	}
	return f
}

func unclip(s string, clipped bool) (string, bool) {
	if !clipped {
		return s, false
	}
	return strings.CutSuffix(s, clipMarker)
}

// splitLines 按行切开；结尾的换行不算多出一个空行。
func splitLines(text string) []string {
	if text == "" {
		return nil
	}
	return strings.Split(strings.TrimSuffix(text, "\n"), "\n")
}

// compareLines 对比两段文字，切成带上下文的若干段；行号从 1 起，是相对这两段文字的。
// 逐行对比用 mygo 的对比器（git 的 histogram 算法），这里只把它找出的改动连同上下文排成行。
func compareLines(old, nw string) []items.Hunk {
	a, b := splitLines(old), splitLines(nw)
	d := editor.DiffState{Old: editor.NewBuffer(joinLines(a)), New: editor.NewBuffer(joinLines(b))}
	var lines []items.Line
	i, j := 0, 0
	same := func(untilOld, untilNew int) {
		for ; i < untilOld && j < untilNew; i, j = i+1, j+1 {
			lines = append(lines, items.Line{Kind: items.Context, Text: a[i], Old: i + 1, New: j + 1})
		}
	}
	for _, h := range d.Hunks() {
		same(h.OldStart, h.NewStart)
		for ; i < h.OldEnd; i++ {
			lines = append(lines, items.Line{Kind: items.Removed, Text: a[i], Old: i + 1})
		}
		for ; j < h.NewEnd; j++ {
			lines = append(lines, items.Line{Kind: items.Added, Text: b[j], New: j + 1})
		}
	}
	same(len(a), len(b))
	return groupLines(lines, contextLines)
}

// joinLines 是 splitLines 的反面：每行都带换行，对比器按行比较时最后一行不会因为有没有换行而算作改了。
func joinLines(lines []string) string {
	if len(lines) == 0 {
		return ""
	}
	return strings.Join(lines, "\n") + "\n"
}

// groupLines 只留改动行和它们前后 context 行；相隔不超过 2×context 行的改动并成一段。
func groupLines(lines []items.Line, context int) []items.Hunk {
	var hunks []items.Hunk
	start, end := -1, -1 // 当前这段的范围 [start, end)
	for k, l := range lines {
		if l.Kind == items.Context {
			continue
		}
		from, to := max(k-context, 0), min(k+context+1, len(lines))
		if start >= 0 && from > end {
			hunks = append(hunks, items.Hunk{Lines: lines[start:end]})
			start = -1
		}
		if start < 0 {
			start = from
		}
		end = to
	}
	if start >= 0 {
		hunks = append(hunks, items.Hunk{Lines: lines[start:end]})
	}
	return hunks
}

// takeLines 从头取最多 limit 行，最后一段可能只取一部分。
func takeLines(hunks []items.Hunk, limit int) []items.Hunk {
	var out []items.Hunk
	for _, h := range hunks {
		if limit <= 0 {
			break
		}
		if len(h.Lines) > limit {
			h = items.Hunk{Lines: h.Lines[:limit]}
		}
		out = append(out, h)
		limit -= len(h.Lines)
	}
	return out
}

// maxLineRunes：一行太长时只画前面这些字。
const maxLineRunes = 400

// toPatch 把各段对比写成 mygo 对比视图认的补丁：每段一个 hunk，行带 +、-、空格前缀。
func toPatch(hunks []items.Hunk) editor.FilePatch {
	var p editor.FilePatch
	for _, h := range hunks {
		ph := editor.PatchHunk{}
		for _, l := range h.Lines {
			prefix := " "
			switch l.Kind {
			case items.Added:
				prefix = "+"
			case items.Removed:
				prefix = "-"
			}
			if l.Kind != items.Added {
				ph.OldLines++
				ph.OldStart = firstNumber(ph.OldStart, l.Old)
			}
			if l.Kind != items.Removed {
				ph.NewLines++
				ph.NewStart = firstNumber(ph.NewStart, l.New)
			}
			ph.Lines = append(ph.Lines, prefix+displayLine(l.Text))
		}
		p.Hunks = append(p.Hunks, ph)
	}
	return p
}

func firstNumber(have, n int) int {
	if have > 0 {
		return have
	}
	return n
}

// displayLine 去掉回车，太长的截断：免得一行排成几行、和行号对不上，也免得一行几万字的压缩文件拖慢排版。
func displayLine(text string) string {
	text = strings.ReplaceAll(text, "\r", "")
	if utf8.RuneCountInString(text) > maxLineRunes {
		return string([]rune(text)[:maxLineRunes]) + " …"
	}
	return text
}

// hunkStart 从对比视图给的分隔条标题（"@@ -12,7 +12,8 @@"）里读出新文件里的起始行。
func hunkStart(header string) int {
	var oldStart, oldLines, newStart, newLines int
	if _, err := fmt.Sscanf(header, "@@ -%d,%d +%d,%d @@", &oldStart, &oldLines, &newStart, &newLines); err != nil {
		return 0
	}
	return newStart
}
