package agyacct

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

func setupIsolatedAgyEnv(t *testing.T) (string, *Host) {
	t.Helper()
	home := t.TempDir()
	agyDir := filepath.Join(home, ".gemini", "antigravity-cli")
	t.Setenv("ZETA_AGY_DIR", agyDir)
	h := &Host{
		Home:    home,
		Lang:    "zh-Hans",
		Package: "zeta-test",
		Wait:    100 * time.Millisecond,
	}
	return home, h
}

func writeFakeToken(t *testing.T, home, content string) {
	t.Helper()
	p := oauthTokenPath(home)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestAccountLifecycleAndRelaySwitch(t *testing.T) {
	home, h := setupIsolatedAgyEnv(t)
	ctx := context.Background()

	writeFakeToken(t, home, "opaque-oauth-token-alpha")
	st, err := h.List(ctx)
	if err != nil || st.Current == nil || st.Current.Name != "" || st.Current.DisplayName != "Google OAuth" || !st.Current.Current {
		t.Fatalf("unexpected Current before save: %+v (%v)", st.Current, err)
	}
	if savedName, err := h.Save(ctx); err != nil || savedName != "agy" {
		t.Fatalf("Save = %q, %v; want agy", savedName, err)
	}
	if fi, err := os.Stat(tokenSlotPath(home, "agy")); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("slot perm = %v, %v", fi, err)
	}

	nextName, err := h.PrepareLogin(ctx)
	if err != nil || nextName != "agy-b" {
		t.Fatalf("PrepareLogin = %q, %v; want agy-b", nextName, err)
	}
	writeFakeToken(t, home, "opaque-oauth-token-beta")
	if gotFinish, err := h.finishLogin(nextName); err != nil || gotFinish != "agy-b" {
		t.Fatalf("finishLogin = %q, %v", gotFinish, err)
	}

	verifyRelaySwitchAndRestore(t, ctx, home, h)
}

func verifyRelaySwitchAndRestore(t *testing.T, ctx context.Context, home string, h *Host) {
	t.Helper()
	err := h.SaveRelay(ctx, accounts.RelayInput{
		Name:    "my-gemini",
		BaseURL: "https://relay.example.com",
		Key:     "fake-gemini-key-123",
		Model:   "gemini-2.5-pro",
		Create:  true,
	})
	if err != nil || h.Switch(ctx, "my-gemini") != nil || CurrentRelay(home) != "my-gemini" {
		t.Fatalf("SaveRelay / Switch failed: %v", err)
	}
	envMap := ActiveRelayEnv(home)
	if envMap["GEMINI_API_KEY"] != "fake-gemini-key-123" || envMap["GOOGLE_GEMINI_BASE_URL"] != "https://relay.example.com" {
		t.Fatalf("ActiveRelayEnv = %+v", envMap)
	}
	if settingsRaw, err := os.ReadFile(agySettingsPath(home)); err != nil || !strings.Contains(string(settingsRaw), `"modelProvider": "gemini"`) {
		t.Fatalf("settings.json after relay switch: %s (%v)", settingsRaw, err)
	}

	if err := h.Switch(ctx, "agy"); err != nil || CurrentRelay(home) != "" || ActiveRelayEnv(home) != nil {
		t.Fatalf("Switch back to agy: %v", err)
	}
	if tokBytes, err := os.ReadFile(oauthTokenPath(home)); err != nil || string(tokBytes) != "opaque-oauth-token-alpha" {
		t.Fatalf("restored token mismatch: %q (%v)", tokBytes, err)
	}
	if settingsRaw, _ := os.ReadFile(agySettingsPath(home)); strings.Contains(string(settingsRaw), `"modelProvider"`) {
		t.Fatalf("expected modelProvider removed from settings.json: %s", settingsRaw)
	}
	if err := h.Remove(ctx, "agy-b"); err != nil || h.RemoveRelay(ctx, "my-gemini") != nil {
		t.Fatalf("Remove / RemoveRelay failed: %v", err)
	}
}

func TestRelayModelsGeminiAndOpenAICompat(t *testing.T) {
	_, h := setupIsolatedAgyEnv(t)
	ctx := context.Background()

	var sawHeader string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawHeader = r.Header.Get("x-goog-api-key")
		if r.URL.Path != "/v1beta/models" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`{
			"models": [
				{"name": "models/gemini-2.5-pro", "displayName": "Gemini 2.5 Pro"},
				{"name": "models/gemini-2.5-flash", "displayName": ""}
			],
			"data": [
				{"id": "custom-model-1", "display_name": "Custom One"}
			]
		}`))
	}))
	defer srv.Close()

	models, err := h.RelayModels(ctx, accounts.ModelsQuery{
		BaseURL: srv.URL,
		Key:     "test-api-key",
	})
	if err != nil {
		t.Fatalf("RelayModels: %v", err)
	}
	if sawHeader != "test-api-key" {
		t.Fatalf("x-goog-api-key = %q; want test-api-key", sawHeader)
	}
	if len(models) != 3 || models[0].ID != "gemini-2.5-pro" || models[0].DisplayName != "Gemini 2.5 Pro" || models[2].ID != "custom-model-1" {
		t.Fatalf("unexpected models: %+v", models)
	}
}

func TestLoginFlowAcceptsPlainCodeAndCallbackURL(t *testing.T) {
	home, h := setupIsolatedAgyEnv(t)
	ctx := context.Background()

	origCmd := LoginCommandFunc
	t.Cleanup(func() { LoginCommandFunc = origCmd })

	for _, tc := range []struct {
		name string
		code string
		want string
	}{
		{"plain_code", "4/0AeanS0examplePlainCode", "agy"},
		{"callback_url", "https://antigravity.google/oauth-callback?code=4/0CallbackCode&scope=email", "agy-b"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			tokenPath := oauthTokenPath(home)
			LoginCommandFunc = func(_ string) *exec.Cmd {
				script := `printf "Visit https://accounts.google.com/o/oauth2/v2/auth?client_id=test\nPaste code > "; IFS= read -r line; if [ -z "$line" ]; then exit 1; fi; mkdir -p "$(dirname "$1")"; printf "tok-%s" "$line" > "$1"; exit 0`
				return exec.Command("bash", "-c", script, "--", tokenPath)
			}
			var flow LoginFlow
			started, err := flow.Start(ctx, h)
			if err != nil {
				t.Fatalf("Start: %v", err)
			}
			if !strings.HasPrefix(started["url"], "https://accounts.google.com/") || started["name"] != tc.want {
				t.Fatalf("Start = %+v; want name %s", started, tc.want)
			}
			res, err := flow.Submit(ctx, h, tc.code)
			if err != nil {
				t.Fatalf("Submit: %v", err)
			}
			if res["name"] != tc.want {
				t.Fatalf("Submit = %+v; want %s", res, tc.want)
			}
		})
	}
}

func fakeAgyUsageRunner(agyDir, usageConv, creditsConv string) func(context.Context, string, string) ([]byte, error) {
	return func(_ context.Context, _ string, prompt string) ([]byte, error) {
		switch prompt {
		case "/usage":
			_ = os.MkdirAll(filepath.Join(agyDir, "brain", usageConv), 0o755)
			_ = os.MkdirAll(filepath.Join(agyDir, "conversations"), 0o755)
			_ = os.WriteFile(filepath.Join(agyDir, "conversations", usageConv+".pb"), []byte("x"), 0o600)
			return []byte(`{"conversation_id":"` + usageConv + `","command":{"data":{"groups":[` +
				`{"name":"Gemini Models","buckets":[{"window":"5h","remaining_fraction":0.75,"reset_time":"2026-12-01T12:00:00Z"},{"window":"weekly","remaining_fraction":1.0,"reset_time":"1970-01-01T00:00:00Z"}]},` +
				`{"name":"Claude and GPT models","buckets":[{"window":"5h","remaining_fraction":0.4,"reset_time":"2026-12-01T10:00:00Z"}]}]}}}`), nil
		case "/credits":
			_ = os.MkdirAll(filepath.Join(agyDir, "brain", creditsConv), 0o755)
			return []byte(`{"conversation_id":"` + creditsConv + `","command":{"data":{"remaining_credits":125.5}}}`), nil
		default:
			return nil, errors.New("unexpected prompt: " + prompt)
		}
	}
}

func TestQueryUsageCleansEmptySessionsAndParsesCredits(t *testing.T) {
	home, h := setupIsolatedAgyEnv(t)
	writeFakeToken(t, home, "active-sub-token")
	agyDir := AgyDir(home)
	usageConv := "11111111-2222-3333-4444-555555555555"
	creditsConv := "66666666-7777-8888-9999-aaaaaaaaaaaa"

	origRunner := UsageCommandFunc
	t.Cleanup(func() { UsageCommandFunc = origRunner })
	UsageCommandFunc = fakeAgyUsageRunner(agyDir, usageConv, creditsConv)

	raw, err := QueryUsageJSON(context.Background(), home)
	if err != nil {
		t.Fatalf("QueryUsageJSON: %v", err)
	}
	assertEmptySessionsCleaned(t, agyDir, usageConv, creditsConv)

	now := time.Date(2026, 12, 1, 8, 0, 0, 0, time.UTC)
	store := QuotaStore{Home: home}
	snap, fresh, err := store.Usage(raw, now)
	if err != nil || !fresh || snap.Plan != "Antigravity" || len(snap.Windows) != 3 {
		t.Fatalf("QuotaStore.Usage: snap=%+v fresh=%v err=%v", snap, fresh, err)
	}
	verifyParsedAgyWindowsAndCredits(t, snap)
	if cached, err := store.Get(); err != nil || cached == nil || len(cached.Windows) != 3 {
		t.Fatalf("store.Get() = %+v, %v", cached, err)
	}
	verifyRelayBlocksUsageQuery(t, home, h, store)
}

func assertEmptySessionsCleaned(t *testing.T, agyDir string, ids ...string) {
	t.Helper()
	for _, cid := range ids {
		if _, err := os.Stat(filepath.Join(agyDir, "brain", cid)); !os.IsNotExist(err) {
			t.Fatalf("brain/%s was not cleaned up", cid)
		}
		if _, err := os.Stat(filepath.Join(agyDir, "conversations", cid+".pb")); !os.IsNotExist(err) {
			t.Fatalf("conversations/%s.pb was not cleaned up", cid)
		}
	}
}

func verifyParsedAgyWindowsAndCredits(t *testing.T, snap accounts.Snapshot) {
	t.Helper()
	if snap.Windows[0].Scope != "Gemini" || snap.Windows[0].Percent() != 25 || snap.Windows[0].ResetsAt.IsZero() {
		t.Fatalf("unexpected window[0]: %+v", snap.Windows[0])
	}
	if !snap.Windows[1].ResetsAt.IsZero() || snap.Windows[1].Percent() != 0 {
		t.Fatalf("expected 1970 reset_time cleared on window[1]: %+v", snap.Windows[1])
	}
	if snap.Windows[2].Scope != "Claude / GPT" || snap.Windows[2].Percent() != 60 || !snap.Windows[2].Active {
		t.Fatalf("expected window[2] active at 60%%: %+v", snap.Windows[2])
	}
	if snap.Extra == nil || !snap.Extra.Enabled || snap.Extra.Currency != "credits" || snap.Extra.LimitMin == nil || *snap.Extra.LimitMin != 12550 {
		t.Fatalf("unexpected Extra credits: %+v", snap.Extra)
	}
}

func verifyRelayBlocksUsageQuery(t *testing.T, home string, h *Host, store QuotaStore) {
	t.Helper()
	_ = h.SaveRelay(context.Background(), accounts.RelayInput{Name: "r1", Key: "k1", Create: true})
	_ = h.Switch(context.Background(), "r1")
	_, err := QueryUsageJSON(context.Background(), home)
	var coded interface{ Code() string }
	if !errors.As(err, &coded) || coded.Code() != sessionproto.CodeNoSubscription {
		t.Fatalf("expected CodeNoSubscription on relay, got %v", err)
	}
	if got, _ := store.Get(); got != nil {
		t.Fatalf("expected nil quota snapshot on relay, got %+v", got)
	}
}
