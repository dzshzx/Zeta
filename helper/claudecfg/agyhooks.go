package claudecfg

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
)

// AgyHookEvents 是 Antigravity hooks.json 支持的生命周期事件顺序。
var AgyHookEvents = []string{
	"SessionStart",
	"PreInvocation",
	"PreToolUse",
	"PostToolUse",
	"PostInvocation",
	"Stop",
}

// AgyHookCommand 是一条钩子命令。
type AgyHookCommand struct {
	Type    string `json:"type,omitempty"`
	Command string `json:"command"`
	Timeout int    `json:"timeout,omitempty"`
}

// AgyHookMatcher 是一条按工具名/模式匹配的规则组（PreToolUse/PostToolUse）。
type AgyHookMatcher struct {
	Matcher string           `json:"matcher,omitempty"`
	Hooks   []AgyHookCommand `json:"hooks"`
}

// AgyHookEntry 是 hooks.json 中的一个命名钩子条目。
type AgyHookEntry struct {
	Name           string           `json:"name"`
	Scope          string           `json:"scope"` // "user" | "project" | "plugin"
	Plugin         string           `json:"plugin,omitempty"`
	ReadOnly       bool             `json:"readOnly,omitempty"`
	Enabled        bool             `json:"enabled"`
	Event          string           `json:"event"`
	Matcher        string           `json:"matcher,omitempty"`
	Command        string           `json:"command"`
	Timeout        int              `json:"timeout,omitempty"`
	Events         []string         `json:"events,omitempty"`
	CommandCount   int              `json:"commandCount"`
	RawEvents      map[string][]any `json:"-"`
	PreToolUse     []AgyHookMatcher `json:"preToolUse,omitempty"`
	PostToolUse    []AgyHookMatcher `json:"postToolUse,omitempty"`
	SessionStart   []AgyHookCommand `json:"sessionStart,omitempty"`
	PreInvocation  []AgyHookCommand `json:"preInvocation,omitempty"`
	PostInvocation []AgyHookCommand `json:"postInvocation,omitempty"`
	Stop           []AgyHookCommand `json:"stop,omitempty"`
}

// AgyHookDraft 用于新建或修改 user/project 级钩子。
type AgyHookDraft struct {
	OriginalName string `json:"originalName,omitempty"`
	Name         string `json:"name"`
	Scope        string `json:"scope"` // "user" | "project"
	Enabled      bool   `json:"enabled"`
	Event        string `json:"event"`
	Matcher      string `json:"matcher,omitempty"`
	Command      string `json:"command"`
	Timeout      int    `json:"timeout,omitempty"`
}

// AgyHooksView 是 `config.hooks.read` 返回的聚合视图。
type AgyHooksView struct {
	UserPath    string         `json:"userPath"`
	ProjectPath string         `json:"projectPath,omitempty"`
	Hooks       []AgyHookEntry `json:"hooks"`
}

func agyUserHooksPath(home string) string {
	return agyConfigDir(home) + "/hooks.json"
}

func agyProjectHooksPath(cwd string) string {
	cwd = strings.TrimSpace(cwd)
	if cwd == "" {
		return ""
	}
	for _, dir := range []string{".agents", ".agent", "_agents", "_agent"} {
		p := filepath.Join(cwd, dir, "hooks.json")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return filepath.Join(cwd, ".agents", "hooks.json")
}

// ReadHooks 聚合读取用户级、工作区级与已安装插件中的 hooks.json。
func (s *Store) ReadHooks(cwd string) (AgyHooksView, error) {
	userPath := agyUserHooksPath(s.home)
	projectPath := agyProjectHooksPath(cwd)
	out := AgyHooksView{
		UserPath:    userPath,
		ProjectPath: projectPath,
		Hooks:       []AgyHookEntry{},
	}
	userHooks, err := loadHooksFile(userPath, "user", "", false)
	if err != nil {
		return out, err
	}
	out.Hooks = append(out.Hooks, userHooks...)
	if projectPath != "" {
		projHooks, err := loadHooksFile(projectPath, "project", "", false)
		if err != nil {
			return out, err
		}
		out.Hooks = append(out.Hooks, projHooks...)
	}
	out.Hooks = append(out.Hooks, s.loadPluginHooks()...)
	return out, nil
}

// SetHookEnabled 切换 user 或 project 级钩子的 enabled 状态。
func (s *Store) SetHookEnabled(cwd, scope, name string, enabled bool) (AgyHooksView, error) {
	path, err := s.writableHooksPath(cwd, scope)
	if err != nil {
		return AgyHooksView{}, err
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return AgyHooksView{}, errors.New("钩子名称不能为空")
	}
	err = mutateJSONMap(path, func(m map[string]any) error {
		entry := subMap(m, name)
		if entry == nil {
			return errors.New("找不到钩子：" + name)
		}
		entry["enabled"] = enabled
		return nil
	})
	if err != nil {
		return AgyHooksView{}, err
	}
	return s.ReadHooks(cwd)
}

// SaveHook 新建或更新 user 或 project 级钩子。
func (s *Store) SaveHook(cwd string, draft AgyHookDraft) (AgyHooksView, error) {
	path, err := s.writableHooksPath(cwd, draft.Scope)
	if err != nil {
		return AgyHooksView{}, err
	}
	name := strings.TrimSpace(draft.Name)
	event := strings.TrimSpace(draft.Event)
	cmd := strings.TrimSpace(draft.Command)
	if name == "" || cmd == "" {
		return AgyHooksView{}, errors.New("钩子名称和命令不能为空")
	}
	if !slices.Contains(AgyHookEvents, event) {
		return AgyHooksView{}, errors.New("不支持的钩子事件：" + event)
	}
	err = mutateJSONMap(path, func(m map[string]any) error {
		if orig := strings.TrimSpace(draft.OriginalName); orig != "" && orig != name {
			delete(m, orig)
		}
		m[name] = encodeHookDraft(draft, event, cmd)
		return nil
	})
	if err != nil {
		return AgyHooksView{}, err
	}
	return s.ReadHooks(cwd)
}

// DeleteHook 删除 user 或 project 级钩子。
func (s *Store) DeleteHook(cwd, scope, name string) (AgyHooksView, error) {
	path, err := s.writableHooksPath(cwd, scope)
	if err != nil {
		return AgyHooksView{}, err
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return AgyHooksView{}, errors.New("钩子名称不能为空")
	}
	err = mutateJSONMap(path, func(m map[string]any) error {
		delete(m, name)
		return nil
	})
	if err != nil {
		return AgyHooksView{}, err
	}
	return s.ReadHooks(cwd)
}

func (s *Store) writableHooksPath(cwd, scope string) (string, error) {
	switch strings.TrimSpace(scope) {
	case "", "user":
		return agyUserHooksPath(s.home), nil
	case "project":
		p := agyProjectHooksPath(cwd)
		if p == "" {
			return "", errors.New("未提供工作目录，无法写入项目级钩子")
		}
		return p, nil
	default:
		return "", errors.New("插件钩子为只读，无法修改")
	}
}

func (s *Store) loadPluginHooks() []AgyHookEntry {
	dir := filepath.Join(agyConfigDir(s.home), "plugins")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []AgyHookEntry
	for _, de := range entries {
		if !de.IsDir() {
			continue
		}
		hooks, err := loadHooksFile(filepath.Join(dir, de.Name(), "hooks.json"), "plugin", de.Name(), true)
		if err == nil {
			out = append(out, hooks...)
		}
	}
	return out
}

func loadHooksFile(path, scope, plugin string, readOnly bool) ([]AgyHookEntry, error) {
	m, err := readJSONMap(path)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(m))
	for k := range m {
		names = append(names, k)
	}
	sort.Strings(names)
	out := make([]AgyHookEntry, 0, len(names))
	for _, name := range names {
		raw := subMap(m, name)
		if raw == nil {
			continue
		}
		out = append(out, parseHookEntry(name, scope, plugin, readOnly, raw))
	}
	return out, nil
}

func parseHookEntry(name, scope, plugin string, readOnly bool, raw map[string]any) AgyHookEntry {
	enabled := true
	if v, ok := raw["enabled"].(bool); ok {
		enabled = v
	}
	entry := AgyHookEntry{
		Name:     name,
		Scope:    scope,
		Plugin:   plugin,
		ReadOnly: readOnly,
		Enabled:  enabled,
	}
	for _, ev := range AgyHookEvents {
		v, ok := raw[ev]
		if !ok {
			continue
		}
		buf, err := json.Marshal(v)
		if err != nil {
			continue
		}
		entry.Events = append(entry.Events, ev)
		populateHookEvent(&entry, ev, buf)
	}
	return entry
}

func populateHookEvent(entry *AgyHookEntry, ev string, buf []byte) {
	if ev == "PreToolUse" || ev == "PostToolUse" {
		var matchers []AgyHookMatcher
		if json.Unmarshal(buf, &matchers) != nil {
			return
		}
		if ev == "PreToolUse" {
			entry.PreToolUse = matchers
		} else {
			entry.PostToolUse = matchers
		}
		for _, m := range matchers {
			entry.CommandCount += len(m.Hooks)
			if entry.Event == "" && len(m.Hooks) > 0 {
				entry.Event = ev
				entry.Matcher = m.Matcher
				entry.Command = m.Hooks[0].Command
				entry.Timeout = m.Hooks[0].Timeout
			}
		}
		return
	}
	var cmds []AgyHookCommand
	if json.Unmarshal(buf, &cmds) != nil {
		return
	}
	switch ev {
	case "SessionStart":
		entry.SessionStart = cmds
	case "PreInvocation":
		entry.PreInvocation = cmds
	case "PostInvocation":
		entry.PostInvocation = cmds
	case "Stop":
		entry.Stop = cmds
	}
	entry.CommandCount += len(cmds)
	if entry.Event == "" && len(cmds) > 0 {
		entry.Event = ev
		entry.Command = cmds[0].Command
		entry.Timeout = cmds[0].Timeout
	}
}

func encodeHookDraft(d AgyHookDraft, event, cmd string) map[string]any {
	hc := map[string]any{
		"type":    "command",
		"command": cmd,
	}
	if d.Timeout > 0 {
		hc["timeout"] = d.Timeout
	}
	entry := map[string]any{
		"enabled": d.Enabled,
	}
	if event == "PreToolUse" || event == "PostToolUse" {
		matcher := map[string]any{"hooks": []any{hc}}
		if m := strings.TrimSpace(d.Matcher); m != "" {
			matcher["matcher"] = m
		}
		entry[event] = []any{matcher}
	} else {
		entry[event] = []any{hc}
	}
	return entry
}
