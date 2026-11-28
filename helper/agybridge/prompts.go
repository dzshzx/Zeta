package agybridge

import (
	"cmp"
	"encoding/json"
	"strconv"
	"strings"
)

type acpPermParams struct {
	SessionID string        `json:"sessionId"`
	ToolCall  acpToolUpdate `json:"toolCall"`
	Options   []acpPermOpt  `json:"options"`
}

type acpPermOpt struct {
	OptionID string `json:"optionId"`
	Name     string `json:"name"`
	Kind     string `json:"kind"` // allow_once, allow_always, reject_once, reject_always
}

type acpElicitParams struct {
	ServerName      string          `json:"serverName"`
	Message         string          `json:"message"`
	Mode            string          `json:"mode"`
	URL             string          `json:"url"`
	RequestedSchema json.RawMessage `json:"requestedSchema"`
}

type pendingPrompt struct {
	reqID    string
	rpcID    json.RawMessage
	kind     string // "permission" | "elicitation"
	options  []acpPermOpt
	wireLine json.RawMessage
}

func (b *bridge) handleACPRequest(rpcID json.RawMessage, method string, params json.RawMessage) {
	switch method {
	case "session/request_permission":
		b.onPermissionRequest(rpcID, params)
	case "elicitation/create":
		b.onElicitationRequest(rpcID, params)
	default:
		_ = b.acp.replyError(rpcID, -32601, "Method not found: "+method)
	}
}

func (b *bridge) onPermissionRequest(rpcID json.RawMessage, rawParams json.RawMessage) {
	var p acpPermParams
	if json.Unmarshal(rawParams, &p) != nil {
		_ = b.acp.replyError(rpcID, -32602, "Invalid params")
		return
	}
	toolName := inferToolName(p.ToolCall)
	input := normalizeInputObj(p.ToolCall.RawInput)

	b.mu.Lock()
	bypass := b.userMode == "bypassPermissions"
	b.promptSeq++
	reqID := "agy-perm-" + strconv.Itoa(b.promptSeq)
	b.mu.Unlock()

	if bypass && toolName != "AskUserQuestion" {
		if allowID := pickPermOption(p.Options, "allow_once", "allow_always"); allowID != "" {
			_ = b.acp.reply(rpcID, map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": allowID}})
			return
		}
	}

	reqBody := map[string]any{
		"subtype":     "can_use_tool",
		"tool_name":   toolName,
		"tool_use_id": p.ToolCall.ToolCallID,
		"input":       input,
	}
	if p.ToolCall.Title != "" && p.ToolCall.Title != toolName {
		reqBody["decision_reason"] = p.ToolCall.Title
	}
	if pickPermOption(p.Options, "allow_always") != "" {
		reqBody["permission_suggestions"] = []any{
			map[string]any{
				"type":        "addRules",
				"behavior":    "allow",
				"destination": "session",
				"rules":       []any{map[string]any{"toolName": toolName}},
			},
		}
	}
	wireMap := map[string]any{"type": "control_request", "request_id": reqID, "request": reqBody}
	wireBytes, _ := json.Marshal(wireMap)

	b.mu.Lock()
	b.prompts[reqID] = &pendingPrompt{reqID: reqID, rpcID: rpcID, kind: "permission", options: p.Options, wireLine: wireBytes}
	b.promptOrder = append(b.promptOrder, reqID)
	b.mu.Unlock()

	b.emitRaw(wireBytes)
}

func (b *bridge) onElicitationRequest(rpcID json.RawMessage, rawParams json.RawMessage) {
	var p acpElicitParams
	if json.Unmarshal(rawParams, &p) != nil {
		_ = b.acp.replyError(rpcID, -32602, "Invalid params")
		return
	}
	b.mu.Lock()
	b.promptSeq++
	reqID := "agy-elicit-" + strconv.Itoa(b.promptSeq)
	b.mu.Unlock()

	reqBody := map[string]any{
		"subtype":         "elicitation",
		"mcp_server_name": cmp.Or(strings.TrimSpace(p.ServerName), "Antigravity"),
		"message":         p.Message,
		"mode":            p.Mode,
		"url":             p.URL,
	}
	if len(p.RequestedSchema) > 0 {
		reqBody["requested_schema"] = p.RequestedSchema
	}
	wireMap := map[string]any{"type": "control_request", "request_id": reqID, "request": reqBody}
	wireBytes, _ := json.Marshal(wireMap)

	b.mu.Lock()
	b.prompts[reqID] = &pendingPrompt{reqID: reqID, rpcID: rpcID, kind: "elicitation", wireLine: wireBytes}
	b.promptOrder = append(b.promptOrder, reqID)
	b.mu.Unlock()

	b.emitRaw(wireBytes)
}

func (b *bridge) handlePromptResponse(reqID string, resp json.RawMessage) bool {
	b.mu.Lock()
	pp, ok := b.prompts[reqID]
	if ok {
		delete(b.prompts, reqID)
		b.removePromptOrderLocked(reqID)
	}
	b.mu.Unlock()
	if !ok {
		return false
	}
	if pp.kind == "elicitation" {
		var el struct {
			Action  string          `json:"action"`
			Content json.RawMessage `json:"content"`
		}
		_ = json.Unmarshal(resp, &el)
		res := map[string]any{"action": cmp.Or(el.Action, "cancel")}
		if len(el.Content) > 0 {
			res["content"] = el.Content
		}
		_ = b.acp.reply(pp.rpcID, res)
		return true
	}

	var perm struct {
		Behavior           string            `json:"behavior"`
		UpdatedPermissions []json.RawMessage `json:"updatedPermissions"`
		Interrupt          bool              `json:"interrupt"`
	}
	_ = json.Unmarshal(resp, &perm)
	if perm.Behavior == "allow" {
		optID := pickPermOption(pp.options, "allow_once", "allow_always")
		if len(perm.UpdatedPermissions) > 0 {
			if always := pickPermOption(pp.options, "allow_always"); always != "" {
				optID = always
			}
		}
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": optID}})
		return true
	}
	if perm.Interrupt {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
		return true
	}
	rejID := pickPermOption(pp.options, "reject_once", "reject_always")
	if rejID != "" {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": rejID}})
	} else {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
	}
	return true
}

func (b *bridge) cancelPendingPrompts() {
	b.mu.Lock()
	order := b.promptOrder
	prompts := b.prompts
	b.promptOrder = nil
	b.prompts = map[string]*pendingPrompt{}
	b.mu.Unlock()

	for _, id := range order {
		pp := prompts[id]
		if pp == nil {
			continue
		}
		if pp.kind == "elicitation" {
			_ = b.acp.reply(pp.rpcID, map[string]any{"action": "cancel"})
		} else {
			_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
		}
		b.emit(map[string]any{"type": "control_cancel_request", "request_id": id})
	}
}

func (b *bridge) pendingPermissionSnapshot() []json.RawMessage {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := []json.RawMessage{}
	for _, id := range b.promptOrder {
		if pp := b.prompts[id]; pp != nil && pp.kind == "permission" {
			out = append(out, pp.wireLine)
		}
	}
	return out
}

func (b *bridge) removePromptOrderLocked(reqID string) {
	kept := b.promptOrder[:0]
	for _, id := range b.promptOrder {
		if id != reqID {
			kept = append(kept, id)
		}
	}
	b.promptOrder = kept
}

func pickPermOption(opts []acpPermOpt, kinds ...string) string {
	for _, want := range kinds {
		for _, o := range opts {
			if o.Kind == want && o.OptionID != "" {
				return o.OptionID
			}
		}
	}
	if len(opts) > 0 {
		return opts[0].OptionID
	}
	return ""
}

func normalizeInputObj(raw json.RawMessage) json.RawMessage {
	trimmed := strings.TrimSpace(string(raw))
	if strings.HasPrefix(trimmed, "{") {
		return raw
	}
	return json.RawMessage(`{}`)
}
