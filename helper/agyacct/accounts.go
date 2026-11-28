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
)

const (
	// StateRelDir 是 agy 账号与中转状态目录（相对家目录）。
	StateRelDir = ".local/state/zeta-agy-accounts"
	builtin     = "agy"
)

var (
	changing    sync.Mutex
	accountName = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$`)
	hostMarkRe  = regexp.MustCompile(`^ZETA_HOST=(zeta[^/]*)/[^/.][^/]*$`)
)

// AgyDir 返回家目录 home 下的 Antigravity CLI 数据目录；设了 ZETA_AGY_DIR 时优先用它（供单测隔离）。
func AgyDir(home string) string {
	if d := strings.TrimSpace(os.Getenv("ZETA_AGY_DIR")); d != "" {
		return filepath.Clean(d)
	}
	return filepath.Join(home, ".gemini", "antigravity-cli")
}

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
	Current      string               `json:"current,omitempty"`
	CurrentRelay string               `json:"currentRelay,omitempty"`
	PendingLogin string               `json:"pendingLogin,omitempty"`
	RelaySetMode bool                 `json:"relaySetMode,omitempty"`
	Accounts     map[string]savedMeta `json:"accounts,omitempty"`
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
func FreeName(taken map[string]bool) string {
	for i := 0; ; i++ {
		var name string
		switch {
		case i == 0:
			name = builtin
		case i < 26:
			name = "agy-" + string(rune('a'+i))
		default:
			name = "agy-" + strconv.Itoa(i+1)
		}
		if !taken[name] {
			return name
		}
	}
}

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
			matched := matchSlot(fp, ds.Current, slots)
			if matched != "" {
				live := makeAccount(matched, ds.Accounts[matched], true, true)
				cur = &live
			} else {
				live := accounts.Account{
					Name:         "",
					DisplayName:  "Google OAuth",
					Subscription: "antigravity",
					Plan:         "Antigravity",
					Current:      true,
				}
				cur = &live
			}
		}
	}
	var saved []accounts.Account
	for name := range slots {
		if cur != nil && cur.Name == name {
			continue
		}
		saved = append(saved, makeAccount(name, ds.Accounts[name], false, true))
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

func makeAccount(name string, meta savedMeta, current, parked bool) accounts.Account {
	return accounts.Account{
		Name:         name,
		Email:        meta.Email,
		DisplayName:  cmp.Or(meta.DisplayName, "Google OAuth"),
		Subscription: "antigravity",
		Plan:         "Antigravity",
		Current:      current,
		Parked:       parked,
	}
}

// Save 将当前未命名的 Google OAuth 登录归档为一个新账号名（agy、agy-b…）。未登录时返回空串。
func (h *Host) Save(_ context.Context) (string, error) {
	changing.Lock()
	defer changing.Unlock()
	unlock, err := lockState(h.Home)
	if err != nil {
		return "", err
	}
	defer unlock()
	snap := h.readStateLocked()
	if snap.Current == nil {
		return "", nil
	}
	if snap.Current.Name != "" {
		return snap.Current.Name, nil
	}
	return h.archiveActiveTokenLocked(&snap)
}

func (h *Host) archiveActiveTokenLocked(snap *stateSnapshot) (string, error) {
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
	name := FreeName(snap.taken)
	if err := writeFileAtomic(tokenSlotPath(h.Home, name), data, 0o600); err != nil {
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
	unlock, err := lockState(h.Home)
	if err != nil {
		return err
	}
	defer unlock()
	snap := h.readStateLocked()
	_, isAcct := snap.slots[name]
	relayIdx := slices.IndexFunc(snap.Relays, func(r accounts.Relay) bool { return r.Name == name })
	if !isAcct && relayIdx < 0 {
		return accounts.ErrMissing
	}
	if _, err := h.archiveActiveTokenLocked(&snap); err != nil {
		return err
	}
	if isAcct {
		return h.activateAccountLocked(name, &snap.ds)
	}
	return h.activateRelayLocked(snap.Relays[relayIdx], &snap.ds)
}

func (h *Host) activateAccountLocked(name string, ds *diskState) error {
	data, err := os.ReadFile(tokenSlotPath(h.Home, name))
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return accounts.ErrMissing
		}
		return err
	}
	if err := writeFileAtomic(oauthTokenPath(h.Home), data, 0o600); err != nil {
		return err
	}
	if err := syncAgySettingsForRelay(h.Home, nil, ds.RelaySetMode); err != nil {
		return err
	}
	ds.Current = name
	ds.CurrentRelay = ""
	ds.PendingLogin = ""
	ds.RelaySetMode = false
	if err := saveDiskState(h.Home, *ds); err != nil {
		return err
	}
	syncQuotaCache(h.Home)
	return nil
}

func (h *Host) activateRelayLocked(r accounts.Relay, ds *diskState) error {
	if !removed(oauthTokenPath(h.Home)) {
		return locales.Err("errRelayWrite", "path", oauthTokenPath(h.Home))
	}
	if err := syncAgySettingsForRelay(h.Home, &r, true); err != nil {
		return err
	}
	ds.Current = ""
	ds.CurrentRelay = r.Name
	ds.PendingLogin = ""
	ds.RelaySetMode = true
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
	unlock, err := lockState(h.Home)
	if err != nil {
		return "", err
	}
	defer unlock()
	snap := h.readStateLocked()
	if _, err := h.archiveActiveTokenLocked(&snap); err != nil {
		return "", err
	}
	if !removed(oauthTokenPath(h.Home)) {
		return "", locales.Err("errRelayWrite", "path", oauthTokenPath(h.Home))
	}
	if err := syncAgySettingsForRelay(h.Home, nil, snap.ds.RelaySetMode); err != nil {
		return "", err
	}
	name := FreeName(snap.taken)
	snap.ds.Current = ""
	snap.ds.CurrentRelay = ""
	snap.ds.PendingLogin = name
	snap.ds.RelaySetMode = false
	if err := saveDiskState(h.Home, snap.ds); err != nil {
		return "", err
	}
	syncQuotaCache(h.Home)
	return name, nil
}

// Remove 删除一个不在使用中的已保存账号。
func (h *Host) Remove(_ context.Context, name string) error {
	if name == "" {
		return accounts.ErrMissing
	}
	changing.Lock()
	defer changing.Unlock()
	unlock, err := lockState(h.Home)
	if err != nil {
		return err
	}
	defer unlock()
	snap := h.readStateLocked()
	if _, ok := snap.slots[name]; !ok {
		return accounts.ErrMissing
	}
	if snap.Current != nil && snap.Current.Name == name {
		return locales.Err("errAgyAccountInUse")
	}
	slot := tokenSlotPath(h.Home, name)
	if !removed(slot) {
		return locales.Err("errRelayWrite", "path", slot)
	}
	delete(snap.ds.Accounts, name)
	if snap.ds.Current == name {
		snap.ds.Current = ""
	}
	return saveDiskState(h.Home, snap.ds)
}

func (h *Host) finishLogin(preferred string) (string, error) {
	changing.Lock()
	defer changing.Unlock()
	unlock, err := lockState(h.Home)
	if err != nil {
		return "", err
	}
	defer unlock()
	snap := h.readStateLocked()
	activePath := oauthTokenPath(h.Home)
	fp, ok := fileFingerprint(activePath)
	if !ok {
		return "", locales.Err("errLoginFailed")
	}
	if matched := matchSlot(fp, snap.ds.Current, snap.slots); matched != "" {
		snap.ds.Current = matched
		snap.ds.CurrentRelay = ""
		snap.ds.PendingLogin = ""
		_ = saveDiskState(h.Home, snap.ds)
		return matched, nil
	}
	data, err := os.ReadFile(activePath)
	if err != nil || len(bytes.TrimSpace(data)) == 0 {
		return "", locales.Err("errLoginFailed")
	}
	name := cmp.Or(preferred, snap.ds.PendingLogin)
	if name == "" || snap.taken[name] {
		name = FreeName(snap.taken)
	}
	if err := writeFileAtomic(tokenSlotPath(h.Home, name), data, 0o600); err != nil {
		return "", err
	}
	if snap.ds.Accounts == nil {
		snap.ds.Accounts = map[string]savedMeta{}
	}
	snap.ds.Accounts[name] = savedMeta{DisplayName: "Google OAuth"}
	snap.ds.Current = name
	snap.ds.CurrentRelay = ""
	snap.ds.PendingLogin = ""
	if err := saveDiskState(h.Home, snap.ds); err != nil {
		return "", err
	}
	return name, nil
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
	for _, holder := range agystore.ActiveHolders(AgyDir(h.Home), "") {
		if seen[holder.PID] {
			continue
		}
		seen[holder.PID] = true
		out = append(out, accounts.Proc{
			PID:    holder.PID,
			Owner:  ownerFromMark(holder.HostMark),
			Status: "idle",
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

func isAgySessionCmdline(argv []string) bool {
	if len(argv) == 0 {
		return false
	}
	if len(argv) >= 2 && argv[1] == "agy-bridge" {
		return true
	}
	base := filepath.Base(argv[0])
	if base != "agy" {
		return false
	}
	if len(argv) >= 2 {
		switch argv[1] {
		case "mcp", "models", "update", "--version", "-v", "--help", "-h":
			return false
		case "acp":
			return !slices.Contains(argv[2:], "--login")
		}
	}
	return !slices.Contains(argv[1:], "-p")
}

func agyBlocker(procs []accounts.Proc, mine string) locales.Msg {
	var zeta, elsewhere []accounts.Proc
	for _, p := range procs {
		switch {
		case p.Owner == mine:
		case p.Owner != "":
			zeta = append(zeta, p)
		default:
			elsewhere = append(elsewhere, p)
		}
	}
	var names []locales.Msg
	var owners []string
	for _, p := range zeta {
		if !slices.Contains(owners, p.Owner) {
			owners = append(owners, p.Owner)
			names = append(names, zetaName(p.Owner))
		}
	}
	switch {
	case len(zeta) > 0 && len(elsewhere) > 0:
		return locales.M("errAgyBlockedBoth", "apps", names, "count", len(zeta), "busy", busyCount(zeta),
			"others", len(elsewhere), "othersBusy", busyCount(elsewhere))
	case len(zeta) > 0:
		return locales.M("errSwitchBlockedZeta", "apps", names, "count", len(zeta), "busy", busyCount(zeta))
	case len(elsewhere) > 0:
		return locales.M("errAgyBlockedElsewhere", "others", len(elsewhere), "othersBusy", busyCount(elsewhere))
	}
	return locales.Msg{}
}

func busyCount(procs []accounts.Proc) int {
	n := 0
	for _, p := range procs {
		if p.Busy() {
			n++
		}
	}
	return n
}

func zetaName(owner string) locales.Msg {
	switch {
	case owner == "zeta":
		return locales.M("zetaRelease")
	case strings.HasPrefix(owner, "zeta-dev"):
		return locales.M("zetaDev", "suffix", strings.TrimPrefix(owner, "zeta-dev"))
	}
	return locales.M("zetaOther", "name", owner)
}

func ownerFromMark(mark string) string {
	if mark == "" {
		return ""
	}
	if m := hostMarkRe.FindStringSubmatch("ZETA_HOST=" + mark); m != nil {
		return m[1]
	}
	return ""
}

func procStart(pid int) (string, bool) {
	s, _, ok := readProcStat(pid)
	return s, ok
}

func readProcStat(pid int) (start string, state byte, ok bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return "", 0, false
	}
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return "", 0, false
	}
	fields := strings.Fields(string(data[i+1:]))
	if len(fields) < 20 {
		return "", 0, false
	}
	return fields[19], fields[0][0], true
}

func readCmdline(pid int) ([]string, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil || len(data) == 0 {
		return nil, false
	}
	var out []string
	for _, part := range bytes.Split(bytes.TrimRight(data, "\x00"), []byte{0}) {
		out = append(out, string(part))
	}
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
	return writeFileAtomic(stateFilePath(home), append(data, '\n'), 0o600)
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

func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(mode); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

func removed(path string) bool {
	err := os.Remove(path)
	return err == nil || errors.Is(err, fs.ErrNotExist)
}
