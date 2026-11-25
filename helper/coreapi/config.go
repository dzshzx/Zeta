package coreapi

import (
	"context"
	"errors"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 这一组是 docs/core.md「Claude Code 配置」的 config.* 方法：读写那台主机上 Claude Code 自己的配置（~/.claude、~/.claude.json），
// 逻辑在 helper/claudecfg，结果类型照它的 JSON。要按工作目录找的（记忆的默认项目、MCP 的本地配置、权限页列的 MCP 服务器）
// 由调用方给 cwd（设备的默认工作目录），空串是那边的家目录。标「慢」的要跑 claude 或连 MCP 服务器，十几秒到一分钟。

// ---------- 新对话默认 ----------

// ModelCatalog 是生效中转和它的默认模型、模型列表。没有生效中转时三个字段都为空。
type ModelCatalog struct {
	Relay  string       `json:"relay"`
	Model  string       `json:"model"`
	Models []RelayModel `json:"models"`
}

// ModelCatalog 读生效中转的模型目录（config.models.read）。拉取失败时仍返回中转和默认模型。
func (cl *Client) ModelCatalog(ctx context.Context) (ModelCatalog, error) {
	return get[ModelCatalog](ctx, cl.c, "config.models.read", nil)
}

// Defaults 是一台主机上新对话的默认值，生效中转的模型优先于 ~/.claude/settings.json 的模型。
type Defaults struct {
	Model          string        `json:"model"`          // 空是 Claude Code 内置的默认模型
	PermissionMode string        `json:"permissionMode"` // 空是逐项确认
	Efforts        []ModelEffort `json:"efforts"`        // 按模型存的思考强度
	Effort         string        `json:"effort"`         // 没单独设的模型用的那一档
	CompactWindow  int64         `json:"compactWindow"`  // 上下文窗口（token 数），0 是按模型的
}

// ModelEffort 是按模型存的一档思考强度。
type ModelEffort struct {
	Model string `json:"model"`
	Level string `json:"level"`
}

// EffortOf 是 model 单独设的那一档，没设时为空。
func (d Defaults) EffortOf(model string) string {
	for _, e := range d.Efforts {
		if e.Model == model {
			return e.Level
		}
	}
	return ""
}

// MinCompactWindow 是 Claude Code 肯认的最小上下文窗口，核心不写比它小的（helper/claudecfg.MinCompactWindow）。
const MinCompactWindow = 100_000

// Defaults 读新对话的默认值（config.defaults.read）。
func (cl *Client) Defaults(ctx context.Context) (Defaults, error) {
	return get[Defaults](ctx, cl.c, "config.defaults.read", nil)
}

// SetDefaultModel 设默认模型（config.defaults.setModel）。有生效中转时只改中转，空值继承用户级默认。
func (cl *Client) SetDefaultModel(ctx context.Context, model string) (Defaults, error) {
	return get[Defaults](ctx, cl.c, "config.defaults.setModel", params{"value": model})
}

// SetDefaultPermissionMode 设默认权限模式，不能是 bypassPermissions（config.defaults.setPermissionMode）。
func (cl *Client) SetDefaultPermissionMode(ctx context.Context, mode string) (Defaults, error) {
	return get[Defaults](ctx, cl.c, "config.defaults.setPermissionMode", params{"value": mode})
}

// SetDefaultEffort 设默认思考强度：model 是完整模型 ID，空是没单独设的模型共用的那一档；level 空是删掉（config.defaults.setEffort）。
func (cl *Client) SetDefaultEffort(ctx context.Context, model, level string) (Defaults, error) {
	return get[Defaults](ctx, cl.c, "config.defaults.setEffort", params{"model": model, "value": level})
}

// SetCompactWindow 设上下文窗口（token 数）；0 是按模型的（config.defaults.setCompactWindow）。
func (cl *Client) SetCompactWindow(ctx context.Context, tokens int64) (Defaults, error) {
	return get[Defaults](ctx, cl.c, "config.defaults.setCompactWindow", params{"value": tokens})
}

// ---------- 记忆 ----------

// ConfigFile 是一个配置文件在不在、多大。
type ConfigFile struct {
	Path   string `json:"path"`
	Exists bool   `json:"exists"`
	Size   int64  `json:"size"`
}

// MemoryProject 是记忆页上可选的一个项目。
type MemoryProject struct {
	Key     string `json:"key"`
	Label   string `json:"label"`
	Count   int    `json:"count"`
	Default bool   `json:"default"`
}

// MemoryEntry 是一条自动记忆。
type MemoryEntry struct {
	Path        string `json:"path"`
	FileName    string `json:"fileName"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Type        string `json:"type"`
	Size        int64  `json:"size"`
}

// Memory 是记忆页的内容。
type Memory struct {
	AutoMemory   bool            `json:"autoMemory"`
	ForcedOff    bool            `json:"forcedOff"`
	CustomDir    string          `json:"customDir"`
	Instructions ConfigFile      `json:"instructions"`
	Projects     []MemoryProject `json:"projects"`
	Selected     string          `json:"selected"`
	Dir          string          `json:"dir"`
	Index        *ConfigFile     `json:"index"`
	Files        []MemoryEntry   `json:"files"`
}

// Memory 读记忆页：cwd 是默认工作目录，project 空是它所在的项目（config.memory.read）。
func (cl *Client) Memory(ctx context.Context, cwd, project string) (Memory, error) {
	return get[Memory](ctx, cl.c, "config.memory.read", params{"cwd": cwd, "project": project})
}

// Document 是读到的一个文本文件；SHA 是读到时的版本。
type Document struct {
	Content string `json:"content"`
	SHA     string `json:"sha"`
	Exists  bool   `json:"exists"`
}

// MaxMemoryFile 是记忆页上的文件能写多大，核心不写更大的（helper/claudecfg.MaxText）。
const MaxMemoryFile = 1 << 20

// ReadMemoryFile 读记忆页上的一个文件：只认 CLAUDE.md 和记忆目录里的 .md（config.memory.readFile）。
func (cl *Client) ReadMemoryFile(ctx context.Context, path string) (Document, error) {
	return get[Document](ctx, cl.c, "config.memory.readFile", params{"path": path})
}

// ErrConflict：文件在编辑期间被别人改过了（多半是 Claude 在对话里改的），这次没写。
var ErrConflict = errors.New("文件在编辑期间被改过了")

// WriteMemoryFile 写记忆页上的一个文件，返回新版本。sha 是读到时的版本（原来不存在时为空）；force 时不比对直接覆盖。
// 被别人改过时返回 ErrConflict，什么都没写（config.memory.writeFile）。
func (cl *Client) WriteMemoryFile(ctx context.Context, path, content, sha string, force bool) (string, error) {
	r, err := get[struct {
		SHA      string `json:"sha"`
		Conflict bool   `json:"conflict"`
	}](ctx, cl.c, "config.memory.writeFile", params{"path": path, "content": content, "sha": sha, "force": force})
	if err != nil {
		return "", err
	}
	if r.Conflict {
		return "", ErrConflict
	}
	return r.SHA, nil
}

// DeleteMemory 删一条记忆，连同 MEMORY.md 里指向它的那一行（config.memory.delete）。
func (cl *Client) DeleteMemory(ctx context.Context, path string) error {
	return call(ctx, cl.c, "config.memory.delete", params{"path": path})
}

// SetAutoMemory 打开或关掉自动记忆（config.memory.setAuto）。
func (cl *Client) SetAutoMemory(ctx context.Context, on bool) error {
	return call(ctx, cl.c, "config.memory.setAuto", params{"on": on})
}

// ---------- Skills ----------

// Skill 是用户装的一个 Skill。
type Skill struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
	Valid       bool   `json:"valid"`
}

// SyncedSkill 是账号同步的一个 Skill。
type SyncedSkill struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Enabled     bool   `json:"enabled"`
}

// Skills 是 Skills 页的内容。
type Skills struct {
	User   []Skill       `json:"user"`
	Synced []SyncedSkill `json:"synced"`
}

// SkillImport 是装 Skill 的结果；Exists 时什么都没装，要用户确认替换。
type SkillImport struct {
	Name                string `json:"name"`
	Exists              bool   `json:"exists"`
	ExistingDescription string `json:"existingDescription"`
	Replaced            bool   `json:"replaced"`
	Enabled             bool   `json:"enabled"`
}

// MaxSkillZip 是能导入的 ZIP 的上限：经 serve 的一行最长 64 MiB，base64 以后要放得下。
const MaxSkillZip = 45 << 20

// Skills 读 Skills 页（config.skills.list）。
func (cl *Client) Skills(ctx context.Context) (Skills, error) {
	return get[Skills](ctx, cl.c, "config.skills.list", nil)
}

// ImportSkill 从 ZIP 装一个 Skill；replace 时替换同名的（config.skills.import）。超过 MaxSkillZip 的不发。
func (cl *Client) ImportSkill(ctx context.Context, fileName string, zip []byte, replace bool) (SkillImport, error) {
	if len(zip) > MaxSkillZip {
		return SkillImport{}, locales.Err("errSkillZipTooBig")
	}
	return get[SkillImport](ctx, cl.c, "config.skills.import", params{"fileName": fileName, "zip": zip, "replace": replace})
}

// SetSkillEnabled 启用或停用一个 Skill（写 skillOverrides，和 Claude Code 里 /skills 一样；config.skills.setEnabled）。
func (cl *Client) SetSkillEnabled(ctx context.Context, s Skill, enabled bool) error {
	return call(ctx, cl.c, "config.skills.setEnabled", params{"name": s.Name, "title": s.Title, "enabled": enabled})
}

// DeleteSkill 删掉一个 Skill 的文件夹（config.skills.delete）。
func (cl *Client) DeleteSkill(ctx context.Context, name string) error {
	return call(ctx, cl.c, "config.skills.delete", params{"name": name})
}

// ---------- Subagent ----------

// Subagent 是 ~/.claude/agents 下的一个 Subagent 文件。
type Subagent struct {
	File        string   `json:"file"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // nil 是全部工具
	Prompt      string   `json:"prompt"`
	ExtraKeys   []string `json:"extraKeys"`
	SHA         string   `json:"sha"`
}

// SubagentDraft 是编辑页上的内容。
type SubagentDraft struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Model       string   `json:"model"`
	Tools       []string `json:"tools"` // nil 是全部工具
	Prompt      string   `json:"prompt"`
}

// Agents 列出 ~/.claude/agents 下的 Subagent（config.agents.list）。
func (cl *Client) Agents(ctx context.Context) ([]Subagent, error) {
	return get[[]Subagent](ctx, cl.c, "config.agents.list", nil)
}

// SaveAgent 保存；original 是编辑前读到的那一项，nil 是新建。返回保存后的文件（config.agents.save）。
func (cl *Client) SaveAgent(ctx context.Context, original *Subagent, d SubagentDraft) (string, error) {
	r, err := get[struct {
		File string `json:"file"`
	}](ctx, cl.c, "config.agents.save", params{"original": original, "draft": d})
	return r.File, err
}

// DeleteAgent 删掉一个 Subagent 文件（config.agents.delete）。
func (cl *Client) DeleteAgent(ctx context.Context, file string) error {
	return call(ctx, cl.c, "config.agents.delete", params{"file": file})
}

// ---------- 工具权限 ----------

// Behavior 是规则所在的表：allow、ask、deny；空是没设（按权限模式走）。
type Behavior string

const (
	Allow Behavior = "allow"
	Ask   Behavior = "ask"
	Deny  Behavior = "deny"
)

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

// ToolView 是一个工具一行；More 的收在“更多内置工具”里。State 是生效的状态（MCP 工具算上整个服务器的设置），Own 是它
// 自己那条规则（下拉里显示的），改的时候把 Rule 交给 SetToolPermission。
type ToolView struct {
	Name    string    `json:"name"`
	Label   string    `json:"label"`
	Example string    `json:"example"`
	Rule    string    `json:"rule"`
	Own     Behavior  `json:"own"`
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

// Permissions 是工具权限页，由核心按规则算好。
type Permissions struct {
	Rules       []RuleView   `json:"rules"`
	Builtin     []ToolView   `json:"builtin"`
	BuiltinBulk BulkPair     `json:"builtinBulk"`
	Servers     []ServerView `json:"servers"`
	ServerTools []ToolView   `json:"serverTools"`
	Focus       *ServerView  `json:"focus"` // 给了 Server 时它整体的设置；不在配置里的服务器也有
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

// PermissionView 是权限方法都带的、决定返回的页面怎么算：按 Cwd 列 MCP 服务器；Server 不空时另算它的 Tools 的状态。
type PermissionView struct {
	Cwd    string
	Server string
	Tools  []string
}

func (v PermissionView) params(p params) params {
	p["cwd"], p["server"], p["tools"] = v.Cwd, v.Server, v.Tools
	return p
}

// Permissions 读工具权限页（config.permissions.read）。
func (cl *Client) Permissions(ctx context.Context, v PermissionView) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.read", v.params(params{}))
}

// SetToolPermission 把整个工具（或整个 MCP 服务器 mcp__xxx）设成 b，空是去掉（config.permissions.setTool）。
func (cl *Client) SetToolPermission(ctx context.Context, v PermissionView, tool string, b Behavior) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.setTool", v.params(params{"tool": tool, "behavior": b}))
}

// AddPermissionRule 加一条规则（config.permissions.add）。
func (cl *Client) AddPermissionRule(ctx context.Context, v PermissionView, r Rule) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.add", v.params(params{"behavior": r.Behavior, "rule": r.Text}))
}

// RemovePermissionRule 删一条规则（config.permissions.remove）。
func (cl *Client) RemovePermissionRule(ctx context.Context, v PermissionView, r Rule) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.remove", v.params(params{"behavior": r.Behavior, "rule": r.Text}))
}

// ReplacePermissionRule 把 old 换成 nw；old 已经不在了时出错（config.permissions.replace）。
func (cl *Client) ReplacePermissionRule(ctx context.Context, v PermissionView, old, nw Rule) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.replace", v.params(params{"old": old, "new": nw}))
}

// SetPermissionGroup 一键开启或关闭一组工具（config.permissions.bulk）。
func (cl *Client) SetPermissionGroup(ctx context.Context, v PermissionView, g PermissionGroup, enable bool) (Permissions, error) {
	return get[Permissions](ctx, cl.c, "config.permissions.bulk", v.params(params{"group": g, "enable": enable}))
}

// ---------- MCP ----------

// McpServer 是配置里的一个 MCP 服务器：密钥都只说有没有。
type McpServer struct {
	Name       string   `json:"name"`
	Scope      string   `json:"scope"` // user、local、project
	Type       string   `json:"type"`  // http、sse、stdio…
	URL        string   `json:"url"`
	Command    string   `json:"command"`
	Args       []string `json:"args"`
	EnvKeys    []string `json:"envKeys"`
	HeaderKeys []string `json:"headerKeys"`
	Bearer     bool     `json:"bearer"`
	AuthHeader bool     `json:"authHeader"`
	OAuth      bool     `json:"oauth"`
	ShadowedBy string   `json:"shadowedBy"` // 被优先级更高的范围里的同名服务器盖住
}

// Key 是它在 McpStatus.Servers 里的键。
func (s McpServer) Key() string { return s.Scope + "/" + s.Name }

// McpState 是健康检查报的状态。
type McpState struct {
	State string `json:"state"`
	Issue string `json:"issue"`
}

// McpConnector 是 Claude 账号的连接器。
type McpConnector struct {
	Name  string   `json:"name"`
	State McpState `json:"state"`
}

// McpStatus 是健康检查的结果。
type McpStatus struct {
	Servers    map[string]McpState `json:"servers"`
	Connectors []McpConnector      `json:"connectors"`
}

// McpTool 是 MCP 服务器的一个工具。
type McpTool struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

// McpServers 列出 cwd 下看得到的服务器（config.mcp.list）。
func (cl *Client) McpServers(ctx context.Context, cwd string) ([]McpServer, error) {
	return get[[]McpServer](ctx, cl.c, "config.mcp.list", params{"cwd": cwd})
}

// McpStatus 逐个连接做健康检查（慢；config.mcp.status）。
func (cl *Client) McpStatus(ctx context.Context, cwd string) (McpStatus, error) {
	return get[McpStatus](ctx, cl.c, "config.mcp.status", params{"cwd": cwd})
}

func serverParams(cwd string, s McpServer) params {
	return params{"cwd": cwd, "scope": s.Scope, "name": s.Name}
}

// McpTools 连上服务器 s 取工具清单（慢；config.mcp.tools）。
func (cl *Client) McpTools(ctx context.Context, cwd string, s McpServer) ([]McpTool, error) {
	return get[[]McpTool](ctx, cl.c, "config.mcp.tools", serverParams(cwd, s))
}

// HTTPMcp 是要加的一个 HTTP 或 SSE 服务器。
type HTTPMcp struct {
	Name  string
	Type  string // http、sse
	URL   string
	Token string // Bearer Token，可以为空
}

// AddHTTPMcp 加一个 HTTP 或 SSE 服务器到用户配置（慢；config.mcp.addHttp）。
func (cl *Client) AddHTTPMcp(ctx context.Context, s HTTPMcp) error {
	return call(ctx, cl.c, "config.mcp.addHttp", params{"name": s.Name, "type": s.Type, "url": s.URL, "token": s.Token})
}

// StdioMcp 是要加的一个命令行服务器。
type StdioMcp struct {
	Name    string
	Command string
	Args    []string
	Env     map[string]string
}

// AddStdioMcp 加一个命令行服务器到用户配置（慢；config.mcp.addStdio）。
func (cl *Client) AddStdioMcp(ctx context.Context, s StdioMcp) error {
	return call(ctx, cl.c, "config.mcp.addStdio", params{"name": s.Name, "command": s.Command, "args": s.Args, "env": s.Env})
}

// RemoveMcp 删掉服务器 s（慢；config.mcp.remove）。
func (cl *Client) RemoveMcp(ctx context.Context, cwd string, s McpServer) error {
	return call(ctx, cl.c, "config.mcp.remove", serverParams(cwd, s))
}

// SetMcpToken 换掉服务器 s 的 Bearer Token，空是去掉身份认证；只认 user、local 范围（慢；config.mcp.setToken）。
func (cl *Client) SetMcpToken(ctx context.Context, cwd string, s McpServer, token string) error {
	p := serverParams(cwd, s)
	p["token"] = token
	return call(ctx, cl.c, "config.mcp.setToken", p)
}
