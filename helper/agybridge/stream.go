package agybridge

import (
	"cmp"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/dzshzx/Zeta/helper/agystore"
)

type acpUpdateEnvelope struct {
	SessionID string          `json:"sessionId"`
	Update    json.RawMessage `json:"update"`
}

type acpUpdateHeader struct {
	SessionUpdate string `json:"sessionUpdate"`
}

type acpChunkUpdate struct {
	Content struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"content"`
}

type acpToolUpdate struct {
	ToolCallID string          `json:"toolCallId"`
	Title      string          `json:"title"`
	Kind       string          `json:"kind"`
	Status     string          `json:"status"`
	RawInput   json.RawMessage `json:"rawInput"`
	RawOutput  json.RawMessage `json:"rawOutput"`
	Content    []acpToolBlock  `json:"content"`
	Meta       map[string]any  `json:"_meta"`
}

type acpToolBlock struct {
	Type    string `json:"type"` // "content" | "diff" | "terminal"
	Path    string `json:"path"`
	OldText string `json:"oldText"`
	NewText string `json:"newText"`
	Content struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"content"`
}

type acpPlanUpdate struct {
	Entries []struct {
		Content string `json:"content"`
		Status  string `json:"status"`
	} `json:"entries"`
}

type acpUsageUpdate struct {
	Used int64 `json:"used"`
	Size int64 `json:"size"`
}

type acpInfoUpdate struct {
	Title string `json:"title"`
}

type acpCommandsUpdate struct {
	AvailableCommands []struct {
		Name        string `json:"name"`
		Description string `json:"description"`
		Input       *struct {
			Hint string `json:"hint"`
		} `json:"input"`
	} `json:"availableCommands"`
}

type acpConfigUpdate struct {
	ConfigOptions []acpConfigOption `json:"configOptions"`
}

type trackedTool struct {
	name      string
	inputSent bool
	done      bool
	rawInput  json.RawMessage
	patch     map[string]any
	texts     []string
}

type turnStream struct {
	turnSeq      int
	msgID        string
	startedMsg   bool
	blockIndex   int
	openKind     string // "" | "thinking" | "text"
	openBuf      strings.Builder
	tools        map[string]*trackedTool
	hasStepsTool bool
	syntheticSeq int
}

func newTurnStream(turnSeq int) *turnStream {
	return &turnStream{
		turnSeq: turnSeq,
		msgID:   fmt.Sprintf("agy-turn-%d", turnSeq),
		tools:   map[string]*trackedTool{},
	}
}

func (b *bridge) handleACPNotify(method string, params json.RawMessage) {
	if method != "session/update" {
		return
	}
	var env acpUpdateEnvelope
	if json.Unmarshal(params, &env) != nil || len(env.Update) == 0 {
		return
	}
	var hdr acpUpdateHeader
	if json.Unmarshal(env.Update, &hdr) != nil {
		return
	}
	switch hdr.SessionUpdate {
	case "agent_thought_chunk":
		b.onTextOrThoughtChunk(env.Update, "thinking")
	case "agent_message_chunk":
		b.onTextOrThoughtChunk(env.Update, "text")
	case "tool_call", "tool_call_update":
		b.onToolUpdate(env.Update)
	case "plan":
		b.onPlanUpdate(env.Update)
	case "usage_update":
		b.onUsageUpdate(env.Update)
	case "session_info_update":
		b.onSessionInfoUpdate(env.Update)
	case "available_commands_update":
		b.onCommandsUpdate(env.Update)
	case "config_option_update":
		var cu acpConfigUpdate
		if json.Unmarshal(env.Update, &cu) == nil {
			b.applyConfigOptions(cu.ConfigOptions)
		}
	}
}

func (b *bridge) onTextOrThoughtChunk(raw json.RawMessage, kind string) {
	var u acpChunkUpdate
	if json.Unmarshal(raw, &u) != nil || u.Content.Text == "" {
		return
	}
	b.streamM.Lock()
	defer b.streamM.Unlock()
	ts := b.curStream
	if ts == nil {
		return
	}
	b.ensureStreamMsg(ts)
	if ts.openKind != kind {
		b.closeOpenBlock(ts)
		ts.openKind = kind
		ts.openBuf.Reset()
		block := map[string]any{"type": kind}
		if kind == "thinking" {
			block["thinking"] = ""
		} else {
			block["text"] = ""
		}
		b.emitStreamEvent(map[string]any{
			"type":          "content_block_start",
			"index":         ts.blockIndex,
			"content_block": block,
		})
	}
	ts.openBuf.WriteString(u.Content.Text)
	delta := map[string]any{"type": "text_delta", "text": u.Content.Text}
	if kind == "thinking" {
		delta = map[string]any{"type": "thinking_delta", "thinking": u.Content.Text}
	}
	b.emitStreamEvent(map[string]any{
		"type":  "content_block_delta",
		"index": ts.blockIndex,
		"delta": delta,
	})
}

func (b *bridge) onToolUpdate(raw json.RawMessage) {
	var u acpToolUpdate
	if json.Unmarshal(raw, &u) != nil || u.ToolCallID == "" {
		return
	}
	b.streamM.Lock()
	defer b.streamM.Unlock()
	ts := b.curStream
	if ts == nil {
		return
	}
	b.ensureStreamMsg(ts)
	b.closeOpenBlock(ts)

	tr, exists := ts.tools[u.ToolCallID]
	if !exists {
		name := inferToolName(u)
		if name == "update_steps" {
			ts.hasStepsTool = true
		}
		tr = &trackedTool{name: name}
		ts.tools[u.ToolCallID] = tr
		idx := ts.blockIndex
		ts.blockIndex++
		b.emitStreamEvent(map[string]any{
			"type":  "content_block_start",
			"index": idx,
			"content_block": map[string]any{
				"type": "tool_use",
				"id":   u.ToolCallID,
				"name": tr.name,
			},
		})
	}
	b.mergeToolFields(ts, u.ToolCallID, tr, u)
}

func (b *bridge) mergeToolFields(ts *turnStream, callID string, tr *trackedTool, u acpToolUpdate) {
	absorbToolUpdate(tr, u)
	finished := u.Status == "completed" || u.Status == "failed"
	if !tr.inputSent && (len(tr.rawInput) > 0 || finished) {
		tr.inputSent = true
		b.emitAssistantBlocks(ts.msgID, []any{
			map[string]any{"type": "tool_use", "id": callID, "name": tr.name, "input": normalizeInputObj(tr.rawInput)},
		})
	}
	if !finished || tr.done {
		return
	}
	tr.done = true
	b.emitFinishedToolResult(callID, tr, u)
}

func absorbToolUpdate(tr *trackedTool, u acpToolUpdate) {
	if hasNonEmptyJSONObject(u.RawInput) {
		tr.rawInput = u.RawInput
		if name := inferToolName(u); name != "" && tr.name == "tool" {
			tr.name = name
		}
	}
	for _, blk := range u.Content {
		switch blk.Type {
		case "diff":
			if p := buildToolPatch(blk.OldText, blk.NewText); p != nil {
				tr.patch = p
			}
		case "content":
			if blk.Content.Text != "" {
				tr.texts = []string{blk.Content.Text}
			}
		}
	}
}

func (b *bridge) emitFinishedToolResult(callID string, tr *trackedTool, u acpToolUpdate) {
	resText := strings.Join(tr.texts, "\n")
	if resText == "" && len(u.RawOutput) > 0 && string(u.RawOutput) != "null" {
		resText = stringifyOutput(u.RawOutput)
	}
	ev := map[string]any{
		"type": "user",
		"message": map[string]any{
			"content": []any{
				map[string]any{
					"type":        "tool_result",
					"tool_use_id": callID,
					"is_error":    u.Status == "failed",
					"content":     resText,
				},
			},
		},
	}
	if tr.patch != nil {
		ev["tool_use_result"] = tr.patch
	}
	b.emit(ev)
}

func (b *bridge) onPlanUpdate(raw json.RawMessage) {
	var p acpPlanUpdate
	if json.Unmarshal(raw, &p) != nil || len(p.Entries) == 0 {
		return
	}
	b.streamM.Lock()
	defer b.streamM.Unlock()
	ts := b.curStream
	if ts == nil || ts.hasStepsTool {
		return
	}
	b.closeOpenBlock(ts)
	ts.syntheticSeq++
	callID := fmt.Sprintf("%s-plan-%d", ts.msgID, ts.syntheticSeq)
	todos := make([]map[string]any, 0, len(p.Entries))
	for _, e := range p.Entries {
		if strings.TrimSpace(e.Content) == "" {
			continue
		}
		todos = append(todos, map[string]any{
			"content":    e.Content,
			"activeForm": e.Content,
			"status":     cmp.Or(e.Status, "pending"),
		})
	}
	if len(todos) == 0 {
		return
	}
	b.emitAssistantBlocks(ts.msgID, []any{
		map[string]any{"type": "tool_use", "id": callID, "name": "TodoWrite", "input": map[string]any{"todos": todos}},
	})
	b.emit(map[string]any{
		"type": "user",
		"message": map[string]any{
			"content": []any{map[string]any{"type": "tool_result", "tool_use_id": callID, "content": "ok"}},
		},
	})
}

func (b *bridge) onUsageUpdate(raw json.RawMessage) {
	var u acpUsageUpdate
	if json.Unmarshal(raw, &u) != nil {
		return
	}
	b.mu.Lock()
	if u.Size > 0 {
		b.windowSize = u.Size
	}
	if u.Used > 0 {
		b.contextTokens = u.Used
	}
	used := b.contextTokens
	b.mu.Unlock()

	if used > 0 {
		b.emitStreamEvent(map[string]any{
			"type":  "message_delta",
			"usage": map[string]any{"input_tokens": used},
		})
	}
}

func (b *bridge) onSessionInfoUpdate(raw json.RawMessage) {
	var u acpInfoUpdate
	if json.Unmarshal(raw, &u) != nil {
		return
	}
	title := strings.TrimSpace(u.Title)
	if title == "" {
		return
	}
	b.mu.Lock()
	sid := b.sessionID
	b.title = title
	b.mu.Unlock()
	if sid != "" {
		agystore.WriteZetaMeta(b.agyDir, sid, agystore.ZetaMeta{Title: title})
	}
	b.emit(map[string]any{"type": "system", "subtype": "session_title_changed", "title": title})
}

func (b *bridge) onCommandsUpdate(raw json.RawMessage) {
	var u acpCommandsUpdate
	if json.Unmarshal(raw, &u) != nil {
		return
	}
	cmds := make([]map[string]any, 0, len(u.AvailableCommands))
	for _, c := range u.AvailableCommands {
		if c.Name == "" || strings.HasPrefix(c.Name, "__") {
			continue
		}
		item := map[string]any{"name": c.Name, "description": c.Description}
		if c.Input != nil && c.Input.Hint != "" {
			item["argumentHint"] = c.Input.Hint
		}
		cmds = append(cmds, item)
	}
	data, _ := json.Marshal(cmds)
	b.mu.Lock()
	b.commands = data
	b.mu.Unlock()
	select {
	case b.commandsReady <- struct{}{}:
	default:
	}
}

func (b *bridge) ensureStreamMsg(ts *turnStream) {
	if ts.startedMsg {
		return
	}
	ts.startedMsg = true
	b.mu.Lock()
	model := b.currentModel
	b.mu.Unlock()
	b.emitStreamEvent(map[string]any{
		"type":    "message_start",
		"message": map[string]any{"id": ts.msgID, "model": model},
	})
}

func (b *bridge) closeOpenBlock(ts *turnStream) {
	if ts.openKind == "" {
		return
	}
	kind := ts.openKind
	text := ts.openBuf.String()
	idx := ts.blockIndex
	ts.blockIndex++
	ts.openKind = ""
	ts.openBuf.Reset()

	b.emitStreamEvent(map[string]any{"type": "content_block_stop", "index": idx})
	block := map[string]any{"type": kind}
	if kind == "thinking" {
		block["thinking"] = text
	} else {
		block["text"] = text
	}
	b.emitAssistantBlocks(ts.msgID, []any{block})
}

func (b *bridge) emitStreamEvent(ev map[string]any) {
	b.emit(map[string]any{"type": "stream_event", "event": ev})
}

func (b *bridge) emitAssistantBlocks(msgID string, blocks []any) {
	b.mu.Lock()
	model := b.currentModel
	b.mu.Unlock()
	b.emit(map[string]any{
		"type": "assistant",
		"message": map[string]any{
			"id":      msgID,
			"model":   model,
			"content": blocks,
		},
	})
}

func inferToolName(u acpToolUpdate) string {
	for _, k := range []string{"toolName", "name", "tool_name"} {
		if s, ok := u.Meta[k].(string); ok && s != "" {
			return normalizeMCPToolName(s)
		}
	}
	var in map[string]any
	_ = json.Unmarshal(u.RawInput, &in)
	if n := inferFromInputKeys(in, u.Kind); n != "" {
		return n
	}
	t := strings.TrimSpace(u.Title)
	if t != "" && !strings.ContainsAny(t, " \t\n/") {
		return normalizeMCPToolName(t)
	}
	switch u.Kind {
	case "execute":
		return "run_command"
	case "read":
		return "read_file"
	case "edit":
		return "edit_file"
	case "search":
		return "grep_search"
	case "fetch":
		return "search_web"
	}
	return cmp.Or(t, "tool")
}

func inferFromInputKeys(in map[string]any, kind string) string {
	if len(in) == 0 {
		return ""
	}
	switch {
	case in["questions"] != nil:
		return "AskUserQuestion"
	case in["steps"] != nil:
		return "update_steps"
	case in["TypeName"] != nil && in["Prompt"] != nil:
		return "invoke_subagent"
	case in["CommandLine"] != nil:
		return "run_command"
	case in["Target"] != nil && in["Replacement"] != nil:
		return "edit_file"
	case in["Content"] != nil && in["Path"] != nil:
		return "write_file"
	case in["SearchPath"] != nil && in["Query"] != nil:
		return "grep_search"
	case in["SearchDirectory"] != nil && in["Pattern"] != nil:
		return "find_by_name"
	case in["ServerName"] != nil && in["ToolName"] != nil:
		srv, _ := in["ServerName"].(string)
		tl, _ := in["ToolName"].(string)
		if srv != "" && tl != "" {
			return "mcp__" + srv + "__" + tl
		}
	case in["Path"] != nil && kind == "read":
		return "read_file"
	case in["query"] != nil:
		return "search_web"
	}
	return ""
}

func normalizeMCPToolName(name string) string {
	if strings.HasPrefix(name, "mcp_phone_") {
		return "mcp__phone__" + strings.TrimPrefix(name, "mcp_phone_")
	}
	if rest, ok := strings.CutPrefix(name, "phone:"); ok && rest != "" {
		return "mcp__phone__" + rest
	}
	return name
}

func hasNonEmptyJSONObject(raw json.RawMessage) bool {
	s := strings.TrimSpace(string(raw))
	return strings.HasPrefix(s, "{") && s != "{}"
}

func stringifyOutput(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var m map[string]any
	if json.Unmarshal(raw, &m) == nil {
		for _, k := range []string{"output", "content", "text", "result"} {
			if v, ok := m[k].(string); ok && v != "" {
				return v
			}
		}
	}
	return string(raw)
}
