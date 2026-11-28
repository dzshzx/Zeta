package claudecfg

import (
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
)

// Antigravity CLI 权限引擎使用 action(target) 资源动作语法，合法动作集合见官方 settings.json Schema 与 permissions 文档。
var agyPermActions = []string{
	"command", "read_file", "write_file", "read_url",
	"execute_url", "download_url", "unsandboxed", "mcp",
}

type agyParsedRule struct {
	action    string
	target    string
	malformed bool
}

func parseAgyRule(rule string) agyParsedRule {
	open := strings.IndexByte(rule, '(')
	if open <= 0 || !strings.HasSuffix(rule, ")") {
		return agyParsedRule{malformed: true}
	}
	action := rule[:open]
	if !slices.Contains(agyPermActions, action) {
		return agyParsedRule{malformed: true}
	}
	target := rule[open+1 : len(rule)-1]
	if strings.TrimSpace(target) == "" {
		return agyParsedRule{malformed: true}
	}
	return agyParsedRule{action: action, target: target}
}

func agyRuleProblem(rule string) error {
	switch {
	case strings.TrimSpace(rule) == "":
		return locales.Err("cfgRuleEmpty")
	case rule != strings.TrimSpace(rule):
		return locales.Err("cfgRuleSpaces")
	case strings.ContainsAny(rule, "\n\r\t"):
		return locales.Err("cfgRuleNewline")
	case parseAgyRule(rule).malformed:
		return locales.Err("cfgAgyRuleMalformed")
	}
	return nil
}

func agyRuleMalformed(rule string) bool { return parseAgyRule(rule).malformed }

func agyWholeRule(action string) string         { return action + "(*)" }
func agyMcpServerRule(server string) string     { return "mcp(" + server + "/*)" }
func agyMcpToolRule(server, tool string) string { return "mcp(" + server + "/" + tool + ")" }

func agyToolState(rs Rules, action string) ToolState {
	var st ToolState
	var whole []Behavior
	for _, r := range rs {
		p := parseAgyRule(r.Text)
		if p.malformed || p.action != action {
			continue
		}
		if p.target == "*" {
			whole = append(whole, r.Behavior)
		} else {
			st.Specific = append(st.Specific, r)
		}
	}
	st.Behavior = strongest(whole)
	return st
}

func agyServerState(rs Rules, server string) ToolState {
	var st ToolState
	var whole []Behavior
	prefix := server + "/"
	for _, r := range rs {
		p := parseAgyRule(r.Text)
		if p.malformed || p.action != "mcp" {
			continue
		}
		switch {
		case p.target == prefix+"*" || p.target == server:
			whole = append(whole, r.Behavior)
		case strings.HasPrefix(p.target, prefix):
			st.Specific = append(st.Specific, r)
		}
	}
	own := strongest(whole)
	all := agyToolState(rs, "mcp").Behavior
	combined := strongest([]Behavior{own, all})
	st.Behavior = combined
	st.Inherited = combined != "" && combined != own
	return st
}

func agyMcpToolState(rs Rules, server, tool string) ToolState {
	target := server + "/" + tool
	var own []Behavior
	for _, r := range rs {
		p := parseAgyRule(r.Text)
		if !p.malformed && p.action == "mcp" && p.target == target {
			own = append(own, r.Behavior)
		}
	}
	ownB := strongest(own)
	srvB := agyServerState(rs, server).Behavior
	combined := strongest([]Behavior{ownB, srvB})
	return ToolState{
		Behavior:  combined,
		Inherited: combined != "" && combined != ownB,
	}
}

func normalizeAgyToolTarget(tool string) (canonical string, match func(string) bool) {
	if slices.Contains(agyPermActions, tool) {
		canonical = agyWholeRule(tool)
		return canonical, func(rule string) bool {
			p := parseAgyRule(rule)
			return !p.malformed && p.action == tool && p.target == "*"
		}
	}
	p := parseAgyRule(tool)
	if !p.malformed && p.action == "mcp" && strings.HasSuffix(p.target, "/*") {
		srv := strings.TrimSuffix(p.target, "/*")
		return tool, func(rule string) bool {
			q := parseAgyRule(rule)
			return !q.malformed && q.action == "mcp" && (q.target == srv+"/*" || q.target == srv)
		}
	}
	return tool, func(rule string) bool { return rule == tool }
}

func agyWithTool(s *object, tool string, b Behavior) error {
	canonical, matches := normalizeAgyToolTarget(tool)
	return editTables(s, func(t Behavior, list []any) []any {
		kept := slices.DeleteFunc(list, func(v any) bool { return matches(ruleText(v)) })
		if t == b {
			kept = append(kept, canonical)
		}
		return kept
	})
}

func agyGroupContains(g PermissionGroup, rule string) bool {
	p := parseAgyRule(rule)
	if p.malformed {
		return false
	}
	if g.Server != "" {
		if p.action != "mcp" {
			return false
		}
		return p.target == g.Server || p.target == g.Server+"/*" || strings.HasPrefix(p.target, g.Server+"/")
	}
	for _, t := range g.Tools {
		action := strings.TrimSuffix(t, "(*)")
		if p.action == action {
			return true
		}
	}
	return false
}

func agyBulk(rs Rules, g PermissionGroup, enable bool) BulkChange {
	target, keep := Deny, Deny
	if enable {
		target, keep = Allow, Allow
	}
	var ch BulkChange
	for _, r := range rs {
		if r.Behavior != keep && agyGroupContains(g, r.Text) && !slices.Contains(ch.Remove, r) {
			ch.Remove = append(ch.Remove, r)
		}
	}
	present := rs.Of(target)
	if g.Server != "" {
		srvRule := agyMcpServerRule(g.Server)
		has := slices.ContainsFunc(present, func(r string) bool {
			p := parseAgyRule(r)
			return !p.malformed && p.action == "mcp" && (p.target == g.Server+"/*" || p.target == g.Server)
		})
		if !has {
			ch.Add = append(ch.Add, Rule{target, srvRule})
		}
		return ch
	}
	var seen []string
	for _, t := range g.Tools {
		action := strings.TrimSuffix(t, "(*)")
		if slices.Contains(seen, action) {
			continue
		}
		seen = append(seen, action)
		has := slices.ContainsFunc(present, func(r string) bool {
			p := parseAgyRule(r)
			return !p.malformed && p.action == action && p.target == "*"
		})
		if !has {
			ch.Add = append(ch.Add, Rule{target, agyWholeRule(action)})
		}
	}
	return ch
}

func agyBulkPair(rs Rules, g PermissionGroup) BulkPair {
	return BulkPair{agyBulk(rs, g, true), agyBulk(rs, g, false)}
}

func agyWithBulk(s *object, g PermissionGroup, enable bool) error {
	rs, err := rulesOf(s)
	if err != nil {
		return err
	}
	ch := agyBulk(rs, g, enable)
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

func (s *Store) agyPermissionsView(rs Rules, cwd, server string, tools []string) PermissionsView {
	v := PermissionsView{Rules: []RuleView{}, Builtin: []ToolView{}, Servers: []ServerView{}, ServerTools: []ToolView{}}
	for _, r := range rs {
		v.Rules = append(v.Rules, RuleView{r, agyRuleMalformed(r.Text)})
	}
	for i, t := range append(slices.Clone(AgyCommonTools), AgyOtherTools...) {
		st := agyToolState(rs, t.Name)
		v.Builtin = append(v.Builtin, ToolView{
			ToolInfo: t.in(s.lang), Rule: agyWholeRule(t.Name), Own: st.Behavior,
			More: i >= len(AgyCommonTools), State: st,
		})
	}
	v.BuiltinBulk = agyBulkPair(rs, AgyBuiltinGroup())
	var seen []string
	for _, e := range s.mcpEntries(cwd) {
		if slices.Contains(seen, e.name) {
			continue
		}
		seen = append(seen, e.name)
		v.Servers = append(v.Servers, ServerView{
			Name: e.name, Rule: agyMcpServerRule(e.name), Scope: e.scope, OAuth: e.config.obj("oauth") != nil,
			State: agyServerState(rs, e.name), Bulk: agyBulkPair(rs, PermissionGroup{Server: e.name}),
		})
	}
	if server != "" {
		focus := ServerView{
			Name: server, Rule: agyMcpServerRule(server),
			State: agyServerState(rs, server), Bulk: agyBulkPair(rs, PermissionGroup{Server: server}),
		}
		if i := slices.IndexFunc(v.Servers, func(sv ServerView) bool { return sv.Name == server }); i >= 0 {
			focus = v.Servers[i]
		}
		v.Focus = &focus
		for _, t := range tools {
			rule := agyMcpToolRule(server, t)
			own := agyMcpToolOwn(rs, server, t)
			v.ServerTools = append(v.ServerTools, ToolView{
				ToolInfo: ToolInfo{Name: t}, Rule: rule, Own: own, State: agyMcpToolState(rs, server, t),
			})
		}
	}
	return v
}

func agyMcpToolOwn(rs Rules, server, tool string) Behavior {
	target := server + "/" + tool
	var own []Behavior
	for _, r := range rs {
		p := parseAgyRule(r.Text)
		if !p.malformed && p.action == "mcp" && p.target == target {
			own = append(own, r.Behavior)
		}
	}
	return strongest(own)
}
