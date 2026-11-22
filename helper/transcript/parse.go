package transcript

import "strings"

// parsePatch 读改文件工具的结构化结果（tool_use_result）：Created 是 Write 新建了文件，Hunks 来自 structuredPatch。
// 不是改文件工具的结果时为 nil。
func parsePatch(result obj) *EditPatch {
	raw, ok := result["structuredPatch"].([]any)
	if result == nil || !ok {
		return nil
	}
	p := &EditPatch{Created: strOr(result, "type") == "create"}
	for _, h := range raw {
		hunk, _ := h.(map[string]any)
		if parsed := parseHunk(hunk); parsed != nil {
			p.Hunks = append(p.Hunks, *parsed)
		}
	}
	return p
}

func parseHunk(hunk obj) *Hunk {
	old, ok1 := integer(hunk, "oldStart")
	cur, ok2 := integer(hunk, "newStart")
	if hunk == nil || !ok1 || !ok2 {
		return nil
	}
	var out []DiffLine
	lines, _ := hunk["lines"].([]any)
	for _, e := range lines {
		raw, ok := str(obj{"v": e}, "v")
		if !ok || raw == "" {
			continue
		}
		switch raw[0] {
		case '+':
			out = append(out, DiffLine{Kind: "added", Text: raw[1:], New: ptr(cur)})
			cur++
		case '-':
			out = append(out, DiffLine{Kind: "removed", Text: raw[1:], Old: ptr(old)})
			old++
		case ' ':
			out = append(out, DiffLine{Kind: "context", Text: raw[1:], Old: ptr(old), New: ptr(cur)})
			old++
			cur++
		}
		// 别的（"\ No newline at end of file" 之类的说明行）不要
	}
	if len(out) == 0 {
		return nil
	}
	return &Hunk{Lines: out}
}

const (
	filePrefix   = "[Attached file: "
	folderPrefix = "[Attached folder: "
)

// splitAttachedFiles 把消息开头的附件行（Kotlin 的 AttachedPaths.header 写的）拆出来：返回附件说明和其余的文字。
func splitAttachedFiles(text string) ([]string, string) {
	if !strings.HasPrefix(text, "[Attached ") {
		return nil, text
	}
	all := lines(text)
	var files []string
	index := 0
	for ; index < len(all); index++ {
		entry, ok := parseAttachedHeader(all[index])
		if !ok {
			break
		}
		files = append(files, entry)
	}
	return files, trim(strings.Join(all[index:], "\n"))
}

// parseAttachedHeader 是附件行的附件说明：复制进来的文件是它的路径，引用的是 @路径（文件夹结尾带 /）。
func parseAttachedHeader(line string) (string, bool) {
	if !strings.HasSuffix(line, "]") {
		return "", false
	}
	folder := strings.HasPrefix(line, folderPrefix)
	if !folder && !strings.HasPrefix(line, filePrefix) {
		return "", false
	}
	prefix := filePrefix
	if folder {
		prefix = folderPrefix
	}
	body := line[len(prefix) : len(line)-1]
	if len(body) > 3 && strings.HasPrefix(body, `@"`) && strings.HasSuffix(body, `"`) {
		path := body[2 : len(body)-1]
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
