package claudecfg

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

var ctx = context.Background()

// rpcHandler 回 MCP 的请求：initialize、两页 tools/list；要求带上 want 这个 Authorization。
func rpcReplyFor(t *testing.T, body []byte) (id int, reply []byte, ok bool) {
	var req struct {
		ID     *int           `json:"id"`
		Method string         `json:"method"`
		Params map[string]any `json:"params"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		t.Errorf("bad request %s", body)
		return 0, nil, false
	}
	if req.ID == nil {
		return 0, nil, false
	}
	var result any = map[string]any{}
	if req.Method == "tools/list" {
		if req.Params["cursor"] == "p2" {
			result = map[string]any{"tools": []any{map[string]any{"name": "b"}}}
		} else {
			result = map[string]any{"tools": []any{map[string]any{"name": "a", "description": "工具 A"}}, "nextCursor": "p2"}
		}
	}
	out, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": *req.ID, "result": result})
	return *req.ID, out, true
}

// streamableServer 是 Streamable HTTP 的 MCP 服务器：initialize 回 JSON，tools/list 回事件流。
func streamableServer(t *testing.T, wantAuth string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != wantAuth {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.Method == http.MethodDelete {
			return
		}
		body, _ := io.ReadAll(r.Body)
		_, reply, ok := rpcReplyFor(t, body)
		if !ok {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		w.Header().Set("Mcp-Session-Id", "s1")
		if strings.Contains(string(body), "tools/list") {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, ": ping\n\nevent: message\ndata: %s\n\n", reply)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(reply)
	}))
}

// sseServer 是旧的 SSE 传输：GET 拿事件流，先给 endpoint，POST 来的请求从流里回。
func sseServer(t *testing.T) *httptest.Server {
	replies := make(chan []byte, 10)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /sse", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: endpoint\ndata: /messages?session=1\n\n")
		w.(http.Flusher).Flush()
		for {
			select {
			case reply := <-replies:
				fmt.Fprintf(w, "data: %s\n\n", reply)
				w.(http.Flusher).Flush()
			case <-r.Context().Done():
				return
			}
		}
	})
	mux.HandleFunc("POST /messages", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if _, reply, ok := rpcReplyFor(t, body); ok {
			replies <- reply
		}
		w.WriteHeader(http.StatusAccepted)
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.CloseClientConnections)
	return srv
}

func TestMcpConfigStatusAndTools(t *testing.T) {
	home, c := newHost(t)
	project := filepath.Join(home, "proj")
	if err := os.MkdirAll(project, 0o755); err != nil {
		t.Fatal(err)
	}
	const token = "tok-secret-1234567890"
	http1 := streamableServer(t, "Bearer "+token)
	defer http1.Close()
	sse := sseServer(t)
	defer sse.Close()
	claudeJSON := map[string]any{
		"mcpServers": map[string]any{
			"gh": map[string]any{"type": "http", "url": "https://user:pw@api.example.invalid/mcp?key=SECRETVALUE&x=1",
				"headers": map[string]any{"Authorization": "Bearer " + token, "X-Extra": "1"}},
			"local-tools": map[string]any{"command": "claude", "args": []any{"mcp-server", "--api-key=abcdef"},
				"env": map[string]any{"TOOL_PREFIX": "${MY_PREFIX:-env_}"}},
		},
		"projects": map[string]any{project: map[string]any{"mcpServers": map[string]any{
			"gh": map[string]any{"type": "http", "url": http1.URL + "/mcp", "headers": map[string]any{"Authorization": "${GH_AUTH}"}},
		}}},
	}
	data, _ := json.Marshal(claudeJSON)
	write(t, filepath.Join(home, ".claude.json"), string(data))
	write(t, filepath.Join(project, ".mcp.json"), `{"mcpServers":{"events":{"type":"sse","url":"`+sse.URL+`/sse"}}}`)
	write(t, filepath.Join(home, ".profile"), read(t, filepath.Join(home, ".profile"))+"export GH_AUTH='Bearer "+token+"'\n")

	servers, err := c.McpServers(project)
	if err != nil {
		t.Fatal(err)
	}
	byKey := map[string]McpServer{}
	for _, s := range servers {
		byKey[s.Scope+"/"+s.Name] = s
	}
	user := byKey["user/gh"]
	if user.URL != "https://…@api.example.invalid/mcp?key=…&x=…" || !user.Bearer || !slices.Equal(user.HeaderKeys, []string{"X-Extra"}) ||
		user.ShadowedBy != "local" {
		t.Errorf("user gh %+v", user)
	}
	if lt := byKey["user/local-tools"]; lt.Type != "stdio" || !slices.Equal(lt.Args, []string{"mcp-server", "--api-key=…"}) ||
		!slices.Equal(lt.EnvKeys, []string{"TOOL_PREFIX"}) {
		t.Errorf("stdio %+v", lt)
	}
	if _, ok := byKey["project/events"]; !ok || len(servers) != 4 {
		t.Errorf("servers %+v", servers)
	}
	for _, s := range servers {
		if b, _ := json.Marshal(s); strings.Contains(string(b), token) || strings.Contains(string(b), "SECRETVALUE") {
			t.Errorf("secret shown: %s", b)
		}
	}

	st, err := c.McpStatus(ctx, project)
	if err != nil {
		t.Fatal(err)
	}
	if st.Servers["user/gh"].State != "connected" || st.Servers["user/local-tools"].State != "failed" ||
		st.Servers["user/local-tools"].Issue != "see …" {
		t.Errorf("status %+v", st.Servers)
	}
	if len(st.Connectors) != 1 || st.Connectors[0].Name != "claude.ai Gmail" || st.Connectors[0].State.State != "auth" {
		t.Errorf("connectors %+v", st.Connectors)
	}

	for _, s := range []struct{ scope, name, want string }{
		{"local", "gh", "a,b"}, {"project", "events", "a,b"}, {"user", "local-tools", "env_read,env_write"},
	} {
		tools, err := c.McpTools(ctx, s.scope, s.name, project)
		if err != nil {
			t.Errorf("%s/%s: %v", s.scope, s.name, err)
			continue
		}
		var names []string
		for _, tool := range tools {
			names = append(names, tool.Name)
		}
		if strings.Join(names, ",") != s.want {
			t.Errorf("%s/%s tools %v", s.scope, s.name, names)
		}
	}
	// Token 不对：报错里不带 Token
	write(t, filepath.Join(home, ".profile"), read(t, filepath.Join(home, ".profile"))+"export GH_AUTH='Bearer wrong-token-value'\n")
	if _, err := c.McpTools(ctx, "local", "gh", project); err == nil || !strings.Contains(err.Error(), "HTTP 401") {
		t.Errorf("wrong token: %v", err)
	}
}

func TestMcpAddTokenRemove(t *testing.T) {
	home, c := newHost(t)
	const token = "Bearer abcdefghijklmnop"
	if err := c.AddHTTPServer(ctx, "api", "http", "https://a.example.invalid/mcp", token); err != nil {
		t.Fatal(err)
	}
	if err := c.AddHTTPServer(ctx, "api", "http", "https://b.example.invalid", ""); err == nil || !strings.Contains(err.Error(), "已存在") {
		t.Errorf("duplicate: %v", err)
	}
	if err := c.AddHTTPServer(ctx, "bad name", "http", "https://b.example.invalid", ""); err == nil {
		t.Error("bad name accepted")
	}
	// claude 报错时把配置原文打出来，报错里的 Token 换成“…”
	err := c.AddHTTPServer(ctx, "rejected", "http", "https://reject.example.invalid", "zyxwvutsrqponm")
	if err == nil || strings.Contains(err.Error(), "zyxwvutsrqponm") || !strings.Contains(err.Error(), "invalid config") {
		t.Errorf("masked error: %v", err)
	}
	if err := c.AddStdioServer(ctx, "cmd", "npx", []string{"-y", "pkg"}, map[string]string{"B": "2", "A": "1"}); err != nil {
		t.Fatal(err)
	}
	if err := c.SetMcpToken(ctx, "user", "api", "", "new-token-value"); err != nil {
		t.Fatal(err)
	}
	cfg := read(t, filepath.Join(home, ".claude.json"))
	if !strings.Contains(cfg, "Bearer new-token-value") || strings.Contains(cfg, "abcdefghijklmnop") {
		t.Errorf("token not replaced: %s", cfg)
	}
	if err := c.SetMcpToken(ctx, "user", "cmd", "", "x"); err == nil {
		t.Error("token on stdio server")
	}
	if err := c.SetMcpToken(ctx, "user", "api", "", ""); err != nil || strings.Contains(read(t, filepath.Join(home, ".claude.json")), "Authorization") {
		t.Errorf("token removed: %v", err)
	}
	if err := c.RemoveMcp(ctx, "user", "api", ""); err != nil {
		t.Fatal(err)
	}
	if err := c.RemoveMcp(ctx, "user", "api", ""); err == nil || !strings.Contains(err.Error(), "No user-scoped") {
		t.Errorf("remove missing: %v", err)
	}
	servers, _ := c.McpServers("")
	if len(servers) != 1 || servers[0].Name != "cmd" || !slices.Equal(servers[0].EnvKeys, []string{"A", "B"}) {
		t.Errorf("servers %+v", servers)
	}
}
