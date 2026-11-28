package claudecfg

import (
	"encoding/json"
	"slices"
	"strings"
)

// AgyOrchestrator 是 Antigravity 规划/执行双模型配置（settings.json 的 orchestrator）。
type AgyOrchestrator struct {
	Planner  string `json:"planner"`
	Executor string `json:"executor"`
}

// AgySandboxProxy 是终端沙箱代理配置；读回时不返回密码明文，只返回 HasPassword。
type AgySandboxProxy struct {
	HTTP        string `json:"http"`
	HTTPS       string `json:"https"`
	NoProxy     string `json:"noProxy"`
	Username    string `json:"username"`
	HasPassword bool   `json:"hasPassword"`
}

// AgyGCP 是 Antigravity 的 GCP 项目与区域配置（settings.json 的 gcp）。
type AgyGCP struct {
	Project  string `json:"project"`
	Location string `json:"location"`
}

// AgySettings 是 ~/.gemini/antigravity-cli/settings.json 中面向用户的专有设置视图。
type AgySettings struct {
	ToolPermission          string          `json:"toolPermission"`
	EnableTerminalSandbox   bool            `json:"enableTerminalSandbox"`
	AllowNonWorkspaceAccess bool            `json:"allowNonWorkspaceAccess"`
	ArtifactReviewPolicy    string          `json:"artifactReviewPolicy"`
	UseG1Credits            bool            `json:"useG1Credits"`
	QueuedMessages          string          `json:"queuedMessages"`
	Orchestrator            AgyOrchestrator `json:"orchestrator"`
	SandboxProxy            AgySandboxProxy `json:"sandboxProxy"`
	TrustedWorkspaces       []string        `json:"trustedWorkspaces"`
	CustomModelsConfig      string          `json:"customModelsConfig"`
	GCP                     AgyGCP          `json:"gcp"`
}

// AgySandboxProxyPatch 用于局部更新 sandboxProxy；Password 为空且 ClearPassword 为假时保留原密码。
type AgySandboxProxyPatch struct {
	HTTP          *string `json:"http,omitempty"`
	HTTPS         *string `json:"https,omitempty"`
	NoProxy       *string `json:"noProxy,omitempty"`
	Username      *string `json:"username,omitempty"`
	Password      *string `json:"password,omitempty"`
	ClearPassword bool    `json:"clearPassword,omitempty"`
}

// AgySettingsPatch 用于局部更新 ~/.gemini/antigravity-cli/settings.json（非空指针字段才改动）。
type AgySettingsPatch struct {
	ToolPermission          *string               `json:"toolPermission,omitempty"`
	EnableTerminalSandbox   *bool                 `json:"enableTerminalSandbox,omitempty"`
	AllowNonWorkspaceAccess *bool                 `json:"allowNonWorkspaceAccess,omitempty"`
	ArtifactReviewPolicy    *string               `json:"artifactReviewPolicy,omitempty"`
	UseG1Credits            *bool                 `json:"useG1Credits,omitempty"`
	QueuedMessages          *string               `json:"queuedMessages,omitempty"`
	Orchestrator            *AgyOrchestrator      `json:"orchestrator,omitempty"`
	SandboxProxy            *AgySandboxProxyPatch `json:"sandboxProxy,omitempty"`
	TrustedWorkspaces       *[]string             `json:"trustedWorkspaces,omitempty"`
	CustomModelsConfig      *string               `json:"customModelsConfig,omitempty"`
	GCP                     *AgyGCP               `json:"gcp,omitempty"`
}

// ReadAgySettings 读取 ~/.gemini/antigravity-cli/settings.json 的 Antigravity 专有设置。
func (s *Store) ReadAgySettings() (AgySettings, error) {
	path := agySettingsPath(s.home)
	raw, err := readJSONMap(path)
	if err != nil {
		return AgySettings{}, err
	}
	return parseAgySettings(raw), nil
}

// UpdateAgySettings 按 patch 局部更新 ~/.gemini/antigravity-cli/settings.json，保留未知字段。
func (s *Store) UpdateAgySettings(patch AgySettingsPatch) (AgySettings, error) {
	path := agySettingsPath(s.home)
	var out AgySettings
	err := mutateJSONMap(path, func(raw map[string]any) error {
		applyAgySettingsPatch(raw, patch)
		out = parseAgySettings(raw)
		return nil
	})
	return out, err
}

func readJSONMap(path string) (map[string]any, error) {
	doc, err := load(path, MaxText)
	if err != nil {
		return nil, err
	}
	if !doc.Exists || strings.TrimSpace(doc.Content) == "" {
		return map[string]any{}, nil
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(doc.Content), &m); err != nil {
		return nil, err
	}
	if m == nil {
		m = map[string]any{}
	}
	return m, nil
}

func mutateJSONMap(path string, fn func(map[string]any) error) error {
	for range 3 {
		doc, err := load(path, MaxText)
		if err != nil {
			return err
		}
		m := map[string]any{}
		expected := "-"
		if doc.Exists {
			expected = doc.SHA
			if strings.TrimSpace(doc.Content) != "" {
				if err := json.Unmarshal([]byte(doc.Content), &m); err != nil {
					return err
				}
			}
		}
		if err := fn(m); err != nil {
			return err
		}
		buf, err := json.MarshalIndent(m, "", "  ")
		if err != nil {
			return err
		}
		buf = append(buf, '\n')
		if _, err := store(path, buf, expected, ""); err == ErrConflict {
			continue
		} else {
			return err
		}
	}
	return ErrConflict
}

func parseAgySettings(m map[string]any) AgySettings {
	out := AgySettings{
		ToolPermission:          mapString(m, "toolPermission"),
		EnableTerminalSandbox:   mapBool(m, "enableTerminalSandbox"),
		AllowNonWorkspaceAccess: mapBool(m, "allowNonWorkspaceAccess"),
		ArtifactReviewPolicy:    mapString(m, "artifactReviewPolicy"),
		UseG1Credits:            mapBool(m, "useG1Credits"),
		QueuedMessages:          mapString(m, "queuedMessages"),
		TrustedWorkspaces:       mapStringSlice(m, "trustedWorkspaces"),
		CustomModelsConfig:      mapString(m, "customModelsConfig"),
	}
	if orch := subMap(m, "orchestrator"); orch != nil {
		out.Orchestrator = AgyOrchestrator{
			Planner:  mapString(orch, "planner"),
			Executor: mapString(orch, "executor"),
		}
	}
	if proxy := subMap(m, "sandboxProxy"); proxy != nil {
		out.SandboxProxy = AgySandboxProxy{
			HTTP:        mapString(proxy, "http"),
			HTTPS:       mapString(proxy, "https"),
			NoProxy:     mapString(proxy, "noProxy"),
			Username:    mapString(proxy, "username"),
			HasPassword: mapString(proxy, "password") != "",
		}
	}
	if gcp := subMap(m, "gcp"); gcp != nil {
		out.GCP = AgyGCP{
			Project:  mapString(gcp, "project"),
			Location: mapString(gcp, "location"),
		}
	}
	return out
}

func applyAgySettingsPatch(m map[string]any, p AgySettingsPatch) {
	setOptionalString(m, "toolPermission", p.ToolPermission)
	setOptionalBool(m, "enableTerminalSandbox", p.EnableTerminalSandbox)
	setOptionalBool(m, "allowNonWorkspaceAccess", p.AllowNonWorkspaceAccess)
	setOptionalString(m, "artifactReviewPolicy", p.ArtifactReviewPolicy)
	setOptionalBool(m, "useG1Credits", p.UseG1Credits)
	setOptionalString(m, "queuedMessages", p.QueuedMessages)
	setOptionalString(m, "customModelsConfig", p.CustomModelsConfig)
	if p.TrustedWorkspaces != nil {
		clean := cleanStringList(*p.TrustedWorkspaces)
		if len(clean) == 0 {
			delete(m, "trustedWorkspaces")
		} else {
			m["trustedWorkspaces"] = clean
		}
	}
	if p.Orchestrator != nil {
		applyOrchestratorPatch(m, *p.Orchestrator)
	}
	if p.SandboxProxy != nil {
		applyProxyPatch(m, *p.SandboxProxy)
	}
	if p.GCP != nil {
		applyGCPPatch(m, *p.GCP)
	}
}

func applyOrchestratorPatch(m map[string]any, o AgyOrchestrator) {
	sub := ensureSubMap(m, "orchestrator")
	setTrimmedOrDelete(sub, "planner", o.Planner)
	setTrimmedOrDelete(sub, "executor", o.Executor)
	if len(sub) == 0 {
		delete(m, "orchestrator")
	}
}

func applyProxyPatch(m map[string]any, p AgySandboxProxyPatch) {
	sub := ensureSubMap(m, "sandboxProxy")
	setOptionalString(sub, "http", p.HTTP)
	setOptionalString(sub, "https", p.HTTPS)
	setOptionalString(sub, "noProxy", p.NoProxy)
	setOptionalString(sub, "username", p.Username)
	if p.ClearPassword {
		delete(sub, "password")
	} else if p.Password != nil && *p.Password != "" {
		sub["password"] = *p.Password
	}
	if len(sub) == 0 {
		delete(m, "sandboxProxy")
	}
}

func applyGCPPatch(m map[string]any, g AgyGCP) {
	sub := ensureSubMap(m, "gcp")
	setTrimmedOrDelete(sub, "project", g.Project)
	setTrimmedOrDelete(sub, "location", g.Location)
	if len(sub) == 0 {
		delete(m, "gcp")
	}
}

func setOptionalString(m map[string]any, key string, v *string) {
	if v == nil {
		return
	}
	setTrimmedOrDelete(m, key, *v)
}

func setTrimmedOrDelete(m map[string]any, key, v string) {
	if s := strings.TrimSpace(v); s == "" {
		delete(m, key)
	} else {
		m[key] = s
	}
}

func setOptionalBool(m map[string]any, key string, v *bool) {
	if v != nil {
		m[key] = *v
	}
}

func ensureSubMap(m map[string]any, key string) map[string]any {
	if existing := subMap(m, key); existing != nil {
		return existing
	}
	sub := map[string]any{}
	m[key] = sub
	return sub
}

func subMap(m map[string]any, key string) map[string]any {
	v, _ := m[key].(map[string]any)
	return v
}

func mapString(m map[string]any, key string) string {
	s, _ := m[key].(string)
	return strings.TrimSpace(s)
}

func mapBool(m map[string]any, key string) bool {
	b, _ := m[key].(bool)
	return b
}

func mapStringSlice(m map[string]any, key string) []string {
	arr, _ := m[key].([]any)
	out := make([]string, 0, len(arr))
	for _, it := range arr {
		if s, ok := it.(string); ok && strings.TrimSpace(s) != "" {
			out = append(out, strings.TrimSpace(s))
		}
	}
	return out
}

func cleanStringList(in []string) []string {
	out := make([]string, 0, len(in))
	for _, raw := range in {
		s := strings.TrimSpace(raw)
		if s != "" && !slices.Contains(out, s) {
			out = append(out, s)
		}
	}
	return out
}
