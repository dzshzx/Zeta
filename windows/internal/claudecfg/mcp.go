package claudecfg

import (
	"context"
	"regexp"
)

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

var mcpNamePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// ValidMcpName：字母、数字、下划线和连字符，最长 64（添加前在表单里先查一遍，那边还会再查）。
func ValidMcpName(name string) bool { return mcpNamePattern.MatchString(name) }

// McpServers 列出配置里的服务器。
func (cl *Client) McpServers(ctx context.Context) ([]McpServer, error) {
	var list []McpServer
	err := cl.call(ctx, "config.mcp.list", obj{"cwd": cl.Cwd}, &list)
	return list, err
}

// McpStatus 逐个连接做健康检查，可能要十几秒。
func (cl *Client) McpStatus(ctx context.Context) (McpStatus, error) {
	var st McpStatus
	err := cl.call(ctx, "config.mcp.status", obj{"cwd": cl.Cwd}, &st)
	return st, err
}

func (cl *Client) server(s McpServer) obj {
	return obj{"cwd": cl.Cwd, "scope": s.Scope, "name": s.Name}
}

// McpTools 连上服务器取工具清单。
func (cl *Client) McpTools(ctx context.Context, s McpServer) ([]McpTool, error) {
	var tools []McpTool
	err := cl.call(ctx, "config.mcp.tools", cl.server(s), &tools)
	return tools, err
}

// AddHTTPServer 加一个 HTTP 或 SSE（typ）服务器到用户配置，token 可以为空。
func (cl *Client) AddHTTPServer(ctx context.Context, name, typ, url, token string) error {
	return cl.call(ctx, "config.mcp.addHttp", obj{"name": name, "type": typ, "url": url, "token": token}, nil)
}

// AddStdioServer 加一个命令行服务器到用户配置。
func (cl *Client) AddStdioServer(ctx context.Context, name, command string, args []string, env map[string]string) error {
	return cl.call(ctx, "config.mcp.addStdio", obj{"name": name, "command": command, "args": args, "env": env}, nil)
}

// RemoveMcp 删掉一个服务器。
func (cl *Client) RemoveMcp(ctx context.Context, s McpServer) error {
	return cl.call(ctx, "config.mcp.remove", cl.server(s), nil)
}

// SetMcpToken 换掉 Bearer Token，空是去掉身份认证。
func (cl *Client) SetMcpToken(ctx context.Context, s McpServer, token string) error {
	p := cl.server(s)
	p["token"] = token
	return cl.call(ctx, "config.mcp.setToken", p, nil)
}
