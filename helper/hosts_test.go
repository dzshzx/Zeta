package main

import (
	"strings"
	"testing"
)

// 等着回应的请求：主对话和后台子代理的确认可以同时挂着，按到达顺序全列出来；有了结果、被撤回或这一轮结束（子代理的除外）就去掉。
func TestScanHostListsEveryUnansweredPrompt(t *testing.T) {
	cases := []struct {
		name  string
		lines []string
		want  []string // 留下的 request_id
	}{
		{"两个同时等", []string{
			`{"type":"system","subtype":"init","session_id":"s"}`,
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"s1","agent_id":"a1"}}`,
		}, []string{"main", "sub"}},
		{"答了一个", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_use_id":"s1","agent_id":"a1"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"m1","content":"ok"}]}}`,
		}, []string{"sub"}},
		{"撤回", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_cancel_request","request_id":"main"}`,
		}, nil},
		{"一轮结束时子代理的还在等", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_use_id":"s1","agent_id":"a1"}}`,
			`{"type":"result","subtype":"success"}`,
		}, []string{"sub"}},
		{"elicitation 在那台服务器的调用出结果后去掉", []string{
			`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"mcp__my_srv__ask"},{"type":"tool_use","id":"t2","name":"mcp__other__x"}]}}`,
			`{"type":"control_request","request_id":"el","request":{"subtype":"elicitation","mcp_server_name":"my.srv","mode":"form"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}}`,
		}, []string{"el"}},
		{"elicitation 答过", []string{
			`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"mcp__my_srv__ask"}]}}`,
			`{"type":"control_request","request_id":"el","request":{"subtype":"elicitation","mcp_server_name":"my.srv","mode":"form"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`,
		}, nil},
		{"手机工具的 MCP 消息不算", []string{
			`{"type":"control_request","request_id":"mcp","request":{"subtype":"mcp_message","server_name":"phone"}}`,
		}, nil},
	}
	for _, c := range cases {
		dir := t.TempDir()
		writeFile(t, dir+"/out.jsonl", strings.Join(c.lines, "\n")+"\n")
		scan, err := scanHost(dir)
		if err != nil {
			t.Fatal(err)
		}
		var got []string
		for _, p := range scan.pending {
			got = append(got, asString(asObj(p)["request_id"]))
		}
		if strings.Join(got, ",") != strings.Join(c.want, ",") {
			t.Errorf("%s: pending = %v, want %v", c.name, got, c.want)
		}
	}
}
