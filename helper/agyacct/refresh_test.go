package agyacct

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestTokenRefreshKeepsAccountIdentityAndPlan(t *testing.T) {
	home := t.TempDir()
	t.Setenv("ZETA_AGY_DIR", filepath.Join(home, ".gemini", "antigravity-cli"))
	h := &Host{Home: home}

	tokenPath := oauthTokenPath(home)
	if err := os.MkdirAll(filepath.Dir(tokenPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(tokenPath, []byte("opaque-token-v1"), 0o600); err != nil {
		t.Fatal(err)
	}
	name, err := h.Save(context.Background())
	if err != nil || name == "" {
		t.Fatalf("Save() = %q, %v", name, err)
	}
	SaveCurrentPlan(home, "Google AI Ultra")

	// 模拟 agy 自动刷新 OAuth 令牌，内容变化但仍属同一账号
	if err := os.WriteFile(tokenPath, []byte("opaque-token-v2-refreshed"), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := h.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if st.Current == nil || st.Current.Name != name {
		t.Fatalf("refreshed token lost identity: got %+v, want name %q", st.Current, name)
	}
	if st.Current.Plan != "Google AI Ultra" {
		t.Fatalf("refreshed token lost plan: got %q", st.Current.Plan)
	}
	if len(st.Accounts) != 1 {
		t.Fatalf("expected 1 account, got %d", len(st.Accounts))
	}
}

func TestRestorePendingLoginAndLogout(t *testing.T) {
	home := t.TempDir()
	t.Setenv("ZETA_AGY_DIR", filepath.Join(home, ".gemini", "antigravity-cli"))
	h := &Host{Home: home}

	tokenPath := oauthTokenPath(home)
	if err := os.MkdirAll(filepath.Dir(tokenPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(tokenPath, []byte("opaque-token-a"), 0o600); err != nil {
		t.Fatal(err)
	}
	name, err := h.Save(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.PrepareLogin(context.Background()); err != nil {
		t.Fatal(err)
	}
	// 登录取消时应恢复先前的账号
	h.restorePendingLogin()
	st, err := h.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if st.Current == nil || st.Current.Name != name {
		t.Fatalf("restorePendingLogin failed: got %+v, want %q", st.Current, name)
	}

	// Logout 后活跃账号应为空
	if err := h.Logout(context.Background()); err != nil {
		t.Fatal(err)
	}
	st, err = h.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if st.Current != nil {
		t.Fatalf("after Logout expected Current=nil, got %+v", st.Current)
	}
}
