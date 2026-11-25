package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/claude-switch/account"
)

// serve 的中转方法（accounts.relay_save、relay_remove、relay_models，换过去用 accounts.switch）：参数和结果的 JSON、密钥不回传，
// 换到中转以后起对话进程带上那份设置（launchArgs），换回账号就不带。
func TestServeRelays(t *testing.T) {
	const key = "sk-test-serve-relay-0002"
	e := accountsHome(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-api-key") != key {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5"}]}`))
	}))
	defer srv.Close()

	// 旧客户端还带着已经不用的 auth：多的键不管
	mustAccounts(t, e, "accounts.relay_save", `{"name":"gw","baseUrl":"`+srv.URL+`","key":"`+key+`","create":true,"auth":"bearer"}`)
	models := mustAccounts(t, e, "accounts.relay_models", `{"name":"gw","baseUrl":"`+srv.URL+`"}`)
	if got := models["models"].([]any); len(got) != 1 || got[0].(map[string]any)["id"] != "claude-opus-5-5" ||
		got[0].(map[string]any)["displayName"] != "Claude Opus 5.5" {
		t.Fatalf("relay_models：%v", models)
	}
	mustAccounts(t, e, "accounts.relay_save", `{"name":"gw","baseUrl":"`+srv.URL+`","model":"claude-opus-5-5"}`)

	list := mustAccounts(t, e, "accounts.list", ``)
	relays := list["relays"].([]any)
	want := map[string]any{"name": "gw", "baseUrl": srv.URL, "model": "claude-opus-5-5", "hasKey": true,
		"keyEditable": true, "current": false}
	if len(relays) != 1 || !mapsEqual(relays[0].(map[string]any), want) {
		t.Fatalf("relays：%v", relays)
	}
	if got := launchArgs(e.home, []string{"-p"}); !slices.Equal(got, []string{"-p"}) {
		t.Fatalf("用账号时的参数 %q", got)
	}

	mustAccounts(t, e, "accounts.switch", `{"name":"gw"}`)
	list = mustAccounts(t, e, "accounts.list", ``)
	if list["current"] != nil || list["relays"].([]any)[0].(map[string]any)["current"] != true {
		t.Fatalf("换到中转后：%v", list)
	}
	settings := filepath.Join(e.home, ".config/claude-switch/targets/gw.json")
	if got := launchArgs(e.home, []string{"-p"}); !slices.Equal(got, []string{"--settings", settings, "-p"}) {
		t.Fatalf("用中转时的参数 %q", got)
	}
	if _, err := callAccounts(t, e, "accounts.relay_remove", `{"name":"gw"}`); err == nil {
		t.Fatal("正在用的中转不该能删")
	}
	mustAccounts(t, e, "accounts.switch", `{"name":"claude"}`)
	if got := launchArgs(e.home, []string{"-p"}); !slices.Equal(got, []string{"-p"}) {
		t.Fatalf("换回账号后的参数 %q", got)
	}
	mustAccounts(t, e, "accounts.relay_remove", `{"name":"gw"}`)
	if relays := mustAccounts(t, e, "accounts.list", ``)["relays"].([]any); len(relays) != 0 {
		t.Fatalf("删掉以后：%v", relays)
	}
	_, err := callAccounts(t, e, "accounts.relay_save", `{"name":"x","baseUrl":"`+srv.URL+`","key":"bad key `+key+`","create":true}`)
	if err == nil || strings.Contains(err.Error(), key) {
		t.Fatalf("密钥不合规时的出错：%v", err)
	}
}

const emptyModelCatalog = `{"relay":"","model":"","models":[]}`

func modelCatalog(t *testing.T) string {
	t.Helper()
	return mustConfig(t, `{"method":"config.models.read"}`)
}

func relayConfigHome(t *testing.T) (home, target, user string) {
	t.Helper()
	home = t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("ANTHROPIC_BASE_URL", "")
	t.Setenv("BASH_ENV", "")
	return home, filepath.Join(home, account.TargetsDir, "gw.json"), filepath.Join(home, ".claude", "settings.json")
}

func selectRelay(t *testing.T, home string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(home, account.StateDir), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := account.WriteCurrent(home, "gw"); err != nil {
		t.Fatal(err)
	}
}

func TestServeModelCatalog(t *testing.T) {
	home, target, user := relayConfigHome(t)
	const key = "fixture-catalog-secret"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" || r.Header.Get("x-api-key") != key || r.Header.Get("Authorization") != "Bearer "+key {
			t.Error("模型列表请求未使用已保存的目标配置")
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"custom","display_name":"Custom"},{"id":"id-only"},{"id":"` + key + `"}]}`))
	}))
	t.Cleanup(srv.Close)
	writeFile(t, user, `{"model":"inherited"}`)
	writeFile(t, filepath.Join(home, "relay.key"), key)
	writeFile(t, target, `{"env":{"ANTHROPIC_BASE_URL":`+quoteJSON(srv.URL)+`},"apiKeyHelper":"cat ~/relay.key"}`)
	if got := modelCatalog(t); got != emptyModelCatalog {
		t.Fatalf("未选择目标时：%s", got)
	}
	selectRelay(t, home)
	if got, want := modelCatalog(t), `{"relay":"gw","model":"inherited","models":[{"id":"custom","displayName":"Custom"},{"id":"id-only","displayName":""}]}`; got != want {
		t.Fatalf("生效中转目录：%s", got)
	}
	writeFile(t, target, `{"env":{"ANTHROPIC_BASE_URL":`+quoteJSON(srv.URL)+`},"apiKeyHelper":"touch ~/helper-ran; exit 1","model":"relay-only"}`)
	if got := modelCatalog(t); got != `{"relay":"gw","model":"relay-only","models":[]}` {
		t.Fatalf("不能读取密钥时：%s", got)
	}
	if _, err := os.Stat(filepath.Join(home, "helper-ran")); !os.IsNotExist(err) {
		t.Fatal("不应执行自定义 apiKeyHelper")
	}
}

func TestServeModelCatalogAuth(t *testing.T) {
	const key = "fixture-catalog-auth"
	for _, tc := range []struct{ name, apiKey, token string }{
		{"ANTHROPIC_API_KEY", key, ""},
		{"ANTHROPIC_AUTH_TOKEN", "", "Bearer " + key},
	} {
		t.Run(tc.name, func(t *testing.T) {
			home, target, _ := relayConfigHome(t)
			requested := make(chan struct{}, 1)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requested <- struct{}{}
				if r.Header.Get("x-api-key") != tc.apiKey || r.Header.Get("Authorization") != tc.token {
					t.Error("请求使用了错误的鉴权头")
				}
				http.Error(w, key, http.StatusUnauthorized)
			}))
			t.Cleanup(srv.Close)
			writeFile(t, target, `{"env":{"ANTHROPIC_BASE_URL":`+quoteJSON(srv.URL)+`,"`+tc.name+`":"`+key+`"},"model":"relay-only"}`)
			selectRelay(t, home)
			if got := modelCatalog(t); got != `{"relay":"gw","model":"relay-only","models":[]}` {
				t.Fatalf("拉取失败时：%s", got)
			}
			select {
			case <-requested:
			default:
				t.Fatal("未请求上游模型目录")
			}
		})
	}
}

func TestModelCatalogDoesNotBlockModelWrite(t *testing.T) {
	home, target, _ := relayConfigHome(t)
	started, reply := make(chan struct{}), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		select {
		case <-reply:
			_, _ = w.Write([]byte(`{"data":[{"id":"older"}]}`))
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	writeFile(t, target, `{"env":{"ANTHROPIC_BASE_URL":`+quoteJSON(srv.URL)+`,"ANTHROPIC_API_KEY":"fixture-key"},"model":"older"}`)
	selectRelay(t, home)
	catalog := make(chan accounts.ModelCatalog, 1)
	go func() {
		out, _ := accounts.ReadModelCatalog(ctx, home, "")
		catalog <- out
	}()
	select {
	case <-started:
	case <-ctx.Done():
		t.Fatal("模型列表请求未开始")
	}
	changed, err := accounts.SetRelayDefaultModel(ctx, home, "newer")
	close(reply)
	if err != nil || !changed || ctx.Err() != nil {
		t.Fatalf("模型列表请求未完成时应能改默认模型：%t %v", changed, err)
	}
	if got := <-catalog; got.Model != "older" || len(got.Models) != 1 || got.Models[0].ID != "older" {
		t.Fatalf("目录应返回发出请求时的模型：%+v", got)
	}
	if got := mustConfig(t, `{"method":"config.defaults.read"}`); !strings.Contains(got, `"model":"newer"`) {
		t.Fatalf("默认模型未更新：%s", got)
	}
}

func TestServeRelayDefaultModel(t *testing.T) {
	home, target, user := relayConfigHome(t)
	const userConfig = `{"model":"inherited","effortLevel":"high"}`
	const targetFields = `{"env":{"ANTHROPIC_BASE_URL":"https://relay.example","OTHER":"keep"},"custom":9007199254740993`
	writeFile(t, user, userConfig)
	writeFile(t, target, targetFields+`,"model":"custom"}`)
	selectRelay(t, home)
	for _, req := range []string{
		`{"method":"config.defaults.read"}`,
		`{"method":"config.defaults.setPermissionMode","value":"plan"}`,
		`{"method":"config.defaults.setEffort","model":"custom","value":"high"}`,
		`{"method":"config.defaults.setCompactWindow","value":200000}`,
	} {
		if got := mustConfig(t, req); !strings.Contains(got, `"model":"custom"`) {
			t.Errorf("默认值没有反映生效中转：%s", got)
		}
	}
	writeFile(t, user, userConfig)
	if got := mustConfig(t, `{"method":"config.defaults.setModel","value":"new-model"}`); !strings.Contains(got, `"model":"new-model"`) {
		t.Fatalf("设置中转模型：%s", got)
	}
	if got := mustConfig(t, `{"method":"config.defaults.setModel","value":""}`); !strings.Contains(got, `"model":"inherited"`) {
		t.Fatalf("清除后继承用户模型：%s", got)
	}
	assertConfigFile(t, user, userConfig)
	assertConfigFile(t, target, `{"custom":9007199254740993,"env":{"ANTHROPIC_BASE_URL":"https://relay.example","OTHER":"keep"}}`)
}

func TestServeRelayModelOverride(t *testing.T) {
	for _, source := range []string{"settings.json", "environment", "login shell"} {
		t.Run(source, func(t *testing.T) {
			home, target, user := relayConfigHome(t)
			const relayDoc = `{"env":{"ANTHROPIC_BASE_URL":"https://relay.example"},"model":"relay-only"}`
			writeFile(t, target, relayDoc)
			settings := `{"model":"ordinary"}`
			switch source {
			case "settings.json":
				settings = `{"model":"ordinary","env":{"ANTHROPIC_BASE_URL":"https://override.example"}}`
			case "environment":
				t.Setenv("ANTHROPIC_BASE_URL", "https://override.example")
			case "login shell":
				script := filepath.Join(home, "bash-env")
				writeFile(t, script, "export ANTHROPIC_BASE_URL=https://override.example\n")
				t.Setenv("BASH_ENV", script)
			}
			writeFile(t, user, settings)
			selectRelay(t, home)
			if got := modelCatalog(t); got != emptyModelCatalog {
				t.Fatalf("自配地址时：%s", got)
			}
			if got := mustConfig(t, `{"method":"config.defaults.read"}`); !strings.Contains(got, `"model":"ordinary"`) {
				t.Fatalf("自配地址时的默认模型：%s", got)
			}
			if got := mustConfig(t, `{"method":"config.defaults.setModel","value":"new-ordinary"}`); !strings.Contains(got, `"model":"new-ordinary"`) {
				t.Fatalf("自配地址时仍应更新用户默认：%s", got)
			}
			assertConfigFile(t, target, relayDoc)
		})
	}
}

func assertConfigFile(t *testing.T, path, want string) {
	t.Helper()
	if got, err := os.ReadFile(path); err != nil || string(got) != want {
		t.Fatalf("配置文件内容不符：%s，%v", path, err)
	}
}

func quoteJSON(s string) string {
	v, _ := json.Marshal(s)
	return string(v)
}

func mapsEqual(a, b map[string]any) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range b {
		if a[k] != v {
			return false
		}
	}
	return true
}

// tmux 窗口里接回会话（desk 的 runResume）：走中转时带 --settings，交互 shell 的环境里自己配了 ANTHROPIC_BASE_URL 时不带。
// 这里用 bash -c 代替 -ic（不读测试机的 ~/.bashrc），claude 换成报参数的替身。
func TestResumeCommandRelaySettings(t *testing.T) {
	bin := t.TempDir()
	if err := os.WriteFile(filepath.Join(bin, "claude"), []byte("#!/bin/sh\necho \"$*\"\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ settings, baseURL, want string }{
		{"", "", "--resume s1"},
		{"/h/relay.json", "", "--resume s1 --settings /h/relay.json"},
		{"/h/relay.json", "https://own.example.com", "--resume s1"},
	} {
		args := resumeArgs("s1", c.settings)
		if args[0] != "-ic" {
			t.Fatalf("交给 shell 的参数 %q", args)
		}
		cmd := exec.Command("bash", append([]string{"-c"}, args[1:]...)...)
		// BASH_ENV 置空：跑测试的人的 shell 配置（比如给 claude 套一层、按当前中转加 --settings 的函数）不能混进来
		cmd.Env = append(os.Environ(), "BASH_ENV=", "PATH="+bin+":"+os.Getenv("PATH"), "ANTHROPIC_BASE_URL="+c.baseURL)
		out, err := cmd.Output()
		if err != nil || strings.TrimSpace(string(out)) != c.want {
			t.Errorf("%+v：%q %v", c, out, err)
		}
	}
}
