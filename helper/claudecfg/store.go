// Package claudecfg 读写这台主机上 Claude Code 自己的配置：~/.claude 下的记忆、Skills、Subagent，settings.json 里的
// 新对话默认和工具权限，以及经 claude mcp 管的 MCP 服务器。zeta-helper 核心的 config.* 方法（config.go）用它，
// 手机版和桌面版都经那几个方法读写。行为照手机版原来的 shared/…/core/claude。
//
// 文件直接在本机读写（zeta-helper 以 Claude Code 所属的用户身份跑，文件的所有者自然是那个用户）；要用登录环境的
// （claude 命令、MCP 配置引用的环境变量、stdio 的 MCP 服务器）经 bash -lc 起。方法之间不共享状态，可以在多个 goroutine
// 里同时调；同时改 settings.json 的，按读到时的校验值写回，撞上了在新内容上重做。
package claudecfg

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"math/rand/v2"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 没成功的操作：Zeta 自己说的是 *locales.Error（核心按发请求的客户端的语言出字），claude、MCP 服务器原样给的话是 *Error。
// 两种都不含 Token（maskErr）。

// Error 是 claude 或 MCP 服务器原样给的出错（不翻译）。
type Error struct{ msg string }

func (e *Error) Error() string { return e.msg }

func rawError(text string) error { return &Error{text} }

// maskErr 把 err 里出现的 secrets 打码：原话的 *Error 和 *locales.Error 的字符串参数；别的出错原样返回。
func maskErr(err error, secrets []string) error {
	var raw *Error
	if errors.As(err, &raw) {
		return rawError(mask(raw.msg, secrets))
	}
	if said, ok := errors.AsType[*locales.Error](err); ok {
		masked := *said
		masked.Args = make(map[string]any, len(said.Args))
		for k, v := range said.Args {
			if s, ok := v.(string); ok {
				v = mask(s, secrets)
			}
			masked.Args[k] = v
		}
		return &masked
	}
	return err
}

// ErrConflict：文件在读到之后被别人改过（多半是 Claude 在对话里改的），这次没写。
var ErrConflict = errors.New("文件已经被改过了")

// MaxText 是记忆、Subagent 这类文本文件能读写的上限。
const MaxText = 1 << 20

const defaultTimeout = time.Minute

type harnessSpec struct {
	settingsPath    func(home string) string
	agentsDir       func(home string) string
	skillsDir       func(home string) string
	commonTools     []ToolInfo
	otherTools      []ToolInfo
	builtinGroup    func() PermissionGroup
	protectedMemory func(home, path, name string) bool
}

var (
	claudeSpec = harnessSpec{
		settingsPath: settingsPath,
		agentsDir:    agentsDir,
		skillsDir:    func(home string) string { return claudeDir(home) + "/skills" },
		commonTools:  CommonTools,
		otherTools:   OtherTools,
		builtinGroup: BuiltinGroup,
		protectedMemory: func(home, path, name string) bool {
			return name == "MEMORY.md" || path == instructions(home)
		},
	}
	agySpec = harnessSpec{
		settingsPath: agySettingsPath,
		agentsDir:    agyAgentsDir,
		skillsDir:    agySkillsDir,
		commonTools:  AgyCommonTools,
		otherTools:   AgyOtherTools,
		builtinGroup: AgyBuiltinGroup,
		protectedMemory: func(home, path, name string) bool {
			return name == "MEMORY.md" || name == "AGENTS.md" || name == "GEMINI.md" || path == instructions(home)
		},
	}
)

// Store 是家目录 home 下的 Claude Code 或 Antigravity（agy）配置。
type Store struct {
	home    string
	lang    string // 结果里 Zeta 自己写的说明（工具权限页的工具说明）用这个语言，见 In
	harness string // "claude"（默认）或 "agy"
	spec    *harnessSpec
}

// New 是家目录 home 下的配置（默认 Claude Code）。
func New(home string) *Store {
	return &Store{home: strings.TrimRight(home, "/"), harness: "claude", spec: &claudeSpec}
}

func (s *Store) activeSpec() *harnessSpec {
	if s.spec != nil {
		return s.spec
	}
	if s.harness == "agy" {
		return &agySpec
	}
	return &claudeSpec
}

// In 是同一份配置，结果里 Zeta 自己写的说明用语言 lang（发请求的客户端的语言，core.Client.Lang）。出错不用它：
// 出错是 *locales.Error，由核心按连接的语言出字。
func (s *Store) In(lang string) *Store {
	return &Store{home: s.home, lang: lang, harness: s.harness, spec: s.activeSpec()}
}

// ForHarness 返回读写指定 harness（"claude" 或 "agy"）配置的 Store。
func (s *Store) ForHarness(harness string) *Store {
	h, spec := "claude", &claudeSpec
	if strings.TrimSpace(harness) == "agy" {
		h, spec = "agy", &agySpec
	}
	return &Store{home: s.home, lang: s.lang, harness: h, spec: spec}
}

// IsAgy 判断当前 Store 是否面向 Antigravity CLI。
func (s *Store) IsAgy() bool { return s.harness == "agy" }

// Home 是家目录。
func (s *Store) Home() string {
	if s.home == "" {
		return "/"
	}
	return s.home
}

// Document 是读到的一个文本文件。SHA 是读到时内容的 sha256，写回时用它判断中间有没有被别人改过；文件不存在时为空。
type Document struct {
	Content string `json:"content"`
	SHA     string `json:"sha"`
	Exists  bool   `json:"exists"`
}

// readHead 读一个普通文件的开头最多 limit 个字节；不存在、不是普通文件或读不了时 ok 为 false。
func readHead(path string, limit int64) (head []byte, size int64, ok bool) {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() {
		return nil, 0, false
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, 0, false
	}
	defer f.Close()
	head, err = io.ReadAll(io.LimitReader(f, limit))
	if err != nil {
		return nil, 0, false
	}
	return head, st.Size(), true
}

// load 读整个文本文件；超过 limit 报错。
func load(path string, limit int) (Document, error) {
	data, _, ok := readHead(path, int64(limit)+1)
	if !ok {
		return Document{}, nil
	}
	if len(data) > limit {
		return Document{}, locales.Err("cfgFileTooBig", "mib", limit>>20)
	}
	return Document{Content: string(data), SHA: sha256Hex(data), Exists: true}, nil
}

// storeMu 让本进程里的写文件一个一个来。
var storeMu sync.Mutex

// store 写文件：先写到同一目录下的临时文件再改名；路径是符号链接时写到它指向的文件上，已有文件的权限照旧。
// expected 是读到时的 sha256：* 不检查直接覆盖，- 表示应当还不存在；现有内容对不上时返回 ErrConflict，什么都不改。
// removeAfter 是写成以后要删掉的旧文件（Subagent 改名用）。返回新内容的 sha256。
func store(path string, content []byte, expected, removeAfter string) (string, error) {
	// 核对和改名之间不让本进程里别的请求插进来（核心并发处理请求）；和 Claude Code 之间照旧是尽力而为
	storeMu.Lock()
	defer storeMu.Unlock()
	target := path
	if real, err := filepath.EvalSymlinks(path); err == nil {
		target = real
	}
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0o777); err != nil {
		return "", locales.Err("cfgDirOrDelete")
	}
	st, statErr := os.Stat(target)
	if expected != "*" {
		cur := "-"
		if statErr == nil {
			data, err := os.ReadFile(target)
			if err != nil {
				return "", locales.Err("cfgCantRead", "name", filepath.Base(target))
			}
			cur = sha256Hex(data)
		}
		if cur != expected {
			return "", ErrConflict
		}
	}
	tmp := filepath.Join(dir, "."+filepath.Base(target)+".zeta-"+strconv.Itoa(os.Getpid())+"-"+strconv.FormatUint(rand.Uint64(), 36))
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o666)
	if err != nil {
		return "", locales.Err("cfgWriteFailed", "reason", pathReason(err))
	}
	defer os.Remove(tmp)
	_, err = f.Write(content)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return "", locales.Err("cfgWriteFailedSpace")
	}
	if statErr == nil {
		_ = os.Chmod(tmp, st.Mode().Perm())
	}
	if err := os.Rename(tmp, target); err != nil {
		return "", locales.Err("cfgReplaceFailed")
	}
	if removeAfter != "" && removeAfter != path {
		_ = os.Remove(removeAfter)
	}
	return sha256Hex(content), nil
}

// pathReason 是读写文件没成的原因，放进出错那句话的参数里。
func pathReason(err error) locales.Msg {
	switch {
	case errors.Is(err, fs.ErrPermission):
		return locales.M("cfgNoPermission")
	case errors.Is(err, fs.ErrNotExist):
		return locales.M("cfgNoDirectory")
	}
	return locales.M("cfgNoSpace")
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func lastLine(s string) string {
	lines := strings.Split(s, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if t := strings.TrimSpace(lines[i]); t != "" {
			return t
		}
	}
	return ""
}

// clip 截到最多 n 个字符。
func clip(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func hasControl(s string) bool {
	for _, r := range s {
		if r < ' ' {
			return true
		}
	}
	return false
}

// loginCommand 是经登录 shell（bash -lc）跑的 script：要用登录配置里的 PATH 和环境变量（claude 命令、MCP 配置引用的
// 变量）。$1… 是 args；HOME 是这份配置的家目录，extraEnv 追加在后面。
func (s *Store) loginCommand(ctx context.Context, script string, args []string, extraEnv []string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, "bash", append([]string{"-lc", script, "zeta"}, args...)...)
	env := make([]string, 0, len(os.Environ())+len(extraEnv)+1)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "HOME=") {
			env = append(env, kv)
		}
	}
	cmd.Env = append(append(env, "HOME="+s.Home()), extraEnv...)
	cmd.Dir = s.Home()
	cmd.WaitDelay = 2 * time.Second
	return cmd
}

// run 跑 cmd，标准输入是 stdin，返回合在一起的输出和退出码。
func run(ctx context.Context, cmd *exec.Cmd, stdin []byte) (string, int, error) {
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	err := cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return "", -1, locales.Err("cfgTimeout")
	}
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		return out.String(), exit.ExitCode(), nil
	}
	if err != nil {
		return "", -1, locales.Err("cfgBashFailed", "detail", err.Error())
	}
	return out.String(), 0, nil
}
