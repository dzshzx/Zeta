package agystore

import (
	"encoding/json"
	"slices"
	"strings"
)

// SwarmMember 是 Antigravity 协同工作板（swarm）中的一位成员。
type SwarmMember struct {
	Name           string `json:"name"`
	Model          string `json:"model,omitempty"`
	Role           string `json:"role,omitempty"`
	Status         string `json:"status"` // "working" | "waiting" | "stopped" | "killed"
	ConversationID string `json:"conversationId,omitempty"`
}

// SwarmState 是当前对话中通过 swarm 工具建立的协同工作板状态。
type SwarmState struct {
	Title   string        `json:"title,omitempty"`
	Members []SwarmMember `json:"members"`
}

// ApplySwarmCall 根据单次 swarm 工具调用的入参与输出增量更新 SwarmState。
func ApplySwarmCall(state *SwarmState, rawInput json.RawMessage, outputText string) *SwarmState {
	var in struct {
		Action string   `json:"action"`
		Title  string   `json:"title"`
		Name   string   `json:"name"`
		Model  string   `json:"model"`
		Brief  string   `json:"brief"`
		Tasks  []string `json:"tasks"`
		N      int      `json:"n"`
	}
	if json.Unmarshal(rawInput, &in) != nil {
		return state
	}
	action := strings.TrimSpace(in.Action)
	if action == "" {
		return state
	}
	if state == nil {
		state = &SwarmState{Members: []SwarmMember{}}
	}
	switch action {
	case "title":
		if t := strings.TrimSpace(in.Title); t != "" {
			state.Title = t
		}
	case "add":
		applySwarmAdd(state, strings.TrimSpace(in.Name), strings.TrimSpace(in.Model), firstLineBrief(in.Brief), len(in.Tasks), in.N)
	case "model":
		if name := strings.TrimSpace(in.Name); name != "" {
			if m := findSwarmMember(state, name); m != nil && strings.TrimSpace(in.Model) != "" {
				m.Model = strings.TrimSpace(in.Model)
			}
		}
	case "kill":
		if name := strings.TrimSpace(in.Name); name != "" {
			if m := findSwarmMember(state, name); m != nil {
				m.Status = "killed"
			}
		}
	}
	mergeSwarmOutput(state, outputText)
	if state.Title == "" && len(state.Members) == 0 {
		return nil
	}
	return state
}

// CloneSwarm 深拷贝 SwarmState，避免对外发布时发生并发修改。
func CloneSwarm(s *SwarmState) *SwarmState {
	if s == nil {
		return nil
	}
	return &SwarmState{
		Title:   s.Title,
		Members: slices.Clone(s.Members),
	}
}

func applySwarmAdd(state *SwarmState, base, model, role string, taskCount, n int) {
	if base == "" {
		return
	}
	count := max(taskCount, n)
	if count > 1 {
		for i := 1; i <= count; i++ {
			upsertSwarmMember(state, base+"-"+itoaSmall(i), model, role, "working")
		}
		return
	}
	upsertSwarmMember(state, base, model, role, "working")
}

func upsertSwarmMember(state *SwarmState, name, model, role, status string) {
	if m := findSwarmMember(state, name); m != nil {
		if model != "" {
			m.Model = model
		}
		if role != "" && m.Role == "" {
			m.Role = role
		}
		if status != "" {
			m.Status = status
		}
		return
	}
	state.Members = append(state.Members, SwarmMember{
		Name:   name,
		Model:  model,
		Role:   role,
		Status: status,
	})
}

func findSwarmMember(state *SwarmState, name string) *SwarmMember {
	for i := range state.Members {
		if state.Members[i].Name == name {
			return &state.Members[i]
		}
	}
	return nil
}

func mergeSwarmOutput(state *SwarmState, outputText string) {
	trimmed := strings.TrimSpace(outputText)
	if trimmed == "" || trimmed[0] != '{' {
		return
	}
	var obj struct {
		Title   string `json:"title"`
		Members []struct {
			Name           string `json:"name"`
			Model          string `json:"model"`
			State          string `json:"state"`
			Status         string `json:"status"`
			ConversationID string `json:"conversationId"`
		} `json:"members"`
	}
	if json.Unmarshal([]byte(trimmed), &obj) != nil {
		return
	}
	if t := strings.TrimSpace(obj.Title); t != "" {
		state.Title = t
	}
	for _, it := range obj.Members {
		name := strings.TrimSpace(it.Name)
		if name == "" {
			continue
		}
		st := strings.TrimSpace(it.Status)
		if st == "" {
			st = strings.TrimSpace(it.State)
		}
		upsertSwarmMember(state, name, strings.TrimSpace(it.Model), "", st)
		if m := findSwarmMember(state, name); m != nil && strings.TrimSpace(it.ConversationID) != "" {
			m.ConversationID = strings.TrimSpace(it.ConversationID)
		}
	}
}

func firstLineBrief(s string) string {
	s = strings.TrimSpace(s)
	if idx := strings.IndexByte(s, '\n'); idx >= 0 {
		s = s[:idx]
	}
	r := []rune(strings.TrimSpace(s))
	if len(r) > 80 {
		return string(r[:80]) + "…"
	}
	return string(r)
}

func itoaSmall(n int) string {
	if n <= 0 {
		return "0"
	}
	var digits [12]byte
	i := len(digits)
	for n > 0 {
		i--
		digits[i] = byte('0' + n%10)
		n /= 10
	}
	return string(digits[i:])
}
