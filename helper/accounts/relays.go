package accounts

// 中转：claude-switch 的非账号目标（~/.config/claude-switch/targets/<名字>.json，一份 Claude Code 设置文件）。切到它时
// claude-switch 把在用的号存档并退出登录，之后 Claude Code 照这份设置里的 env.ANTHROPIC_BASE_URL、apiKeyHelper 和 model
// 连中转，起 claude 时要带 --settings <这份文件>（Settings）。协议只有 Claude Code 自己会说的 Anthropic Messages，Zeta 不做
// 协议翻译，只读写这份设置和它指向的密钥文件，另外替界面拉中转的模型列表（models.go）。
//
// Zeta 写的目标是 {"env":{"ANTHROPIC_BASE_URL":"<地址>"},"apiKeyHelper":"cat ~/.local/state/claude-switch/<名字>-key"}，
// 选了默认模型时多一项 "model"；密钥文件 0600。Claude Code 用 apiKeyHelper 的密钥时 x-api-key 和 Authorization: Bearer
// 两个头都发（2.1.330 实测；env 的 ANTHROPIC_API_KEY 只发前者，ANTHROPIC_AUTH_TOKEN 只发后者），所以只认其中一种头的中转
// 也都能用，不用让用户选鉴权方式；拉模型列表时同样两个头都带。
//
// 用户自己放的目标照样列出、切换、改地址和默认模型，别的设置原样留着；它的 apiKeyHelper 是 `cat <家目录里的文件>` 时按那个
// 文件改密钥，别的写法只读。密钥的值只写进密钥文件，拉模型列表时读一次，不回传、不进日志和出错。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"syscall"
	"time"
	"unicode"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/claude-switch/account"
)

// Relay 是一个中转（非账号目标），accounts.list 的 relays 一项。没有密钥的值。
type Relay struct {
	Name string `json:"name"`
	// BaseURL 是设置里的 env.ANTHROPIC_BASE_URL；没写时为空。
	BaseURL string `json:"baseUrl"`
	// Model 是设置里的 model（这个中转上新对话的默认模型）；没写时为空。
	Model string `json:"model"`
	// HasKey 是配了密钥：apiKeyHelper 是 `cat <文件>` 时看那个文件在不在、空不空；别的写法（或 env 里的
	// ANTHROPIC_API_KEY、ANTHROPIC_AUTH_TOKEN）只看配了没有。
	HasKey bool `json:"hasKey"`
	// KeyEditable 是 Zeta 能改它的密钥（apiKeyHelper 是 `cat <家目录里的文件>`，或还没配密钥）。
	KeyEditable bool `json:"keyEditable"`
	// Current 是切换记录指着它（Claude Code 正走这个中转）。
	Current bool `json:"current"`
}

// RelayInput 是添加、修改一个中转的参数。
type RelayInput struct {
	Name    string
	BaseURL string
	// Key 是新的密钥；修改时为空表示不改。
	Key string
	// Model 是默认模型，空是不设（删掉设置里的 model）。
	Model string
	// Create 为真是添加（名字不能已被占用），否则是修改已有的。
	Create bool
}

var (
	// ErrRelayMissing 是要改、要删的中转已经不在了。
	ErrRelayMissing = locales.Err("errRelayMissing")
	// ErrRelayInUse 是要删的中转正在用。
	ErrRelayInUse = locales.Err("errRelayInUse")
)

// relayKeyHelper 认 `cat <路径>` 这种 apiKeyHelper：路径只许常见的文件名字符，可以 ~/ 开头。
var relayKeyHelper = regexp.MustCompile(`^\s*cat\s+(~/[A-Za-z0-9._/-]+|/[A-Za-z0-9._/-]+)\s*$`)

// CurrentRelay 是切换记录指着的中转（非账号目标）名；指着账号时为空，这时才有订阅额度。只读两个小文件，快。
func CurrentRelay(home string) string {
	name := account.Current(home)
	if !account.IsTarget(home, name) {
		return ""
	}
	return name
}

// Settings 是切换记录指着中转时那份设置文件的路径（起 claude 时要带 --settings 它，否则 Claude Code 当成没登录）；
// 指着账号时为空。起对话进程前调。
//
// 用户在这台 Claude Code 的 settings.json 里自己配了 env.ANTHROPIC_BASE_URL 时也为空：--settings 里的 env 盖过 settings.json
// 的（2.1.330 实测），带上就把用户自己的地址换掉了。和终端里一样（dotfiles 的 shell-environment.sh 给 claude 定义的函数）；
// 登录环境里的 ANTHROPIC_BASE_URL 核心看不到，由托管脚本和 tmux 窗口自己看（zeta_host.sh、desk.go 的 runResume）。
func Settings(home string) string {
	if name := selectedRelay(home); name != "" {
		return targetPath(home, name)
	}
	return ""
}

func selectedRelay(home string) string {
	name := CurrentRelay(home)
	if name == "" || ownBaseURL(home) {
		return ""
	}
	return name
}

// effectiveRelay 和新对话启动一样：先看用户设置，再看登录 shell 传给托管脚本的环境。
// 只取退出状态，不读取 shell 输出或地址；探测失败时不猜该改哪份配置。
func effectiveRelay(ctx context.Context, home string) (string, error) {
	name := selectedRelay(home)
	if name == "" {
		return "", nil
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bash", "-lc", `exec env bash -c 'if [ -n "${ANTHROPIC_BASE_URL:-}" ]; then exit 42; fi; exit 43'`)
	cmd.Dir = home
	cmd.Env = append(os.Environ(), "HOME="+home)
	var exit *exec.ExitError
	if errors.As(cmd.Run(), &exit) {
		switch exit.ExitCode() {
		case 42:
			return "", nil
		case 43:
			return name, nil
		}
	}
	return "", errors.New("无法确认登录环境使用的中转")
}

// RelayDefaultModel 读生效中转的 model；没配则由调用方继承用户级默认值。
func RelayDefaultModel(ctx context.Context, home string) (string, error) {
	name, err := effectiveRelay(ctx, home)
	if err != nil || name == "" {
		return "", err
	}
	s, _ := readTarget(home, name)
	return strings.TrimSpace(s.Model), nil
}

// SetRelayDefaultModel 只改生效目标的 model。没有生效目标时返回 false，由调用方改用户级配置。
func SetRelayDefaultModel(ctx context.Context, home, model string) (bool, error) {
	changing.Lock()
	defer changing.Unlock()
	name, err := effectiveRelay(ctx, home)
	if err != nil || name == "" {
		return false, err
	}
	model = strings.TrimSpace(model)
	if err := validRelayModel(model); err != nil {
		return true, err
	}
	unlock, err := lockRelayFiles(home)
	if err != nil {
		return true, err
	}
	defer unlock()
	path := targetPath(home, name)
	settings, err := readTargetObject[json.RawMessage](path)
	if err != nil {
		return true, err
	}
	if model == "" {
		delete(settings, "model")
	} else {
		settings["model"], _ = json.Marshal(model)
	}
	return true, writeTarget(path, settings)
}

// lockRelayFiles 让同一用户的不同 Zeta 核心也串行读改写目标及密钥文件。
func lockRelayFiles(home string) (func(), error) {
	path := filepath.Join(home, account.StateDir, "relay-files.lock")
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

// ownBaseURL 是 ~/.claude/settings.json 是一个 JSON 对象、env.ANTHROPIC_BASE_URL 是不全是空白的字符串。
func ownBaseURL(home string) bool {
	data, err := os.ReadFile(filepath.Join(home, ".claude", "settings.json"))
	if err != nil {
		return false
	}
	var s struct {
		Env map[string]any `json:"env"`
	}
	if json.Unmarshal(data, &s) != nil {
		return false
	}
	return targetEnv(s.Env, "ANTHROPIC_BASE_URL") != ""
}

func targetPath(home, name string) string {
	return filepath.Join(home, account.TargetsDir, name+".json")
}

// defaultKeyFile 是 Zeta 给中转 name 写的密钥文件（相对家目录）。
func defaultKeyFile(name string) string {
	return filepath.Join(account.StateDir, name+"-key")
}

// targetSettings 是一份目标设置里 Zeta 要看的几项。
type targetSettings struct {
	Env          map[string]any `json:"env"`
	APIKeyHelper string         `json:"apiKeyHelper"`
	Model        string         `json:"model"`
}

// envString 是 env 里 name 那一项的字符串原文；没有、不是字符串时为空。
func envString(env map[string]any, name string) string {
	v, _ := env[name].(string)
	return v
}

// targetEnv 是 env 里 name 那一项去掉首尾空白后的字符串；没有、不是字符串时为空。
func targetEnv(env map[string]any, name string) string {
	return strings.TrimSpace(envString(env, name))
}

// envHasKey 是 env 里写了 ANTHROPIC_API_KEY 或 ANTHROPIC_AUTH_TOKEN（不是 null 就算，不看值）。列中转和改密钥都按它
// 判断密钥写在 env 里；拉模型列表取密钥另按 targetEnv 要去空白后非空（targetModelAuth）。
func envHasKey(env map[string]any) bool {
	return env["ANTHROPIC_API_KEY"] != nil || env["ANTHROPIC_AUTH_TOKEN"] != nil
}

// keyFile 是 apiKeyHelper `cat <路径>` 里的文件（展开 ~/），只认家目录里的；认不出、在家目录外时为空。
func keyFile(home, helper string) string {
	m := relayKeyHelper.FindStringSubmatch(helper)
	if m == nil {
		return ""
	}
	path := m[1]
	if rest, ok := strings.CutPrefix(path, "~/"); ok {
		path = filepath.Join(home, rest)
	}
	path = filepath.Clean(path)
	if rel, err := filepath.Rel(home, path); err != nil || rel == "." || strings.HasPrefix(rel, "..") {
		return ""
	}
	return path
}

// readTarget 读中转 name 的设置；读不了、不是 JSON 对象时 ok 为假。
func readTarget(home, name string) (s targetSettings, ok bool) {
	data, err := os.ReadFile(targetPath(home, name))
	if err != nil || json.Unmarshal(data, &s) != nil {
		return targetSettings{}, false
	}
	return s, true
}

// readRelay 读中转 name；current 是切换记录指着它。读不了、不是 JSON 对象时只列名字。
func readRelay(home, name string, current bool) Relay {
	r := Relay{Name: name, Current: current}
	s, ok := readTarget(home, name)
	if !ok {
		return r
	}
	r.BaseURL = envString(s.Env, "ANTHROPIC_BASE_URL")
	r.Model = s.Model
	envKey := envHasKey(s.Env)
	switch {
	case strings.TrimSpace(s.APIKeyHelper) == "":
		r.HasKey, r.KeyEditable = envKey, !envKey
	case keyFile(home, s.APIKeyHelper) != "":
		info, err := os.Stat(keyFile(home, s.APIKeyHelper))
		r.HasKey, r.KeyEditable = err == nil && info.Mode().IsRegular() && info.Size() > 0, true
	default:
		r.HasKey = true
	}
	return r
}

// relays 是 claude-switch --json 列的目标，按名字排（claude-switch 已排好）；current 是切换记录。
func relays(home string, names []string, current string) []Relay {
	out := make([]Relay, 0, len(names))
	for _, name := range names {
		out = append(out, readRelay(home, name, name == current))
	}
	return out
}

// validBaseURL 核对中转地址：http(s)、有主机、不带用户名密码。
func validBaseURL(raw string) error {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil {
		return locales.Err("errRelayURL")
	}
	return nil
}

// validRelay 核对名字、地址、密钥和模型；出错是给用户看的那句。
func validRelay(in RelayInput) error {
	if !account.Valid(in.Name) || in.Name == builtin {
		return locales.Err("errRelayName")
	}
	if err := validBaseURL(in.BaseURL); err != nil {
		return err
	}
	if strings.ContainsAny(in.Key, " \t\r\n") {
		return locales.Err("errRelayKey")
	}
	return validRelayModel(in.Model)
}

func validRelayModel(model string) error {
	if len(model) > 200 || strings.ContainsFunc(model, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) }) {
		return locales.Err("errRelayModel")
	}
	return nil
}

// SaveRelay 添加或修改一个中转（见 RelayInput）。正在用的中转改了，之后起的对话进程才用上。
func (h *Host) SaveRelay(ctx context.Context, in RelayInput) error {
	in.BaseURL, in.Key, in.Model = strings.TrimSpace(in.BaseURL), strings.TrimSpace(in.Key), strings.TrimSpace(in.Model)
	if err := validRelay(in); err != nil {
		return err
	}
	changing.Lock()
	defer changing.Unlock()
	unlock, err := lockRelayFiles(h.Home)
	if err != nil {
		return err
	}
	defer unlock()
	st, err := h.List(ctx)
	if err != nil {
		return err
	}
	existing := slices.IndexFunc(st.Relays, func(r Relay) bool { return r.Name == in.Name }) >= 0
	switch {
	case in.Create && st.taken[in.Name]:
		return locales.Err("errRelayNameTaken")
	case in.Create && in.Key == "":
		return locales.Err("errRelayKeyRequired")
	case !in.Create && !existing:
		return ErrRelayMissing
	}
	path := targetPath(h.Home, in.Name)
	settings := map[string]any{}
	if !in.Create {
		if settings, err = readTargetObject[any](path); err != nil {
			return err
		}
	}
	env, _ := settings["env"].(map[string]any)
	if env == nil {
		env = map[string]any{}
	}
	env["ANTHROPIC_BASE_URL"] = in.BaseURL
	settings["env"] = env
	if in.Model != "" {
		settings["model"] = in.Model
	} else {
		delete(settings, "model")
	}
	if in.Key != "" {
		if err := h.writeKey(settings, env, in.Name, in.Key); err != nil {
			return err
		}
	}
	return writeTarget(path, settings)
}

// readTargetObject 读要改写的目标设置文件：必须整份是一个 JSON 对象，否则报 errRelayUnreadable。数字按原文留着
// （UseNumber）；V 是 json.RawMessage 时各项连原文一起留着，写回只改动过的那几项。
func readTargetObject[V any](path string) (map[string]V, error) {
	var settings map[string]V
	data, err := os.ReadFile(path)
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if err != nil || !json.Valid(data) || dec.Decode(&settings) != nil || settings == nil {
		return nil, locales.Err("errRelayUnreadable")
	}
	return settings, nil
}

func writeTarget(path string, settings any) error {
	data, err := json.Marshal(settings)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(path, data, 0o600); err != nil {
		return locales.Err("errRelayWrite", "path", path)
	}
	return nil
}

// writeKey 把密钥写进中转 name 的密钥文件：apiKeyHelper 是 `cat <家目录里的文件>` 时写那个文件；还没配密钥时配上 Zeta 的
// 默认写法（settings 里加 apiKeyHelper）；别的写法、密钥写在 env 里的不能改。
func (h *Host) writeKey(settings, env map[string]any, name, key string) error {
	helper, _ := settings["apiKeyHelper"].(string)
	file := keyFile(h.Home, helper)
	switch {
	case strings.TrimSpace(helper) == "" && envHasKey(env):
		return locales.Err("errRelayKeyReadOnly")
	case strings.TrimSpace(helper) == "":
		settings["apiKeyHelper"] = "cat ~/" + filepath.ToSlash(defaultKeyFile(name))
		file = filepath.Join(h.Home, defaultKeyFile(name))
	case file == "":
		return locales.Err("errRelayKeyReadOnly")
	}
	if err := writeFileAtomic(file, []byte(key+"\n"), 0o600); err != nil {
		return locales.Err("errRelayWrite", "path", file)
	}
	return nil
}

// RemoveRelay 删掉一个不在用的中转：它的设置文件，和 Zeta 给它写的那份密钥文件（别的中转也用着时留着）。
func (h *Host) RemoveRelay(ctx context.Context, name string) error {
	changing.Lock()
	defer changing.Unlock()
	unlock, err := lockRelayFiles(h.Home)
	if err != nil {
		return err
	}
	defer unlock()
	st, err := h.List(ctx)
	if err != nil {
		return err
	}
	i := slices.IndexFunc(st.Relays, func(r Relay) bool { return r.Name == name })
	switch {
	case i < 0:
		return ErrRelayMissing
	case st.Relays[i].Current:
		return ErrRelayInUse
	}
	s, _ := readTarget(h.Home, name)
	if path := targetPath(h.Home, name); !removed(path) {
		return locales.Err("errRelayWrite", "path", path)
	}
	key := keyFile(h.Home, s.APIKeyHelper)
	if key == "" || key != filepath.Join(h.Home, defaultKeyFile(name)) || keyShared(h.Home, st.Relays, name, key) {
		return nil
	}
	if !removed(key) {
		return locales.Err("errRelayWrite", "path", key)
	}
	return nil
}

// removed 删掉 path，本来就不在也算删了。
func removed(path string) bool {
	err := os.Remove(path)
	return err == nil || errors.Is(err, fs.ErrNotExist)
}

// keyShared 是除 name 以外还有中转的 apiKeyHelper 读 key 这个文件。
func keyShared(home string, relays []Relay, name, key string) bool {
	for _, r := range relays {
		if r.Name == name {
			continue
		}
		if s, ok := readTarget(home, r.Name); ok && keyFile(home, s.APIKeyHelper) == key {
			return true
		}
	}
	return false
}

// writeFileAtomic 先写同目录的临时文件（权限 mode）、落盘，再改名成 path；目录不在时按 0700 建。
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
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
