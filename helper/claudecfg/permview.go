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
}

// RuleView 是一条规则；Malformed 是括号不配对，Claude Code 认不出它。
type RuleView struct {
	Rule
	Malformed bool `json:"malformed"`
}

// ToolView 是一个工具一行。
type ToolView struct {
	ToolInfo
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
	Scope string    `json:"scope"`
	OAuth bool      `json:"oauth"`
	State ToolState `json:"state"`
	Bulk  BulkPair  `json:"bulk"`
}

func bulkPair(rs Rules, g PermissionGroup) BulkPair {
	return BulkPair{rs.Bulk(g, true), rs.Bulk(g, false)}
}

// PermissionsView 把规则 rs 摊成页面：cwd 是默认工作目录（列 MCP 服务器用，空是家目录）；server 不空时另算它的 tools。
func (s *Store) PermissionsView(rs Rules, cwd, server string, tools []string) PermissionsView {
	v := PermissionsView{Rules: []RuleView{}, Builtin: []ToolView{}, Servers: []ServerView{}, ServerTools: []ToolView{}}
	for _, r := range rs {
		v.Rules = append(v.Rules, RuleView{r, RuleMalformed(r.Text)})
	}
	for i, t := range append(slices.Clone(CommonTools), OtherTools...) {
		v.Builtin = append(v.Builtin, ToolView{t, i >= len(CommonTools), rs.Tool(t.Name)})
	}
	v.BuiltinBulk = bulkPair(rs, BuiltinGroup())
	var seen []string
	for _, e := range s.mcpEntries(cwd) {
		if slices.Contains(seen, e.name) {
			continue
		}
		seen = append(seen, e.name)
		v.Servers = append(v.Servers, ServerView{
			Name: e.name, Scope: e.scope, OAuth: e.config.obj("oauth") != nil,
			State: rs.Server(e.name), Bulk: bulkPair(rs, PermissionGroup{Server: e.name}),
		})
	}
	if server != "" {
		for _, t := range tools {
			v.ServerTools = append(v.ServerTools, ToolView{ToolInfo: ToolInfo{Name: t}, State: rs.McpTool(server, t)})
		}
	}
	return v
}
