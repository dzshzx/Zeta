// Command fakeclaude 在测试里冒充主机上的 claude：mcp list、mcp add-json、mcp remove 照 Claude Code 的样子读写
// ~/.claude.json；mcp-server 是一个 stdio 的 MCP 服务器（工具清单分两页，名字前缀取环境变量 TOOL_PREFIX）。
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

func main() {
	args := os.Args[1:]
	switch {
	case len(args) >= 1 && args[0] == "mcp-server":
		serve()
	case len(args) == 2 && args[0] == "mcp" && args[1] == "list":
		list()
	case len(args) == 6 && args[0] == "mcp" && args[1] == "add-json" && args[2] == "-s" && args[5] == "-":
		addJSON(args[3], args[4])
	case len(args) == 5 && args[0] == "mcp" && args[1] == "remove" && args[2] == "-s":
		remove(args[3], args[4])
	default:
		fmt.Println("error: unknown command", strings.Join(args, " "))
		os.Exit(1)
	}
}

func configPath() string { return filepath.Join(os.Getenv("HOME"), ".claude.json") }

func load() map[string]any {
	data := map[string]any{}
	if b, err := os.ReadFile(configPath()); err == nil {
		if err := json.Unmarshal(b, &data); err != nil {
			fmt.Println("error: bad ~/.claude.json")
			os.Exit(1)
		}
	}
	return data
}

func save(data map[string]any) {
	b, _ := json.MarshalIndent(data, "", "  ")
	if err := os.WriteFile(configPath(), b, 0o600); err != nil {
		fmt.Println("error:", err)
		os.Exit(1)
	}
}

// servers 是 scope 的 mcpServers（local 按当前目录存在 projects 里）。
func servers(data map[string]any, scope string, create bool) map[string]any {
	parent := data
	if scope == "local" {
		cwd, _ := os.Getwd()
		projects, _ := data["projects"].(map[string]any)
		if projects == nil {
			projects = map[string]any{}
			data["projects"] = projects
		}
		parent, _ = projects[cwd].(map[string]any)
		if parent == nil {
			parent = map[string]any{}
			projects[cwd] = parent
		}
	}
	s, _ := parent["mcpServers"].(map[string]any)
	if s == nil && create {
		s = map[string]any{}
		parent["mcpServers"] = s
	}
	return s
}

func addJSON(scope, name string) {
	in, _ := io.ReadAll(os.Stdin)
	var cfg map[string]any
	if err := json.Unmarshal(in, &cfg); err != nil {
		fmt.Println("error: invalid JSON")
		os.Exit(1)
	}
	// 测试用：地址里有 reject 时像 Claude Code 校验失败那样报错，报错里带上配置原文
	if u, _ := cfg["url"].(string); strings.Contains(u, "reject") {
		fmt.Println("error: invalid config", string(in))
		os.Exit(1)
	}
	data := load()
	servers(data, scope, true)[name] = cfg
	save(data)
	fmt.Printf("Added %s MCP server %s\n", scope, name)
}

func remove(scope, name string) {
	data := load()
	s := servers(data, scope, false)
	if _, ok := s[name]; !ok {
		fmt.Printf("No %s-scoped MCP server found with name: %s\n", scope, name)
		os.Exit(1)
	}
	delete(s, name)
	save(data)
	fmt.Printf("Removed MCP server %s\n", name)
}

func list() {
	fmt.Println("Checking MCP server health…")
	fmt.Println()
	s := servers(load(), "user", false)
	names := make([]string, 0, len(s))
	for n := range s {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		cfg, _ := s[n].(map[string]any)
		if cmd, ok := cfg["command"].(string); ok {
			parts := []string{cmd}
			for _, a := range cfg["args"].([]any) {
				parts = append(parts, fmt.Sprint(a))
			}
			fmt.Printf("%s: %s - ✗ Failed to connect — see https://example.invalid/logs\n", n, strings.Join(parts, " "))
			continue
		}
		fmt.Printf("%s: %s (%s) - ✓ Connected\n", n, cfg["url"], strings.ToUpper(fmt.Sprint(cfg["type"])))
	}
	fmt.Println("claude.ai Gmail: https://gmail.example.invalid/mcp - ! Needs authentication")
}

// serve 是 stdio 的 MCP 服务器：先打一行不是 JSON 的字，再按行收发 JSON-RPC。
func serve() {
	fmt.Println("starting fake server")
	prefix := os.Getenv("TOOL_PREFIX")
	in := bufio.NewScanner(os.Stdin)
	for in.Scan() {
		var msg struct {
			ID     *int           `json:"id"`
			Method string         `json:"method"`
			Params map[string]any `json:"params"`
		}
		if json.Unmarshal(in.Bytes(), &msg) != nil || msg.ID == nil {
			continue
		}
		var result any
		switch msg.Method {
		case "initialize":
			result = map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{}}
		case "tools/list":
			if msg.Params["cursor"] == "2" {
				result = map[string]any{"tools": []any{map[string]any{"name": prefix + "write", "description": "写入"}}}
			} else {
				result = map[string]any{"tools": []any{map[string]any{"name": prefix + "read", "description": "读取"}}, "nextCursor": "2"}
			}
		default:
			out, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": *msg.ID, "error": map[string]any{"code": -32601, "message": "no such method"}})
			fmt.Println(string(out))
			continue
		}
		out, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": *msg.ID, "result": result})
		fmt.Println(string(out))
	}
}
