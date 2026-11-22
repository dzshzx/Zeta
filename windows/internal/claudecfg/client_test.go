package claudecfg_test

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/claudecfg/cfgtest"
)

var ctx = context.Background()

// 客户端经真的 serve 调 config.*，结果解得出来，错误原样带回来。
func TestClientThroughServe(t *testing.T) {
	s := cfgtest.Start(t)
	s.Write(t, ".claude/settings.json", `{"theme":"dark"}`)
	s.Write(t, ".claude/CLAUDE.md", "回答用中文\n")
	cl := claudecfg.New(s, "")

	d, err := cl.SetDefaultEffort(ctx, "claude-opus-5-5", "max")
	if err != nil || d.EffortOf("claude-opus-5-5") != "max" {
		t.Fatalf("effort %+v %v", d, err)
	}
	if _, err := cl.SetDefaultPermissionMode(ctx, "bypassPermissions"); err == nil || !strings.Contains(err.Error(), "完全放行") {
		t.Errorf("bypass: %v", err)
	}

	m, err := cl.Memory(ctx, "")
	if err != nil || !m.Instructions.Exists || len(m.Projects) != 1 || !m.Projects[0].Default {
		t.Fatalf("memory %+v %v", m, err)
	}
	doc, err := cl.ReadFile(ctx, m.Instructions.Path)
	if err != nil || doc.Content != "回答用中文\n" {
		t.Fatal(doc, err)
	}
	if _, err := cl.WriteFile(ctx, m.Instructions.Path, "x", "stale", false); !errors.Is(err, claudecfg.ErrConflict) {
		t.Errorf("conflict: %v", err)
	}
	if _, err := cl.WriteFile(ctx, m.Instructions.Path, "新的", doc.SHA, false); err != nil || s.Read(".claude/CLAUDE.md") != "新的" {
		t.Errorf("write: %v", err)
	}

	file, err := cl.SaveAgent(ctx, nil, claudecfg.SubagentDraft{Name: "rev", Description: "审查", Prompt: "p"})
	if err != nil || file != "rev.md" {
		t.Fatal(file, err)
	}
	agents, err := cl.Agents(ctx)
	if err != nil || len(agents) != 1 || agents[0].Tools != nil {
		t.Errorf("agents %+v %v", agents, err)
	}

	p, err := cl.AddRule(ctx, claudecfg.PermissionScope{}, claudecfg.Rule{Behavior: claudecfg.Deny, Text: "Bash(rm:*)"})
	if err != nil || len(p.Rules) != 1 || len(p.Builtin) == 0 || p.Builtin[0].Name != "Bash" || len(p.Builtin[0].State.Specific) != 1 {
		t.Fatalf("permissions %+v %v", p, err)
	}
	if p, err = cl.SetGroup(ctx, claudecfg.PermissionScope{}, p.BuiltinGroup(), false); err != nil || p.Builtin[0].State.Behavior != claudecfg.Deny ||
		!p.BuiltinBulk.Disable.Empty() {
		t.Errorf("bulk %+v %v", p.Builtin[0], err)
	}
	if !strings.Contains(s.Read(".claude/settings.json"), `"theme": "dark"`) {
		t.Error("other settings lost")
	}
	if servers, err := cl.McpServers(ctx); err != nil || len(servers) != 0 {
		t.Errorf("mcp %v %v", servers, err)
	}
	if !slices.Contains(s.Calls(), "config.permissions.bulk") {
		t.Error(s.Calls())
	}
}

// fakePrefs 是内存里的本地设置，方法同 *settings.Store。
type fakePrefs struct {
	bools   map[string]bool
	ints    map[string]int64
	strings map[string][]string
}

func (f *fakePrefs) Bool(k string) (bool, bool)            { v, ok := f.bools[k]; return v, ok }
func (f *fakePrefs) Int(k string) (int64, bool)            { v, ok := f.ints[k]; return v, ok }
func (f *fakePrefs) Strings(k string) ([]string, bool)     { v, ok := f.strings[k]; return v, ok }
func (f *fakePrefs) SetStrings(k string, v []string) error { f.strings[k] = v; return nil }

// 每台设备的自动压缩、自动标题存在 remoteClaude，没设过的照手机本机的那几项。
func TestDeviceSettings(t *testing.T) {
	p := &fakePrefs{bools: map[string]bool{"autoTitle": false}, ints: map[string]int64{"autoCompactPercent": 70}, strings: map[string][]string{
		"remoteClaude": {"other/autoCompact=false"},
	}}
	d := claudecfg.DeviceSettings(p, "h1")
	if !d.AutoCompact() || d.AutoTitle() || d.CompactPercent() != 70 {
		t.Fatalf("fallbacks %v %v %v", d.AutoCompact(), d.AutoTitle(), d.CompactPercent())
	}
	if err := d.SetAutoCompact(false); err != nil {
		t.Fatal(err)
	}
	_ = d.SetCompactPercent(0)
	_ = d.SetAutoTitle(true)
	want := []string{"h1/autoCompact=false", "h1/autoCompactPercent=default", "h1/autoTitle=true", "other/autoCompact=false"}
	if !slices.Equal(p.strings["remoteClaude"], want) {
		t.Errorf("stored %v", p.strings["remoteClaude"])
	}
	if d.AutoCompact() || d.CompactPercent() != 0 || !d.AutoTitle() {
		t.Error("stored values not read back")
	}
	if claudecfg.CompactPercentLabel(0) != "默认（快满时）" || claudecfg.CompactPercentLabel(80) != "用到 80% 时" {
		t.Error("labels")
	}
}
