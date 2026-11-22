package claudecfg

import "context"

// Behavior 是规则所在的表：allow、ask、deny；空是没设（按权限模式走）。
type Behavior string

const (
	Allow Behavior = "allow"
	Ask   Behavior = "ask"
	Deny  Behavior = "deny"
)

// Behaviors 是下拉里的三张表，按页面上的顺序。
var Behaviors = []Behavior{Allow, Ask, Deny}

// Label 是界面上的叫法。
func (b Behavior) Label() string {
	switch b {
	case Allow:
		return "允许"
	case Ask:
		return "询问"
	case Deny:
		return "拒绝"
	}
	return "未设置"
}

// PermissionSemantics 是页面上说的规则语义。
const PermissionSemantics = "规则按“拒绝 > 询问 > 允许”取最严的，写得再细也一样。完全放行模式下，询问和拒绝照样生效。"

// Rule 是一条规则和它所在的表。
type Rule struct {
	Behavior Behavior `json:"behavior"`
	Text     string   `json:"rule"`
}

// RuleView 是页面上的一条规则；Malformed 是括号不配对，Claude Code 认不出。
type RuleView struct {
	Rule
	Malformed bool `json:"malformed"`
}

// ToolState 是一个工具（或一个 MCP 服务器）的状态。
type ToolState struct {
	Behavior  Behavior `json:"behavior"`
	Inherited bool     `json:"inherited"` // 来自整个 MCP 服务器的规则
	Specific  []Rule   `json:"specific"`  // 带参数的规则
}

// ToolView 是一个工具一行；More 的收在“更多内置工具”里。
type ToolView struct {
	Name    string    `json:"name"`
	Label   string    `json:"label"`
	Example string    `json:"example"`
	More    bool      `json:"more"`
	State   ToolState `json:"state"`
}

// BulkChange 是一键开启或关闭会加、删的规则。
type BulkChange struct {
	Add    []Rule `json:"add"`
	Remove []Rule `json:"remove"`
}

// Empty：已经全是那个状态。
func (c BulkChange) Empty() bool { return len(c.Add) == 0 && len(c.Remove) == 0 }

// BulkPair 是一组工具开启、关闭各会怎么改。
type BulkPair struct {
	Enable  BulkChange `json:"enable"`
	Disable BulkChange `json:"disable"`
}

// ServerView 是一个 MCP 服务器整体的设置。
type ServerView struct {
	Name  string    `json:"name"`
	Rule  string    `json:"rule"` // 整个服务器的规则名 mcp__服务器，设整个服务器时交给 SetToolPermission
	Scope string    `json:"scope"`
	OAuth bool      `json:"oauth"`
	State ToolState `json:"state"`
	Bulk  BulkPair  `json:"bulk"`
}

// Permissions 是工具权限页，由那边按规则算好。
type Permissions struct {
	Rules       []RuleView   `json:"rules"`
	Builtin     []ToolView   `json:"builtin"`
	BuiltinBulk BulkPair     `json:"builtinBulk"`
	Servers     []ServerView `json:"servers"`
	ServerTools []ToolView   `json:"serverTools"`
}

// PermissionGroup 是一键开关的一组：内置工具（Tools）或一个 MCP 服务器（Server）。
type PermissionGroup struct {
	Tools  []string `json:"tools,omitempty"`
	Server string   `json:"server,omitempty"`
}

// BuiltinGroup 是页面上的全部内置工具。
func (p Permissions) BuiltinGroup() PermissionGroup {
	var names []string
	for _, t := range p.Builtin {
		names = append(names, t.Name)
	}
	return PermissionGroup{Tools: names}
}

// PermissionScope 是权限页要连带算的：server 不空时另算它的 tools 的状态。
type PermissionScope struct {
	Server string
	Tools  []string
}

func (cl *Client) permissions(ctx context.Context, method string, scope PermissionScope, params obj) (Permissions, error) {
	if params == nil {
		params = obj{}
	}
	params["cwd"], params["server"], params["tools"] = cl.Cwd, scope.Server, scope.Tools
	var p Permissions
	err := cl.call(ctx, method, params, &p)
	return p, err
}

// Permissions 读工具权限页。
func (cl *Client) Permissions(ctx context.Context, scope PermissionScope) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.read", scope, nil)
}

// SetToolPermission 把整个工具（或整个 MCP 服务器 mcp__xxx）设成 b，空是去掉。
func (cl *Client) SetToolPermission(ctx context.Context, scope PermissionScope, tool string, b Behavior) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.setTool", scope, obj{"tool": tool, "behavior": b})
}

// AddRule 加一条规则。
func (cl *Client) AddRule(ctx context.Context, scope PermissionScope, r Rule) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.add", scope, obj{"behavior": r.Behavior, "rule": r.Text})
}

// RemoveRule 删一条规则。
func (cl *Client) RemoveRule(ctx context.Context, scope PermissionScope, r Rule) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.remove", scope, obj{"behavior": r.Behavior, "rule": r.Text})
}

// ReplaceRule 把 old 换成 nw。
func (cl *Client) ReplaceRule(ctx context.Context, scope PermissionScope, old, nw Rule) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.replace", scope, obj{"old": old, "new": nw})
}

// SetGroup 一键开启或关闭一组工具。
func (cl *Client) SetGroup(ctx context.Context, scope PermissionScope, g PermissionGroup, enable bool) (Permissions, error) {
	return cl.permissions(ctx, "config.permissions.bulk", scope, obj{"group": g, "enable": enable})
}
