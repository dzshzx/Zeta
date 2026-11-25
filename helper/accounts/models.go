package accounts

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 拉中转的模型列表（accounts.relay_models）：GET <地址>/v1/models，Anthropic 的（data[].id、display_name）和 OpenAI 的
// （data[].id）两种都认。聊天和设置共用这份上游列表，结果不写入配置。响应最多 4 MB；跨源的重定向不跟（x-api-key 不是标准的凭据头，
// Go 跨主机重定向时不会替它去掉）；出错说原因（鉴权失败、没有这个接口、超时、不是 JSON），不带密钥。

// RelayModel 是中转报的一个模型。
type RelayModel struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

// ModelCatalog 是生效中转的名称、默认模型和上游模型列表。没有生效中转时三个字段都为空；中转没设默认模型时 Model 为空，
// 由调用方填上继承的用户级默认值。
type ModelCatalog struct {
	Relay  string       `json:"relay"`
	Model  string       `json:"model"`
	Models []RelayModel `json:"models"`
}

// ReadModelCatalog 只自动访问生效的已保存中转。拉取失败仍回传已知的默认模型，但不回传网络错误。
func ReadModelCatalog(ctx context.Context, home string) (ModelCatalog, error) {
	out := ModelCatalog{Models: []RelayModel{}}
	name, err := effectiveRelay(ctx, home)
	if err != nil || name == "" {
		return out, err
	}
	var baseURL string
	var auth modelAuth
	out.Relay = name
	out.Model, baseURL, auth = catalogTarget(home, name)
	if auth == (modelAuth{}) || validBaseURL(baseURL) != nil {
		return out, nil
	}
	models, _ := fetchAuthenticatedModels(ctx, baseURL, auth)
	for _, m := range models {
		// 上游可能在成功响应里回显鉴权信息；这类条目不往客户端发。
		if !auth.reflectedIn(m.ID) && !auth.reflectedIn(m.DisplayName) {
			out.Models = append(out.Models, m)
		}
	}
	return out, nil
}

type modelAuth struct{ apiKey, token string }

func (a modelAuth) reflectedIn(s string) bool {
	return (a.apiKey != "" && strings.Contains(s, a.apiKey)) || (a.token != "" && strings.Contains(s, a.token))
}

// catalogTarget 用同一份目标设置取模型、地址和密钥，和目标编辑串行，但不持锁联网。
func catalogTarget(home, name string) (string, string, modelAuth) {
	unlock, err := lockRelayFiles(home)
	if err != nil {
		s, _ := readTarget(home, name)
		return strings.TrimSpace(s.Model), "", modelAuth{}
	}
	defer unlock()
	s, _ := readTarget(home, name)
	return strings.TrimSpace(s.Model), targetEnv(s.Env, "ANTHROPIC_BASE_URL"), targetModelAuth(home, s)
}

func targetModelAuth(home string, s targetSettings) modelAuth {
	auth := modelAuth{apiKey: targetEnv(s.Env, "ANTHROPIC_API_KEY"), token: targetEnv(s.Env, "ANTHROPIC_AUTH_TOKEN")}
	if auth != (modelAuth{}) {
		return auth
	}
	// 只读 Zeta 认识的 cat <文件>，不执行自定义 apiKeyHelper。
	key, _ := readStoredKey(home, s.APIKeyHelper)
	return modelAuth{apiKey: key, token: key}
}

// ModelsQuery 是拉模型列表的参数：编辑页里还没保存的地址、密钥；Key 为空时用已保存的中转 Name 的密钥文件。
type ModelsQuery struct {
	Name    string
	BaseURL string
	Key     string
}

const (
	modelsLimit   = 4 << 20
	modelsTimeout = 20 * time.Second
	// anthropicVersion 是 Anthropic 接口要的 anthropic-version 头（Claude Code 发的也是它）。
	anthropicVersion = "2023-06-01"
)

// modelsClient 是拉模型列表用的 HTTP 客户端：代理照环境变量，跨源的重定向不跟。
var modelsClient = &http.Client{
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		first := via[0].URL
		if req.URL.Scheme != first.Scheme || req.URL.Host != first.Host {
			return locales.Err("errModelsRedirect", "host", req.URL.Host)
		}
		if len(via) >= 5 {
			return locales.Err("errModelsRedirect", "host", req.URL.Host)
		}
		return nil
	},
}

// RelayModels 拉中转的模型列表。
func (h *Host) RelayModels(ctx context.Context, q ModelsQuery) ([]RelayModel, error) {
	q.BaseURL, q.Key = strings.TrimSpace(q.BaseURL), strings.TrimSpace(q.Key)
	if err := validBaseURL(q.BaseURL); err != nil {
		return nil, err
	}
	if q.Key == "" {
		key, err := h.storedKey(q.Name)
		if err != nil {
			return nil, err
		}
		q.Key = key
	}
	return fetchModels(ctx, q)
}

// storedKey 读已保存的中转 name 的密钥（只认 `cat <家目录里的文件>`）。
func (h *Host) storedKey(name string) (string, error) {
	s, _ := readTarget(h.Home, name)
	return readStoredKey(h.Home, s.APIKeyHelper)
}

func readStoredKey(home, helper string) (string, error) {
	file := keyFile(home, helper)
	if file == "" {
		return "", locales.Err("errModelsNoKey")
	}
	// 自动拉取时持有目标锁：不能让特殊文件或过大的文件挡住其他配置操作。
	f, err := os.OpenFile(file, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return "", locales.Err("errModelsNoKey")
	}
	defer f.Close()
	if info, err := f.Stat(); err != nil || !info.Mode().IsRegular() {
		return "", locales.Err("errModelsNoKey")
	}
	const keyLimit = 64 << 10
	data, err := io.ReadAll(io.LimitReader(f, keyLimit+1))
	key := strings.TrimSpace(string(data))
	if err != nil || len(data) > keyLimit || key == "" {
		return "", locales.Err("errModelsNoKey")
	}
	return key, nil
}

func fetchModels(ctx context.Context, q ModelsQuery) ([]RelayModel, error) {
	// 编辑时输入的密钥按 apiKeyHelper 的写法，同时发两个头。
	return fetchAuthenticatedModels(ctx, q.BaseURL, modelAuth{apiKey: q.Key, token: q.Key})
}

func fetchAuthenticatedModels(ctx context.Context, baseURL string, auth modelAuth) ([]RelayModel, error) {
	ctx, cancel := context.WithTimeout(ctx, modelsTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(baseURL, "/")+"/v1/models?limit=1000", nil)
	if err != nil {
		return nil, locales.Err("errRelayURL")
	}
	req.Header.Set("anthropic-version", anthropicVersion)
	req.Header.Set("Accept", "application/json")
	if auth.apiKey != "" {
		req.Header.Set("x-api-key", auth.apiKey)
	}
	if auth.token != "" {
		req.Header.Set("Authorization", "Bearer "+auth.token)
	}
	resp, err := modelsClient.Do(req)
	if err != nil {
		return nil, requestError(ctx, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, modelsLimit+1))
	if err != nil {
		return nil, requestError(ctx, err)
	}
	switch {
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		return nil, locales.Err("errModelsAuth", "status", strconv.Itoa(resp.StatusCode))
	case resp.StatusCode == http.StatusNotFound:
		return nil, locales.Err("errModelsNotFound")
	case resp.StatusCode/100 != 2:
		return nil, locales.Err("errModelsStatus", "status", strconv.Itoa(resp.StatusCode))
	case len(body) > modelsLimit:
		return nil, locales.Err("errModelsTooLarge")
	}
	return parseModels(body)
}

// requestError 把请求没成的原因说成一句：超时、连不上。Go 的出错里有地址，没有请求头，所以不会带出密钥。
func requestError(ctx context.Context, err error) error {
	var said *locales.Error
	var netErr net.Error
	switch {
	case errors.As(err, &said):
		return said
	case errors.Is(ctx.Err(), context.DeadlineExceeded), errors.As(err, &netErr) && netErr.Timeout():
		return locales.Err("errModelsTimeout")
	}
	return locales.Err("errModelsNetwork", "detail", err.Error())
}

// parseModels 读 Anthropic（{"data":[{"id","display_name"}]}）或 OpenAI（{"data":[{"id","object":"model"}]}）的模型列表，
// 照原来的顺序，重复的去掉。
func parseModels(body []byte) ([]RelayModel, error) {
	var resp struct {
		Data []struct {
			ID          string `json:"id"`
			DisplayName string `json:"display_name"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &resp) != nil || resp.Data == nil {
		return nil, locales.Err("errModelsNotJSON")
	}
	out := []RelayModel{}
	seen := map[string]bool{}
	for _, m := range resp.Data {
		id := strings.TrimSpace(m.ID)
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, RelayModel{ID: id, DisplayName: strings.TrimSpace(m.DisplayName)})
	}
	return out, nil
}
