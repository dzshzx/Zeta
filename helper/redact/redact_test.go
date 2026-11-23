package redact

import (
	"strings"
	"testing"
)

// 用例同手机版的 RedactTest。值都是一眼能看出是假的（fake-…），推送前的密钥扫描按值放行（仓根 .gitleaks-test-values.toml）。

const mask = Mask

func redacted(t *testing.T, text string) string {
	t.Helper()
	out := Text(text)
	if again := Text(out); again != out {
		t.Errorf("应当幂等：%q 再打一遍成了 %q", out, again)
	}
	return out
}

func check(t *testing.T, want, text string) {
	t.Helper()
	if got := redacted(t, text); got != want {
		t.Errorf("Text(%q)\n得 %q\n要 %q", text, got, want)
	}
}

func hidden(t *testing.T, text string, secrets ...string) {
	t.Helper()
	out := redacted(t, text)
	for _, s := range secrets {
		if strings.Contains(out, s) {
			t.Errorf("没打掉 %s：%s", s, out)
		}
	}
	if !strings.Contains(out, mask) {
		t.Errorf("应有打码标记：%s", out)
	}
}

func TestAuthorizationHeaderKeepsScheme(t *testing.T) {
	check(t, "Authorization: Bearer "+mask, "Authorization: Bearer fake-token-1")
	check(t, "authorization=Basic "+mask, "authorization=Basic fake-basic-1")
	check(t, "Proxy-Authorization: "+mask, "Proxy-Authorization: fake-token-2")
	check(t, `{"Authorization":"Bearer `+mask+`","Accept":"*/*"}`, `{"Authorization":"Bearer fake-token-3","Accept":"*/*"}`)
	// 拆开写，免得推送前的密钥扫描把测试语料当成真的 curl 凭据
	header := "Authorization: Bearer"
	check(t, "curl -H '"+header+" "+mask+"' https://example.invalid", "curl -H '"+header+" fake-token-4' https://example.invalid")
}

func TestCookiesHideTheWholeValue(t *testing.T) {
	check(t, "Cookie: "+mask, "Cookie: sid=fake-cookie-1; theme=dark")
	check(t, "set-cookie: "+mask+"\nnext line", "set-cookie: sid=fake-cookie-2; Path=/; HttpOnly\nnext line")
	check(t, `"cookie": "`+mask+`"`, `"cookie": "sid=fake-cookie-3"`)
	hidden(t, "session_cookie=fake-cookie-4", "fake-cookie-4")
	// 只是提到这个词的不动
	check(t, "the cookies jar is empty", "the cookies jar is empty")
}

func TestSecretKeyValuePairs(t *testing.T) {
	check(t, "ANTHROPIC_API_KEY="+mask, "ANTHROPIC_API_KEY=fake-key-1")
	check(t, "export GH_TOKEN="+mask, "export GH_TOKEN=fake-token-5")
	check(t, `"password": "`+mask+`"`, `"password": "fake-pass-1"`)
	check(t, "api_key: "+mask, "api_key: fake-key-2")
	check(t, `{"accessToken":"`+mask+`","expiresAt":1700000000000}`, `{"accessToken":"fake-token-6","expiresAt":1700000000000}`)
	check(t, "https://example.invalid/cb?code=1&access_token="+mask+"&state=x",
		"https://example.invalid/cb?code=1&access_token=fake-token-7&state=x")
	check(t, "client_secret = '"+mask+"'", "client_secret = 'fake-secret-1'")
	check(t, "X-Api-Key: "+mask, "X-Api-Key: fake-key-3")
	// 不像机密的键，值里嵌着的照样打
	check(t, "url: https://example.invalid/?token="+mask, "url: https://example.invalid/?token=fake-token-12")
	check(t, `"note": "set password=`+mask+` later"`, `"note": "set password=fake-pass-4 later"`)
}

func TestEscapedJSONInsideALogLine(t *testing.T) {
	check(t, `{"type":"log","text":"{\"refreshToken\":\"`+mask+`\",\"user\":\"bob\"}"}`,
		`{"type":"log","text":"{\"refreshToken\":\"fake-token-8\",\"user\":\"bob\"}"}`)
}

func TestCountsAndFlagsAreKept(t *testing.T) {
	for _, s := range []string{
		`{"input_tokens":1234,"output_tokens":56,"cache_read_input_tokens":0}`,
		`{"hasToken":true,"apiKey":null,"password":""}`,
		"PWD=/workspace/claude SHELL=/bin/bash",
		"sessionId: 0a1b2c3d author: someone",
		`{"token_type":"bearer","max_tokens":4096}`,
	} {
		check(t, s, s)
	}
}

func TestCommandLineFlags(t *testing.T) {
	check(t, "claude mcp add x --api-key "+mask+" --verbose", "claude mcp add x --api-key fake-key-4 --verbose")
	check(t, "tool --token="+mask+" -p", "tool --token=fake-token-9 -p")
	check(t, "tool --password '"+mask+"'", "tool --password 'fake-pass-2'")
	// 后面跟的是另一个参数，不是值
	check(t, "tool --verbose --model opus", "tool --verbose --model opus")
	check(t, "tool --verbose --token "+mask, "tool --verbose --token fake-token-10")
}

func TestURLPasswords(t *testing.T) {
	check(t, "git clone https://someone:"+mask+"@example.invalid/x.git", "git clone https://someone:fake-pass-3@example.invalid/x.git")
	// 端口不是密码
	check(t, "http://localhost:8080/a@b", "http://localhost:8080/a@b")
}

func TestRecognisableTokenShapesAnywhere(t *testing.T) {
	check(t, "key is sk-ant-"+mask+" here", "key is sk-ant-fake-token-0001 here")
	check(t, "sk-"+mask, "sk-fakefakefakefake0001")
	check(t, "sent Bearer "+mask+" ok", "sent Bearer fake.token.value-11 ok")
	check(t, "gh "+mask+" x", "gh ghp_fakefakefakefake01 x")
	check(t, "slack "+mask, "slack xoxb-fake-fake-01")
	check(t, "jwt "+mask+" end", "jwt eyJfake.eyJfake.fakesig end")
	// 普通的词不误伤
	check(t, "task-runner desk-lamp skip-it", "task-runner desk-lamp skip-it")
}

func TestPrivateKeyBlocks(t *testing.T) {
	// 拆开写，理由同上
	kind := "OPENSSH PRIVATE"
	begin := "-----BEGIN " + kind + " KEY-----"
	end := "-----END " + kind + " KEY-----"
	check(t, "before\n"+begin+"\n"+mask+"\n"+end+"\nafter",
		"before\n"+begin+"\nfake-key-material-1\nfake-key-material-2\n"+end+"\nafter")
	// 尾部截断、没有 END 的也打到末尾
	bare := "PRIVATE"
	hidden(t, "-----BEGIN "+bare+" KEY-----\nfake-key-material-3", "fake-key-material-3")
}

func TestSecretNames(t *testing.T) {
	for _, n := range []string{"token", "GITHUB_TOKEN", "oauthToken", "x-api-key", "apiKey", "client_secret", "PGPASSWORD",
		"aws_secret_access_key", "credentials", "basic_auth"} {
		if !SecretName(n) {
			t.Errorf("%s 应当算机密", n)
		}
	}
	for _, n := range []string{"tokens", "input_tokens", "author", "key", "monkey", "PWD", "sessionId", "token_type", "path"} {
		if SecretName(n) {
			t.Errorf("%s 不该算机密", n)
		}
	}
}

func TestStackTraceFromStderr(t *testing.T) {
	text := "# zeta: claude 退出码 1，2026-11-19 09:59:50 +0800\n" +
		"[ERROR] MCP server \"github\" connection failed: Authorization: Bearer fake-token-1\n" +
		"Error: spawn npx ENOENT\n    at ChildProcess._handle.onexit (node:internal/child_process:285:19)"
	out := redacted(t, text)
	if strings.Contains(out, "fake-token-1") || !strings.Contains(out, "(node:internal/child_process:285:19)") {
		t.Errorf("打得不对：%s", out)
	}
}

func TestPlainTextUntouched(t *testing.T) {
	text := "2026-11-19 10:00:00.123 信息 [Claude 进程] 起 Claude 进程（cwd /workspace/claude，--model opus）\nexit code 143"
	check(t, text, text)
}
