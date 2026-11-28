package agybridge

import "strings"

// patchHunk 对应 Zeta transcript.ToolUseResult.StructuredPatch 的一段对比。
type patchHunk struct {
	OldStart int      `json:"oldStart"`
	OldLines int      `json:"oldLines"`
	NewStart int      `json:"newStart"`
	NewLines int      `json:"newLines"`
	Lines    []string `json:"lines"`
}

// buildToolPatch 从 ACP diff 块（oldText -> newText）构造 Zeta 识别的 tool_use_result（含 structuredPatch 与 create/update 类型）。
func buildToolPatch(oldText, newText string) map[string]any {
	if oldText == newText {
		return nil
	}
	hunks := computePatchHunks(splitLines(oldText), splitLines(newText))
	if len(hunks) == 0 {
		return nil
	}
	kind := "update"
	if oldText == "" {
		kind = "create"
	}
	return map[string]any{
		"type":            kind,
		"structuredPatch": hunks,
	}
}

func splitLines(s string) []string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.TrimSuffix(s, "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

func computePatchHunks(oldLines, newLines []string) []patchHunk {
	const maxLines = 1500
	if len(oldLines)+len(newLines) > maxLines {
		return nil
	}
	prefix := commonPrefixLen(oldLines, newLines)
	suffix := commonSuffixLen(oldLines[prefix:], newLines[prefix:])

	const ctx = 3
	oldStart := max(0, prefix-ctx)
	newStart := max(0, prefix-ctx)
	oldEnd := min(len(oldLines), len(oldLines)-suffix+ctx)
	newEnd := min(len(newLines), len(newLines)-suffix+ctx)

	var lines []string
	for i := oldStart; i < prefix; i++ {
		lines = append(lines, " "+oldLines[i])
	}
	for i := prefix; i < len(oldLines)-suffix; i++ {
		lines = append(lines, "-"+oldLines[i])
	}
	for i := prefix; i < len(newLines)-suffix; i++ {
		lines = append(lines, "+"+newLines[i])
	}
	tailStart := len(oldLines) - suffix
	for i := tailStart; i < oldEnd; i++ {
		lines = append(lines, " "+oldLines[i])
	}
	if len(lines) == 0 {
		return nil
	}
	return []patchHunk{{
		OldStart: oldStart + 1,
		OldLines: oldEnd - oldStart,
		NewStart: newStart + 1,
		NewLines: newEnd - newStart,
		Lines:    lines,
	}}
}

func commonPrefixLen(a, b []string) int {
	n := min(len(a), len(b))
	for i := 0; i < n; i++ {
		if a[i] != b[i] {
			return i
		}
	}
	return n
}

func commonSuffixLen(a, b []string) int {
	n := min(len(a), len(b))
	for i := 0; i < n; i++ {
		if a[len(a)-1-i] != b[len(b)-1-i] {
			return i
		}
	}
	return n
}
