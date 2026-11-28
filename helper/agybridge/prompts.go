package agybridge

import (
	"cmp"
	"encoding/json"
	"slices"
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

	if bypass && toolName != "AskUserQuestion" && toolName != "ask_question" {
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
	b.registerPrompt(reqID, "permission", rpcID, p.Options, reqBody)
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
	b.registerPrompt(reqID, "elicitation", rpcID, nil, reqBody)
}

func (b *bridge) registerPrompt(reqID, kind string, rpcID json.RawMessage, opts []acpPermOpt, reqBody map[string]any) {
	wireMap := map[string]any{"type": "control_request", "request_id": reqID, "request": reqBody}
	wireBytes, _ := json.Marshal(wireMap)
	b.mu.Lock()
	b.prompts = append(b.prompts, &pendingPrompt{reqID: reqID, rpcID: rpcID, kind: kind, options: opts, wireLine: wireBytes})
	b.mu.Unlock()
	b.emitRaw(wireBytes)
}

func (b *bridge) handlePromptResponse(reqID string, resp json.RawMessage) {
	b.mu.Lock()
	var pp *pendingPrompt
	if idx := slices.IndexFunc(b.prompts, func(p *pendingPrompt) bool { return p.reqID == reqID }); idx >= 0 {
		pp = b.prompts[idx]
		b.prompts = slices.Delete(b.prompts, idx, idx+1)
	}
	b.mu.Unlock()
	if pp == nil {
		return
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
		return
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
		if optID == "" && len(pp.options) > 0 {
			optID = pp.options[0].OptionID
		}
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": optID}})
		return
	}
	if perm.Interrupt {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
		return
	}
	rejID := pickPermOption(pp.options, "reject_once", "reject_always")
	if rejID != "" {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "selected", "optionId": rejID}})
	} else {
		_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
	}
}

func (b *bridge) cancelPendingPrompts() {
	b.mu.Lock()
	prompts := b.prompts
	b.prompts = nil
	b.mu.Unlock()

	for _, pp := range prompts {
		if pp.kind == "elicitation" {
			_ = b.acp.reply(pp.rpcID, map[string]any{"action": "cancel"})
		} else {
			_ = b.acp.reply(pp.rpcID, map[string]any{"outcome": map[string]any{"outcome": "cancelled"}})
		}
		b.emit(map[string]any{"type": "control_cancel_request", "request_id": pp.reqID})
	}
}

func (b *bridge) pendingPermissionSnapshot() []json.RawMessage {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := []json.RawMessage{}
	for _, pp := range b.prompts {
		out = append(out, pp.wireLine)
	}
	return out
}

func pickPermOption(opts []acpPermOpt, kinds ...string) string {
	for _, want := range kinds {
		for _, o := range opts {
			if o.Kind == want && o.OptionID != "" {
				return o.OptionID
			}
		}
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
