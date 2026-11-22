package claudecfg

import (
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestParseRule(t *testing.T) {
	cases := map[string]parsedRule{
		"Bash":                 {tool: "Bash"},
		"Task":                 {tool: "Agent"},
		"Bash(git push:*)":     {tool: "Bash", content: "git push:*", hasContent: true},
		`Bash(echo \(x\))`:     {tool: "Bash", content: "echo (x)", hasContent: true},
		"Bash(x":               {tool: "Bash(x", malformed: true},
		"(x)":                  {tool: "(x)", malformed: true},
		"Bash(x) y":            {tool: "Bash(x) y", malformed: true},
		"mcp__github__get(x)":  {tool: "mcp__github__get", content: "x", hasContent: true},
		"WebFetch(domain:a.b)": {tool: "WebFetch", content: "domain:a.b", hasContent: true},
	}
	for rule, want := range cases {
		if got := parseRule(rule); got != want {
			t.Errorf("%s: %+v, want %+v", rule, got, want)
		}
	}
	if RuleProblem(" Bash") == "" || RuleProblem("Bash(") == "" || RuleProblem("") == "" || RuleProblem("Read(./.env)") != "" {
		t.Error("rule problems")
	}
}

func TestToolStates(t *testing.T) {
	rs := Rules{{Deny, "WebFetch"}, {Ask, "Bash"}, {Allow, "Bash(git push:*)"}, {Allow, "Read(*)"}, {Allow, "mcp__gh"}, {Deny, "mcp__gh__delete"}}
	if st := rs.Tool("Bash"); st.Behavior != Ask || len(st.Specific) != 1 {
		t.Errorf("Bash %+v", st)
	}
	// 允许表里的 Read(*) 只放行工作目录，不算整个工具
	if st := rs.Tool("Read"); st.Behavior != "" || len(st.Specific) != 1 {
		t.Errorf("Read %+v", st)
	}
	if st := rs.McpTool("gh", "list"); st.Behavior != Allow || !st.Inherited {
		t.Errorf("inherited %+v", st)
	}
	if st := rs.McpTool("gh", "delete"); st.Behavior != Deny || st.Inherited {
		t.Errorf("own deny %+v", st)
	}
	if McpServerName("claude.ai Google Drive") != "mcp__claude_ai_Google_Drive" {
		t.Error(McpServerName("claude.ai Google Drive"))
	}
	ch := rs.Bulk(PermissionGroup{Tools: []string{"Bash", "WebFetch", "Read"}}, true)
	if !slices.Equal(ch.Remove, []Rule{{Deny, "WebFetch"}, {Ask, "Bash"}}) ||
		!slices.Equal(ch.Add, []Rule{{Allow, "Bash"}, {Allow, "WebFetch"}, {Allow, "Read"}}) {
		t.Errorf("bulk %+v", ch)
	}
	if ch := rs.Bulk(PermissionGroup{Server: "gh"}, false); !slices.Equal(ch.Remove, []Rule{{Allow, "mcp__gh"}}) || len(ch.Add) != 1 {
		t.Errorf("server bulk %+v", ch)
	}
}

func TestPermissionEditsKeepOtherSettings(t *testing.T) {
	home, c := newHost(t)
	path := filepath.Join(home, ".claude", "settings.json")
	write(t, path, "{\n  \"permissions\": {\n    \"defaultMode\": \"plan\",\n    \"allow\": [\"Bash(ls:*)\", 7]\n  },\n  \"model\": \"opus\"\n}\n")
	rs, err := c.SetToolPermission("Task", Deny)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(rs.Of(Deny), []string{"Agent"}) {
		t.Errorf("deny %v", rs)
	}
	if rs, err = c.AddPermissionRule(Rule{Ask, "Bash(ls:*)"}); err != nil || len(rs.Of(Allow)) != 0 || len(rs.Of(Ask)) != 1 {
		t.Errorf("move rule %v %v", rs, err)
	}
	if rs, err = c.ReplacePermissionRule(Rule{Ask, "Bash(ls:*)"}, Rule{Ask, "Bash(ls -la:*)"}); err != nil || rs.Of(Ask)[0] != "Bash(ls -la:*)" {
		t.Errorf("replace %v %v", rs, err)
	}
	if _, err = c.ReplacePermissionRule(Rule{Ask, "gone"}, Rule{Ask, "x"}); err == nil {
		t.Error("replacing a missing rule")
	}
	if _, err = c.AddPermissionRule(Rule{Allow, "Bash("}); err == nil {
		t.Error("malformed rule accepted")
	}
	if rs, err = c.SetGroupPermission(BuiltinGroup(), false); err != nil || !slices.Contains(rs.Of(Deny), "Bash") || len(rs.Of(Ask)) != 0 {
		t.Errorf("bulk %v %v", rs, err)
	}
	got := read(t, path)
	// 不认识的值（7）、defaultMode、别的键和顺序原样；原来有的表删空了留着 []
	if !strings.HasPrefix(got, "{\n  \"permissions\": {\n    \"defaultMode\": \"plan\",\n    \"allow\": [\n      7\n    ],\n    \"deny\": [\n      \"Agent\",") ||
		!strings.Contains(got, "\"ask\": []") || !strings.HasSuffix(got, "\n  },\n  \"model\": \"opus\"\n}\n") {
		t.Errorf("settings:\n%s", got)
	}
	write(t, path, `{"permissions":"bad"}`)
	if _, err := c.SetToolPermission("Bash", Allow); err == nil || !strings.Contains(err.Error(), "permissions 不是一个对象") {
		t.Errorf("bad permissions: %v", err)
	}
}

func TestPermissionsView(t *testing.T) {
	home, c := newHost(t)
	write(t, filepath.Join(home, ".claude.json"), `{"mcpServers":{"gh":{"type":"http","url":"https://x.invalid"}}}`)
	v := c.PermissionsView(Rules{{Allow, "mcp__gh"}, {Allow, "Bash("}}, "", "gh", []string{"list"})
	if len(v.Rules) != 2 || !v.Rules[1].Malformed || len(v.Builtin) != len(CommonTools)+len(OtherTools) || !v.Builtin[len(v.Builtin)-1].More {
		t.Fatalf("view %+v", v)
	}
	if len(v.Servers) != 1 || v.Servers[0].State.Behavior != Allow || len(v.Servers[0].Bulk.Enable.Add) != 0 {
		t.Errorf("servers %+v", v.Servers)
	}
	if len(v.ServerTools) != 1 || !v.ServerTools[0].State.Inherited {
		t.Errorf("server tools %+v", v.ServerTools)
	}
}

func TestDefaults(t *testing.T) {
	home, c := newHost(t)
	path := filepath.Join(home, ".claude", "settings.json")
	write(t, path, `{"modelSettings":{"claude-opus-5-5":{"maxEffortLevel":"max"}},"autoCompactWindow":50000}`)
	d, err := c.Defaults()
	if err != nil || d.CompactWindow != 0 || len(d.Efforts) != 0 {
		t.Fatalf("%+v %v", d, err)
	}
	if d, err = c.SetDefaultEffort("claude-opus-5-5", "xhigh"); err != nil || d.Efforts[0] != (ModelEffort{"claude-opus-5-5", "xhigh"}) {
		t.Fatalf("effort %+v %v", d, err)
	}
	if d, err = c.SetDefaultEffort("claude-opus-5-5", ""); err != nil || len(d.Efforts) != 0 {
		t.Fatalf("effort removed %+v %v", d, err)
	}
	if !strings.Contains(read(t, path), "maxEffortLevel") {
		t.Error("other keys of the model dropped")
	}
	if _, err = c.SetDefaultEffort("claude-haiku-4-5", ""); err != nil || strings.Contains(read(t, path), "haiku") {
		t.Errorf("deleting unset effort created keys: %v", err)
	}
	if d, err = c.SetDefaultEffort("", "high"); err != nil || d.Effort != "high" {
		t.Errorf("top effort %+v", d)
	}
	if d, err = c.SetDefaultModel("opus"); err != nil || d.Model != "opus" {
		t.Errorf("model %+v", d)
	}
	if d, err = c.SetCompactWindow(400_000); err != nil || d.CompactWindow != 400_000 {
		t.Errorf("window %+v", d)
	}
	if _, err = c.SetCompactWindow(50_000); err == nil {
		t.Error("tiny window accepted")
	}
	if d, err = c.SetDefaultPermissionMode("acceptEdits"); err != nil || d.PermissionMode != "acceptEdits" {
		t.Errorf("mode %+v", d)
	}
	if _, err = c.SetDefaultPermissionMode("bypassPermissions"); err == nil {
		t.Error("bypass accepted")
	}
	if d, err = c.SetDefaultModel(""); err != nil || d.Model != "" || strings.Contains(read(t, path), `"model"`) {
		t.Errorf("model removed %+v", d)
	}
	if FormatTokens(400_000) != "400K" || FormatTokens(1_000_000) != "1M" || FormatTokens(1234) != "1.2K" {
		t.Error(FormatTokens(400_000), FormatTokens(1_000_000), FormatTokens(1234))
	}
}
