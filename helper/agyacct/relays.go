package agyacct

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/locales"
)

// DefaultGeminiBaseURL 是 Gemini API 默认的上游地址。
const DefaultGeminiBaseURL = "https://generativelanguage.googleapis.com"

const (
	modelsLimit   = 4 << 20
	modelsTimeout = 20 * time.Second
)

type relayRecord struct {
	Name    string `json:"name"`
	BaseURL string `json:"baseUrl,omitempty"`
	Model   string `json:"model,omitempty"`
}

func relaysDir(home string) string {
	return filepath.Join(stateDir(home), "relays")
}

func relayConfigPath(home, name string) string {
	return filepath.Join(relaysDir(home), name+".json")
}

func relayKeyPath(home, name string) string {
	return filepath.Join(relaysDir(home), name+"-key")
}

// CurrentRelay 返回当前启用的 agy 中转名；处于 Google OAuth 账号或未登录时返回空串。
func CurrentRelay(home string) string {
	ds := loadDiskState(home)
	if ds.CurrentRelay == "" {
		return ""
	}
	if _, ok := readRelayRecord(home, ds.CurrentRelay); !ok {
		return ""
	}
	return ds.CurrentRelay
}

// ActiveRelayEnv 返回当前启用的 agy 中转需注入子进程的环境变量（GEMINI_API_KEY 与非空时的 GOOGLE_GEMINI_BASE_URL）；
// 未启用中转或密钥缺失时返回 nil。
func ActiveRelayEnv(home string) map[string]string {
	name := CurrentRelay(home)
	if name == "" {
		return nil
	}
	rec, ok := readRelayRecord(home, name)
	if !ok {
		return nil
	}
	key, err := readKeyFile(relayKeyPath(home, name))
	if err != nil || key == "" {
		return nil
	}
	env := map[string]string{"GEMINI_API_KEY": key}
	if base := strings.TrimSpace(rec.BaseURL); base != "" {
		env["GOOGLE_GEMINI_BASE_URL"] = base
	}
	return env
}

// RelayDefaultModel 读取当前生效 agy 中转的默认模型；未启用中转或未设模型时返回空串。
func RelayDefaultModel(home string) string {
	name := CurrentRelay(home)
	if name == "" {
		return ""
	}
	rec, _ := readRelayRecord(home, name)
	return strings.TrimSpace(rec.Model)
}

// SetRelayDefaultModel 修改当前生效 agy 中转的默认模型；未启用中转时返回 false。
func SetRelayDefaultModel(home, model string) (bool, error) {
	var active bool
	err := withStateLock(home, func() error {
		name := CurrentRelay(home)
		if name == "" {
			return nil
		}
		model = strings.TrimSpace(model)
		if err := accounts.ValidRelayModel(model); err != nil {
			active = true
			return err
		}
		rec, ok := readRelayRecord(home, name)
		if !ok {
			return nil
		}
		active = true
		rec.Model = model
		if err := writeRelayRecord(home, rec); err != nil {
			return err
		}
		return enableRelaySettings(home, rec.Model)
	})
	return active, err
}

// ReadModelCatalog 读取生效 agy 中转的模型目录；未启用中转时 Relay 为空。
func ReadModelCatalog(ctx context.Context, home string) (accounts.ModelCatalog, error) {
	out := accounts.ModelCatalog{Models: []accounts.RelayModel{}}
	name := CurrentRelay(home)
	if name == "" {
		return out, nil
	}
	rec, ok := readRelayRecord(home, name)
	if !ok {
		return out, nil
	}
	out.Relay = name
	out.Model = strings.TrimSpace(rec.Model)
	key, err := readKeyFile(relayKeyPath(home, name))
	if err != nil || key == "" {
		return out, nil
	}
	base := cmp.Or(strings.TrimSpace(rec.BaseURL), DefaultGeminiBaseURL)
	if accounts.ValidBaseURL(base) != nil {
		return out, nil
	}
	if models, err := fetchGeminiModels(ctx, base, key); err == nil {
		out.Models = models
	}
	return out, nil
}

// SaveRelay 添加或修改一个 Gemini API Key 中转。
func (h *Host) SaveRelay(_ context.Context, in accounts.RelayInput) error {
	in.BaseURL = strings.TrimSpace(in.BaseURL)
	in.Key = strings.TrimSpace(in.Key)
	in.Model = strings.TrimSpace(in.Model)
	if err := validAgyRelay(in); err != nil {
		return err
	}
	return withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		existing := slices.IndexFunc(snap.Relays, func(r accounts.Relay) bool { return r.Name == in.Name }) >= 0
		switch {
		case in.Create && snap.taken[in.Name]:
			return locales.Err("errRelayNameTaken")
		case in.Create && in.Key == "":
			return locales.Err("errRelayKeyRequired")
		case !in.Create && !existing:
			return accounts.ErrRelayMissing
		}
		if in.Key != "" {
			kp := relayKeyPath(h.Home, in.Name)
			if err := accounts.WriteFileAtomic(kp, []byte(in.Key+"\n"), 0o600); err != nil {
				return locales.Err("errRelayWrite", "path", kp)
			}
		}
		rec := relayRecord{Name: in.Name, BaseURL: in.BaseURL, Model: in.Model}
		if err := writeRelayRecord(h.Home, rec); err != nil {
			return err
		}
		if snap.ds.CurrentRelay == in.Name {
			return enableRelaySettings(h.Home, rec.Model)
		}
		return nil
	})
}

// RemoveRelay 删除一个未在使用中的 agy 中转。
func (h *Host) RemoveRelay(_ context.Context, name string) error {
	return withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		i := slices.IndexFunc(snap.Relays, func(r accounts.Relay) bool { return r.Name == name })
		switch {
		case i < 0:
			return accounts.ErrRelayMissing
		case snap.Relays[i].Current:
			return accounts.ErrRelayInUse
		}
		for _, p := range []string{relayConfigPath(h.Home, name), relayKeyPath(h.Home, name)} {
			if !accounts.Removed(p) {
				return locales.Err("errRelayWrite", "path", p)
			}
		}
		return nil
	})
}

// RelayModels 请求 <baseUrl>/v1beta/models（默认 https://generativelanguage.googleapis.com）拉取可用模型列表。
func (h *Host) RelayModels(ctx context.Context, q accounts.ModelsQuery) ([]accounts.RelayModel, error) {
	q.BaseURL, q.Key = strings.TrimSpace(q.BaseURL), strings.TrimSpace(q.Key)
	if q.BaseURL == "" && q.Name != "" {
		if rec, ok := readRelayRecord(h.Home, q.Name); ok {
			q.BaseURL = strings.TrimSpace(rec.BaseURL)
		}
	}
	base := cmp.Or(q.BaseURL, DefaultGeminiBaseURL)
	if err := accounts.ValidBaseURL(base); err != nil {
		return nil, err
	}
	key := q.Key
	if key == "" {
		var err error
		key, err = readKeyFile(relayKeyPath(h.Home, q.Name))
		if err != nil || key == "" {
			return nil, locales.Err("errModelsNoKey")
		}
	}
	return fetchGeminiModels(ctx, base, key)
}

var modelsClient = &http.Client{
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		first := via[0].URL
		if req.URL.Scheme != first.Scheme || req.URL.Host != first.Host || len(via) >= 5 {
			return locales.Err("errModelsRedirect", "host", req.URL.Host)
		}
		return nil
	},
}

func fetchGeminiModels(ctx context.Context, baseURL, key string) ([]accounts.RelayModel, error) {
	ctx, cancel := context.WithTimeout(ctx, modelsTimeout)
	defer cancel()
	endpoint := geminiModelsEndpoint(baseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, locales.Err("errRelayURL")
	}
	req.Header.Set("x-goog-api-key", key)
	req.Header.Set("Accept", "application/json")
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
	models, err := parseGeminiOrOpenAIModels(body)
	if err != nil {
		return nil, err
	}
	out := make([]accounts.RelayModel, 0, len(models))
	for _, m := range models {
		if !strings.Contains(m.ID, key) && !strings.Contains(m.DisplayName, key) {
			out = append(out, m)
		}
	}
	return out, nil
}

func geminiModelsEndpoint(baseURL string) string {
	trimmed := strings.TrimRight(baseURL, "/")
	trimmed = strings.TrimSuffix(trimmed, "/v1beta")
	return trimmed + "/v1beta/models?pageSize=1000"
}

func parseGeminiOrOpenAIModels(body []byte) ([]accounts.RelayModel, error) {
	var payload struct {
		Models []struct {
			Name        string `json:"name"`
			DisplayName string `json:"displayName"`
		} `json:"models"`
		Data []struct {
			ID          string `json:"id"`
			DisplayName string `json:"display_name"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &payload) != nil || (payload.Models == nil && payload.Data == nil) {
		return nil, locales.Err("errModelsNotJSON")
	}
	out := []accounts.RelayModel{}
	seen := map[string]bool{}
	add := func(id, display string) {
		if id == "" || seen[id] {
			return
		}
		seen[id] = true
		out = append(out, accounts.RelayModel{ID: id, DisplayName: cmp.Or(strings.TrimSpace(display), id)})
	}
	for _, m := range payload.Models {
		add(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(m.Name), "models/")), m.DisplayName)
	}
	for _, m := range payload.Data {
		add(strings.TrimSpace(m.ID), m.DisplayName)
	}
	return out, nil
}

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

// ---------- 中转文件与 settings.json 同步 ----------

func listRelayNames(home string) []string {
	entries, err := os.ReadDir(relaysDir(home))
	if err != nil {
		return nil
	}
	var names []string
	for _, e := range entries {
		name, ok := strings.CutSuffix(e.Name(), ".json")
		if ok && validAccountName(name) && name != builtin {
			names = append(names, name)
		}
	}
	slices.Sort(names)
	return names
}

func readRelayRecord(home, name string) (relayRecord, bool) {
	if !validAccountName(name) {
		return relayRecord{}, false
	}
	data, err := os.ReadFile(relayConfigPath(home, name))
	if err != nil {
		return relayRecord{}, false
	}
	var r relayRecord
	if json.Unmarshal(data, &r) != nil {
		return relayRecord{}, false
	}
	r.Name = name
	return r, true
}

func writeRelayRecord(home string, rec relayRecord) error {
	path := relayConfigPath(home, rec.Name)
	data, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return err
	}
	if err := accounts.WriteFileAtomic(path, append(data, '\n'), 0o600); err != nil {
		return locales.Err("errRelayWrite", "path", path)
	}
	return nil
}

func loadRelays(home string, names []string, current string) []accounts.Relay {
	out := make([]accounts.Relay, 0, len(names))
	for _, name := range names {
		rec, _ := readRelayRecord(home, name)
		info, err := os.Stat(relayKeyPath(home, name))
		hasKey := err == nil && info.Mode().IsRegular() && info.Size() > 0
		out = append(out, accounts.Relay{
			Name:        name,
			BaseURL:     rec.BaseURL,
			Model:       rec.Model,
			HasKey:      hasKey,
			KeyEditable: true,
			Current:     name == current,
		})
	}
	return out
}

func readKeyFile(path string) (string, error) {
	f, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
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

func validAgyRelay(in accounts.RelayInput) error {
	if !validAccountName(in.Name) || in.Name == builtin {
		return locales.Err("errRelayName")
	}
	if in.BaseURL != "" {
		if err := accounts.ValidBaseURL(in.BaseURL); err != nil {
			return err
		}
	}
	if strings.ContainsAny(in.Key, " \t\r\n") {
		return locales.Err("errRelayKey")
	}
	return accounts.ValidRelayModel(in.Model)
}

func enableRelaySettings(home, model string) error {
	return updateAgySettings(home, func(obj map[string]json.RawMessage, _ bool) bool {
		obj["modelProvider"], _ = json.Marshal("gemini")
		if m := strings.TrimSpace(model); m != "" {
			obj["model"], _ = json.Marshal(m)
		}
		return true
	})
}

func clearRelaySettings(home string, clearProvider bool) error {
	if !clearProvider {
		return nil
	}
	return updateAgySettings(home, func(obj map[string]json.RawMessage, existed bool) bool {
		var cur string
		if rawProv, ok := obj["modelProvider"]; ok && json.Unmarshal(rawProv, &cur) == nil && cur == "gemini" {
			delete(obj, "modelProvider")
		}
		return len(obj) > 0 || existed
	})
}

func updateAgySettings(home string, mutate func(obj map[string]json.RawMessage, existed bool) bool) error {
	path := agySettingsPath(home)
	obj, existed, ok := loadAgySettingsMap(path)
	if !ok || !mutate(obj, existed) {
		return nil
	}
	encoded, err := json.MarshalIndent(obj, "", "  ")
	if err != nil {
		return err
	}
	return accounts.WriteFileAtomic(path, append(encoded, '\n'), 0o644)
}

func loadAgySettingsMap(path string) (map[string]json.RawMessage, bool, bool) {
	data, err := os.ReadFile(path)
	obj := map[string]json.RawMessage{}
	if err != nil {
		return obj, false, true
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return obj, true, true
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	if dec.Decode(&obj) != nil || obj == nil {
		return nil, true, false
	}
	return obj, true, true
}
