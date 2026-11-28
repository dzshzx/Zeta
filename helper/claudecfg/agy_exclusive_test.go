package claudecfg

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestAgySettingsRoundTripAndProxyPasswordRedaction(t *testing.T) {
	home := t.TempDir()
	path := agySettingsPath(home)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	initial := `{
  "toolPermission": "proceed-in-sandbox",
  "unknownKey": "keep-me",
  "sandboxProxy": {
    "http": "http://127.0.0.1:7890",
    "username": "alice",
    "password": "super-secret-value"
  }
}`
	if err := os.WriteFile(path, []byte(initial), 0o600); err != nil {
		t.Fatal(err)
	}
	s := New(home).ForHarness("agy")
	got, err := s.ReadAgySettings()
	if err != nil {
		t.Fatal(err)
	}
	if got.ToolPermission != "proceed-in-sandbox" || !got.SandboxProxy.HasPassword {
		t.Fatalf("unexpected settings: %+v", got)
	}
	encoded, _ := json.Marshal(got)
	if strings.Contains(string(encoded), "super-secret-value") {
		t.Fatal("ReadAgySettings leaked raw proxy password")
	}

	// Update unrelated field: password and unknownKey must remain intact.
	sandbox := true
	policy := "agent-decides"
	httpURL := "http://127.0.0.1:8080"
	updated, err := s.UpdateAgySettings(AgySettingsPatch{
		EnableTerminalSandbox: &sandbox,
		ArtifactReviewPolicy:  &policy,
		SandboxProxy:          &AgySandboxProxyPatch{HTTP: &httpURL},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !updated.EnableTerminalSandbox || updated.ArtifactReviewPolicy != "agent-decides" || !updated.SandboxProxy.HasPassword {
		t.Fatalf("unexpected updated settings: %+v", updated)
	}
	raw, err := readJSONMap(path)
	if err != nil {
		t.Fatal(err)
	}
	if raw["unknownKey"] != "keep-me" {
		t.Fatalf("unknownKey lost: %v", raw["unknownKey"])
	}
	if subMap(raw, "sandboxProxy")["password"] != "super-secret-value" {
		t.Fatalf("existing password should be preserved when not clearing")
	}

	// Clear password explicitly.
	cleared, err := s.UpdateAgySettings(AgySettingsPatch{
		SandboxProxy: &AgySandboxProxyPatch{ClearPassword: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	if cleared.SandboxProxy.HasPassword {
		t.Fatal("expected HasPassword=false after ClearPassword")
	}
}

func TestAgyPluginsRunnerMock(t *testing.T) {
	orig := runAgyPluginCmd
	t.Cleanup(func() { runAgyPluginCmd = orig })

	var calls [][]string
	plugins := []AgyPlugin{{Name: "demo", Version: "1.0.0", Enabled: true}}
	runAgyPluginCmd = func(_ context.Context, _ string, args ...string) ([]byte, error) {
		calls = append(calls, slices.Clone(args))
		switch args[0] {
		case "disable":
			plugins[0].Enabled = false
			return nil, nil
		case "install":
			plugins = append(plugins, AgyPlugin{Name: args[1], Enabled: true})
			return nil, nil
		case "uninstall":
			plugins = plugins[:1]
			return nil, nil
		default:
			return json.Marshal(plugins)
		}
	}

	s := New(t.TempDir()).ForHarness("agy")
	got, err := s.SetPluginEnabled(context.Background(), "demo", false)
	if err != nil || len(got) != 1 || got[0].Enabled {
		t.Fatalf("SetPluginEnabled: %+v err=%v", got, err)
	}
	got, err = s.InstallPlugin(context.Background(), "extra")
	if err != nil || len(got) != 2 {
		t.Fatalf("InstallPlugin: %+v err=%v", got, err)
	}
	got, err = s.UninstallPlugin(context.Background(), "extra")
	if err != nil || len(got) != 1 {
		t.Fatalf("UninstallPlugin: %+v err=%v", got, err)
	}
	if len(calls) < 6 {
		t.Fatalf("expected plugin CLI calls, got %v", calls)
	}
}

func TestAgyHooksCRUDAndScopes(t *testing.T) {
	home := t.TempDir()
	cwd := t.TempDir()
	pluginHooks := filepath.Join(agyConfigDir(home), "plugins", "myplug", "hooks.json")
	if err := os.MkdirAll(filepath.Dir(pluginHooks), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(pluginHooks, []byte(`{"plug-hook":{"SessionStart":[{"type":"command","command":"echo hi"}]}}`), 0o644); err != nil {
		t.Fatal(err)
	}

	s := New(home).ForHarness("agy")
	view, err := s.SaveHook(cwd, AgyHookDraft{
		Name:    "guard",
		Scope:   "user",
		Enabled: true,
		Event:   "PreToolUse",
		Matcher: "run_command",
		Command: "echo check",
		Timeout: 10,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(view.Hooks) != 2 {
		t.Fatalf("expected user + plugin hooks, got %+v", view.Hooks)
	}
	view, err = s.SetHookEnabled(cwd, "user", "guard", false)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range view.Hooks {
		if h.Name == "guard" && h.Enabled {
			t.Fatal("expected guard to be disabled")
		}
	}
	if _, err := s.SetHookEnabled(cwd, "plugin", "plug-hook", false); err == nil {
		t.Fatal("expected plugin hook toggle to fail")
	}
	view, err = s.DeleteHook(cwd, "user", "guard")
	if err != nil || len(view.Hooks) != 1 || view.Hooks[0].Scope != "plugin" {
		t.Fatalf("DeleteHook: %+v err=%v", view.Hooks, err)
	}
}

func TestAgySidecarsDiscoverAndToggle(t *testing.T) {
	home := t.TempDir()
	userSC := filepath.Join(agyConfigDir(home), "sidecars", "indexer", "sidecar.json")
	plugSC := filepath.Join(agyConfigDir(home), "plugins", "ext", "sidecars", "watcher", "sidecar.json")
	for _, p := range []string{userSC, plugSC} {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	_ = os.WriteFile(userSC, []byte(`{"display_name":"Indexer","command":"idx","restart_policy":"on-failure"}`), 0o644)
	_ = os.WriteFile(plugSC, []byte(`{"display_name":"Watcher","builtin":"watch"}`), 0o644)
	_ = os.WriteFile(agyGlobalConfigPath(home), []byte(`{"keepOther":true}`), 0o644)

	s := New(home).ForHarness("agy")
	list, err := s.ListSidecars()
	if err != nil || len(list) != 2 {
		t.Fatalf("ListSidecars: %+v err=%v", list, err)
	}
	proj := "proj-1"
	list, err = s.SetSidecarEnabled("ext/watcher", true, &proj)
	if err != nil {
		t.Fatal(err)
	}
	var found bool
	for _, sc := range list {
		if sc.ID == "ext/watcher" && sc.Enabled && sc.ProjectID == "proj-1" {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected ext/watcher enabled with proj-1, got %+v", list)
	}
	raw, _ := readJSONMap(agyGlobalConfigPath(home))
	if raw["keepOther"] != true {
		t.Fatal("config.json unknown key was lost")
	}
}
