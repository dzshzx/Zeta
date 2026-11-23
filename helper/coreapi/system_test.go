package coreapi

import "testing"

// system.* 的每个函数发对方法和参数，结果解得出来（null 的版本、负载是没有）。
func TestSystemMethods(t *testing.T) {
	busy := 37
	runCases(t, []apiCase{
		{"CPU", func(cl *Client) (any, error) { return cl.CPU(ctx) },
			"system.cpu", `{}`, `{"percent":37,"cores":8}`, CPU{Percent: &busy, Cores: 8}},
		{"CPU unknown", func(cl *Client) (any, error) { return cl.CPU(ctx) },
			"system.cpu", `{}`, `{"percent":null,"cores":4}`, CPU{Cores: 4}},
		{"IdleMinutes", func(cl *Client) (any, error) { return cl.IdleMinutes(ctx) },
			"system.idle", `{}`, `{"minutes":10}`, 10},
		{"SetIdleMinutes", func(cl *Client) (any, error) { return cl.SetIdleMinutes(ctx, 0) },
			"system.set_idle", `{"minutes":0}`, `{"minutes":0}`, 0},
		{"ClaudeVersion", func(cl *Client) (any, error) { return cl.ClaudeVersion(ctx) },
			"system.claude", `{}`, `{"version":"2.1.330"}`, "2.1.330"},
		{"ClaudeVersion missing", func(cl *Client) (any, error) { return cl.ClaudeVersion(ctx) },
			"system.claude", `{}`, `{"version":null}`, ""},
		{"UpdateClaude", func(cl *Client) (any, error) { return cl.UpdateClaude(ctx) },
			"system.claude_update", `{}`, `{"ok":true,"before":"2.1.329","version":"2.1.330","output":"Updated"}`,
			ClaudeUpdate{OK: true, Before: "2.1.329", Version: "2.1.330", Output: "Updated"}},
		{"UpdateClaude not installed", func(cl *Client) (any, error) { return cl.UpdateClaude(ctx) },
			"system.claude_update", `{}`, `{"ok":false,"before":null,"version":null,"output":"claude: not found"}`,
			ClaudeUpdate{Output: "claude: not found"}},
	})
}
