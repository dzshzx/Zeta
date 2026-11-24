package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
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
