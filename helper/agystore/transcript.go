package agystore

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	transcriptRelPath = ".system_generated/logs/transcript.jsonl"
	maxClipRunes      = 32000
)

// Dir 返回 agy 数据根目录（默认 ~/.gemini/antigravity-cli，支持 ZETA_AGY_DIR 覆盖）。
func Dir(home string) string {
	if override := strings.TrimSpace(os.Getenv("ZETA_AGY_DIR")); override != "" {
		return filepath.Clean(override)
	}
	return filepath.Join(home, ".gemini", "antigravity-cli")
}

// TranscriptPath 是 agy 会话 id 的会话记录路径。
func TranscriptPath(agyDir, id string) string {
	return filepath.Join(agyDir, "brain", id, transcriptRelPath)
}

// ParseTranscriptPath 从任意 agy transcript.jsonl 路径还原 agy 根目录和会话 id。
func ParseTranscriptPath(p string) (agyDir, id string, ok bool) {
	clean := filepath.Clean(p)
	suffix := string(filepath.Separator) + filepath.FromSlash(transcriptRelPath)
	rest, has := strings.CutSuffix(clean, suffix)
	if !has {
		return "", "", false
	}
	id = filepath.Base(rest)
	brainDir := filepath.Dir(rest)
	if filepath.Base(brainDir) != "brain" || !IsSessionID(id) {
		return "", "", false
	}
	return filepath.Dir(brainDir), id, true
}

// SessionIDFromTranscript 从 transcript.jsonl 的路径还原会话 id；不是 agyDir 下的会话记录路径时 ok 为假。
func SessionIDFromTranscript(agyDir, p string) (string, bool) {
	dir, id, ok := ParseTranscriptPath(p)
	if !ok || filepath.Clean(dir) != filepath.Clean(agyDir) {
		return "", false
	}
	return id, true
}

// IsSessionID 判断 id 是否是合法的会话标识。
func IsSessionID(id string) bool {
	if len(id) < 8 || len(id) > 64 {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-') {
			return false
		}
	}
	return true
}

func toolCallID(stepIndex, idx int, tc rawTool) string {
	if tc.ID != "" {
		return tc.ID
	}
	return fmt.Sprintf("agy-%d-%d", stepIndex, idx)
}

// rawStep 是 agy transcript.jsonl 里的一行。
type rawStep struct {
	StepIndex       int       `json:"step_index"`
	Source          string    `json:"source"`
	Type            string    `json:"type"`
	Status          string    `json:"status"`
	CreatedAt       string    `json:"created_at"`
	Model           string    `json:"model"`
	Thinking        string    `json:"thinking"`
	Content         string    `json:"content"`
	Output          string    `json:"output"`
	InputTokens     int64     `json:"input_tokens"`
	CacheReadTokens int64     `json:"cache_read_tokens"`
	OutputTokens    int64     `json:"output_tokens"`
	ToolCalls       []rawTool `json:"tool_calls"`
	TruncatedFields []string  `json:"truncated_fields"`
}

type rawTool struct {
	ID   string         `json:"id"`
	Name string         `json:"name"`
	Args map[string]any `json:"args"`
}

// Timeline 是从 agy transcript.jsonl 转出的 Zeta 历史事件序列。
type Timeline struct {
	Events []map[string]any
	Owners []any
	Model  string
}

// ExtractUserRequest 从 agy 的 USER_INPUT.content 里取出用户真正输入的话（剥掉 <USER_REQUEST> 与附加元数据块）。
func ExtractUserRequest(content string) string {
	const openTag = "<USER_REQUEST>"
	const closeTag = "</USER_REQUEST>"
	if start := strings.Index(content, openTag); start >= 0 {
		rest := content[start+len(openTag):]
		if end := strings.LastIndex(rest, closeTag); end >= 0 {
			return strings.TrimSpace(rest[:end])
		}
		return strings.TrimSpace(rest)
	}
	for _, marker := range []string{"\n<ADDITIONAL_METADATA>", "\n<USER_SETTINGS_CHANGE>", "<ADDITIONAL_METADATA>"} {
		if idx := strings.Index(content, marker); idx >= 0 {
			content = content[:idx]
		}
	}
	return strings.TrimSpace(content)
}

// ExtractWorkspaceFromContent 从 agy 注入的 <user_information> 段落里兜底提取工作目录（无 SQLite 元数据时用）。
func ExtractWorkspaceFromContent(content string) string {
	const marker = "Command Working Directory:"
	idx := strings.Index(content, marker)
	if idx < 0 {
		return ""
	}
	rest := content[idx+len(marker):]
	line, _, _ := strings.Cut(rest, "\n")
	dir := strings.TrimSpace(line)
	if strings.HasPrefix(dir, "/") {
		return dir
	}
	return ""
}

// DecodeToolArgs 把 agy transcript.jsonl 里双层序列化的参数值（如 "\"/tmp/a.txt\""、"100"）还原成真实 JSON 值。
func DecodeToolArgs(args map[string]any) map[string]any {
	if len(args) == 0 {
		return map[string]any{}
	}
	out := make(map[string]any, len(args))
	for k, v := range args {
		out[k] = decodeArgValue(v)
	}
	return out
}

func decodeArgValue(v any) any {
	s, ok := v.(string)
	if !ok || s == "" {
		return v
	}
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	var parsed any
	if err := dec.Decode(&parsed); err != nil {
		return s
	}
	var trailing any
	if dec.Decode(&trailing) == nil {
		return s
	}
	return parsed
}

// ReadTimeline 读取 transcript.jsonl 并转成与 Zeta history 一致的事件流。
func ReadTimeline(path string) (*Timeline, error) {
	steps, err := readSteps(path)
	if err != nil {
		return nil, err
	}
	tb := &timelineBuilder{
		tl:          &Timeline{},
		fullOutputs: loadFullOutputs(path, steps),
	}
	for _, st := range steps {
		tb.apply(st)
	}
	for _, h := range tb.pendingHooks {
		tb.tl.add(h, "step-0")
	}
	return tb.tl, nil
}

type timelineBuilder struct {
	tl           *Timeline
	fullOutputs  map[int]string
	pendingCalls []string
	pendingHooks []map[string]any
}

func (tb *timelineBuilder) apply(st rawStep) {
	owner := "step-" + strconv.Itoa(st.StepIndex)
	switch st.Type {
	case "SYSTEM_MESSAGE":
		if h := parseHookStep(st); h != nil {
			tb.pendingHooks = append(tb.pendingHooks, h)
		}
	case "USER_INPUT":
		tb.applyUserInput(st, owner)
	case "PLANNER_RESPONSE":
		tb.applyPlanner(st, owner)
	case "GENERIC":
		tb.applyGeneric(st, owner)
	}
}

func (tb *timelineBuilder) applyUserInput(st rawStep, owner string) {
	tb.pendingCalls = nil
	text := ExtractUserRequest(st.Content)
	if text == "" {
		return
	}
	tb.tl.add(userTextEvent(text, owner), owner)
	for _, h := range tb.pendingHooks {
		tb.tl.add(h, owner)
	}
	tb.pendingHooks = nil
}

func (tb *timelineBuilder) applyPlanner(st rawStep, owner string) {
	ev, ids := plannerEvent(st)
	tb.pendingCalls = append(tb.pendingCalls, ids...)
	if ev == nil {
		return
	}
	if realModelName(st.Model) {
		tb.tl.Model = st.Model
	}
	tb.tl.add(ev, owner)
}

func (tb *timelineBuilder) applyGeneric(st rawStep, owner string) {
	if st.Source != "MODEL" || len(tb.pendingCalls) == 0 {
		return
	}
	callID := tb.pendingCalls[0]
	tb.pendingCalls = tb.pendingCalls[1:]
	output := st.Output
	if len(st.TruncatedFields) > 0 {
		if full, ok := tb.fullOutputs[st.StepIndex]; ok {
			output = full
		}
	}
	tb.tl.add(genericToolResultEvent(callID, st.Status, output), owner)
}

func (t *Timeline) add(ev map[string]any, owner any) {
	t.Events = append(t.Events, ev)
	t.Owners = append(t.Owners, owner)
}

func parseHookStep(st rawStep) map[string]any {
	const hookPrefix = "injected by a SessionStart hook:\n"
	idx := strings.Index(st.Content, hookPrefix)
	if idx < 0 {
		return nil
	}
	body := st.Content[idx+len(hookPrefix):]
	body = strings.TrimSuffix(strings.TrimSpace(body), "</SYSTEM_MESSAGE>")
	body = strings.TrimSpace(body)
	if body == "" {
		return nil
	}
	return map[string]any{
		"type":       "system",
		"subtype":    "hook_context",
		"hook_event": "SessionStart",
		"text":       clipString(body),
	}
}

func userTextEvent(text, owner string) map[string]any {
	return map[string]any{
		"type": "user",
		"uuid": owner,
		"message": map[string]any{
			"content": []any{map[string]any{"type": "text", "text": clipString(text)}},
		},
	}
}

func plannerEvent(st rawStep) (map[string]any, []string) {
	var blocks []any
	var ids []string
	if strings.TrimSpace(st.Thinking) != "" {
		blocks = append(blocks, map[string]any{"type": "thinking", "thinking": clipString(st.Thinking)})
	}
	if strings.TrimSpace(st.Content) != "" {
		blocks = append(blocks, map[string]any{"type": "text", "text": clipString(st.Content)})
	}
	for i, tc := range st.ToolCalls {
		callID := toolCallID(st.StepIndex, i, tc)
		ids = append(ids, callID)
		decoded := DecodeToolArgs(tc.Args)
		clipped, wasClipped := clipMap(decoded)
		block := map[string]any{"type": "tool_use", "id": callID, "name": tc.Name, "input": clipped}
		if wasClipped {
			block["clipped"] = true
		}
		blocks = append(blocks, block)
	}
	usage := stepUsage(st)
	if len(blocks) == 0 && usage == nil {
		return nil, ids
	}
	msg := map[string]any{
		"id":      fmt.Sprintf("agy-msg-%d", st.StepIndex),
		"model":   st.Model,
		"content": blocks,
	}
	if usage != nil {
		msg["usage"] = usage
	}
	return map[string]any{"type": "assistant", "message": msg}, ids
}

func stepUsage(st rawStep) map[string]any {
	if st.InputTokens <= 0 && st.OutputTokens <= 0 && st.CacheReadTokens <= 0 {
		return nil
	}
	return map[string]any{
		"input_tokens":                st.InputTokens,
		"output_tokens":               st.OutputTokens,
		"cache_read_input_tokens":     st.CacheReadTokens,
		"cache_creation_input_tokens": int64(0),
	}
}

func genericToolResultEvent(callID, status, output string) map[string]any {
	isErr := status == "ERROR" || status == "FAILED" || status == "CANCELED"
	return map[string]any{
		"type": "user",
		"message": map[string]any{
			"content": []any{
				map[string]any{
					"type":        "tool_result",
					"tool_use_id": callID,
					"is_error":    isErr,
					"content":     clipString(output),
				},
			},
		},
	}
}

func loadFullOutputs(transcriptPath string, steps []rawStep) map[int]string {
	need := map[int]bool{}
	for _, st := range steps {
		if st.Type == "GENERIC" && len(st.TruncatedFields) > 0 {
			need[st.StepIndex] = true
		}
	}
	if len(need) == 0 {
		return nil
	}
	fullPath := filepath.Join(filepath.Dir(transcriptPath), "transcript_full.jsonl")
	fullSteps, err := readSelectedSteps(fullPath, need)
	if err != nil {
		return nil
	}
	return fullSteps
}

func readSteps(path string) ([]rawStep, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var steps []rawStep
	br := bufio.NewReaderSize(f, 64*1024)
	for {
		line, err := br.ReadBytes('\n')
		if len(bytes.TrimSpace(line)) > 0 {
			var st rawStep
			if json.Unmarshal(line, &st) == nil {
				steps = append(steps, st)
			}
		}
		if err != nil {
			if err == io.EOF {
				break
			}
			return nil, err
		}
	}
	return steps, nil
}

func readSelectedSteps(path string, need map[int]bool) (map[int]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := make(map[int]string, len(need))
	br := bufio.NewReaderSize(f, 64*1024)
	for len(out) < len(need) {
		line, err := br.ReadBytes('\n')
		if len(bytes.TrimSpace(line)) > 0 {
			var probe struct {
				StepIndex int    `json:"step_index"`
				Output    string `json:"output"`
			}
			if json.Unmarshal(line, &probe) == nil && need[probe.StepIndex] {
				out[probe.StepIndex] = probe.Output
			}
		}
		if err != nil {
			break
		}
	}
	return out, nil
}

func clipString(s string) string {
	if !utf8.ValidString(s) {
		s = strings.ToValidUTF8(s, "\uFFFD")
	}
	if utf8.RuneCountInString(s) <= maxClipRunes {
		return s
	}
	n := 0
	for i := range s {
		if n == maxClipRunes {
			return s[:i] + "\n…"
		}
		n++
	}
	return s
}

func clipMap(m map[string]any) (map[string]any, bool) {
	out := make(map[string]any, len(m))
	clipped := false
	for k, v := range m {
		cv, c := clipAny(v)
		out[k] = cv
		clipped = clipped || c
	}
	return out, clipped
}

func clipAny(v any) (any, bool) {
	switch x := v.(type) {
	case string:
		c := clipString(x)
		return c, c != x
	case map[string]any:
		return clipMap(x)
	case []any:
		out := make([]any, len(x))
		clipped := false
		for i, item := range x {
			cv, c := clipAny(item)
			out[i] = cv
			clipped = clipped || c
		}
		return out, clipped
	default:
		return v, false
	}
}

func realModelName(m string) bool {
	return m != "" && !strings.HasPrefix(m, "<")
}

var agyTimeLayouts = []string{
	time.RFC3339Nano,
	"2006-01-02 15:04:05.999999999Z07:00",
	"2006-01-02 15:04:05Z07:00",
	"2006-01-02 15:04:05.999999999-07:00",
	"2006-01-02 15:04:05-07:00",
}

func parseISOMillis(s string) int64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	for _, layout := range agyTimeLayouts {
		if t, err := time.Parse(layout, s); err == nil {
			if t.Year() <= 1971 {
				return 0
			}
			return t.UnixMilli()
		}
	}
	return 0
}
