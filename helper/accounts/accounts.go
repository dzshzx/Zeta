// Package accounts 是一台主机上的 Claude 账号与订阅额度，zeta-helper serve 的 accounts.*、quota.* 方法用它（见 docs/core.md
// 「账号与额度」）。
//
// 账号由 claude-switch 管（开源程序 github.com/dzshzx/claude-switch，Zeta 带着它、按内容哈希装在 ~/.cache/<包>/bin）：存档、
// 换号、加号、删号，有 Claude Code 进程在跑时拒绝，都是它的事。这里直接跑它、把输出和退出码换成结果，另外按
// ~/.claude/sessions 的登记和进程身上的 ZETA_HOST 列出开着的 Claude Code 归哪个 Zeta 包，好说清是谁挡着。
// claude-switch 的输出里没有令牌；这里也不读凭据文件。
//
// 额度都是 Claude Code 给的（get_usage 的应答、对话里的 rate_limit_event），由对话核心交来，存在 QuotaStore；这里不碰令牌、
// 不自己调接口。
package accounts

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Account 是 claude-switch 记下的一个账号：身份和套餐，没有令牌。字段照 `claude-switch --json`。
type Account struct {
	// Name 是 claude-switch 的账号名（claude、claude-b…）；在用的登录还没记过、认不出是哪个号时为空。
	Name         string `json:"name"`
	Email        string `json:"email,omitempty"`
	Organization string `json:"organization,omitempty"`
	DisplayName  string `json:"displayName,omitempty"`
	// Subscription 是订阅类型（pro、max、team…）。
	Subscription string `json:"subscription,omitempty"`
	// RefreshExpiresAt 是续期凭证的到期时刻（Unix 毫秒），过了只能重新登录；不知道时为 0。
	RefreshExpiresAt int64 `json:"refreshExpiresAt,omitempty"`
	Current          bool  `json:"current"`
	Parked           bool  `json:"parked"`
}

// Title 是给人看的名字：邮箱，没有时是显示名，再没有是账号名。
func (a Account) Title() string {
	for _, s := range []string{a.Email, a.DisplayName} {
		if strings.TrimSpace(s) != "" {
			return s
		}
	}
	return a.Name
}

// Proc 是一个还活着的 Claude Code 进程（~/.claude/sessions 里登记的）。Owner 是托管它的 Zeta 包（zeta、zeta-dev…），别处起的为空。
type Proc struct {
	PID    int    `json:"pid"`
	Owner  string `json:"owner"`
	Status string `json:"status"`
	Kind   string `json:"kind"`
}

// Busy 报告它在忙（不是闲着）。
func (p Proc) Busy() bool { return p.Status != "" && p.Status != "idle" }

// State 是一台主机上的账号，也是 accounts.list 的结果。
type State struct {
	// Current 是在用的登录，未登录时为 nil；认不出是哪个号时 Name 为空。
	Current *Account `json:"current"`
	// Accounts 是界面列的账号：在用的在前，再是存着的其他账号，按名字排。
	Accounts []Account `json:"accounts"`
	// Procs 是开着的 Claude Code 进程。
	Procs []Proc `json:"procs"`
	// Blocker 说明别的 Claude Code 挡着换号（见 Blocker），没有时为空。
	Blocker string `json:"blocker,omitempty"`
	// taken 是新账号不能用的名字：列出的账号、非账号目标和切换记录里的名字。
	taken map[string]bool
}

// Host 是一台主机上的 claude-switch。方法都阻塞（跑 claude-switch、换号前等进程退出），可以并发调用：改登录状态的几个
// 一个一个来。
type Host struct {
	// Home 是家目录。
	Home string
	// Package 是调用方所属的 Zeta 包（ZETA_NAME）：它自己的对话由它先收掉，不算阻碍。
	Package string
	// Program 是 claude-switch 的路径（Find）。
	Program string
	// Wait 是换号前等开着的 Claude Code 退出最多多久（调用方刚叫停的自己的对话要几秒）；0 是 8 秒。
	Wait time.Duration
	// EndIdle 在换号、存档前结束本包闲着的托管进程（它们平常留给看门狗按闲置超时收，开着时 claude-switch 不换）；可以是 nil。
	EndIdle func()
}

// ErrNoProgram 是 claude-switch 还没装上：调用方装上（同 zeta-helper，按内容哈希装在 ~/.cache/<包>/bin）再试。
var ErrNoProgram = errors.New("claude-switch 还没装上")

// Find 找装好的 claude-switch：rel 不空时是它相对家目录的路径（客户端知道自己带的版本）；空时在 ~/.cache/<pkg>/bin 下
// 找 claude-switch-<版本>（装新版时会删掉旧的，所以通常只有一个；有几个时取最新的）。都没有时是 ErrNoProgram。
func Find(home, pkg, rel string) (string, error) {
	if rel != "" {
		path := filepath.Join(home, rel)
		if !executable(path) {
			return "", ErrNoProgram
		}
		return path, nil
	}
	found, _ := filepath.Glob(filepath.Join(home, ".cache", pkg, "bin", "claude-switch-*"))
	var best string
	var bestTime time.Time
	for _, path := range found {
		info, err := os.Stat(path)
		if err != nil || !executable(path) {
			continue
		}
		if best == "" || info.ModTime().After(bestTime) {
			best, bestTime = path, info.ModTime()
		}
	}
	if best == "" {
		return "", ErrNoProgram
	}
	return best, nil
}

func executable(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Mode()&0o111 != 0
}

// changing 让改登录状态的命令一个一个来（同一个 serve 里几个请求并发时）。
var changing sync.Mutex

// result 是 claude-switch 一次运行的结果。
type result struct {
	code           int
	stdout, stderr []byte
}

const (
	busyMark    = "还有 Claude Code 进程在跑"
	missingMark = "没有叫"
	prefix      = "claude-switch: "
)

// run 跑 `claude-switch <args…>`。起不来时返回错误；以非 0 退出不算错误，在 result 里。
func (h *Host) run(ctx context.Context, args ...string) (result, error) {
	if h.Program == "" || !executable(h.Program) {
		return result{}, ErrNoProgram
	}
	cmd := exec.CommandContext(ctx, h.Program, args...)
	cmd.Env = append(os.Environ(), "HOME="+h.Home)
	cmd.Dir = h.Home
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	var exit *exec.ExitError
	if err != nil && !errors.As(err, &exit) {
		return result{}, fmt.Errorf("跑不了 claude-switch：%w", err)
	}
	code := 0
	if exit != nil {
		code = exit.ExitCode()
	}
	return result{code: code, stdout: stdout.Bytes(), stderr: stderr.Bytes()}, nil
}

// message 是 claude-switch 的提示去掉前缀；没有提示时说退出码。
func (r result) message() string {
	m := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(string(r.stderr)), prefix))
	if m == "" {
		return fmt.Sprintf("没有成功（claude-switch 退出码 %d）", r.code)
	}
	return m
}

// BusyError 是还有 Claude Code 进程开着、claude-switch 没有动。
type BusyError struct {
	// Blocker 是能认出的挡着的进程（Blocker），Message 是 claude-switch 的原话（列着它看到的进程）。
	Blocker string
	Message string
	Own     bool // 只剩调用方自己的对话进程还没退出
}

func (e *BusyError) Error() string {
	switch {
	case e.Blocker != "":
		return e.Blocker
	case e.Own || e.Message == "":
		return "这里的对话进程还没退出"
	}
	return e.Message
}

// ErrMissing 是要换、要删的号已经不在了。
var ErrMissing = errors.New("这个账号已经不在了")

// err 把这次运行的结果换成错误：成功是 nil，被进程挡着是 *BusyError，号不在了是 ErrMissing。
func (h *Host) err(r result) error {
	switch {
	case r.code == 0:
		return nil
	case r.code == 1 && strings.Contains(string(r.stderr), busyMark):
		procs := h.procs()
		return &BusyError{Blocker: Blocker(procs, h.Package), Message: r.message(), Own: len(procs) > 0}
	case r.code == 2 && strings.Contains(string(r.stderr), missingMark):
		return ErrMissing
	}
	return errors.New(r.message())
}

// switchStatus 是 `claude-switch --json` 的输出。
type switchStatus struct {
	Current  string    `json:"current"`
	Live     *Account  `json:"live"`
	Accounts []Account `json:"accounts"`
	Targets  []struct {
		Name string `json:"name"`
	} `json:"targets"`
}

// parseStatus 把 `claude-switch --json` 的输出读成账号状态。
func parseStatus(out []byte) (State, error) {
	var st switchStatus
	if err := json.Unmarshal(out, &st); err != nil {
		return State{}, errors.New("读不懂 claude-switch 的输出")
	}
	state := State{Current: st.Live, Accounts: []Account{}, Procs: []Proc{}, taken: map[string]bool{}}
	var saved []Account
	for _, a := range st.Accounts {
		state.taken[a.Name] = true
		// 切换记录里刚 add、还没登录的号既不在用也没有存档，不列；在用的单独放在最前
		if a.Name != "" && (a.Parked || a.Current) && (st.Live == nil || a.Name != st.Live.Name) {
			saved = append(saved, a)
		}
	}
	for _, t := range st.Targets {
		state.taken[t.Name] = true
	}
	state.taken[st.Current] = true
	delete(state.taken, "")
	slices.SortStableFunc(saved, func(a, b Account) int {
		return strings.Compare(strings.ToLower(a.Title()), strings.ToLower(b.Title()))
	})
	if st.Live != nil {
		state.Accounts = append(state.Accounts, *st.Live)
	}
	state.Accounts = append(state.Accounts, saved...)
	return state, nil
}

// List 读这台主机上的账号和开着的 Claude Code 进程。
func (h *Host) List(ctx context.Context) (State, error) {
	procs := h.procs()
	r, err := h.run(ctx, "--json")
	if err != nil {
		return State{}, err
	}
	if r.code != 0 {
		return State{}, errors.New(r.message())
	}
	st, err := parseStatus(r.stdout)
	if err != nil {
		return State{}, err
	}
	if procs != nil {
		st.Procs = procs
	}
	st.Blocker = Blocker(procs, h.Package)
	return st, nil
}

// Switch 换成存着的 name。没换成时是 *BusyError、ErrMissing 或带 claude-switch 原话的错误。
func (h *Host) Switch(ctx context.Context, name string) error {
	if name == "" {
		return ErrMissing
	}
	changing.Lock()
	defer changing.Unlock()
	return h.change(ctx, name)
}

// Save 给还没记过（认不出是哪个号）的在用登录挑个空闲的名字记下（`claude-switch add`，不改登录）。返回在用的账号名；
// 没登录时为空。
func (h *Host) Save(ctx context.Context) (string, error) {
	changing.Lock()
	defer changing.Unlock()
	st, err := h.List(ctx)
	if err != nil || st.Current == nil {
		return "", err
	}
	if st.Current.Name != "" {
		return st.Current.Name, nil
	}
	name := FreeName(withNames(st.taken, builtin))
	return name, h.change(ctx, "add", name)
}

// PrepareLogin 为登录另一个号做准备：挑个空闲的名字，把在用的号存档并退出登录，登录后的新号由 claude-switch 下次运行时
// 记成这个名字（固定的 claude 用 `claude-switch claude`，别的用 `claude-switch add <名字>`）。在用的登录还没记过时先 add
// 一个名字把它记下，不丢它的令牌。返回新号的名字；没登录时什么都不做，返回空。
func (h *Host) PrepareLogin(ctx context.Context) (string, error) {
	changing.Lock()
	defer changing.Unlock()
	st, err := h.List(ctx)
	if err != nil || st.Current == nil {
		return "", err
	}
	taken := withNames(st.taken)
	if st.Current.Name == "" {
		name := FreeName(withNames(taken, builtin))
		if err := h.change(ctx, "add", name); err != nil {
			return "", err
		}
		taken[name] = true
	}
	name := FreeName(taken)
	if name == builtin {
		return name, h.change(ctx, name)
	}
	return name, h.change(ctx, "add", name)
}

// Remove 删掉一个不在用的号的存档与记录（只删这台主机上的这一份，不注销那个账号）。
func (h *Host) Remove(ctx context.Context, name string) error {
	changing.Lock()
	defer changing.Unlock()
	r, err := h.run(ctx, "remove", name)
	if err != nil {
		return err
	}
	return h.err(r)
}

// change 跑一个改登录状态的 claude-switch 命令：开着的 Claude Code 都是调用方自己的（它刚叫停、几秒内会退出）时先等它们退出
// （最多 Wait）；有别人的就不等，那些不会自己退。不管等没等到都交给 claude-switch，它自己会拒绝。
func (h *Host) change(ctx context.Context, args ...string) error {
	if h.EndIdle != nil {
		h.EndIdle()
	}
	wait := h.Wait
	if wait == 0 {
		wait = 8 * time.Second
	}
	deadline := time.Now().Add(wait)
	for onlyMine(h.procs(), h.Package) && time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
	r, err := h.run(ctx, args...)
	if err != nil {
		return err
	}
	return h.err(r)
}

// onlyMine 是还开着的 Claude Code 都是 mine 这个包的（至少一个）：只有这种值得等。
func onlyMine(procs []Proc, mine string) bool {
	return len(procs) > 0 && !slices.ContainsFunc(procs, func(p Proc) bool { return p.Owner != mine })
}

// builtin 是 claude-switch 里总能用作目标的号（没有切换记录时默认配置目录里那个）：直接换过去，add 不收它。
const builtin = "claude"

// FreeName 是新号的名字：claude、claude-b……claude-z、claude-27……里第一个不在 taken 里的。
func FreeName(taken map[string]bool) string {
	for i := 0; ; i++ {
		var name string
		switch {
		case i == 0:
			name = builtin
		case i < 26:
			name = "claude-" + string(rune('a'+i))
		default:
			name = "claude-" + strconv.Itoa(i+1)
		}
		if !taken[name] {
			return name
		}
	}
}

// withNames 复制 m，再加上 extra。
func withNames(m map[string]bool, extra ...string) map[string]bool {
	out := make(map[string]bool, len(m)+len(extra))
	for k, v := range m {
		out[k] = v
	}
	for _, k := range extra {
		out[k] = true
	}
	return out
}

// Blocker 说明别的 Claude Code 进程挡着换号；没有时为空。mine 是调用方的包：它自己的对话由它先收掉，闲着的不算阻碍；
// 别的 Claude Code 进程哪怕闲着也不行：按账号统计用量的程序假定一个进程只属于一个账号，开着换号会把用量和额度读数分错账号（见 claude-switch 的 README「为什么是冷切换」）。
// claude-switch 自己也会拒绝，这里只是为了说清是谁挡着。
func Blocker(procs []Proc, mine string) string {
	var zeta, elsewhere []Proc
	for _, p := range procs {
		switch {
		case p.Owner == mine:
		case p.Owner != "":
			zeta = append(zeta, p)
		default:
			elsewhere = append(elsewhere, p)
		}
	}
	var reasons []string
	if len(zeta) > 0 {
		var names []string
		for _, p := range zeta {
			if n := zetaName(p.Owner); !slices.Contains(names, n) {
				names = append(names, n)
			}
		}
		reasons = append(reasons, fmt.Sprintf("另一个 Zeta（%s）开着 %d 个对话", strings.Join(names, "、"), len(zeta))+busyCount(zeta))
	}
	if len(elsewhere) > 0 {
		reasons = append(reasons, fmt.Sprintf("还有 %d 个别处开着的 Claude Code（比如终端里的 claude）", len(elsewhere))+busyCount(elsewhere))
	}
	return strings.Join(reasons, "；")
}

func busyCount(procs []Proc) string {
	n := 0
	for _, p := range procs {
		if p.Busy() {
			n++
		}
	}
	if n == 0 {
		return ""
	}
	return fmt.Sprintf("，其中 %d 个在进行", n)
}

// zetaName 是 Zeta 包的叫法：zeta 是正式版，zeta-dev 是测试版。
func zetaName(owner string) string {
	switch {
	case owner == "zeta":
		return "正式版"
	case strings.HasPrefix(owner, "zeta-dev"):
		return "测试版" + strings.TrimPrefix(owner, "zeta-dev")
	}
	return owner
}

// ---------- 开着的 Claude Code 进程 ----------

// hostMark 是 zeta_host.sh 给它起的 Claude Code 设的环境变量，值是 <包>/<托管目录名>，样子同 zeta-helper 的 hostMark：
// 包名以 zeta 开头，托管目录名不空、不含 /、不以 . 开头。
var hostMark = regexp.MustCompile(`^ZETA_HOST=(zeta[^/]*)/[^/.][^/]*$`)

// procs 是 ~/.claude/sessions 里登记的、还活着的 Claude Code 进程；pid 被别的进程复用了（启动时刻对不上）的不算。
// 登记目录读不了时为 nil。
func (h *Host) procs() []Proc {
	files, err := filepath.Glob(filepath.Join(h.Home, ".claude", "sessions", "*.json"))
	if err != nil {
		return nil
	}
	var out []Proc
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		var reg struct {
			PID       json.RawMessage `json:"pid"`
			ProcStart json.RawMessage `json:"procStart"`
			Status    string          `json:"status"`
			Kind      string          `json:"kind"`
		}
		if json.Unmarshal(data, &reg) != nil {
			continue
		}
		pid, err := strconv.Atoi(plain(reg.PID))
		if err != nil || pid <= 0 {
			continue
		}
		start, ok := procStart(pid)
		if !ok || (plain(reg.ProcStart) != "" && plain(reg.ProcStart) != start) {
			continue
		}
		out = append(out, Proc{PID: pid, Owner: owner(pid), Status: reg.Status, Kind: reg.Kind})
	}
	return out
}

// procStart 是进程的启动时刻（/proc/<pid>/stat 第 22 项，原样的文字）。
func procStart(pid int) (string, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return "", false
	}
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return "", false
	}
	fields := strings.Fields(string(data[i+1:]))
	if len(fields) < 20 {
		return "", false
	}
	return fields[19], true
}

// owner 是进程身上 ZETA_HOST 记的包；没有、读不了时为空。
func owner(pid int) string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/environ")
	if err != nil {
		return ""
	}
	pkg := ""
	for item := range bytes.SplitSeq(data, []byte{0}) {
		if m := hostMark.FindSubmatch(item); m != nil {
			pkg = string(m[1])
		}
	}
	return pkg
}

// plain 是登记文件里一项的文字：字符串去掉引号，数原样；没有、是 null 时为空。
func plain(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return strings.TrimSpace(s)
	}
	if t := strings.TrimSpace(string(raw)); t != "null" {
		return t
	}
	return ""
}
