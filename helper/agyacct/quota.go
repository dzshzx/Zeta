package agyacct

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/locales"
)

const (
	quotaRelDir       = ".cache/zeta-quota"
	usageQueryTimeout = 25 * time.Second
)

// QuotaStore 读写一台主机上 Antigravity 账号最近一次的额度（~/.cache/zeta-quota/agy.json）。
type QuotaStore struct {
	Home string
}

type cachedQuota struct {
	Account string `json:"account,omitempty"`
	accounts.Snapshot
}

func (s QuotaStore) activePath() string {
	return filepath.Join(s.Home, quotaRelDir, "agy.json")
}

func (s QuotaStore) slotPath(fp string) string {
	return filepath.Join(s.Home, quotaRelDir, "agy-"+fp[:16]+".json")
}

// activeOAuth 一次读取 state.json 与 OAuth 令牌指纹；在中转或未登录时 ok 为假。
func (s QuotaStore) activeOAuth() (fp, plan string, ok bool) {
	ds := loadDiskState(s.Home)
	if activeRelayFromState(s.Home, ds) != "" {
		return "", "", false
	}
	fp, ok = fileFingerprint(oauthTokenPath(s.Home))
	if !ok {
		return "", "", false
	}
	return fp, ds.Plans[planKey(fp)], true
}

func (s QuotaStore) getForFP(fp, fallbackPlan string) (*accounts.Snapshot, error) {
	key := planKey(fp)
	if snap, err := loadCachedQuota(s.activePath(), key, fallbackPlan); snap != nil || err != nil {
		return snap, err
	}
	return loadCachedQuota(s.slotPath(fp), key, fallbackPlan)
}

// Get 读取当前在用 Google OAuth 账号最近一次缓存的额度；在用中转、未登录或尚无读数时返回 nil。
func (s QuotaStore) Get() (*accounts.Snapshot, error) {
	fp, plan, ok := s.activeOAuth()
	if !ok {
		return nil, nil
	}
	return s.getForFP(fp, plan)
}

func loadCachedQuota(path, wantAccount, fallbackPlan string) (*accounts.Snapshot, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var c cachedQuota
	if json.Unmarshal(data, &c) != nil || c.UpdatedAt.IsZero() {
		return nil, nil
	}
	if c.Account != "" && wantAccount != "" && c.Account != wantAccount {
		return nil, nil
	}
	snap := normalizeSnapshot(c.Snapshot, fallbackPlan)
	return &snap, nil
}

func normalizeSnapshot(s accounts.Snapshot, fallbackPlan string) accounts.Snapshot {
	s.Subscription = cmp.Or(s.Subscription, "antigravity")
	s.Plan = cmp.Or(fallbackPlan, s.Plan, "Antigravity")
	return s
}

// Usage 解析并保存一次额度应答（兼容 accounts.ParseUsage 输入格式）。
func (s QuotaStore) Usage(raw []byte, now time.Time) (accounts.Snapshot, bool, error) {
	parsed, err := accounts.ParseUsage(raw, now)
	if err != nil {
		return accounts.Snapshot{}, false, err
	}
	fp, plan, ok := s.activeOAuth()
	parsed = normalizeSnapshot(parsed, plan)
	if !ok {
		return parsed.At(now), true, nil
	}
	prev, err := s.getForFP(fp, plan)
	if err != nil {
		return accounts.Snapshot{}, false, err
	}
	merged, fresh := accounts.MergeUsage(prev, parsed, now)
	if !fresh {
		return merged, false, nil
	}
	if err := s.save(fp, merged); err != nil {
		return accounts.Snapshot{}, false, err
	}
	return merged, true, nil
}

// Event 将一条 rate_limit_event 并入当前 agy 账号额度。
func (s QuotaStore) Event(info []byte, now time.Time) (accounts.Snapshot, bool, error) {
	fp, plan, ok := s.activeOAuth()
	if !ok {
		return accounts.Snapshot{}, false, nil
	}
	prev, err := s.getForFP(fp, plan)
	if err != nil {
		return accounts.Snapshot{}, false, err
	}
	snap, changed, err := accounts.ApplyEvent(prev, info, now)
	if err != nil {
		return snap, changed, err
	}
	snap = normalizeSnapshot(snap, plan)
	if !changed {
		return snap, false, nil
	}
	return snap, true, s.save(fp, snap)
}

func (s QuotaStore) save(fp string, snap accounts.Snapshot) error {
	data, err := json.Marshal(cachedQuota{Account: fp[:16], Snapshot: snap})
	if err != nil {
		return err
	}
	if err := accounts.WriteFileAtomic(s.slotPath(fp), data, 0o600); err != nil {
		return err
	}
	return accounts.WriteFileAtomic(s.activePath(), data, 0o600)
}

func syncQuotaCache(home string) {
	s := QuotaStore{Home: home}
	if CurrentRelay(home) != "" {
		_ = accounts.Removed(s.activePath())
		return
	}
	fp, ok := fileFingerprint(oauthTokenPath(home))
	if !ok {
		_ = accounts.Removed(s.activePath())
		return
	}
	data, err := os.ReadFile(s.slotPath(fp))
	if err != nil {
		_ = accounts.Removed(s.activePath())
		return
	}
	_ = accounts.WriteFileAtomic(s.activePath(), data, 0o600)
}

// ---------- agy /usage 与 /credits 真实额度查询 ----------

// UsageCommandFunc 执行 `agy -p <prompt> --output-format json` 并返回标准输出；单测可替换。
var UsageCommandFunc = defaultUsageCommand

func defaultUsageCommand(ctx context.Context, home, prompt string) ([]byte, error) {
	if home == "" {
		home = "/"
	}
	cmd := exec.CommandContext(ctx, "bash", "-lc",
		`export PATH="$PATH:$HOME/.local/bin"; exec "${ZETA_AGY_BIN:-agy}" -p "$1" --output-format json`, "--", prompt)
	cmd.Dir = os.TempDir()
	env := make([]string, 0, len(os.Environ())+1)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "HOME=") {
			env = append(env, kv)
		}
	}
	cmd.Env = append(env, "HOME="+home)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if err != nil && stdout.Len() == 0 {
		msg := strings.TrimSpace(stderr.String())
		if msg != "" {
			return nil, errors.New(msg)
		}
		return nil, err
	}
	return stdout.Bytes(), err
}

// QueryUsageJSON 通过 `agy -p "/usage"` 与 `agy -p "/credits"` 查询订阅额度与 AI Credits，
// 立即清理命令产生的临时空会话，并返回符合 accounts.ParseUsage 输入格式的 JSON。
func QueryUsageJSON(ctx context.Context, home string) (json.RawMessage, error) {
	if CurrentRelay(home) != "" {
		return nil, locales.Err("errNoSubscription")
	}
	ctx, cancel := context.WithTimeout(ctx, usageQueryTimeout)
	defer cancel()

	var (
		usageOut, creditsOut []byte
		usageErr             error
		wg                   sync.WaitGroup
	)
	wg.Add(2)
	go func() {
		defer wg.Done()
		usageOut, usageErr = runAndCleanPrompt(ctx, home, "/usage")
	}()
	go func() {
		defer wg.Done()
		creditsOut, _ = runAndCleanPrompt(ctx, home, "/credits")
	}()
	wg.Wait()

	rows, err := parseAgyUsageRows(usageOut, usageErr)
	if err != nil {
		return nil, err
	}
	extra := parseAgyCreditsExtra(creditsOut)
	return marshalUsagePayload(rows, extra)
}

func runAndCleanPrompt(ctx context.Context, home, prompt string) ([]byte, error) {
	out, err := UsageCommandFunc(ctx, home, prompt)
	if convID := extractConversationID(out); agystore.IsSessionID(convID) {
		_, _ = agystore.Delete(AgyDir(home), convID)
	}
	return out, err
}

func extractConversationID(raw []byte) string {
	var top struct {
		ConversationID string `json:"conversation_id"`
	}
	for line := range bytes.Lines(raw) {
		trimmed := bytes.TrimSpace(line)
		if len(trimmed) > 0 && trimmed[0] == '{' && json.Unmarshal(trimmed, &top) == nil && top.ConversationID != "" {
			return top.ConversationID
		}
	}
	if json.Unmarshal(bytes.TrimSpace(raw), &top) == nil {
		return top.ConversationID
	}
	return ""
}

type agyUsageEnvelope struct {
	Error   string `json:"error"`
	Command *struct {
		Data *struct {
			Groups []agyUsageGroup `json:"groups"`
		} `json:"data"`
	} `json:"command"`
}

type agyUsageGroup struct {
	Name    string           `json:"name"`
	Label   string           `json:"label"`
	Title   string           `json:"title"`
	Group   string           `json:"group"`
	Buckets []agyUsageBucket `json:"buckets"`
}

type agyUsageBucket struct {
	Window            string   `json:"window"`
	RemainingFraction *float64 `json:"remaining_fraction"`
	ResetTime         string   `json:"reset_time"`
}

type outUsageRow struct {
	Kind          string    `json:"kind"`
	Group         string    `json:"group"`
	WindowSeconds int       `json:"window_seconds,omitempty"`
	Percent       float64   `json:"percent"`
	ResetsAt      string    `json:"resets_at"`
	Active        bool      `json:"is_active,omitempty"`
	Scope         *outScope `json:"scope,omitempty"`
}

type outScope struct {
	Model *outScopeName `json:"model,omitempty"`
}

type outScopeName struct {
	DisplayName string `json:"display_name"`
}

type outExtraUsage struct {
	Enabled      bool     `json:"is_enabled"`
	MonthlyLimit *float64 `json:"monthly_limit,omitempty"`
	Currency     string   `json:"currency,omitempty"`
}

func parseAgyUsageRows(raw []byte, runErr error) ([]outUsageRow, error) {
	var env agyUsageEnvelope
	if err := decodeJSONEnvelope(raw, &env); err != nil {
		if runErr != nil {
			return nil, runErr
		}
		return nil, errors.New("unrecognized /usage output")
	}
	if env.Command == nil || env.Command.Data == nil {
		if env.Error != "" {
			return nil, errors.New(env.Error)
		}
		if runErr != nil {
			return nil, runErr
		}
		return nil, errors.New("missing command.data in /usage output")
	}
	rows := buildOutRows(env.Command.Data.Groups)
	markHighestActive(rows)
	return rows, nil
}

func buildOutRows(groups []agyUsageGroup) []outUsageRow {
	rows := []outUsageRow{}
	for _, g := range groups {
		scope := mapAgyGroupScope(cmp.Or(g.Name, g.Label, g.Title, g.Group))
		for _, b := range g.Buckets {
			rows = append(rows, bucketToOutRow(scope, b))
		}
	}
	return rows
}

func bucketToOutRow(scope string, b agyUsageBucket) outUsageRow {
	rem := 1.0
	if b.RemainingFraction != nil {
		rem = *b.RemainingFraction
	}
	pct := math.Round(max(0.0, min(100.0, (1.0-rem)*100.0))*100) / 100
	kind, group, winSec := mapAgyBucketWindow(b.Window, scope)
	row := outUsageRow{
		Kind:          kind,
		Group:         group,
		WindowSeconds: winSec,
		Percent:       pct,
		ResetsAt:      cleanAgyResetTime(b.ResetTime),
	}
	if scope != "" {
		row.Scope = &outScope{Model: &outScopeName{DisplayName: scope}}
	}
	return row
}

func markHighestActive(rows []outUsageRow) {
	if len(rows) == 0 {
		return
	}
	best := 0
	for i := 1; i < len(rows); i++ {
		if rows[i].Percent > rows[best].Percent {
			best = i
		}
	}
	rows[best].Active = true
}

func mapAgyGroupScope(raw string) string {
	trimmed := strings.TrimSpace(raw)
	switch strings.ToLower(trimmed) {
	case "gemini models":
		return "Gemini"
	case "claude and gpt models":
		return "Claude / GPT"
	}
	return trimmed
}

func mapAgyBucketWindow(window, scope string) (kind, group string, seconds int) {
	switch strings.ToLower(strings.TrimSpace(window)) {
	case "5h":
		if scope != "" {
			return "session_scoped", "session", 18000
		}
		return "session", "session", 18000
	case "weekly":
		if scope != "" {
			return "weekly_scoped", "weekly", 604800
		}
		return "weekly_all", "weekly", 604800
	default:
		w := cmp.Or(strings.TrimSpace(window), "session")
		return w, w, 0
	}
}

func cleanAgyResetTime(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil || t.Year() <= 1971 {
		return ""
	}
	return s
}

func parseAgyCreditsExtra(raw []byte) *outExtraUsage {
	var env struct {
		Command *struct {
			Data *struct {
				RemainingCredits *float64 `json:"remaining_credits"`
			} `json:"data"`
		} `json:"command"`
	}
	if decodeJSONEnvelope(raw, &env) != nil || env.Command == nil || env.Command.Data == nil || env.Command.Data.RemainingCredits == nil {
		return nil
	}
	rem := *env.Command.Data.RemainingCredits
	if rem < 0 {
		return nil
	}
	minor := math.Round(rem * 100)
	return &outExtraUsage{
		Enabled:      true,
		MonthlyLimit: &minor,
		Currency:     "credits",
	}
}

func decodeJSONEnvelope(raw []byte, dst any) error {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return errors.New("empty json")
	}
	if json.Unmarshal(trimmed, dst) == nil {
		return nil
	}
	for line := range bytes.Lines(trimmed) {
		l := bytes.TrimSpace(line)
		if len(l) > 0 && l[0] == '{' && json.Unmarshal(l, dst) == nil {
			return nil
		}
	}
	return errors.New("invalid json")
}

func marshalUsagePayload(rows []outUsageRow, extra *outExtraUsage) (json.RawMessage, error) {
	limits := map[string]any{"limits": rows}
	if extra != nil {
		limits["extra_usage"] = extra
	}
	payload := map[string]any{
		"rate_limits_available": true,
		"subscription_type":     "antigravity",
		"rate_limits":           limits,
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(b), nil
}
