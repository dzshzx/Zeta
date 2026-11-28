// Package agyacct 管理一台主机上的 Antigravity CLI（agy）账号、Google OAuth 登录、Gemini API Key 中转与订阅额度。
//
// 状态保存在 ~/.local/state/zeta-agy-accounts/：
//   - state.json：当前选中的账号或中转记录（不含任何令牌或密钥）
//   - tokens/<name>.token：已归档的 OAuth 令牌副本（0600），只按字节级 SHA-256 指纹与活跃令牌比对，绝不解析或输出原文
//   - relays/<name>.json 与 relays/<name>-key（0600）：Gemini API Key 中转配置与密钥
package agyacct

import (
	"bytes"
	"cmp"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/dzshzx/Zeta/helper/accounts"
	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

const (
	// StateRelDir 是 agy 账号与中转状态目录（相对家目录）。
	StateRelDir = ".local/state/zeta-agy-accounts"
	builtin     = "agy"
)

var (
	changing    sync.Mutex
	accountName = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$`)
)

// AgyDir 返回家目录 home 下的 Antigravity CLI 数据目录；设了 ZETA_AGY_DIR 时优先用它（供单测隔离）。
func AgyDir(home string) string { return agystore.Dir(home) }

func oauthTokenPath(home string) string {
	return filepath.Join(AgyDir(home), "antigravity-oauth-token")
}

func agySettingsPath(home string) string {
	return filepath.Join(AgyDir(home), "settings.json")
}

func stateDir(home string) string {
	return filepath.Join(home, StateRelDir)
}

func stateFilePath(home string) string {
	return filepath.Join(stateDir(home), "state.json")
}

func tokensDir(home string) string {
	return filepath.Join(stateDir(home), "tokens")
}

func tokenSlotPath(home, name string) string {
	return filepath.Join(tokensDir(home), name+".token")
}

type savedMeta struct {
	DisplayName string `json:"displayName,omitempty"`
	Email       string `json:"email,omitempty"`
}

type diskState struct {
	Current        string               `json:"current,omitempty"`
	CurrentRelay   string               `json:"currentRelay,omitempty"`
	PendingLogin   string               `json:"pendingLogin,omitempty"`
	PrevAccount    string               `json:"prevAccount,omitempty"`
	PrevRelay      string               `json:"prevRelay,omitempty"`
	RelaySetMode   bool                 `json:"relaySetMode,omitempty"`
	RelaySetModel  bool                 `json:"relaySetModel,omitempty"`
	RelayPrevModel string               `json:"relayPrevModel,omitempty"`
	Accounts       map[string]savedMeta `json:"accounts,omitempty"`
	Plans          map[string]string    `json:"plans,omitempty"`
}

// Host 是一台主机上的 agy 账号与中转管理器。
type Host struct {
	Home    string
	Lang    string
	Package string
	Wait    time.Duration
	EndIdle func()
}

// FreeName 挑第一个不在 taken 里的账号名：agy、agy-b……agy-z、agy-27……。
func FreeName(taken map[string]bool) string { return accounts.FreeNameWith(builtin, taken) }

func validAccountName(name string) bool {
	return accountName.MatchString(name)
}

// List 列出这台主机上的 agy 账号、中转和运行中的 agy 进程。
func (h *Host) List(_ context.Context) (accounts.State, error) {
	procs := h.procs()
	unlock, err := lockState(h.Home)
	if err != nil {
		return accounts.State{}, err
	}
	defer unlock()
	st := h.readStateLocked()
	if procs != nil {
		st.Procs = procs
	}
	if b := agyBlocker(procs, h.Package); b.Key != "" {
		st.Blocker = b.In(h.Lang)
	}
	return st.State, nil
}

type stateSnapshot struct {
	accounts.State
	ds    diskState
	taken map[string]bool
	slots map[string]string // name -> sha256 hex
}

func (h *Host) readStateLocked() stateSnapshot {
	ds := loadDiskState(h.Home)
	slots := readTokenSlots(h.Home)
	relayNames := listRelayNames(h.Home)
	taken := map[string]bool{}
	for name := range slots {
		taken[name] = true
	}
	for _, name := range relayNames {
		taken[name] = true
	}
	activeRelay := ""
	if slices.Contains(relayNames, ds.CurrentRelay) {
		activeRelay = ds.CurrentRelay
	}
	if activeRelay == "" {
		h.syncRefreshedTokenLocked(&ds, slots)
	}
	relays := loadRelays(h.Home, relayNames, activeRelay)
	cur, list := buildAccountList(h.Home, ds, slots, activeRelay != "")
	return stateSnapshot{
		State: accounts.State{
			Current:  cur,
			Accounts: list,
			Procs:    []accounts.Proc{},
			Relays:   relays,
		},
		ds:    ds,
		taken: taken,
		slots: slots,
	}
}

func buildAccountList(home string, ds diskState, slots map[string]string, onRelay bool) (*accounts.Account, []accounts.Account) {
	var cur *accounts.Account
	if !onRelay {
		if fp, ok := fileFingerprint(oauthTokenPath(home)); ok {
			plan := ds.Plans[planKey(fp)]
			matched := matchSlot(fp, ds.Current, slots)
			if matched != "" {
				live := makeAccount(matched, ds.Accounts[matched], plan, true, true)
				cur = &live
			} else {
				live := accounts.Account{
					Name:         "",
					DisplayName:  "Google OAuth",
					Subscription: "antigravity",
					Plan:         cmp.Or(plan, "Antigravity"),
					Current:      true,
				}
				cur = &live
			}
		}
	}
	var saved []accounts.Account
	for name, sfp := range slots {
		if cur != nil && cur.Name == name {
			continue
		}
		saved = append(saved, makeAccount(name, ds.Accounts[name], ds.Plans[planKey(sfp)], false, true))
	}
	slices.SortStableFunc(saved, func(a, b accounts.Account) int {
		return strings.Compare(strings.ToLower(a.Title()), strings.ToLower(b.Title()))
	})
	out := []accounts.Account{}
	if cur != nil {
		out = append(out, *cur)
	}
	out = append(out, saved...)
	return cur, out
}

// syncRefreshedTokenLocked 在 OAuth 令牌被 agy 自动刷新后保留原账号身份：
// 当活跃令牌指纹未命中任何已存槽位，但 ds.Current 指向一个已存槽位时，用新的不透明字节覆盖该槽位并迁移套餐缓存键。
func (h *Host) syncRefreshedTokenLocked(ds *diskState, slots map[string]string) {
	if ds.Current == "" || ds.PendingLogin != "" {
		return
	}
	oldFP, hasSlot := slots[ds.Current]
	if !hasSlot {
		return
	}
	activePath := oauthTokenPath(h.Home)
	fp, ok := fileFingerprint(activePath)
	if !ok || matchSlot(fp, ds.Current, slots) != "" {
		return
	}
	data, err := os.ReadFile(activePath)
	if err != nil || len(bytes.TrimSpace(data)) == 0 {
		return
	}
	if err := accounts.WriteFileAtomic(tokenSlotPath(h.Home, ds.Current), data, 0o600); err != nil {
		return
	}
	slots[ds.Current] = fp
	oldKey, newKey := planKey(oldFP), planKey(fp)
	if plan := ds.Plans[oldKey]; plan != "" && oldKey != newKey {
		if ds.Plans == nil {
			ds.Plans = map[string]string{}
		}
		ds.Plans[newKey] = plan
		delete(ds.Plans, oldKey)
		_ = saveDiskState(h.Home, *ds)
	}
	syncQuotaCache(h.Home)
}

func matchSlot(fp, preferred string, slots map[string]string) string {
	if preferred != "" && slots[preferred] == fp {
		return preferred
	}
	var names []string
	for name, sfp := range slots {
		if sfp == fp {
			names = append(names, name)
		}
	}
	slices.Sort(names)
	if len(names) > 0 {
		return names[0]
	}
	return ""
}

func planKey(fp string) string {
	if len(fp) > 16 {
		return fp[:16]
	}
	return fp
}

func activeRelayFromState(home string, ds diskState) string {
	if ds.CurrentRelay == "" {
		return ""
	}
	if _, ok := readRelayRecord(home, ds.CurrentRelay); !ok {
		return ""
	}
	return ds.CurrentRelay
}

func makeAccount(name string, meta savedMeta, plan string, current, parked bool) accounts.Account {
	return accounts.Account{
		Name:         name,
		Email:        meta.Email,
		DisplayName:  cmp.Or(meta.DisplayName, "Google OAuth"),
		Subscription: "antigravity",
		Plan:         cmp.Or(plan, "Antigravity"),
		Current:      current,
		Parked:       parked,
	}
}

// CurrentPlan 返回当前活跃 Google OAuth 账号记下的真实套餐展示名（如 "Google AI Ultra"）；未记下时返回空串。
func CurrentPlan(home string) string {
	ds := loadDiskState(home)
	if activeRelayFromState(home, ds) != "" {
		return ""
	}
	fp, ok := fileFingerprint(oauthTokenPath(home))
	if !ok {
		return ""
	}
	return ds.Plans[planKey(fp)]
}

// SaveCurrentPlan 将 ACP session/new 报出的真实套餐名按令牌指纹持久化到 state.json。
func SaveCurrentPlan(home, plan string) {
	plan = strings.TrimSpace(plan)
	if home == "" || plan == "" {
		return
	}
	ds := loadDiskState(home)
	if activeRelayFromState(home, ds) != "" {
		return
	}
	fp, ok := fileFingerprint(oauthTokenPath(home))
	if !ok {
		return
	}
	key := planKey(fp)
	if ds.Plans[key] == plan {
		return
	}
	_ = withStateLock(home, func() error {
		cur := loadDiskState(home)
		if activeRelayFromState(home, cur) != "" || cur.Plans[key] == plan {
			return nil
		}
		if cur.Plans == nil {
			cur.Plans = map[string]string{}
		}
		cur.Plans[key] = plan
		return saveDiskState(home, cur)
	})
}

func withFileLock(home string, fn func() error) error {
	unlock, err := lockState(home)
	if err != nil {
		return err
	}
	defer unlock()
	return fn()
}

func withStateLock(home string, fn func() error) error {
	changing.Lock()
	defer changing.Unlock()
	return withFileLock(home, fn)
}

// Save 将当前未命名的 Google OAuth 登录归档为一个新账号名（agy、agy-b…）。未登录时返回空串。
func (h *Host) Save(_ context.Context) (string, error) {
	var name string
	err := withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		if snap.Current == nil {
			return nil
		}
		if snap.Current.Name != "" {
			name = snap.Current.Name
			return nil
		}
		var err error
		name, err = h.archiveActiveTokenLocked(&snap, "")
		return err
	})
	return name, err
}

func (h *Host) archiveActiveTokenLocked(snap *stateSnapshot, preferred string) (string, error) {
	activePath := oauthTokenPath(h.Home)
	fp, ok := fileFingerprint(activePath)
	if !ok {
		return "", nil
	}
	if matched := matchSlot(fp, snap.ds.Current, snap.slots); matched != "" {
		snap.ds.Current = matched
		_ = saveDiskState(h.Home, snap.ds)
		return matched, nil
	}
	data, err := os.ReadFile(activePath)
	if err != nil || len(bytes.TrimSpace(data)) == 0 {
		return "", err
	}
	name := preferred
	if name == "" || snap.taken[name] {
		name = FreeName(snap.taken)
	}
	if err := accounts.WriteFileAtomic(tokenSlotPath(h.Home, name), data, 0o600); err != nil {
		return "", err
	}
	snap.taken[name] = true
	snap.slots[name] = fp
	if snap.ds.Accounts == nil {
		snap.ds.Accounts = map[string]savedMeta{}
	}
	if _, exists := snap.ds.Accounts[name]; !exists {
		snap.ds.Accounts[name] = savedMeta{DisplayName: "Google OAuth"}
	}
	snap.ds.Current = name
	if err := saveDiskState(h.Home, snap.ds); err != nil {
		return "", err
	}
	return name, nil
}

// Switch 切换到已保存的账号或中转 name。
func (h *Host) Switch(ctx context.Context, name string) error {
	if name == "" {
		return accounts.ErrMissing
	}
	changing.Lock()
	defer changing.Unlock()
	if err := h.waitAndCheckIdle(ctx); err != nil {
		return err
	}
	return withFileLock(h.Home, func() error {
		snap := h.readStateLocked()
		_, isAcct := snap.slots[name]
		relayIdx := slices.IndexFunc(snap.Relays, func(r accounts.Relay) bool { return r.Name == name })
		if !isAcct && relayIdx < 0 {
			return accounts.ErrMissing
		}
		if _, err := h.archiveActiveTokenLocked(&snap, ""); err != nil {
			return err
		}
		if isAcct {
			return h.activateAccountLocked(name, &snap.ds)
		}
		return h.activateRelayLocked(snap.Relays[relayIdx], &snap.ds)
	})
}

func (h *Host) activateAccountLocked(name string, ds *diskState) error {
	data, err := os.ReadFile(tokenSlotPath(h.Home, name))
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return accounts.ErrMissing
		}
		return err
	}
	if err := accounts.WriteFileAtomic(oauthTokenPath(h.Home), data, 0o600); err != nil {
		return err
	}
	if err := clearRelaySettings(h.Home, ds.RelaySetMode, ds.RelaySetModel, ds.RelayPrevModel); err != nil {
		return err
	}
	ds.Current = name
	ds.CurrentRelay = ""
	ds.PendingLogin = ""
	ds.PrevAccount = ""
	ds.PrevRelay = ""
	ds.RelaySetMode = false
	ds.RelaySetModel = false
	ds.RelayPrevModel = ""
	if err := saveDiskState(h.Home, *ds); err != nil {
		return err
	}
	syncQuotaCache(h.Home)
	return nil
}

func (h *Host) activateRelayLocked(r accounts.Relay, ds *diskState) error {
	if !accounts.Removed(oauthTokenPath(h.Home)) {
		return locales.Err("errRelayWrite", "path", oauthTokenPath(h.Home))
	}
	prevModel, setModel, err := enableRelaySettings(h.Home, r.Model, !ds.RelaySetModel)
	if err != nil {
		return err
	}
	ds.Current = ""
	ds.CurrentRelay = r.Name
	ds.PendingLogin = ""
	ds.PrevAccount = ""
	ds.PrevRelay = ""
	ds.RelaySetMode = true
	if setModel {
		if !ds.RelaySetModel {
			ds.RelayPrevModel = prevModel
		}
		ds.RelaySetModel = true
	}
	if err := saveDiskState(h.Home, *ds); err != nil {
		return err
	}
	syncQuotaCache(h.Home)
	return nil
}

// PrepareLogin 为登录新 Google OAuth 账号做准备：归档在用令牌、移走活跃令牌、清除中转，返回新账号将使用的名字。
func (h *Host) PrepareLogin(ctx context.Context) (string, error) {
	changing.Lock()
	defer changing.Unlock()
	if err := h.waitAndCheckIdle(ctx); err != nil {
		return "", err
	}
	var name string
	err := withFileLock(h.Home, func() error {
		snap := h.readStateLocked()
		archived, err := h.archiveActiveTokenLocked(&snap, "")
		if err != nil {
			return err
		}
		prevAcct := cmp.Or(archived, snap.ds.Current)
		prevRelay := activeRelayFromState(h.Home, snap.ds)
		if !accounts.Removed(oauthTokenPath(h.Home)) {
			return locales.Err("errRelayWrite", "path", oauthTokenPath(h.Home))
		}
		if err := clearRelaySettings(h.Home, snap.ds.RelaySetMode, snap.ds.RelaySetModel, snap.ds.RelayPrevModel); err != nil {
			return err
		}
		name = FreeName(snap.taken)
		snap.ds.Current = ""
		snap.ds.CurrentRelay = ""
		snap.ds.PendingLogin = name
		snap.ds.PrevAccount = prevAcct
		snap.ds.PrevRelay = prevRelay
		snap.ds.RelaySetMode = false
		snap.ds.RelaySetModel = false
		snap.ds.RelayPrevModel = ""
		if err := saveDiskState(h.Home, snap.ds); err != nil {
			return err
		}
		syncQuotaCache(h.Home)
		return nil
	})
	return name, err
}

// restorePendingLogin 在登录取消或失败且未生成新令牌时，恢复登录前活跃的账号或中转。
func (h *Host) restorePendingLogin() {
	_ = withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		if snap.ds.PendingLogin == "" {
			return nil
		}
		if _, hasLive := fileFingerprint(oauthTokenPath(h.Home)); hasLive {
			return nil
		}
		prevAcct, prevRelay := snap.ds.PrevAccount, snap.ds.PrevRelay
		snap.ds.PendingLogin = ""
		snap.ds.PrevAccount = ""
		snap.ds.PrevRelay = ""
		if prevRelay != "" {
			if idx := slices.IndexFunc(snap.Relays, func(r accounts.Relay) bool { return r.Name == prevRelay }); idx >= 0 {
				return h.activateRelayLocked(snap.Relays[idx], &snap.ds)
			}
		}
		if prevAcct != "" {
			if _, ok := snap.slots[prevAcct]; ok {
				return h.activateAccountLocked(prevAcct, &snap.ds)
			}
		}
		return saveDiskState(h.Home, snap.ds)
	})
}

// Logout 退出当前活跃的 agy 登录或中转，移除活跃令牌并恢复中转前的配置。
func (h *Host) Logout(ctx context.Context) error {
	changing.Lock()
	defer changing.Unlock()
	if err := h.waitAndCheckIdle(ctx); err != nil {
		return err
	}
	return withFileLock(h.Home, func() error {
		snap := h.readStateLocked()
		if !accounts.Removed(oauthTokenPath(h.Home)) {
			return locales.Err("errRelayWrite", "path", oauthTokenPath(h.Home))
		}
		if err := clearRelaySettings(h.Home, snap.ds.RelaySetMode, snap.ds.RelaySetModel, snap.ds.RelayPrevModel); err != nil {
			return err
		}
		snap.ds.Current = ""
		snap.ds.CurrentRelay = ""
		snap.ds.PendingLogin = ""
		snap.ds.PrevAccount = ""
		snap.ds.PrevRelay = ""
		snap.ds.RelaySetMode = false
		snap.ds.RelaySetModel = false
		snap.ds.RelayPrevModel = ""
		if err := saveDiskState(h.Home, snap.ds); err != nil {
			return err
		}
		syncQuotaCache(h.Home)
		return nil
	})
}

// Remove 删除一个不在使用中的已保存账号。
func (h *Host) Remove(_ context.Context, name string) error {
	if name == "" {
		return accounts.ErrMissing
	}
	return withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		if _, ok := snap.slots[name]; !ok {
			return accounts.ErrMissing
		}
		if snap.Current != nil && snap.Current.Name == name {
			return locales.Err("errAgyAccountInUse")
		}
		slot := tokenSlotPath(h.Home, name)
		if !accounts.Removed(slot) {
			return locales.Err("errRelayWrite", "path", slot)
		}
		delete(snap.ds.Accounts, name)
		if snap.ds.Current == name {
			snap.ds.Current = ""
		}
		return saveDiskState(h.Home, snap.ds)
	})
}

func (h *Host) finishLogin(preferred string) (string, error) {
	var name string
	err := withStateLock(h.Home, func() error {
		snap := h.readStateLocked()
		archived, err := h.archiveActiveTokenLocked(&snap, cmp.Or(preferred, snap.ds.PendingLogin))
		if err != nil {
			return err
		}
		if archived == "" {
			return locales.Err("errLoginFailed")
		}
		snap.ds.Current = archived
		snap.ds.CurrentRelay = ""
		snap.ds.PendingLogin = ""
		snap.ds.PrevAccount = ""
		snap.ds.PrevRelay = ""
		if err := saveDiskState(h.Home, snap.ds); err != nil {
			return err
		}
		name = archived
		return nil
	})
	return name, err
}

// ---------- 进程检测与换号阻碍 ----------

type procID struct {
	pid   int
	start string
}

func (h *Host) waitAndCheckIdle(ctx context.Context) error {
	var mine []procID
	for _, p := range h.procs() {
		if start, ok := procStart(p.PID); ok && p.Owner == h.Package {
			mine = append(mine, procID{p.PID, start})
		}
	}
	if h.EndIdle != nil {
		h.EndIdle()
	}
	wait := cmp.Or(h.Wait, 8*time.Second)
	deadline := time.Now().Add(wait)
	for worthWaiting(h.procs(), mine, h.Package) && time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
	procs := h.procs()
	if len(procs) == 0 {
		return nil
	}
	return &accounts.BusyError{
		Blocker: agyBlocker(procs, h.Package),
		Own:     !slices.ContainsFunc(procs, func(p accounts.Proc) bool { return p.Owner != h.Package }),
	}
}

func worthWaiting(procs []accounts.Proc, before []procID, pkg string) bool {
	if slices.ContainsFunc(procs, func(p accounts.Proc) bool { return p.Owner != pkg }) {
		return false
	}
	return len(procs) > 0 || slices.ContainsFunc(before, procAlive)
}

func procAlive(p procID) bool {
	start, state, ok := readProcStat(p.pid)
	return ok && start == p.start && state != 'Z'
}

func (h *Host) procs() []accounts.Proc {
	seen := map[int]bool{}
	var out []accounts.Proc
	agyDir := AgyDir(h.Home)
	for _, holder := range agystore.ActiveHolders(agyDir, "") {
		if seen[holder.PID] {
			continue
		}
		seen[holder.PID] = true
		status := "idle"
		if agystore.SessionRunStatus(agyDir, holder.Session) == sessionproto.StatusBusy {
			status = "busy"
		}
		out = append(out, accounts.Proc{
			PID:    holder.PID,
			Owner:  ownerFromMark(holder.HostMark),
			Status: status,
			Kind:   holder.Entrypoint,
		})
	}
	for _, p := range scanAgyBridgeProcs(h.Home) {
		if !seen[p.PID] {
			seen[p.PID] = true
			out = append(out, p)
		}
	}
	return out
}

func scanAgyBridgeProcs(home string) []accounts.Proc {
	cleanHome := filepath.Clean(home)
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	self := os.Getpid()
	var out []accounts.Proc
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid <= 1 || pid == self {
			continue
		}
		if p, ok := inspectAgyProc(pid, cleanHome); ok {
			out = append(out, p)
		}
	}
	return out
}

func inspectAgyProc(pid int, cleanHome string) (accounts.Proc, bool) {
	if _, state, ok := readProcStat(pid); !ok || state == 'Z' {
		return accounts.Proc{}, false
	}
	argv, ok := readCmdline(pid)
	if !ok || !isAgySessionCmdline(argv) {
		return accounts.Proc{}, false
	}
	pHome, mark := readHomeAndMark(pid)
	if pHome == "" || filepath.Clean(pHome) != cleanHome {
		return accounts.Proc{}, false
	}
	return accounts.Proc{PID: pid, Owner: ownerFromMark(mark), Status: "idle", Kind: "agy"}, true
}

func isAgySessionCmdline(argv []string) bool { return agystore.IsAgySessionCmdline(argv) }

func agyBlocker(procs []accounts.Proc, mine string) locales.Msg {
	return accounts.BlockerFor(procs, mine,
		func(apps []locales.Msg, count, busy, others, othersBusy int) locales.Msg {
			return locales.M("errAgyBlockedBoth", "apps", apps, "count", count, "busy", busy, "others", others, "othersBusy", othersBusy)
		},
		func(others, othersBusy int) locales.Msg {
			return locales.M("errAgyBlockedElsewhere", "others", others, "othersBusy", othersBusy)
		},
	)
}

func ownerFromMark(mark string) string { return accounts.OwnerFromMark(mark) }

func procStart(pid int) (string, bool) {
	s, _, ok := agystore.ReadProcStat(pid)
	return s, ok
}

func readProcStat(pid int) (start string, state byte, ok bool) { return agystore.ReadProcStat(pid) }

func readCmdline(pid int) ([]string, bool) {
	out := agystore.ReadCmdline(pid)
	return out, len(out) > 0
}

func readHomeAndMark(pid int) (home, mark string) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/environ")
	if err != nil {
		return "", ""
	}
	for _, item := range bytes.Split(data, []byte{0}) {
		switch {
		case bytes.HasPrefix(item, []byte("HOME=")):
			home = string(item[len("HOME="):])
		case bytes.HasPrefix(item, []byte("ZETA_HOST=")):
			mark = string(item[len("ZETA_HOST="):])
		}
	}
	return home, mark
}

// ---------- 文件与哈希辅助 ----------

func fileFingerprint(path string) (string, bool) {
	fi, err := os.Stat(path)
	if err != nil || !fi.Mode().IsRegular() || fi.Size() == 0 {
		return "", false
	}
	f, err := os.Open(path)
	if err != nil {
		return "", false
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil || n == 0 {
		return "", false
	}
	var buf []byte
	return hex.EncodeToString(h.Sum(buf)), true
}

func readTokenSlots(home string) map[string]string {
	entries, err := os.ReadDir(tokensDir(home))
	if err != nil {
		return map[string]string{}
	}
	slots := map[string]string{}
	for _, e := range entries {
		name, ok := strings.CutSuffix(e.Name(), ".token")
		if !ok || !validAccountName(name) {
			continue
		}
		if fp, ok := fileFingerprint(filepath.Join(tokensDir(home), e.Name())); ok {
			slots[name] = fp
		}
	}
	return slots
}

func loadDiskState(home string) diskState {
	var ds diskState
	data, err := os.ReadFile(stateFilePath(home))
	if err == nil {
		_ = json.Unmarshal(data, &ds)
	}
	if ds.Accounts == nil {
		ds.Accounts = map[string]savedMeta{}
	}
	return ds
}

func saveDiskState(home string, ds diskState) error {
	data, err := json.MarshalIndent(ds, "", "  ")
	if err != nil {
		return err
	}
	return accounts.WriteFileAtomic(stateFilePath(home), append(data, '\n'), 0o600)
}

func lockState(home string) (func(), error) {
	path := filepath.Join(stateDir(home), "state.lock")
	writeErr := locales.Err("errRelayWrite", "path", path)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, writeErr
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, writeErr
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX); err != nil {
		_ = f.Close()
		return nil, writeErr
	}
	return func() { _ = f.Close() }, nil
}
