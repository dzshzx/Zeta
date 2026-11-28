package claudecfg

import (
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestAgyDefaultsAndNoClaudeKeys(t *testing.T) {
	home := t.TempDir()
	s := New(home).ForHarness("agy")
	settingsFile := filepath.Join(home, ".gemini", "antigravity-cli", "settings.json")

	// 省略 agentMode 时返回空（跟随 CLI 默认）
	write(t, settingsFile, `{"model":"gemini-4-neon-high","effortLevel":"high","autoCompactWindow":400000}`)
	d, err := s.Defaults()
	if err != nil {
		t.Fatal(err)
	}
	if d.Model != "gemini-4-neon-high" || d.PermissionMode != "" || d.Effort != "" || d.CompactWindow != 0 {
		t.Fatalf("unexpected agy defaults: %+v", d)
	}

	// 显式设置 default 写 agentMode=""，不破坏已有非 always-proceed 的 toolPermission
	write(t, settingsFile, `{"toolPermission":"proceed-in-sandbox"}`)
	if d, err = s.SetDefaultPermissionMode("default"); err != nil || d.PermissionMode != "default" {
		t.Fatalf("set default mode: %+v %v", d, err)
	}
	got := read(t, settingsFile)
	if !strings.Contains(got, `"agentMode": ""`) || !strings.Contains(got, `"toolPermission": "proceed-in-sandbox"`) {
		t.Fatalf("settings after default mode:\n%s", got)
	}
	if mode := AgyDefaultModeFromSettings([]byte(got)); mode != "default" {
		t.Fatalf("AgyDefaultModeFromSettings = %q, want default", mode)
	}

	// Claude 专有配置方法对 agy 必须拒绝且不写脏 settings.json
	if _, err := s.SetDefaultEffort("", "high"); err == nil {
		t.Error("SetDefaultEffort should fail for agy")
	}
	if _, err := s.SetCompactWindow(300_000); err == nil {
		t.Error("SetCompactWindow should fail for agy")
	}
	if err := s.SetAutoMemory(false); err == nil {
		t.Error("SetAutoMemory should fail for agy")
	}
	if err := s.SetSkillEnabled(Skill{Name: "demo"}, false); err == nil {
		t.Error("SetSkillEnabled should fail for agy")
	}
	after := read(t, settingsFile)
	for _, forbidden := range []string{"effortLevel", "autoCompactWindow", "autoMemoryEnabled", "skillOverrides"} {
		if strings.Contains(after, forbidden) {
			t.Errorf("agy settings polluted with %s:\n%s", forbidden, after)
		}
	}
}

func TestAgyPermissionsSyntaxAndView(t *testing.T) {
	home := t.TempDir()
	s := New(home).ForHarness("agy")
	write(t, filepath.Join(home, ".gemini", "config", "mcp_config.json"),
		`{"mcpServers":{"gh":{"url":"https://mcp.example.com/mcp"}}}`)

	// 拒绝 Claude 风格工具名与非法写法
	for _, bad := range []string{"Bash", "run_command(git push:*)", "edit_file(src/**)", "command()", "command(  )", "mcp__gh__list"} {
		if _, err := s.AddPermissionRule(Rule{Allow, bad}); err == nil {
			t.Errorf("expected error for invalid agy rule %q", bad)
		}
	}

	// 合法 Antigravity action(target) 规则
	if _, err := s.AddPermissionRule(Rule{Allow, "command(git status)"}); err != nil {
		t.Fatal(err)
	}
	rs, err := s.SetToolPermission("write_file", Allow)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(rs.Of(Allow), "write_file(*)") {
		t.Fatalf("expected write_file(*) in allow: %+v", rs)
	}
	if _, err = s.SetToolPermission("mcp(gh/*)", Allow); err != nil {
		t.Fatal(err)
	}
	rs, err = s.AddPermissionRule(Rule{Deny, "mcp(gh/delete)"})
	if err != nil {
		t.Fatal(err)
	}

	view := s.PermissionsView(rs, home, "gh", []string{"list", "delete"})
	if len(view.Builtin) != len(AgyCommonTools)+len(AgyOtherTools) {
		t.Fatalf("builtin len = %d", len(view.Builtin))
	}
	if view.Builtin[0].Name != "command" || view.Builtin[0].Rule != "command(*)" || len(view.Builtin[0].State.Specific) != 1 {
		t.Fatalf("command view: %+v", view.Builtin[0])
	}
	if view.Focus == nil || view.Focus.Rule != "mcp(gh/*)" || view.Focus.State.Behavior != Allow {
		t.Fatalf("focus: %+v", view.Focus)
	}
	if len(view.ServerTools) != 2 ||
		view.ServerTools[0].Rule != "mcp(gh/list)" || !view.ServerTools[0].State.Inherited || view.ServerTools[0].State.Behavior != Allow ||
		view.ServerTools[1].Rule != "mcp(gh/delete)" || view.ServerTools[1].State.Inherited || view.ServerTools[1].State.Behavior != Deny {
		t.Fatalf("server tools: %+v", view.ServerTools)
	}

	// 一键关闭内置动作不应影响 mcp 规则
	rs, err = s.SetGroupPermission(AgyBuiltinGroup(), false)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(rs.Of(Deny), "command(*)") || !slices.Contains(rs.Of(Allow), "mcp(gh/*)") {
		t.Fatalf("after bulk disable: %+v", rs)
	}
}

func TestAgyMcpListExactMatchAndServerUrl(t *testing.T) {
	home := t.TempDir()
	s := New(home).ForHarness("agy")
	mcpFile := filepath.Join(home, ".gemini", "config", "mcp_config.json")
	write(t, mcpFile, `{"mcpServers":{"foo":{"serverUrl":"https://foo.example/mcp"},"foobar":{"url":"https://foobar.example/mcp"}}}`)

	out := "NAME    TYPE  STATUS    COMMAND/URL\nfoobar  http  enabled   https://foobar.example/mcp\nfoo     http  disabled  https://foo.example/mcp\n"
	entries, err := s.scanAgyMcp(home)
	if err != nil {
		t.Fatal(err)
	}
	st := parseAgyMcpList(out, entries)
	if st.Servers["user/foo"].State != "disabled" || st.Servers["user/foobar"].State != "connected" {
		t.Fatalf("mcp status: %+v", st.Servers)
	}

	// 更新 token 保留 serverUrl，不写多余 url 或 type
	if err := s.setAgyMcpToken("user", "foo", home, "tok-123"); err != nil {
		t.Fatal(err)
	}
	raw := read(t, mcpFile)
	if !strings.Contains(raw, `"serverUrl"`) || strings.Contains(raw, `"foo": {\n      "serverUrl": "https://foo.example/mcp",\n      "url"`) {
		t.Fatalf("unexpected mcp_config.json:\n%s", raw)
	}
}
