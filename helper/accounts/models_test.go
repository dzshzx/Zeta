package accounts

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// modelsServer 是假的中转：按 handler 答 /v1/models，记下每次请求带的鉴权头对不对。
type modelsServer struct {
	*httptest.Server
	apiKey, bearer, version []bool
}

func newModelsServer(t *testing.T, handler func(w http.ResponseWriter, r *http.Request)) *modelsServer {
	s := &modelsServer{}
	s.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.apiKey = append(s.apiKey, r.Header.Get("x-api-key") == testKey)
		s.bearer = append(s.bearer, r.Header.Get("Authorization") == "Bearer "+testKey)
		s.version = append(s.version, r.Header.Get("anthropic-version") != "")
		handler(w, r)
	}))
	t.Cleanup(s.Close)
	return s
}

func modelsOK(body string) func(http.ResponseWriter, *http.Request) {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}
}

func saidKey(err error) string {
	var said *locales.Error
	if errors.As(err, &said) {
		return said.Key
	}
	return ""
}

// 两种返回都认；x-api-key 和 Authorization: Bearer 两个头都带（同 Claude Code），只认一种的中转也拉得到；地址末尾的斜杠不影响。
func TestRelayModelsParse(t *testing.T) {
	h := newHost(t)
	host := h.host()
	anthropic := newModelsServer(t, modelsOK(`{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
		{"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5"},{"id":"claude-opus-5-5"}],"has_more":false}`))
	got, err := host.RelayModels(ctx(t), ModelsQuery{BaseURL: anthropic.URL + "/", Key: testKey})
	if err != nil {
		t.Fatal(err)
	}
	want := []RelayModel{{ID: "claude-opus-5-5", DisplayName: "Claude Opus 5.5"}, {ID: "claude-haiku-5-5", DisplayName: "Claude Haiku 5.5"}}
	if len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("Anthropic 的列表读成 %+v", got)
	}
	if !anthropic.apiKey[0] || !anthropic.bearer[0] || !anthropic.version[0] {
		t.Fatal("请求头不对：要 x-api-key、Bearer 和 anthropic-version")
	}

	openai := newModelsServer(t, modelsOK(`{"object":"list","data":[{"id":"gpt-5","object":"model","owned_by":"x"}]}`))
	got, err = host.RelayModels(ctx(t), ModelsQuery{BaseURL: openai.URL, Key: testKey})
	if err != nil || len(got) != 1 || got[0].ID != "gpt-5" || got[0].DisplayName != "" {
		t.Fatalf("OpenAI 的列表读成 %+v %v", got, err)
	}
	if !openai.apiKey[0] || !openai.bearer[0] {
		t.Fatal("请求头不对：两个鉴权头都要带")
	}
}

// 不填密钥时用已保存的中转的密钥文件；读不到时说先填密钥。
func TestRelayModelsStoredKey(t *testing.T) {
	h := newHost(t)
	host := h.host()
	srv := newModelsServer(t, modelsOK(`{"data":[{"id":"m"}]}`))
	if err := host.SaveRelay(ctx(t), RelayInput{Name: "gw", BaseURL: srv.URL, Key: testKey, Create: true}); err != nil {
		t.Fatal(err)
	}
	if got, err := host.RelayModels(ctx(t), ModelsQuery{Name: "gw", BaseURL: srv.URL}); err != nil || len(got) != 1 || !srv.apiKey[0] {
		t.Fatalf("用存着的密钥：%+v %v", got, err)
	}
	h.write(".config/claude-switch/targets/vault.json", map[string]any{"apiKeyHelper": "pass show relay"})
	for _, name := range []string{"vault", "nope", ""} {
		if _, err := host.RelayModels(ctx(t), ModelsQuery{Name: name, BaseURL: srv.URL}); saidKey(err) != "errModelsNoKey" {
			t.Errorf("%q 没有能读的密钥：%v", name, err)
		}
	}
}

// 出错说得出原因，都不带密钥：401、404、别的状态、不是 JSON、太大、超时、跨源重定向（不跟过去，密钥不发给那边）。
func TestRelayModelsErrors(t *testing.T) {
	h := newHost(t)
	host := h.host()
	other := newModelsServer(t, modelsOK(`{"data":[]}`))
	big := `{"data":[` + strings.Repeat(`{"id":"x"},`, modelsLimit/10) + `{"id":"y"}]}`
	cases := []struct {
		name    string
		handler func(http.ResponseWriter, *http.Request)
		want    string
	}{
		{"401", func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "no", http.StatusUnauthorized) }, "errModelsAuth"},
		{"403", func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "no", http.StatusForbidden) }, "errModelsAuth"},
		{"404", http.NotFound, "errModelsNotFound"},
		{"500", func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "boom", http.StatusInternalServerError) }, "errModelsStatus"},
		{"html", modelsOK("<html>hi</html>"), "errModelsNotJSON"},
		{"no data", modelsOK(`{"models":[]}`), "errModelsNotJSON"},
		{"big", modelsOK(big), "errModelsTooLarge"},
		{"redirect", func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, other.URL+"/v1/models", http.StatusFound)
		}, "errModelsRedirect"},
	}
	for _, c := range cases {
		srv := newModelsServer(t, c.handler)
		_, err := host.RelayModels(ctx(t), ModelsQuery{BaseURL: srv.URL, Key: testKey})
		if saidKey(err) != c.want {
			t.Errorf("%s：%v，应为 %s", c.name, err, c.want)
		}
		if err != nil && (strings.Contains(err.Error(), testKey) || strings.Contains(locales.Said(err, "en"), testKey)) {
			t.Errorf("%s 的出错里有密钥", c.name)
		}
	}
	if len(other.apiKey) != 0 {
		t.Fatal("跨源重定向不该把请求发过去")
	}

	// 同源的重定向照跟
	same := newModelsServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/models" {
			http.Redirect(w, r, "/real/v1/models", http.StatusFound)
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"m"}]}`))
	})
	if got, err := host.RelayModels(ctx(t), ModelsQuery{BaseURL: same.URL, Key: testKey}); err != nil || len(got) != 1 {
		t.Fatalf("同源重定向：%+v %v", got, err)
	}

	// 超时：服务器一直不答，等到调用方给的期限
	release := make(chan struct{})
	slow := newModelsServer(t, func(http.ResponseWriter, *http.Request) { <-release })
	defer close(release)
	short, cancel := context.WithTimeout(ctx(t), 100*time.Millisecond)
	defer cancel()
	if _, err := host.RelayModels(short, ModelsQuery{BaseURL: slow.URL, Key: testKey}); saidKey(err) != "errModelsTimeout" {
		t.Fatalf("超时：%v", err)
	}
	// 连不上
	dead := httptest.NewServer(http.NotFoundHandler())
	dead.Close()
	if _, err := host.RelayModels(ctx(t), ModelsQuery{BaseURL: dead.URL, Key: testKey}); saidKey(err) != "errModelsNetwork" {
		t.Fatalf("连不上：%v", err)
	}
}
