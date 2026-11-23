package claudecfg

import (
	"regexp"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Claude Code（2.1.327 实测）的工具权限：settings.json 的 permissions.allow / ask / deny 三张表（用户级，所有项目生效）。
// 改的时候只动那几条规则，permissions 里别的键（defaultMode 等）、表里别的规则和不认识的值原样留着。

// Behavior 是规则所在的表。
type Behavior string

const (
	Allow Behavior = "allow"
	Ask   Behavior = "ask"
	Deny  Behavior = "deny"
)

// behaviors 按“拒绝 > 询问 > 允许”排：列规则、取最严的都按这个顺序。
var behaviors = []Behavior{Deny, Ask, Allow}

// Rule 是一条规则和它所在的表。
type Rule struct {
	Behavior Behavior `json:"behavior"`
	Text     string   `json:"rule"`
}

// Rules 是读出来的规则（只取字符串，别的值留在文件里不动），按拒绝、询问、允许排，每张表里保持文件里的顺序。
type Rules []Rule

// Of 是一张表里的规则。
func (rs Rules) Of(b Behavior) []string {
	var out []string
	for _, r := range rs {
		if r.Behavior == b {
			out = append(out, r.Text)
		}
	}
	return out
}

// toolAliases 是 Claude Code 工具的旧名字，规则里写哪个都一样。
var toolAliases = map[string]string{
	"Task": "Agent", "KillShell": "TaskStop", "KillBash": "TaskStop", "ListPeers": "ListAgents", "Brief": "SendUserMessage",
}

func canonicalTool(name string) string {
	if a, ok := toolAliases[name]; ok {
		return a
	}
	return name
}

// parsedRule 是一条规则按 Claude Code 的写法拆开：工具 或 工具(参数)；括号不配对时 malformed，Claude Code 也不认。
type parsedRule struct {
	tool       string
	content    string
	hasContent bool
	malformed  bool
}

// whole：工具、工具()、工具(*) 都是整个工具（读写文件那几个工具在允许表里例外，见 wholeTool）。
func (p parsedRule) whole() bool {
	return !p.malformed && (!p.hasContent || p.content == "" || p.content == "*")
}

// parseRule 和 Claude Code 一样拆：第一个没转义的 ( 和最后一个没转义的 )，) 要在末尾，工具名里不能再有括号。
func parseRule(rule string) parsedRule {
	if !strings.ContainsAny(rule, "()") {
		return parsedRule{tool: canonicalTool(rule)}
	}
	open, close := firstUnescaped(rule, '('), lastUnescaped(rule, ')')
	if open < 0 && close < 0 {
		return parsedRule{tool: canonicalTool(rule)}
	}
	if open <= 0 || close <= open || close != len(rule)-1 || strings.ContainsAny(rule[:open], "()") {
		return parsedRule{tool: rule, malformed: true}
	}
	content := rule[open+1 : close]
	content = strings.NewReplacer(`\(`, "(", `\)`, ")", `\\`, `\`).Replace(content)
	return parsedRule{tool: canonicalTool(rule[:open]), content: content, hasContent: true}
}

func escapedAt(s string, i int) bool {
	n := 0
	for j := i - 1; j >= 0 && s[j] == '\\'; j-- {
		n++
	}
	return n%2 == 1
}

func firstUnescaped(s string, c byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == c && !escapedAt(s, i) {
			return i
		}
	}
	return -1
}

func lastUnescaped(s string, c byte) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == c && !escapedAt(s, i) {
			return i
		}
	}
	return -1
}

// RuleProblem 是用户输入的一条规则有什么问题；没问题时为 nil。
func RuleProblem(rule string) error {
	switch {
	case strings.TrimSpace(rule) == "":
		return locales.Err("cfgRuleEmpty")
	case rule != strings.TrimSpace(rule):
		return locales.Err("cfgRuleSpaces")
	case strings.ContainsAny(rule, "\n\r\t"):
		return locales.Err("cfgRuleNewline")
	case parseRule(rule).malformed:
		return locales.Err("cfgRuleMalformed")
	}
	return nil
}

// RuleMalformed：括号不配对，Claude Code 认不出这条。
func RuleMalformed(rule string) bool { return parseRule(rule).malformed }

var errInvalidPermissions = locales.Err("cfgPermissionsInvalid")

func permissionsOf(s *object) (*object, error) {
	v, ok := s.get("permissions")
	if !ok {
		return nil, nil
	}
	p, isObj := v.(*object)
	if !isObj {
		return nil, errInvalidPermissions
	}
	return p, nil
}

func rulesOf(s *object) (Rules, error) {
	p, err := permissionsOf(s)
	if err != nil || p == nil {
		return nil, err
	}
	var out Rules
	for _, b := range behaviors {
		for _, r := range stringItems(p.vals[string(b)]) {
			out = append(out, Rule{b, r})
		}
	}
	return out, nil
}

// strongest：拒绝 > 询问 > 允许；都没有时为空。
func strongest(bs []Behavior) Behavior {
	for _, b := range behaviors {
		if slices.Contains(bs, b) {
			return b
		}
	}
	return ""
}

// workingDirStar：允许表里这几个读写文件的工具写成 工具(*) 或 工具() 时，Claude Code 只在工作目录里放行，不算整个工具；
// 拒绝、询问表里照样是整个工具。
var workingDirStar = []string{"Read", "Edit", "Write", "NotebookEdit", "Glob", "Grep", "LSP", "SendUserFile"}

// wholeTool：这条规则在 b 表里是不是管整个工具。
func wholeTool(b Behavior, p parsedRule) bool {
	return p.whole() && !(b == Allow && p.hasContent && slices.Contains(workingDirStar, p.tool))
}

func matchesWhole(b Behavior, rule string, names []string) bool {
	p := parseRule(rule)
	return wholeTool(b, p) && slices.Contains(names, p.tool)
}

// ToolState 是一个工具（或一个 MCP 服务器）在权限页上的状态。
type ToolState struct {
	Behavior  Behavior `json:"behavior"`  // 整个工具的规则定下的结果；空是没写，按权限模式走
	Inherited bool     `json:"inherited"` // Behavior 来自整个 MCP 服务器的规则，不是这个工具自己的
	Specific  []Rule   `json:"specific"`  // 这个工具带参数的规则（比如 Bash(git push:*)）
}

// Tool 是一个内置工具（或 mcp__服务器__工具、整个服务器 mcp__服务器）整体的规则和它带参数的规则。
func (rs Rules) Tool(tool string) ToolState {
	names := []string{canonicalTool(tool)}
	var st ToolState
	var whole []Behavior
	for _, r := range rs {
		p := parseRule(r.Text)
		switch {
		case matchesWhole(r.Behavior, r.Text, names):
			whole = append(whole, r.Behavior)
		case !p.malformed && !wholeTool(r.Behavior, p) && slices.Contains(names, p.tool):
			st.Specific = append(st.Specific, r)
		}
	}
	st.Behavior = strongest(whole)
	return st
}

// Server 是 MCP 服务器整体：mcp__服务器 和 mcp__服务器__* 是一回事。
func (rs Rules) Server(server string) ToolState {
	names := serverNames(server)
	var whole []Behavior
	for _, r := range rs {
		if matchesWhole(r.Behavior, r.Text, names) {
			whole = append(whole, r.Behavior)
		}
	}
	return ToolState{Behavior: strongest(whole)}
}

// McpTool 是 MCP 的一个工具：它自己的规则和整个服务器的规则一起算，取最严的。
func (rs Rules) McpTool(server, tool string) ToolState {
	own := rs.Tool(McpToolName(server, tool))
	whole := rs.Server(server).Behavior
	combined := strongest([]Behavior{own.Behavior, whole})
	own.Inherited = combined != "" && combined != own.Behavior
	own.Behavior = combined
	return own
}

func serverNames(server string) []string {
	n := McpServerName(server)
	return []string{n, n + "__*"}
}

var (
	nonServerChars = regexp.MustCompile(`[^a-zA-Z0-9_-]`)
	underscores    = regexp.MustCompile(`_+`)
)

// McpServerName 是 Claude Code 给 MCP 服务器起的工具名前缀：mcp__服务器，服务器名里字母、数字、_、- 以外的字符换成 _。
func McpServerName(name string) string {
	n := nonServerChars.ReplaceAllString(name, "_")
	if strings.HasPrefix(name, "claude.ai ") {
		n = strings.Trim(underscores.ReplaceAllString(n, "_"), "_")
	}
	return "mcp__" + n
}

// McpToolName 是 mcp__服务器__工具。
func McpToolName(server, tool string) string { return McpServerName(server) + "__" + tool }

// editTables 改 permissions 里的三张表：edit 拿到每张表（没有就是空的），返回新表；表没变就不动它。原来没有的表只在加了
// 规则时才建；原来有的表删空了留着 []，和 Claude Code 自己写的一样。
func editTables(s *object, edit func(b Behavior, list []any) []any) error {
	p, err := permissionsOf(s)
	if err != nil {
		return err
	}
	created := p == nil
	if created {
		p = newObject()
	}
	changed := false
	for _, b := range []Behavior{Allow, Ask, Deny} {
		before, had := p.get(string(b))
		list, isList := before.([]any)
		if had && !isList {
			return errInvalidPermissions
		}
		after := edit(b, slices.Clone(list))
		if compactJSON(after) == compactJSON(list) || (!had && len(after) == 0) {
			continue
		}
		p.set(string(b), after)
		changed = true
	}
	if changed && created {
		s.set("permissions", p)
	}
	return nil
}

func ruleText(v any) string {
	s, _ := v.(string)
	return s
}

// withTool 把整个工具设成 b（空是去掉，回到按权限模式走）：先从三张表里去掉整个工具的写法（含别名、工具()、工具(*)），
// 再在对应的表末尾加一条。带参数的规则不动。MCP 服务器传 mcp__服务器，mcp__服务器__* 也一并换掉。
func withTool(s *object, tool string, b Behavior) error {
	names := []string{canonicalTool(tool)}
	if strings.HasPrefix(tool, "mcp__") && !strings.Contains(tool[5:], "__") {
		names = []string{tool, tool + "__*"}
	}
	name := canonicalTool(tool)
	return editTables(s, func(t Behavior, list []any) []any {
		kept := slices.DeleteFunc(list, func(v any) bool { return matchesWhole(t, ruleText(v), names) })
		if t == b {
			kept = append(kept, name)
		}
		return kept
	})
}

// withRule 把一条规则加到 b 表；同一条在别的表里的先去掉（一条规则只放一处）。已经在了就不动。
func withRule(s *object, b Behavior, rule string) error {
	rs, err := rulesOf(s)
	if err != nil {
		return err
	}
	present := slices.Contains(rs.Of(b), rule)
	return editTables(s, func(t Behavior, list []any) []any {
		if t == b && present {
			return list
		}
		kept := slices.DeleteFunc(list, func(v any) bool { return ruleText(v) == rule })
		if t == b {
			kept = append(kept, rule)
		}
		return kept
	})
}

func withoutRule(s *object, b Behavior, rule string) error {
	return editTables(s, func(t Behavior, list []any) []any {
		if t != b {
			return list
		}
		return slices.DeleteFunc(list, func(v any) bool { return ruleText(v) == rule })
	})
}

var errRuleChanged = locales.Err("cfgRuleChanged")

// replaceRule 把 b 表里的 rule 换成 nb 表里的 nr；同一张表时留在原位（同一条的其余几处、原有的 nr 去掉）。
// 原来那条已经不在了（别人改过文件）时报错，让界面重新读。
func replaceRule(s *object, b Behavior, rule string, nb Behavior, nr string) error {
	rs, err := rulesOf(s)
	if err != nil {
		return err
	}
	if !slices.Contains(rs.Of(b), rule) {
		return errRuleChanged
	}
	if b != nb {
		if err := withoutRule(s, b, rule); err != nil {
			return err
		}
		return withRule(s, nb, nr)
	}
	return editTables(s, func(t Behavior, list []any) []any {
		if t != b {
			return slices.DeleteFunc(list, func(v any) bool { return ruleText(v) == nr })
		}
		replaced := false
		var out []any
		for _, v := range list {
			switch ruleText(v) {
			case rule:
				if !replaced {
					out = append(out, nr)
					replaced = true
				}
			case nr:
			default:
				out = append(out, v)
			}
		}
		return out
	})
}

// PermissionGroup 是一键开启、关闭的一组工具：内置工具（Tools，逐个写规则）或一个 MCP 服务器（Server，写整个服务器的规则）。
type PermissionGroup struct {
	Tools  []string `json:"tools,omitempty"`
	Server string   `json:"server,omitempty"`
}

func (g PermissionGroup) contains(rule string) bool {
	p := parseRule(rule)
	if p.malformed {
		return false
	}
	if g.Server != "" {
		n := McpServerName(g.Server)
		return p.tool == n || strings.HasPrefix(p.tool, n+"__")
	}
	return slices.ContainsFunc(g.Tools, func(t string) bool { return canonicalTool(t) == p.tool })
}

// BulkChange 是一键开启、关闭要加、要删的规则。
type BulkChange struct {
	Add    []Rule `json:"add"`
	Remove []Rule `json:"remove"`
}

// Empty：已经全是那个状态，没什么可改的。
func (c BulkChange) Empty() bool { return len(c.Add) == 0 && len(c.Remove) == 0 }

// Bulk 算整组开启（设为允许）或关闭（设为拒绝）要加、要删的规则。加：内置工具没有通配写法，每个工具一条；MCP 服务器一条整个
// 服务器的规则；已经有了的不加。删：组里每个工具上和目标相反的规则，带参数的也算（开启删拒绝和询问，关闭删允许和询问），不然
// 它们按“拒绝 > 询问 > 允许”会盖住整组的设置。认不出的写法不动。
func (rs Rules) Bulk(g PermissionGroup, enable bool) BulkChange {
	target, keep := Deny, Deny
	if enable {
		target, keep = Allow, Allow
	}
	var ch BulkChange
	for _, r := range rs {
		if r.Behavior != keep && g.contains(r.Text) && !slices.Contains(ch.Remove, r) {
			ch.Remove = append(ch.Remove, r)
		}
	}
	present := rs.Of(target)
	has := func(names []string) bool {
		return slices.ContainsFunc(present, func(r string) bool { return matchesWhole(target, r, names) })
	}
	if g.Server != "" {
		if !has(serverNames(g.Server)) {
			ch.Add = append(ch.Add, Rule{target, McpServerName(g.Server)})
		}
		return ch
	}
	var seen []string
	for _, t := range g.Tools {
		n := canonicalTool(t)
		if !slices.Contains(seen, n) && !has([]string{n}) {
			ch.Add = append(ch.Add, Rule{target, n})
		}
		seen = append(seen, n)
	}
	return ch
}

func withBulk(s *object, g PermissionGroup, enable bool) error {
	rs, err := rulesOf(s)
	if err != nil {
		return err
	}
	ch := rs.Bulk(g, enable)
	if ch.Empty() {
		return nil
	}
	return editTables(s, func(t Behavior, list []any) []any {
		kept := slices.DeleteFunc(list, func(v any) bool { return slices.Contains(ch.Remove, Rule{t, ruleText(v)}) })
		for _, r := range ch.Add {
			if r.Behavior == t {
				kept = append(kept, r.Text)
			}
		}
		return kept
	})
}

// Permissions 读工具权限。
func (s *Store) Permissions() (Rules, error) {
	settings, err := s.readSettings()
	if err != nil {
		return nil, err
	}
	return rulesOf(settings)
}

// editPermissions 按读到的最新内容改，返回写完以后的规则。
func (s *Store) editPermissions(edit func(*object) error) (Rules, error) {
	settings, err := s.updateSettings(edit)
	if err != nil {
		return nil, err
	}
	return rulesOf(settings)
}

// SetToolPermission 把整个工具（内置工具，或整个 MCP 服务器 mcp__xxx）设成允许、询问、拒绝，空是去掉。
func (s *Store) SetToolPermission(tool string, b Behavior) (Rules, error) {
	if err := checkBehavior(b, true); err != nil {
		return nil, err
	}
	return s.editPermissions(func(o *object) error { return withTool(o, tool, b) })
}

// AddPermissionRule 加一条规则。
func (s *Store) AddPermissionRule(r Rule) (Rules, error) {
	if err := checkRule(r); err != nil {
		return nil, err
	}
	return s.editPermissions(func(o *object) error { return withRule(o, r.Behavior, r.Text) })
}

// RemovePermissionRule 删一条规则。
func (s *Store) RemovePermissionRule(r Rule) (Rules, error) {
	return s.editPermissions(func(o *object) error { return withoutRule(o, r.Behavior, r.Text) })
}

// ReplacePermissionRule 把 old 换成 nw；old 已经被别人删了或改了时报错，界面重新读。
func (s *Store) ReplacePermissionRule(old, nw Rule) (Rules, error) {
	if err := checkRule(nw); err != nil {
		return nil, err
	}
	return s.editPermissions(func(o *object) error { return replaceRule(o, old.Behavior, old.Text, nw.Behavior, nw.Text) })
}

// SetGroupPermission 一键开启或关闭一组工具；按文件里最新的内容算要加、要删的。
func (s *Store) SetGroupPermission(g PermissionGroup, enable bool) (Rules, error) {
	return s.editPermissions(func(o *object) error { return withBulk(o, g, enable) })
}

func checkBehavior(b Behavior, allowEmpty bool) error {
	if b == Allow || b == Ask || b == Deny || (allowEmpty && b == "") {
		return nil
	}
	return locales.Err("cfgUnknownBehavior", "behavior", string(b))
}

func checkRule(r Rule) error {
	if err := checkBehavior(r.Behavior, false); err != nil {
		return err
	}
	if err := RuleProblem(r.Text); err != nil {
		return err
	}
	return nil
}
