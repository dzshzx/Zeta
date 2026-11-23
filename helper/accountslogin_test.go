package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeLoginClaude 在 e 的家目录 ~/.local/bin 放一个假的 claude，auth login 照实测的样子说话（见 accountslogin.go 开头）：
// 打印网址和不换行的提示，逐行读授权码：没有 # 时在错误输出抱怨、接着读；「good#code」登录成 uuid-b 并以 0 退出；
// 别的码说没被接受、以 1 退出。PATH 收窄到系统目录，免得找到本机真的 claude。
func fakeLoginClaude(t *testing.T, e *env) {
	t.Helper()
	t.Setenv("PATH", "/usr/bin:/bin")
	bin := filepath.Join(e.home, ".local/bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/bash
[ "$1 $2" = "auth login" ] || exit 2
echo "Opening browser to sign in…"
echo "If the browser didn't open, visit:"
echo "https://claude.ai/oauth/authorize?code=true&state=fake"
printf "Paste code here if prompted > "
while read -r code; do
  case $code in
  *#*) ;;
  *) echo "That doesn't look like the full code. Copy all of it, including the # in the middle." >&2; continue ;;
  esac
  if [ "$code" = "good#code" ]; then
    echo '{"oauthAccount":{"accountUuid":"uuid-b","emailAddress":"b@example.com"}}' > "$HOME/.claude.json"
    echo '{"claudeAiOauth":{"accessToken":"fake-uuid-b","refreshToken":"fake-r-uuid-b","subscriptionType":"max"}}' > "$HOME/.claude/.credentials.json"
    echo "Login successful."
    exit 0
  fi
  echo "Couldn't log in: That code wasn't accepted. It may be incomplete, expired, already used, or from an earlier sign-in attempt. Sign in again to get a new code." >&2
  exit 1
done
`
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
}

func loginCode(t *testing.T, err error) string {
	t.Helper()
	var coded interface{ Code() string }
	if !errors.As(err, &coded) {
		return ""
	}
	return coded.Code()
}

func TestAccountsLogin(t *testing.T) {
	e := accountsHome(t)
	fakeLoginClaude(t, e)
	t.Cleanup(logins.cancel)

	start := mustAccounts(t, e, "accounts.login_start", ``)
	if start["url"] != "https://claude.ai/oauth/authorize?code=true&state=fake" || start["name"] != "claude-b" {
		t.Fatalf("开始登录：%v", start)
	}
	if !logins.active() || (accountsHandler{}).Idle() {
		t.Fatal("等授权码时核心不算闲着")
	}
	// 在用的号已经存档并退出登录
	if list := mustAccounts(t, e, "accounts.list", ``); list["current"] != nil {
		t.Fatalf("开始登录后还登录着：%v", list)
	}

	// 没有 #：核心直接退回，不交给登录命令
	_, err := callAccounts(t, e, "accounts.login_code", `{"code":"abc"}`)
	if loginCode(t, err) != codeLoginIncomplete {
		t.Fatalf("没有 # 的码：%v", err)
	}
	// 交成功
	got := mustAccounts(t, e, "accounts.login_code", `{"code":" good#code \n"}`)
	if got["name"] != "claude-b" {
		t.Fatalf("登录成功：%v", got)
	}
	if logins.active() {
		t.Fatal("登录完了还挂着")
	}
	list := mustAccounts(t, e, "accounts.list", ``)
	if currentName(list) != "claude-b" || len(list["accounts"].([]any)) != 2 {
		t.Fatalf("登录后的账号：%v", list)
	}
	if _, err := callAccounts(t, e, "accounts.login_code", `{"code":"good#code"}`); loginCode(t, err) != codeNoLogin {
		t.Fatalf("登录结束后再交码：%v", err)
	}
}

func TestAccountsLoginRejected(t *testing.T) {
	e := accountsHome(t)
	fakeLoginClaude(t, e)
	t.Cleanup(logins.cancel)

	mustAccounts(t, e, "accounts.login_start", ``)
	_, err := callAccounts(t, e, "accounts.login_code", `{"code":"bad#code"}`)
	if err == nil || loginCode(t, err) != "" || !strings.Contains(err.Error(), "没有被接受") {
		t.Fatalf("码不对：%v", err)
	}
	if logins.active() {
		t.Fatal("登录命令退出后还挂着")
	}

	// 取消：登录命令结束，再交码时说登录已经结束
	mustAccounts(t, e, "accounts.login_start", ``)
	mustAccounts(t, e, "accounts.login_cancel", ``)
	if logins.active() {
		t.Fatal("取消后还挂着")
	}
	if _, err := callAccounts(t, e, "accounts.login_code", `{"code":"good#code"}`); loginCode(t, err) != codeNoLogin {
		t.Fatalf("取消后交码：%v", err)
	}
}
