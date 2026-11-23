package claudecfg

import "slices"

// 工具权限页要显示的，都在这边按规则算好：界面只画，不另写一份规则的解析。

// PermissionsView 是工具权限页的内容。
type PermissionsView struct {
	Rules       []RuleView   `json:"rules"`       // 全部规则，按拒绝、询问、允许排
	Builtin     []ToolView   `json:"builtin"`     // 内置工具，常用的在前，More 的收在“更多内置工具”里
	BuiltinBulk BulkPair     `json:"builtinBulk"` // 内置工具一键开启、关闭会加、删的规则
	Servers     []ServerView `json:"servers"`     // 配置里的 MCP 服务器（同名的只列一次）
	ServerTools []ToolView   `json:"serverTools"` // 请求里给了 server 和 tools 时，那个服务器的每个工具
	// Focus 是请求里给的 server 整体的设置（同 Servers 的一项）；不在配置里的服务器（比如手机版自己接的 phone）也有，Scope 为空。没给 server 时为 null
	Focus *ServerView `json:"focus"`
}

// RuleView 是一条规则；Malformed 是括号不配对，Claude Code 认不出它。
type RuleView struct {
	Rule
	Malformed bool `json:"malformed"`
}

// ToolView 是一个工具一行。State 是生效的状态（MCP 工具算上整个服务器的设置）；Own 是它自己那条规则，下拉里显示的是它，
// 改的时候把 Rule 交给 SetToolPermission。
type ToolView struct {
	ToolInfo
	Rule  string    `json:"rule"`
	Own   Behavior  `json:"own"`
	More  bool      `json:"more"`
	State ToolState `json:"state"`
}

// BulkPair 是一组工具一键开启、关闭各会加、删的规则；空的说明已经全是那个状态。
type BulkPair struct {
	Enable  BulkChange `json:"enable"`
	Disable BulkChange `json:"disable"`
}

// ServerView 是一个 MCP 服务器整体的设置。
type ServerView struct {
	Name  string    `json:"name"`
	Rule  string    `json:"rule"` // 整个服务器的规则名 mcp__服务器
	Scope string    `json:"scope"`
	OAuth bool      `json:"oauth"`
	State ToolState `json:"state"`
	Bulk  BulkPair  `json:"bulk"`
}

func bulkPair(rs Rules, g PermissionGroup) BulkPair {
	return BulkPair{rs.Bulk(g, true), rs.Bulk(g, false)}
}

// PermissionsView 把规则 rs 摊成页面：cwd 是默认工作目录（列 MCP 服务器用，空是家目录）；server 不空时另算它整体的设置和它的 tools。
func (s *Store) PermissionsView(rs Rules, cwd, server string, tools []string) PermissionsView {
	v := PermissionsView{Rules: []RuleView{}, Builtin: []ToolView{}, Servers: []ServerView{}, ServerTools: []ToolView{}}
	for _, r := range rs {
		v.Rules = append(v.Rules, RuleView{r, RuleMalformed(r.Text)})
	}
	for i, t := range append(slices.Clone(CommonTools), OtherTools...) {
		st := rs.Tool(t.Name)
		v.Builtin = append(v.Builtin, ToolView{ToolInfo: t.in(s.lang), Rule: t.Name, Own: st.Behavior, More: i >= len(CommonTools), State: st})
	}
	v.BuiltinBulk = bulkPair(rs, BuiltinGroup())
	var seen []string
	for _, e := range s.mcpEntries(cwd) {
		if slices.Contains(seen, e.name) {
			continue
		}
		seen = append(seen, e.name)
		v.Servers = append(v.Servers, ServerView{
			Name: e.name, Rule: McpServerName(e.name), Scope: e.scope, OAuth: e.config.obj("oauth") != nil,
			State: rs.Server(e.name), Bulk: bulkPair(rs, PermissionGroup{Server: e.name}),
		})
	}
	if server != "" {
		focus := ServerView{Name: server, Rule: McpServerName(server), State: rs.Server(server), Bulk: bulkPair(rs, PermissionGroup{Server: server})}
		if i := slices.IndexFunc(v.Servers, func(sv ServerView) bool { return sv.Name == server }); i >= 0 {
			focus = v.Servers[i]
		}
		v.Focus = &focus
		for _, t := range tools {
			rule := McpToolName(server, t)
			v.ServerTools = append(v.ServerTools, ToolView{ToolInfo: ToolInfo{Name: t}, Rule: rule, Own: rs.Tool(rule).Behavior, State: rs.McpTool(server, t)})
		}
	}
	return v
}
