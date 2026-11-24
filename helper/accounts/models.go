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
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 拉中转的模型列表（accounts.relay_models）：GET <地址>/v1/models，Anthropic 的（data[].id、display_name）和 OpenAI 的
// （data[].id）两种都认。只给界面挑默认模型用，结果不存。响应最多 4 MB；跨源的重定向不跟（x-api-key 不是标准的凭据头，
// Go 跨主机重定向时不会替它去掉）；出错说原因（鉴权失败、没有这个接口、超时、不是 JSON），不带密钥。

// RelayModel 是中转报的一个模型。
type RelayModel struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName,omitempty"`
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
	s, ok := readTarget(h.Home, name)
	file := keyFile(h.Home, s.APIKeyHelper)
	if !ok || file == "" {
		return "", locales.Err("errModelsNoKey")
	}
	data, err := os.ReadFile(file)
	key := strings.TrimSpace(string(data))
	if err != nil || key == "" {
		return "", locales.Err("errModelsNoKey")
	}
	return key, nil
}

func fetchModels(ctx context.Context, q ModelsQuery) ([]RelayModel, error) {
	ctx, cancel := context.WithTimeout(ctx, modelsTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(q.BaseURL, "/")+"/v1/models?limit=1000", nil)
	if err != nil {
		return nil, locales.Err("errRelayURL")
	}
	req.Header.Set("anthropic-version", anthropicVersion)
	req.Header.Set("Accept", "application/json")
	// 两个头都带，同 Claude Code 用 apiKeyHelper 的密钥时（见 relays.go 开头）：只认其中一种的中转也拉得到
	req.Header.Set("x-api-key", q.Key)
	req.Header.Set("Authorization", "Bearer "+q.Key)
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
