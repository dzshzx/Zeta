package claudecfg

import (
	"os"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
)

// ~/.claude/agents 下的 Subagent 文件：前言写 name、description、model、tools，正文是系统提示词。改写时只重写这四个键，
// 界面不管的键（color、permissionMode、skills……）按原文行留着。

// Subagent 是一个 Subagent 文件。
type Subagent struct {
	File        string   `json:"file"` // 相对 ~/.claude/agents 的路径
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // null 是没写 tools，可以用全部工具
	Prompt      string   `json:"prompt"`
	ExtraKeys   []string `json:"extraKeys"` // 界面不管、原样留着的键
	SHA         string   `json:"sha"`       // 读到时内容的 sha256，保存前判断文件在编辑期间有没有被改过
}

// SubagentDraft 是编辑页上的内容。
type SubagentDraft struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // null 是全部工具
	Prompt      string   `json:"prompt"`
}

var (
	agentKnown       = []string{"name", "description", "model", "tools"}
	agentNamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)
)

// ValidAgentName：小写字母、数字和连字符，以字母或数字开头，最长 64。
func ValidAgentName(name string) bool { return agentNamePattern.MatchString(name) }

func (s *Store) agentsRoot() string { return s.activeSpec().agentsDir(s.Home()) }

// Agents 列出 ~/.claude/agents（或 ~/.gemini/config/agents）下（含子目录，跟着符号链接走）的 Subagent，按路径排。
func (s *Store) Agents() []Subagent {
	dir := s.agentsRoot()
	out := []Subagent{}
	for _, rel := range agentFiles(dir, "", 0) {
		data, size, ok := readHead(dir+"/"+rel, MaxText+1)
		if !ok || size > MaxText {
			continue
		}
		a := parseAgent(rel, string(data))
		a.SHA = sha256Hex(data)
		out = append(out, a)
	}
	return out
}

// agentFiles 是 dir/rel 下的 .md（相对 dir），排好序；最多往下 8 层（防符号链接成环）。
func agentFiles(dir, rel string, depth int) []string {
	if depth > 8 {
		return nil
	}
	entries, _ := os.ReadDir(filepath.Join(dir, rel))
	var out []string
	for _, e := range entries {
		name := path.Join(rel, e.Name())
		st, err := os.Stat(filepath.Join(dir, name))
		switch {
		case err != nil:
		case st.IsDir():
			out = append(out, agentFiles(dir, name, depth+1)...)
		case st.Mode().IsRegular() && strings.HasSuffix(name, ".md"):
			out = append(out, name)
		}
	}
	slices.Sort(out)
	return out
}

func parseAgent(file, text string) Subagent {
	frontText, body, _ := splitFront(text)
	meta := parseFront(frontText)
	a := Subagent{
		File: file, Name: meta.get("name"), Model: meta.get("model"), Prompt: strings.Trim(body, "\n"),
		// 有的文件把换行写成字面上的 \n
		Description: strings.ReplaceAll(meta.get("description"), `\n`, "\n"),
	}
	if v, ok := meta.values["tools"]; ok && v.fields == nil {
		a.Tools = []string{}
		if v.isList {
			a.Tools = append(a.Tools, v.items...)
		} else {
			for _, t := range strings.Split(v.text, ",") {
				if t = strings.TrimSpace(t); t != "" {
					a.Tools = append(a.Tools, t)
				}
			}
		}
	}
	for _, k := range meta.keys {
		if !slices.Contains(agentKnown, k) {
			a.ExtraKeys = append(a.ExtraKeys, k)
		}
	}
	return a
}

// DraftProblem 是草稿的名字和说明的问题；没问题时为 nil。
func DraftProblem(d SubagentDraft) error {
	switch {
	case !ValidAgentName(d.Name):
		return locales.Err("cfgAgentBadName")
	case strings.TrimSpace(d.Description) == "":
		return locales.Err("cfgAgentNoDescription")
	}
	return nil
}

// relativeProblem：相对路径不出 agents 目录、以 .md 结尾才能动。
func relativeProblem(file string) error {
	bad := file == "" || strings.HasPrefix(file, "/") || !strings.HasSuffix(file, ".md") || hasControl(file)
	for _, p := range strings.Split(file, "/") {
		if p == "" || p == "." || p == ".." || strings.HasPrefix(p, ".") {
			bad = true
		}
	}
	if bad {
		return locales.Err("cfgAgentNotEditable")
	}
	return nil
}

// agentTarget：编辑时放在原文件所在的子目录里，文件名跟着名字走；新建放在 agents 目录下。
func agentTarget(original, name string) string {
	if i := strings.LastIndexByte(original, '/'); i > 0 {
		return original[:i] + "/" + name + ".md"
	}
	return name + ".md"
}

// renderAgent 拼出文件内容；original 是原文件的内容（新建为空），从里面留下不认识的键。
func renderAgent(d SubagentDraft, original string) string {
	lines := []string{"---", "name: " + strings.TrimSpace(d.Name), "description: " + yamlValue(strings.TrimSpace(d.Description))}
	if m := strings.TrimSpace(d.Model); m != "" {
		lines = append(lines, "model: "+yamlValue(m))
	}
	if d.Tools != nil {
		var cleaned []string
		for _, t := range d.Tools {
			if t = strings.TrimSpace(t); t != "" {
				cleaned = append(cleaned, t)
			}
		}
		if len(cleaned) == 0 {
			lines = append(lines, "tools: []")
		} else {
			lines = append(lines, "tools: "+yamlValue(strings.Join(cleaned, ", ")))
		}
	}
	if frontText, _, ok := splitFront(original); ok {
		for _, b := range frontBlocks(frontText) {
			if !slices.Contains(agentKnown, b.key) && strings.TrimSpace(strings.Join(b.lines, "")) != "" {
				lines = append(lines, b.lines...)
			}
		}
	}
	lines = append(lines, "---")
	return strings.Join(lines, "\n") + "\n\n" + strings.Trim(d.Prompt, "\n") + "\n"
}

// SaveAgent 保存一个 Subagent，original 为 nil 是新建；改了名字时旧文件删掉。返回保存后的文件（相对 ~/.claude/agents）。
func (s *Store) SaveAgent(original *Subagent, d SubagentDraft) (string, error) {
	d.Name = strings.TrimSpace(d.Name)
	if err := DraftProblem(d); err != nil {
		return "", err
	}
	if original != nil {
		if err := relativeProblem(original.File); err != nil {
			return "", err
		}
	}
	dir := s.agentsRoot()
	target := agentTarget("", d.Name)
	var originalText string
	expected, removeAfter := "-", ""
	if original != nil {
		target = agentTarget(original.File, d.Name)
		doc, err := load(dir+"/"+original.File, MaxText)
		if err != nil {
			return "", err
		}
		if doc.Exists && original.SHA != "" && doc.SHA != original.SHA {
			return "", locales.Err("cfgAgentChanged")
		}
		originalText = doc.Content
		if target == original.File {
			expected = "*"
		} else {
			removeAfter = dir + "/" + original.File
		}
	}
	_, err := store(dir+"/"+target, []byte(renderAgent(d, originalText)), expected, removeAfter)
	if err == ErrConflict {
		return "", locales.Err("cfgAgentExists", "name", d.Name)
	}
	return target, err
}

// DeleteAgent 删掉一个 Subagent 文件。
func (s *Store) DeleteAgent(file string) error {
	if err := relativeProblem(file); err != nil {
		return err
	}
	if err := os.Remove(s.agentsRoot() + "/" + file); err != nil && !os.IsNotExist(err) {
		return locales.Err("cfgDeleteFailed", "reason", pathReason(err))
	}
	return nil
}
