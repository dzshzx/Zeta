package cfgtest_test

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/claudecfg/cfgtest"
)

// coreapi 的 config.* 经真的 serve 调得通：参数核心认得，结果解得出来，错误原样带回来。
func TestCoreAPIThroughServe(t *testing.T) {
	ctx := context.Background()
	s := cfgtest.Start(t)
	s.Write(t, ".claude/settings.json", `{"theme":"dark"}`)
	s.Write(t, ".claude/CLAUDE.md", "回答用中文\n")
	cl := coreapi.New(s)

	d, err := cl.SetDefaultEffort(ctx, "claude-opus-5-5", "max")
	if err != nil || d.EffortOf("claude-opus-5-5") != "max" {
		t.Fatalf("effort %+v %v", d, err)
	}
	if d, err := cl.SetDefaultPermissionMode(ctx, "bypassPermissions"); err != nil || d.PermissionMode != "bypassPermissions" {
		t.Errorf("bypass: %+v %v", d, err)
	}

	m, err := cl.Memory(ctx, "", "")
	if err != nil || !m.Instructions.Exists || len(m.Projects) != 1 || !m.Projects[0].Default {
		t.Fatalf("memory %+v %v", m, err)
	}
	doc, err := cl.ReadMemoryFile(ctx, m.Instructions.Path)
	if err != nil || doc.Content != "回答用中文\n" {
		t.Fatal(doc, err)
	}
	if _, err := cl.WriteMemoryFile(ctx, m.Instructions.Path, "x", "stale", false); !errors.Is(err, coreapi.ErrConflict) {
		t.Errorf("conflict: %v", err)
	}
	if _, err := cl.WriteMemoryFile(ctx, m.Instructions.Path, "新的", doc.SHA, false); err != nil || s.Read(".claude/CLAUDE.md") != "新的" {
		t.Errorf("write: %v", err)
	}

	file, err := cl.SaveAgent(ctx, nil, coreapi.SubagentDraft{Name: "rev", Description: "审查", Prompt: "p"})
	if err != nil || file != "rev.md" {
		t.Fatal(file, err)
	}
	agents, err := cl.Agents(ctx)
	if err != nil || len(agents) != 1 || agents[0].Tools != nil {
		t.Errorf("agents %+v %v", agents, err)
	}

	p, err := cl.AddPermissionRule(ctx, coreapi.PermissionView{}, coreapi.Rule{Behavior: coreapi.Deny, Text: "Bash(rm:*)"})
	if err != nil || len(p.Rules) != 1 || len(p.Builtin) == 0 || p.Builtin[0].Name != "Bash" || len(p.Builtin[0].State.Specific) != 1 {
		t.Fatalf("permissions %+v %v", p, err)
	}
	if p, err = cl.SetPermissionGroup(ctx, coreapi.PermissionView{}, p.BuiltinGroup(), false); err != nil ||
		p.Builtin[0].State.Behavior != coreapi.Deny || !p.BuiltinBulk.Disable.Empty() {
		t.Errorf("bulk %+v %v", p.Builtin[0], err)
	}
	if !strings.Contains(s.Read(".claude/settings.json"), `"theme": "dark"`) {
		t.Error("other settings lost")
	}
	if servers, err := cl.McpServers(ctx, ""); err != nil || len(servers) != 0 {
		t.Errorf("mcp %v %v", servers, err)
	}
	if !slices.Contains(s.Calls(), "config.permissions.bulk") {
		t.Error(s.Calls())
	}
}
