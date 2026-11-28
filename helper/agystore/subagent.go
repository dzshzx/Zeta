package agystore

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var anyUUIDRe = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)

// SubagentFound 是找到的一个 agy 子代理会话。
type SubagentFound struct {
	AgentID string
	Path    string
	Meta    map[string]any
}

// FindSubagent 按发起它的 invoke_subagent 调用 toolUse（或已知的会话号 hint）在父会话 sessions 下找子代理记录。
func FindSubagent(agyDir, toolUse, hint string, sessions []string) (SubagentFound, bool) {
	if IsSessionID(hint) {
		if f, ok := subagentFromID(agyDir, hint, nil); ok {
			return f, true
		}
	}
	for _, parent := range sessions {
		p := TranscriptPath(agyDir, parent)
		if id, callArgs := findChildInTranscript(agyDir, p, toolUse); id != "" {
			if f, ok := subagentFromID(agyDir, id, callArgs); ok {
				return f, true
			}
		}
	}
	return SubagentFound{}, false
}

func subagentFromID(agyDir, id string, callArgs map[string]any) (SubagentFound, bool) {
	p := TranscriptPath(agyDir, id)
	if st, err := os.Stat(p); err != nil || !st.Mode().IsRegular() {
		return SubagentFound{}, false
	}
	meta := map[string]any{}
	if t, ok := callArgs["TypeName"].(string); ok && t != "" {
		meta["agentType"] = t
	}
	if r, ok := callArgs["Role"].(string); ok && r != "" {
		meta["description"] = r
	}
	if m, ok := callArgs["Model"].(string); ok && m != "" && m != "inherit" {
		meta["model"] = m
	}
	rows := readDBRows(agyDir)
	if row, ok := rows[id]; ok && row.Title != "" && meta["description"] == nil {
		meta["description"] = row.Title
	}
	if subagentFinished(p) && len(ActiveHolders(agyDir, id)) == 0 {
		meta["finished"] = true
	}
	return SubagentFound{AgentID: id, Path: p, Meta: meta}, true
}

func subagentFinished(path string) bool {
	_, tail, err := readHeadAndTail(path, 32*1024)
	if err != nil {
		return false
	}
	return scanTailReply(tail) > 0
}

type subagentSearch struct {
	agyDir     string
	toolUse    string
	pending    []string
	targetArgs map[string]any
}

func findChildInTranscript(agyDir, parentTranscript, toolUse string) (string, map[string]any) {
	steps, err := readSteps(parentTranscript)
	if err != nil {
		return "", nil
	}
	s := &subagentSearch{agyDir: agyDir, toolUse: toolUse}
	for _, st := range steps {
		if child := s.step(st); child != "" {
			return child, s.targetArgs
		}
	}
	return "", s.targetArgs
}

func (s *subagentSearch) step(st rawStep) string {
	switch st.Type {
	case "USER_INPUT":
		s.pending = nil
	case "PLANNER_RESPONSE":
		_, ids := plannerEvent(st)
		for i, id := range ids {
			if id == s.toolUse && i < len(st.ToolCalls) {
				s.targetArgs = DecodeToolArgs(st.ToolCalls[i].Args)
			}
			s.pending = append(s.pending, id)
		}
	case "GENERIC":
		if st.Source != "MODEL" || len(s.pending) == 0 {
			return ""
		}
		cur := s.pending[0]
		s.pending = s.pending[1:]
		if cur == s.toolUse {
			return extractChildSessionID(s.agyDir, st.Output)
		}
	}
	return ""
}

func extractChildSessionID(agyDir, text string) string {
	for _, cand := range anyUUIDRe.FindAllString(text, -1) {
		if _, err := os.Stat(TranscriptPath(agyDir, cand)); err == nil {
			return cand
		}
	}
	return ""
}

// SubagentChunkEvents 将读取到的 transcript.jsonl 整行块转为子代理查看页的事件流，第一条 USER_INPUT 标上 prompt:true。
func SubagentChunkEvents(data []byte, startLine int64) ([]map[string]any, int64) {
	var events []map[string]any
	var pendingCalls []string
	line := startLine
	for len(data) > 0 {
		nl := bytes.IndexByte(data, '\n')
		var raw []byte
		if nl >= 0 {
			raw, data = data[:nl], data[nl+1:]
		} else {
			raw, data = data, nil
		}
		curLine := line
		line++
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 {
			continue
		}
		var st rawStep
		if json.Unmarshal(raw, &st) != nil {
			continue
		}
		events, pendingCalls = appendSubagentStep(events, pendingCalls, st, curLine == 0)
	}
	return events, line
}

func appendSubagentStep(events []map[string]any, pending []string, st rawStep, firstLine bool) ([]map[string]any, []string) {
	switch st.Type {
	case "USER_INPUT":
		pending = nil
		txt := ExtractUserRequest(st.Content)
		if txt == "" {
			return events, pending
		}
		ev := map[string]any{
			"type":    "user",
			"message": map[string]any{"content": []any{map[string]any{"type": "text", "text": clipString(txt)}}},
		}
		if firstLine || len(events) == 0 {
			ev["prompt"] = true
		}
		return append(events, ev), pending
	case "PLANNER_RESPONSE":
		ev, ids := plannerEvent(st)
		pending = append(pending, ids...)
		if ev != nil {
			msg, _ := ev["message"].(map[string]any)
			delete(msg, "usage")
			events = append(events, ev)
		}
		return events, pending
	case "GENERIC":
		if st.Source != "MODEL" || len(pending) == 0 {
			return events, pending
		}
		callID := pending[0]
		pending = pending[1:]
		return append(events, genericToolResultEvent(callID, st.Status, st.Output)), pending
	}
	return events, pending
}

type namedCall struct {
	id   string
	name string
}

// ChildTokenModels 统计父会话 parentID 底下由工具调用发起的子代理按模型汇总的 token（供 history 补 subagent_usage）。
func ChildTokenModels(agyDir, parentTranscript string) map[string]map[string][3]int64 {
	steps, err := readSteps(parentTranscript)
	if err != nil {
		return nil
	}
	out := map[string]map[string][3]int64{}
	var pending []namedCall
	for _, st := range steps {
		pending = stepChildTokens(agyDir, out, pending, st)
	}
	return out
}

func stepChildTokens(agyDir string, out map[string]map[string][3]int64, pending []namedCall, st rawStep) []namedCall {
	switch st.Type {
	case "USER_INPUT":
		return nil
	case "PLANNER_RESPONSE":
		return append(pending, plannerNamedCalls(st)...)
	case "GENERIC":
		if st.Source != "MODEL" || len(pending) == 0 {
			return pending
		}
		call := pending[0]
		pending = pending[1:]
		recordInvokeSubagentTokens(agyDir, out, call, st.Output)
		return pending
	}
	return pending
}

func plannerNamedCalls(st rawStep) []namedCall {
	_, ids := plannerEvent(st)
	calls := make([]namedCall, 0, len(ids))
	for i, id := range ids {
		name := ""
		if i < len(st.ToolCalls) {
			name = st.ToolCalls[i].Name
		}
		calls = append(calls, namedCall{id: id, name: name})
	}
	return calls
}

func recordInvokeSubagentTokens(agyDir string, out map[string]map[string][3]int64, call namedCall, output string) {
	if call.name != "invoke_subagent" {
		return
	}
	childID := extractChildSessionID(agyDir, output)
	if childID == "" {
		return
	}
	if totals := sumChildTranscript(agyDir, childID, map[string]bool{childID: true}); len(totals) > 0 {
		out[call.id] = totals
	}
}

func sumChildTranscript(agyDir, id string, seen map[string]bool) map[string][3]int64 {
	steps, err := readSteps(TranscriptPath(agyDir, id))
	if err != nil {
		return nil
	}
	totals := map[string][3]int64{}
	for _, st := range steps {
		if st.Type == "PLANNER_RESPONSE" && realModelName(st.Model) {
			addModelTokens(totals, st.Model, [3]int64{st.InputTokens, st.OutputTokens, st.CacheReadTokens})
		}
		if st.Type == "GENERIC" && st.Source == "MODEL" {
			mergeNestedSubagentTokens(agyDir, totals, seen, st.Output)
		}
	}
	return totals
}

func mergeNestedSubagentTokens(agyDir string, totals map[string][3]int64, seen map[string]bool, output string) {
	sub := extractChildSessionID(agyDir, output)
	if sub == "" || seen[sub] {
		return
	}
	seen[sub] = true
	for m, v := range sumChildTranscript(agyDir, sub, seen) {
		addModelTokens(totals, m, v)
	}
}

func addModelTokens(totals map[string][3]int64, model string, add [3]int64) {
	cur := totals[model]
	cur[0] += add[0]
	cur[1] += add[1]
	cur[2] += add[2]
	totals[model] = cur
}

// CleanPathRoot 校验路径在给定的根目录下。
func CleanPathRoot(p, root string) bool {
	cp := filepath.Clean(p)
	cr := filepath.Clean(root)
	return strings.HasPrefix(cp, cr+string(filepath.Separator))
}
