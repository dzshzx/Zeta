package conv

// 临时接线：helper/prompt、helper/usage、helper/tasks 合进来后整份换掉。

import (
	"encoding/json"
	"strconv"
)

type parsedPrompt struct{ input json.RawMessage }

func parsePrompt(line []byte) *Prompt {
	var e struct {
		Type      string `json:"type"`
		RequestID string `json:"request_id"`
		Request   struct {
			Subtype   string          `json:"subtype"`
			ToolUseID string          `json:"tool_use_id"`
			Input     json.RawMessage `json:"input"`
		} `json:"request"`
	}
	if json.Unmarshal(line, &e) != nil || e.Type != "control_request" || e.RequestID == "" {
		return nil
	}
	switch e.Request.Subtype {
	case "can_use_tool", "elicitation":
	default:
		return nil
	}
	return &Prompt{requestID: e.RequestID, toolUseID: e.Request.ToolUseID, permission: e.Request.Subtype == "can_use_tool",
		view: raw(map[string]string{"request_id": e.RequestID}), parsed: parsedPrompt{e.Request.Input}}
}

func permissionResponse(p *Prompt, reply json.RawMessage) (any, bool, error) {
	var r struct {
		Kind string `json:"kind"`
	}
	if err := json.Unmarshal(reply, &r); err != nil {
		return nil, false, err
	}
	if r.Kind == "allow" {
		return map[string]any{"behavior": "allow", "updatedInput": p.parsed.input}, false, nil
	}
	return map[string]any{"behavior": "deny", "message": "User rejected tool use", "interrupt": true}, true, nil
}

func elicitationResponse(_ *Prompt, action string, _ map[string][]string) (any, map[string]string) {
	return map[string]any{"action": action}, nil
}

func taskOutputView(r json.RawMessage) any { return r }

func formatTokens(n int64) string { return strconv.FormatInt(n, 10) }

type noUsage struct{}

func (noUsage) processStarted(bool, int)                  {}
func (noUsage) observe([]byte) bool                       { return false }
func (noUsage) prependHistory([][]byte)                   {}
func (noUsage) contextUsageAnswered(json.RawMessage) bool { return false }
func (noUsage) settingsAnswered(json.RawMessage) bool     { return false }
func (noUsage) view() any                                 { return nil }

type noTasks struct{}

func (noTasks) observe([]byte, int64) bool                { return false }
func (noTasks) stopping(string)                           {}
func (noTasks) stopAnswered(string, json.RawMessage) bool { return false }
func (noTasks) processEnded(int64) bool                   { return false }
func (noTasks) view() any                                 { return []any{} }

func (Deps) newUsage() usageTracker { return noUsage{} }
func (Deps) newTasks() taskBoard    { return noTasks{} }
