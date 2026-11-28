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

type diffOp struct {
	kind byte // ' ', '-', '+'
	text string
	oldI int
	newI int
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
	const maxLines = 2000
	if len(oldLines)+len(newLines) > maxLines {
		return nil
	}
	ops := myersDiff(oldLines, newLines)
	return groupHunks(ops, 3)
}

func myersDiff(a, b []string) []diffOp {
	n, m := len(a), len(b)
	maxD := n + m
	if maxD == 0 {
		return nil
	}
	offset := maxD
	v := make([]int, 2*maxD+1)
	trace := make([][]int, 0, min(maxD+1, 256))
	for d := 0; d <= maxD; d++ {
		snap := make([]int, len(v))
		copy(snap, v)
		trace = append(trace, snap)
		for k := -d; k <= d; k += 2 {
			x := nextMyersX(v, offset, d, k)
			y := x - k
			for x < n && y < m && a[x] == b[y] {
				x++
				y++
			}
			v[offset+k] = x
			if x >= n && y >= m {
				return backtrackMyers(a, b, trace, offset)
			}
		}
	}
	return nil
}

func nextMyersX(v []int, offset, d, k int) int {
	if k == -d || (k != d && v[offset+k-1] < v[offset+k+1]) {
		return v[offset+k+1]
	}
	return v[offset+k-1] + 1
}

func backtrackMyers(a, b []string, trace [][]int, offset int) []diffOp {
	x, y := len(a), len(b)
	var rev []diffOp
	for d := len(trace) - 1; d > 0; d-- {
		v := trace[d]
		k := x - y
		prevK := k - 1
		if k == -d || (k != d && v[offset+k-1] < v[offset+k+1]) {
			prevK = k + 1
		}
		prevX := v[offset+prevK]
		prevY := prevX - prevK
		for x > prevX && y > prevY {
			x--
			y--
			rev = append(rev, diffOp{kind: ' ', text: a[x], oldI: x, newI: y})
		}
		if x == prevX {
			y--
			rev = append(rev, diffOp{kind: '+', text: b[y], oldI: x, newI: y})
		} else {
			x--
			rev = append(rev, diffOp{kind: '-', text: a[x], oldI: x, newI: y})
		}
	}
	for x > 0 && y > 0 {
		x--
		y--
		rev = append(rev, diffOp{kind: ' ', text: a[x], oldI: x, newI: y})
	}
	for i, j := 0, len(rev)-1; i < j; i, j = i+1, j-1 {
		rev[i], rev[j] = rev[j], rev[i]
	}
	return rev
}

func groupHunks(ops []diffOp, ctx int) []patchHunk {
	var hunks []patchHunk
	i := 0
	for i < len(ops) {
		for i < len(ops) && ops[i].kind == ' ' {
			i++
		}
		if i >= len(ops) {
			break
		}
		start := max(0, i-ctx)
		end := i
		for end < len(ops) {
			if ops[end].kind != ' ' {
				end++
				continue
			}
			eq := end
			for eq < len(ops) && ops[eq].kind == ' ' {
				eq++
			}
			if eq >= len(ops) || eq-end > 2*ctx {
				end = min(len(ops), end+ctx)
				break
			}
			end = eq
		}
		hunks = append(hunks, buildHunk(ops[start:end]))
		i = end
	}
	return hunks
}

func buildHunk(seg []diffOp) patchHunk {
	oldStart := seg[0].oldI + 1
	newStart := seg[0].newI + 1
	oldLines, newLines := 0, 0
	lines := make([]string, 0, len(seg))
	for _, op := range seg {
		lines = append(lines, string(op.kind)+op.text)
		if op.kind != '+' {
			oldLines++
		}
		if op.kind != '-' {
			newLines++
		}
	}
	return patchHunk{
		OldStart: oldStart,
		OldLines: oldLines,
		NewStart: newStart,
		NewLines: newLines,
		Lines:    lines,
	}
}
