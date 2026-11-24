package accounts

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/helper/locales"
)

// testKey 是测试用的假密钥：它不能出现在列表、出错和设置文件里。
const testKey = "sk-test-relay-key-0001"

func (h *testHost) read(rel string) string {
	h.t.Helper()
	data, err := os.ReadFile(filepath.Join(h.home, rel))
	if err != nil {
		h.t.Fatal(err)
	}
	return string(data)
}

func (h *testHost) mode(rel string) os.FileMode {
	h.t.Helper()
	info, err := os.Stat(filepath.Join(h.home, rel))
	if err != nil {
		h.t.Fatal(err)
	}
	return info.Mode().Perm()
}

func (h *testHost) exists(rel string) bool {
	_, err := os.Stat(filepath.Join(h.home, rel))
	return err == nil
}

// listJSON 是 accounts.list 的结果整份写成 JSON：密钥不能在里面。
func listJSON(t *testing.T, host *Host) (State, string) {
	t.Helper()
	st, err := host.List(ctx(t))
	if err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(st)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), testKey) {
		t.Fatalf("列表里有密钥：%s", data)
	}
	return st, string(data)
}

func relayNamed(st State, name string) (Relay, bool) {
	for _, r := range st.Relays {
		if r.Name == name {
			return r, true
		}
	}
	return Relay{}, false
}

// 添加的中转写成固定格式、密钥文件 0600；列出来只有有没有密钥；换过去以后起 claude 要带的设置文件指着它，换回账号就没有。
func TestRelayAddListSwitch(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: " https://relay.example.com/v1 ", Key: " " + testKey + "\n",
		Create: true}); err != nil {
		t.Fatal(err)
	}
	if got, want := h.read(".config/claude-switch/targets/gw.json"),
		`{"apiKeyHelper":"cat ~/.local/state/claude-switch/gw-key","env":{"ANTHROPIC_BASE_URL":"https://relay.example.com/v1"}}`; got != want {
		t.Fatalf("设置文件\n%s\n应为\n%s", got, want)
	}
	if got := h.read(".local/state/claude-switch/gw-key"); got != testKey+"\n" {
		t.Fatal("密钥文件的内容不对")
	}
	if m := h.mode(".local/state/claude-switch/gw-key"); m != 0o600 {
		t.Fatalf("密钥文件的权限 %o", m)
	}

	st, _ := listJSON(t, host)
	r, ok := relayNamed(st, "gw")
	if !ok || r.BaseURL != "https://relay.example.com/v1" || !r.HasKey || !r.KeyEditable || r.Current {
		t.Fatalf("列出的中转 %+v", st.Relays)
	}
	if Settings(h.home) != "" {
		t.Fatal("还在用账号时不该带设置文件")
	}

	if err := host.Switch(ctx(t), "gw"); err != nil {
		t.Fatal(err)
	}
	st, _ = listJSON(t, host)
	if r, _ := relayNamed(st, "gw"); st.Current != nil || !r.Current {
		t.Fatalf("换到中转后：current %+v，中转 %+v", st.Current, st.Relays)
	}
	if got := Settings(h.home); got != filepath.Join(h.home, ".config/claude-switch/targets/gw.json") {
		t.Fatalf("起 claude 要带的设置 %q", got)
	}
	if err := host.RemoveRelay(ctx(t), "gw"); !errors.Is(err, ErrRelayInUse) {
		t.Fatalf("删正在用的中转：%v", err)
	}

	if err := host.Switch(ctx(t), "claude"); err != nil {
		t.Fatal(err)
	}
	st, _ = listJSON(t, host)
	if st.Current == nil || st.Current.Name != "claude" || h.liveUUID() != "uuid-a" {
		t.Fatalf("换回账号后 %+v", st.Current)
	}
	if r, _ := relayNamed(st, "gw"); r.Current || Settings(h.home) != "" {
		t.Fatal("换回账号后中转还算在用")
	}
}

// 在中转上登录新号（同添加账号的流程：中转上没有在用的号，不用存档），记下以后切换记录换成新号，起 claude 不再带中转的设置。
func TestLoginFromRelay(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://relay.example.com", Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	if err := host.Switch(ctx(t), "gw"); err != nil {
		t.Fatal(err)
	}
	if name, err := host.PrepareLogin(ctx(t)); err != nil || name != "" {
		t.Fatalf("中转上准备登录：%q %v", name, err)
	}
	h.login("uuid-b", "b@example.com", "pro")
	name, err := host.Save(ctx(t))
	if err != nil || name == "" || name == "gw" {
		t.Fatalf("记下新号：%q %v", name, err)
	}
	st, _ := listJSON(t, host)
	if st.Current == nil || st.Current.Name != name || Settings(h.home) != "" {
		t.Fatalf("记下以后 current %+v，设置 %q", st.Current, Settings(h.home))
	}
	if r, _ := relayNamed(st, "gw"); r.Current {
		t.Fatal("记下新号以后中转还算在用")
	}
}

// 名字、地址、密钥的核对，重名，改不在的中转；出错里都没有密钥。
func TestRelayValidation(t *testing.T) {
	h := newHost(t)
	host := h.host()
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "http://127.0.0.1:8080", Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		in   RelayInput
		want string
	}{
		{RelayInput{Name: "Bad", BaseURL: "https://x", Key: testKey, Create: true}, "errRelayName"},
		{RelayInput{Name: "-x", BaseURL: "https://x", Key: testKey, Create: true}, "errRelayName"},
		{RelayInput{Name: strings.Repeat("a", 33), BaseURL: "https://x", Key: testKey, Create: true}, "errRelayName"},
		{RelayInput{Name: "claude", BaseURL: "https://x", Key: testKey, Create: true}, "errRelayName"},
		{RelayInput{Name: "x", BaseURL: "ftp://x", Key: testKey, Create: true}, "errRelayURL"},
		{RelayInput{Name: "x", BaseURL: "https://", Key: testKey, Create: true}, "errRelayURL"},
		{RelayInput{Name: "x", BaseURL: "relay.example.com", Key: testKey, Create: true}, "errRelayURL"},
		{RelayInput{Name: "x", BaseURL: "https://u:p@relay.example.com", Key: testKey, Create: true}, "errRelayURL"},
		{RelayInput{Name: "x", BaseURL: "https://x", Key: testKey, Model: "a b", Create: true}, "errRelayModel"},
		{RelayInput{Name: "x", BaseURL: "https://x", Key: testKey + " " + testKey, Create: true}, "errRelayKey"},
		{RelayInput{Name: "x", BaseURL: "https://x", Create: true}, "errRelayKeyRequired"},
		{RelayInput{Name: "gw", BaseURL: "https://x", Key: testKey, Create: true}, "errRelayNameTaken"},
		{RelayInput{Name: "claude-b", BaseURL: "https://x", Key: testKey}, "errRelayMissing"},
	}
	// 已记过的账号名也不能拿来当中转名
	twoAccounts(t, h, host)
	cases = append(cases, struct {
		in   RelayInput
		want string
	}{RelayInput{Name: "claude-b", BaseURL: "https://x", Key: testKey, Create: true}, "errRelayNameTaken"})
	for _, c := range cases {
		err := host.SaveRelay(ctx(t), c.in)
		var said *locales.Error
		if !errors.As(err, &said) || said.Key != c.want {
			t.Errorf("%+v：%v，应为 %s", c.in.Name, err, c.want)
			continue
		}
		if strings.Contains(err.Error(), testKey) || strings.Contains(said.In("en"), testKey) {
			t.Errorf("出错里有密钥：%v", err)
		}
	}
	if err := host.RemoveRelay(ctx(t), "nope"); !errors.Is(err, ErrRelayMissing) {
		t.Fatalf("删不在的中转：%v", err)
	}
	listJSON(t, host)
}

// 修改：密钥留空不动密钥文件，填了就换掉；只改地址。
func TestRelayEdit(t *testing.T) {
	h := newHost(t)
	host := h.host()
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://a.example.com", Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://b.example.com"}); err != nil {
		t.Fatal(err)
	}
	if h.read(".local/state/claude-switch/gw-key") != testKey+"\n" {
		t.Fatal("密钥留空时不该改密钥文件")
	}
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://b.example.com", Key: testKey + "2"}); err != nil {
		t.Fatal(err)
	}
	if h.read(".local/state/claude-switch/gw-key") != testKey+"2\n" || h.mode(".local/state/claude-switch/gw-key") != 0o600 {
		t.Fatal("换密钥没写对")
	}
	st, _ := host.List(ctx(t))
	if r, _ := relayNamed(st, "gw"); r.BaseURL != "https://b.example.com" {
		t.Fatalf("地址没改：%+v", r)
	}
}

// 用户自己放的目标：cat 家目录里的文件的照那个文件改密钥、别的设置原样留着；别的写法只读；密钥写在 env 里的也只读。
func TestExternalRelays(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.write(".config/claude-switch/targets/cpa.json", map[string]any{
		"env":          map[string]any{"ANTHROPIC_BASE_URL": "https://cpa.example.com", "OTHER": "1"},
		"apiKeyHelper": "cat ~/.local/state/claude-switch/cpa-key", "model": "opus",
	})
	h.write(".config/claude-switch/targets/vault.json", map[string]any{
		"env": map[string]any{"ANTHROPIC_BASE_URL": "https://vault.example.com"}, "apiKeyHelper": "pass show relay",
	})
	h.write(".config/claude-switch/targets/envkey.json", map[string]any{
		"env": map[string]any{"ANTHROPIC_BASE_URL": "https://env.example.com", "ANTHROPIC_API_KEY": "x"},
	})
	h.write(".config/claude-switch/targets/outside.json", map[string]any{"apiKeyHelper": "cat /etc/relay-key"})

	st, _ := listJSON(t, host)
	want := map[string]Relay{
		"cpa":     {Name: "cpa", Model: "opus", BaseURL: "https://cpa.example.com", HasKey: false, KeyEditable: true},
		"vault":   {Name: "vault", BaseURL: "https://vault.example.com", HasKey: true},
		"envkey":  {Name: "envkey", BaseURL: "https://env.example.com", HasKey: true},
		"outside": {Name: "outside", HasKey: true},
	}
	for name, w := range want {
		if r, _ := relayNamed(st, name); r != w {
			t.Errorf("%s 列成 %+v，应为 %+v", name, r, w)
		}
	}

	if err := host.SaveRelay(ctx(t), RelayInput{Name: "cpa", BaseURL: "https://cpa.example.com/cpa2", Key: testKey, Model: "opus"}); err != nil {
		t.Fatal(err)
	}
	var cpa map[string]any
	if err := json.Unmarshal([]byte(h.read(".config/claude-switch/targets/cpa.json")), &cpa); err != nil {
		t.Fatal(err)
	}
	env := cpa["env"].(map[string]any)
	if cpa["model"] != "opus" || env["OTHER"] != "1" || env["ANTHROPIC_BASE_URL"] != "https://cpa.example.com/cpa2" ||
		cpa["apiKeyHelper"] != "cat ~/.local/state/claude-switch/cpa-key" {
		t.Fatalf("改过的设置 %v", cpa)
	}
	if h.read(".local/state/claude-switch/cpa-key") != testKey+"\n" || h.mode(".local/state/claude-switch/cpa-key") != 0o600 {
		t.Fatal("cpa 的密钥没写进它自己的文件")
	}
	for _, name := range []string{"vault", "envkey", "outside"} {
		err := host.SaveRelay(ctx(t), RelayInput{Name: name, BaseURL: "https://x.example.com", Key: testKey})
		var said *locales.Error
		if !errors.As(err, &said) || said.Key != "errRelayKeyReadOnly" {
			t.Errorf("改 %s 的密钥：%v", name, err)
		}
	}
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "vault", BaseURL: "https://vault2.example.com"}); err != nil {
		t.Fatalf("只改地址：%v", err)
	}
	if h.exists("etc/relay-key") || h.exists(".local/state/claude-switch/vault-key") {
		t.Fatal("只读的中转不该多出密钥文件")
	}
}

// 删除：设置文件和 Zeta 给它写的密钥文件一起删；别的中转也读同一个密钥文件时留着它。
func TestRemoveRelay(t *testing.T) {
	h := newHost(t)
	host := h.host()
	for _, name := range []string{"gw", "other"} {
		if err := host.SaveRelay(ctx(t), RelayInput{Name: name, BaseURL: "https://relay.example.com", Key: testKey, Create: true}); err != nil {
			t.Fatal(err)
		}
	}
	h.write(".config/claude-switch/targets/twin.json", map[string]any{"apiKeyHelper": "cat ~/.local/state/claude-switch/other-key"})
	if err := host.RemoveRelay(ctx(t), "gw"); err != nil {
		t.Fatal(err)
	}
	if h.exists(".config/claude-switch/targets/gw.json") || h.exists(".local/state/claude-switch/gw-key") {
		t.Fatal("删掉的中转还留着文件")
	}
	if err := host.RemoveRelay(ctx(t), "other"); err != nil {
		t.Fatal(err)
	}
	if h.exists(".config/claude-switch/targets/other.json") || !h.exists(".local/state/claude-switch/other-key") {
		t.Fatal("别的中转还在用的密钥文件不该删")
	}
}

// 默认模型写进设置的 model（Claude Code 认它，2.1.330 实测：--settings 里的 model 就是请求用的模型），清空时删掉。设置里只有
// Claude Code 认的字段；旁边别的文件（比如早先草稿写的 <名字>.zeta，带 auth）不算中转、不影响读写，原样不动。
func TestRelayModel(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.writeBytes(".config/claude-switch/targets/gw.zeta", []byte(`{"auth":"bearer"}`), 0o600)
	in := RelayInput{Name: "gw", BaseURL: "https://relay.example.com", Key: testKey, Model: "claude-opus-5-5", Create: true}
	if err := host.SaveRelay(ctx(t), in); err != nil {
		t.Fatal(err)
	}
	var s map[string]any
	if err := json.Unmarshal([]byte(h.read(".config/claude-switch/targets/gw.json")), &s); err != nil {
		t.Fatal(err)
	}
	if s["model"] != "claude-opus-5-5" || len(s) != 3 {
		t.Fatalf("设置 %v", s)
	}
	st, data := listJSON(t, host)
	if r, _ := relayNamed(st, "gw"); r.Model != "claude-opus-5-5" || len(st.Relays) != 1 {
		t.Fatalf("列出 %+v", st.Relays)
	}
	if strings.Contains(data, `"auth"`) {
		t.Fatalf("列表里不该再有鉴权方式：%s", data)
	}
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://relay.example.com"}); err != nil {
		t.Fatal(err)
	}
	if st, _ = listJSON(t, host); st.Relays[0].Model != "" {
		t.Fatalf("清掉模型后 %+v", st.Relays[0])
	}
	if err := host.RemoveRelay(ctx(t), "gw"); err != nil {
		t.Fatal(err)
	}
	if h.exists(".config/claude-switch/targets/gw.json") {
		t.Fatal("没删设置文件")
	}
}

// settings.json 里自己配了 env.ANTHROPIC_BASE_URL（不全是空白的字符串）时起 claude 不带中转的设置，同终端里的规矩。
func TestSettingsYieldsToOwnBaseURL(t *testing.T) {
	h := newHost(t)
	host := h.host()
	h.login("uuid-a", "a@example.com", "max")
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: "https://relay.example.com", Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	if err := host.Switch(ctx(t), "gw"); err != nil {
		t.Fatal(err)
	}
	relay := filepath.Join(h.home, ".config/claude-switch/targets/gw.json")
	for _, c := range []struct {
		settings any
		want     string
	}{
		{map[string]any{"model": "opus"}, relay},
		{map[string]any{"env": map[string]any{"ANTHROPIC_BASE_URL": "  "}}, relay},
		{map[string]any{"env": map[string]any{"ANTHROPIC_BASE_URL": 1}}, relay},
		{map[string]any{"env": map[string]any{"ANTHROPIC_BASE_URL": "https://own.example.com"}}, ""},
	} {
		h.write(".claude/settings.json", c.settings)
		if got := Settings(h.home); got != c.want {
			t.Errorf("settings.json %v 时 %q，应为 %q", c.settings, got, c.want)
		}
	}
	if CurrentRelay(h.home) != "gw" {
		t.Fatal("自己配了地址也还算在用中转（没有订阅额度）")
	}
}
