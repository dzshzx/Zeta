package claudecfg

import (
	"cmp"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Antigravity CLI（agy）的配置目录与映射：
//   - CLI 设置：~/.gemini/antigravity-cli/settings.json（agentMode、toolPermission、model、permissions）
//   - 全局定制：~/.gemini/config/{rules,skills,agents,mcp_config.json,skills.json}
//   - 工作区定制：<cwd>/AGENTS.md、<cwd>/GEMINI.md、<cwd>/.agents/{rules,skills}

func agySettingsPath(home string) string {
	return resolve(home, ".gemini/antigravity-cli/settings.json")
}

func agyConfigDir(home string) string {
	return resolve(home, ".gemini/config")
}

func agyRulesDir(home string) string {
	return agyConfigDir(home) + "/rules"
}

func agySkillsDir(home string) string {
	return agyConfigDir(home) + "/skills"
}

func agyAgentsDir(home string) string {
	return agyConfigDir(home) + "/agents"
}

func agyMcpConfigPath(home string) string {
	return agyConfigDir(home) + "/mcp_config.json"
}

// AgyCommonTools 与 AgyOtherTools 是 Antigravity CLI 的内置工具列表（供工具权限页展示）。
var AgyCommonTools = []ToolInfo{
	tool("run_command", locales.M("toolBash"), "run_command(git push:*)"),
	tool("read_file", locales.M("toolRead"), "read_file(./.env)"),
	tool("edit_file", locales.M("toolEdit"), "edit_file(src/**)"),
	tool("write_file", locales.M("toolWrite"), ""),
	tool("grep_search", locales.M("toolGrep"), ""),
	tool("find_by_name", locales.M("toolGlob"), ""),
	tool("search_web", locales.M("toolWebSearch"), ""),
	tool("invoke_subagent", locales.M("toolAgent"), ""),
}

var AgyOtherTools = []ToolInfo{
	tool("ask_question", locales.M("toolAskUserQuestion"), ""),
	tool("update_steps", locales.M("toolUpdateSteps"), ""),
	tool("run_workflow", locales.M("toolWorkflow"), ""),
	tool("manage_task", locales.M("toolMonitor"), ""),
	tool("schedule", locales.M("toolCronCreate"), ""),
	tool("send_message", locales.M("toolSendMessage"), ""),
}

// AgyBuiltinGroup 返回 agy 全部内置工具组成的权限分组。
func AgyBuiltinGroup() PermissionGroup {
	var names []string
	for _, t := range append(slices.Clone(AgyCommonTools), AgyOtherTools...) {
		names = append(names, t.Name)
	}
	return PermissionGroup{Tools: names}
}

func agyDefaultsOf(s *object) Defaults {
	d := defaultsOf(s)
	d.PermissionMode = agyModeToZeta(nonBlank(s, "agentMode"), nonBlank(s, "toolPermission"))
	return d
}

// AgyDefaultModeFromSettings 从 ~/.gemini/antigravity-cli/settings.json 的 JSON 解析默认权限模式。
func AgyDefaultModeFromSettings(data []byte) string {
	var raw struct {
		AgentMode      string `json:"agentMode"`
		ToolPermission string `json:"toolPermission"`
	}
	if json.Unmarshal(data, &raw) != nil {
		return ""
	}
	return agyModeToZeta(strings.TrimSpace(raw.AgentMode), strings.TrimSpace(raw.ToolPermission))
}

func agyModeToZeta(agentMode, toolPerm string) string {
	if toolPerm == "always-proceed" {
		return "bypassPermissions"
	}
	switch agentMode {
	case "accept-edits":
		return "acceptEdits"
	case "auto":
		return "auto"
	case "plan":
		return "plan"
	case "default":
		return "default"
	}
	return ""
}

func applyAgyDefaultMode(s *object, mode string) {
	switch mode {
	case "bypassPermissions":
		s.set("agentMode", "auto")
		s.set("toolPermission", "always-proceed")
	case "acceptEdits":
		s.set("agentMode", "accept-edits")
		clearAlwaysProceed(s)
	case "auto":
		s.set("agentMode", "auto")
		clearAlwaysProceed(s)
	case "plan":
		s.set("agentMode", "plan")
		clearAlwaysProceed(s)
	default:
		s.del("agentMode")
		s.set("toolPermission", "request-review")
	}
}

func clearAlwaysProceed(s *object) {
	if nonBlank(s, "toolPermission") == "always-proceed" {
		s.set("toolPermission", "request-review")
	}
}

// agyMemory 把 agy 的全局规则（~/.gemini/config/rules）与工作区规则（<cwd>/AGENTS.md、<cwd>/.agents/rules）映射成记忆页结构。
func (s *Store) agyMemory(cwd, project string) Memory {
	home := s.Home()
	cwd = cmp.Or(cwd, home)
	wsRoot := agyWorkspaceRoot(cwd, home)

	globalDir := agyRulesDir(home)
	globalInstr := ConfigFile{Path: globalDir + "/AGENTS.md"}
	if _, size, ok := readHead(globalInstr.Path, 0); ok {
		globalInstr.Exists, globalInstr.Size = true, size
	}

	projects := []MemoryProject{}
	if wsRoot != "" && wsRoot != home {
		wsRules := wsRoot + "/.agents/rules"
		projects = append(projects, MemoryProject{
			Key:     wsRoot,
			Label:   wsRoot,
			Count:   countMarkdownFiles(wsRules),
			Default: true,
		})
	}
	projects = append(projects, MemoryProject{
		Key:     "global",
		Label:   "~/.gemini/config/rules",
		Count:   countMarkdownFiles(globalDir),
		Default: len(projects) == 0,
	})

	selected := projects[0].Key
	for _, p := range projects {
		if p.Key == project {
			selected = p.Key
			break
		}
	}

	m := Memory{
		AutoMemory:   true,
		Instructions: globalInstr,
		Projects:     projects,
		Selected:     selected,
	}
	if selected == "global" {
		m.Dir = globalDir
		m.Files = listRuleFiles(globalDir, "AGENTS.md")
	} else {
		m.Dir = selected + "/.agents/rules"
		idx := s.workspaceInstructionsFile(selected)
		m.Index = &idx
		m.Files = listRuleFiles(m.Dir, "")
	}
	return m
}

func agyWorkspaceRoot(cwd, home string) string {
	real, g := probeGit(cwd)
	if real == "" {
		return resolve(home, cwd)
	}
	return cmp.Or(canonicalRoot(g), real)
}

func (s *Store) workspaceInstructionsFile(wsRoot string) ConfigFile {
	agents := wsRoot + "/AGENTS.md"
	if _, size, ok := readHead(agents, 0); ok {
		return ConfigFile{Path: agents, Exists: true, Size: size}
	}
	gemini := wsRoot + "/GEMINI.md"
	if _, size, ok := readHead(gemini, 0); ok {
		return ConfigFile{Path: gemini, Exists: true, Size: size}
	}
	return ConfigFile{Path: agents}
}

func countMarkdownFiles(dir string) int {
	entries, _ := os.ReadDir(dir)
	n := 0
	for _, e := range entries {
		if !e.IsDir() && isMemoryFileName(e.Name()) && e.Name() != "AGENTS.md" {
			n++
		}
	}
	return n
}

func listRuleFiles(dir, skipName string) []MemoryEntry {
	entries, _ := os.ReadDir(dir)
	var out []MemoryEntry
	for _, e := range entries {
		name := e.Name()
		if name == skipName || !isMemoryFileName(name) {
			continue
		}
		p := dir + "/" + name
		if head, size, ok := readHead(p, 4096); ok {
			out = append(out, memoryEntry(p, size, head))
		}
	}
	slices.SortFunc(out, func(a, b MemoryEntry) int { return cmp.Compare(a.FileName, b.FileName) })
	return out
}

func (s *Store) checkAgyEditable(path string) error {
	if path != resolve("/", path) {
		return locales.Err("cfgBadPath")
	}
	name := baseName(path)
	if !isMemoryFileName(name) {
		return locales.Err("cfgOnlyMemoryFiles")
	}
	dir := parentDir(path)
	if dir == agyRulesDir(s.Home()) {
		return nil
	}
	if name == "AGENTS.md" || name == "GEMINI.md" {
		if st, err := os.Stat(dir); err == nil && st.IsDir() {
			return nil
		}
	}
	if strings.HasSuffix(dir, "/.agents/rules") || strings.HasSuffix(dir, "/.agent/rules") {
		return nil
	}
	return locales.Err("cfgNotEditable")
}

func (s *Store) agySkills() Skills {
	settings := s.lenientSettings()
	out := Skills{User: []Skill{}, Synced: []SyncedSkill{}}
	seen := map[string]bool{}

	primary := agySkillsDir(s.Home())
	for _, sk := range readSkillDir(primary, settings) {
		seen[sk.Name] = true
		out.User = append(out.User, sk)
	}

	extraDirs := append([]string{resolve(s.Home(), ".agents/skills")}, declaredSkillDirs(s.Home())...)
	for _, d := range extraDirs {
		for _, sk := range readSkillDir(d, settings) {
			if seen[sk.Name] {
				continue
			}
			seen[sk.Name] = true
			out.Synced = append(out.Synced, SyncedSkill{
				Name:        sk.Name,
				Description: sk.Description,
				Enabled:     sk.Enabled,
			})
		}
	}
	slices.SortFunc(out.User, func(a, b Skill) int { return cmp.Compare(a.Name, b.Name) })
	slices.SortFunc(out.Synced, func(a, b SyncedSkill) int { return cmp.Compare(a.Name, b.Name) })
	return out
}

func readSkillDir(root string, settings *object) []Skill {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	var out []Skill
	for _, e := range entries {
		p := filepath.Join(root, e.Name())
		if st, err := os.Stat(p); err != nil || !st.IsDir() || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		sk := Skill{Name: e.Name()}
		if head, _, ok := readHead(p+"/SKILL.md", 8192); ok {
			sk.Valid = true
			text, _, _ := splitFront(string(head))
			meta := parseFront(text)
			sk.Title, sk.Description = meta.get("name"), meta.get("description")
		}
		sk.Enabled = skillOverride(settings, sk.Name, sk.Title) != "off"
		out = append(out, sk)
	}
	return out
}

func declaredSkillDirs(home string) []string {
	data, _, ok := readHead(agyConfigDir(home)+"/skills.json", 512*1024)
	if !ok {
		return nil
	}
	var cfg struct {
		Entries []struct {
			Path string `json:"path"`
		} `json:"entries"`
	}
	if json.Unmarshal(data, &cfg) != nil {
		return nil
	}
	var dirs []string
	for _, e := range cfg.Entries {
		p := strings.TrimSpace(e.Path)
		if rest, ok := strings.CutPrefix(p, "~/"); ok {
			p = resolve(home, rest)
		} else if !strings.HasPrefix(p, "/") {
			continue
		}
		dirs = append(dirs, p)
	}
	return dirs
}

// AgyModelItem 是 `agy models` 输出的一项模型。
type AgyModelItem struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// ReadAgyModels 在登录环境里运行 `agy models` 读取可用模型列表；失败或未安装时返回空切片，不报错。
func (s *Store) ReadAgyModels(ctx context.Context) []AgyModelItem {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	const script = `PATH="$PATH:$HOME/.local/bin"; exec agy models`
	text, code, err := run(ctx, s.loginCommand(ctx, script, nil, nil), nil)
	if err != nil || code != 0 {
		return []AgyModelItem{}
	}
	return ParseAgyModelsOutput(text)
}

// ParseAgyModelsOutput 解析 `agy models` 的制表符分隔输出（<id>\t<displayName>）。
func ParseAgyModelsOutput(text string) []AgyModelItem {
	out := []AgyModelItem{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		id, display, ok := strings.Cut(line, "\t")
		if !ok || id == "" {
			continue
		}
		out = append(out, AgyModelItem{ID: strings.TrimSpace(id), DisplayName: cmp.Or(strings.TrimSpace(display), strings.TrimSpace(id))})
	}
	return out
}
