package claudecfg

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// AgySidecar 描述一个 Antigravity 后台 Sidecar 服务及其启用状态。
type AgySidecar struct {
	ID            string   `json:"id"`
	DisplayName   string   `json:"displayName"`
	Description   string   `json:"description,omitempty"`
	Command       string   `json:"command,omitempty"`
	Builtin       string   `json:"builtin,omitempty"`
	Args          []string `json:"args,omitempty"`
	RestartPolicy string   `json:"restartPolicy,omitempty"`
	Plugin        string   `json:"plugin,omitempty"`
	Enabled       bool     `json:"enabled"`
	ProjectID     string   `json:"projectId,omitempty"`
}

func agyGlobalConfigPath(home string) string {
	return agyConfigDir(home) + "/config.json"
}

// ListSidecars 发现用户目录与插件目录中的 sidecar.json，并合并 config.json 的启停状态。
func (s *Store) ListSidecars() ([]AgySidecar, error) {
	cfg, err := readJSONMap(agyGlobalConfigPath(s.home))
	if err != nil {
		return nil, err
	}
	states := subMap(cfg, "sidecars")
	out := []AgySidecar{}
	out = append(out, scanSidecarDir(filepath.Join(agyConfigDir(s.home), "sidecars"), "", states)...)
	pluginsDir := filepath.Join(agyConfigDir(s.home), "plugins")
	if entries, err := os.ReadDir(pluginsDir); err == nil {
		for _, de := range entries {
			if !de.IsDir() {
				continue
			}
			dir := filepath.Join(pluginsDir, de.Name(), "sidecars")
			out = append(out, scanSidecarDir(dir, de.Name(), states)...)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

// SetSidecarEnabled 更新 ~/.gemini/config/config.json 中 sidecars.<id>.enabled（及可选 projectId），保留其他键。
func (s *Store) SetSidecarEnabled(id string, enabled bool, projectID *string) ([]AgySidecar, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, errors.New("sidecar ID 不能为空")
	}
	err := mutateJSONMap(agyGlobalConfigPath(s.home), func(cfg map[string]any) error {
		sidecars := ensureSubMap(cfg, "sidecars")
		entry := ensureSubMap(sidecars, id)
		entry["enabled"] = enabled
		if projectID != nil {
			setTrimmedOrDelete(entry, "projectId", *projectID)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.ListSidecars()
}

func scanSidecarDir(dir, plugin string, states map[string]any) []AgySidecar {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []AgySidecar
	for _, de := range entries {
		if !de.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, de.Name(), "sidecar.json"))
		if err != nil {
			continue
		}
		var spec struct {
			DisplayName   string   `json:"display_name"`
			Description   string   `json:"description"`
			Command       string   `json:"command"`
			Builtin       string   `json:"builtin"`
			Args          []string `json:"args"`
			RestartPolicy string   `json:"restart_policy"`
		}
		if json.Unmarshal(data, &spec) != nil {
			continue
		}
		id := de.Name()
		if plugin != "" {
			id = plugin + "/" + id
		}
		display := strings.TrimSpace(spec.DisplayName)
		if display == "" {
			display = de.Name()
		}
		sc := AgySidecar{
			ID:            id,
			DisplayName:   display,
			Description:   strings.TrimSpace(spec.Description),
			Command:       strings.TrimSpace(spec.Command),
			Builtin:       strings.TrimSpace(spec.Builtin),
			Args:          spec.Args,
			RestartPolicy: strings.TrimSpace(spec.RestartPolicy),
			Plugin:        plugin,
		}
		if st := subMap(states, id); st != nil {
			sc.Enabled = mapBool(st, "enabled")
			sc.ProjectID = mapString(st, "projectId")
		}
		out = append(out, sc)
	}
	return out
}
