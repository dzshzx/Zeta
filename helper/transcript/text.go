package transcript

import (
	"encoding/json"
	"strconv"
	"strings"
	"unicode"
)

// ApplyShell 处理用户在输入栏执行的命令（终端版的 !）：Claude Code 把它记成两条用户消息，
// <bash-input>命令</bash-input> 和 <bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr><bash-exit-code>N</bash-exit-code>，
// 这里画成单独一组里的一个命令步骤。不是这两种时返回 false。
func (r *Reducer) ApplyShell(text string) bool {
	text = strings.TrimSpace(text)
	switch {
	case strings.HasPrefix(text, "<bash-input>"):
		command, _ := tagged(text, "bash-input")
		first, _, _ := strings.Cut(strings.ReplaceAll(command, "\r", "\n"), "\n")
		input, _ := json.Marshal(map[string]string{"command": command, "description": "! " + first})
		id := r.nextID("sh")
		s := &Step{Kind: StepTool, ID: id, Name: "Bash", Status: ToolRunning, Input: input}
		it := &Item{Kind: KindWork, ID: "w:" + id, Steps: []*Step{s}}
		r.Items = append(r.Items, it)
		r.shell = &stepRef{it, s}
	case strings.HasPrefix(text, "<bash-stdout>"), strings.HasPrefix(text, "<bash-stderr>"):
		if ref := r.shell; ref != nil {
			r.shell = nil
			finishShell(ref, text)
		}
	default:
		return false
	}
	return true
}

func finishShell(ref *stepRef, text string) {
	var parts []string
	for _, tag := range []string{"bash-stdout", "bash-stderr"} {
		if out, _ := tagged(text, tag); strings.TrimRightFunc(out, unicode.IsSpace) != "" {
			parts = append(parts, strings.TrimRightFunc(out, unicode.IsSpace))
		}
	}
	result := strings.Join(parts, "\n")
	if result == "" {
		result = "（没有输出）"
	}
	status := ToolSuccess
	raw, _ := tagged(text, "bash-exit-code")
	if code, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 32); err == nil && code != 0 {
		result += "\n退出码 " + strconv.FormatInt(code, 10)
		status = ToolFailed
	}
	ref.step.Status, ref.step.Result = status, result
	touch(ref.item)
}

// tagged 是 <tag> 之后到 </tag> 之前的文字（没有 </tag> 就到结尾）；没有 <tag> 时 ok 为假。
func tagged(text, tag string) (string, bool) {
	_, after, ok := strings.Cut(text, "<"+tag+">")
	before, _, _ := strings.Cut(after, "</"+tag+">")
	return before, ok
}

// 附件行：客户端把复制进来的文件、引用的路径写在消息开头，一行一个（见 WithAttachedFiles）。
const (
	filePrefix   = "[Attached file: "
	folderPrefix = "[Attached folder: "
)

// WithAttachedFiles 是发给 Claude Code 的消息文字：files 写成开头的附件行，空一行再接 text。
// 复制进来的文件写它的路径；引用的路径以 @ 开头（文件夹以 / 结尾），写成 @"路径"，Claude Code 照它读文件或列目录。
func WithAttachedFiles(text string, files []string) string {
	if len(files) == 0 {
		return text
	}
	headers := make([]string, len(files))
	for i, f := range files {
		ref, isRef := strings.CutPrefix(f, "@")
		switch {
		case !isRef:
			headers[i] = filePrefix + f + "]"
		case strings.HasSuffix(ref, "/"):
			dir := strings.TrimSuffix(ref, "/")
			if dir == "" {
				dir = "/"
			}
			headers[i] = folderPrefix + `@"` + dir + `"]`
		default:
			headers[i] = filePrefix + `@"` + ref + `"]`
		}
	}
	out := strings.Join(headers, "\n")
	if text != "" {
		out += "\n\n" + text
	}
	return out
}

// splitAttachedFiles 把消息开头的附件行拆出来：返回附件和其余的文字。
func splitAttachedFiles(text string) ([]string, string) {
	if !strings.HasPrefix(text, "[Attached ") {
		return nil, text
	}
	lines := strings.Split(text, "\n")
	var files []string
	for len(lines) > 0 {
		f, ok := attachedHeader(lines[0])
		if !ok {
			break
		}
		files = append(files, f)
		lines = lines[1:]
	}
	return files, strings.TrimSpace(strings.Join(lines, "\n"))
}

// attachedHeader 读一行附件行：复制进来的文件是它的路径，引用的是 @路径（文件夹以 / 结尾）。
func attachedHeader(line string) (string, bool) {
	body, ok := strings.CutSuffix(line, "]")
	if !ok {
		return "", false
	}
	folder := false
	if rest, ok := strings.CutPrefix(body, folderPrefix); ok {
		body, folder = rest, true
	} else if rest, ok := strings.CutPrefix(body, filePrefix); ok {
		body = rest
	} else {
		return "", false
	}
	if quoted, ok := strings.CutPrefix(body, `@"`); ok && len(quoted) > 1 && strings.HasSuffix(quoted, `"`) {
		path := strings.TrimSuffix(quoted, `"`)
		if strings.HasPrefix(path, "/") {
			if folder {
				return "@" + strings.TrimRight(path, "/") + "/", true
			}
			return "@" + path, true
		}
	}
	if folder || body == "" {
		return "", false
	}
	return body, true
}

// parsePatch 读改文件的工具的结构化结果：Created 是 Write 新建了文件，Hunks 来自 structuredPatch。不是改文件的工具时为 nil。
func parsePatch(result *ToolUseResult) *Patch {
	if result == nil || result.StructuredPatch == nil {
		return nil
	}
	p := &Patch{Created: result.Type == "create", Hunks: []Hunk{}}
	for _, raw := range result.StructuredPatch {
		var h patchHunk
		if json.Unmarshal(raw, &h) != nil || h.OldStart == nil || h.NewStart == nil {
			continue
		}
		old, cur := *h.OldStart, *h.NewStart
		var lines []DiffLine
		for _, raw := range h.Lines {
			if raw == "" {
				continue
			}
			text := raw[1:]
			switch raw[0] {
			case '+':
				lines = append(lines, DiffLine{Kind: "added", Text: text, New: cur})
				cur++
			case '-':
				lines = append(lines, DiffLine{Kind: "removed", Text: text, Old: old})
				old++
			case ' ':
				lines = append(lines, DiffLine{Kind: "context", Text: text, Old: old, New: cur})
				old++
				cur++
			}
			// 别的（"\ No newline at end of file" 之类的说明行）不要
		}
		if len(lines) > 0 {
			p.Hunks = append(p.Hunks, Hunk{Lines: lines})
		}
	}
	return p
}
