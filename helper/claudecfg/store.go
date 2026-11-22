// Package claudecfg 读写这台主机上 Claude Code 自己的配置：~/.claude 下的记忆、Skills、Subagent，settings.json 里的
// 新对话默认和工具权限，以及经 claude mcp 管的 MCP 服务器。zeta-helper serve 的 config.* 方法（serve_config.go）用它，
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
	"fmt"
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
)

// Error 是一次操作没成功，Error() 是给人看的中文说明（不含 Token）。
type Error struct{ msg string }

func (e *Error) Error() string { return e.msg }

func fail(format string, args ...any) error { return &Error{fmt.Sprintf(format, args...)} }

// ErrConflict：文件在读到之后被别人改过（多半是 Claude 在对话里改的），这次没写。
var ErrConflict = errors.New("文件已经被改过了")

// MaxText 是记忆、Subagent 这类文本文件能读写的上限。
const MaxText = 1 << 20

const defaultTimeout = time.Minute

// Store 是家目录 home 下的 Claude Code 配置。
type Store struct {
	home string
}

// New 是家目录 home 下的配置。
func New(home string) *Store { return &Store{home: strings.TrimRight(home, "/")} }

// Home 是家目录。
func (s *Store) Home() string {
	if s.home == "" {
		return "/"
	}
	return s.home
}

func (s *Store) claudeDir() string { return claudeDir(s.Home()) }

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
		return Document{}, fail("文件超过 %d MiB，请在终端里编辑", limit>>20)
	}
	return Document{Content: string(data), SHA: sha256Hex(data), Exists: true}, nil
}

// storeMu 让本进程里的写文件一个一个来。
var storeMu sync.Mutex

// store 写文件：先写到同一目录下的临时文件再改名；路径是符号链接时写到它指向的文件上，已有文件的权限照旧。
// expected 是读到时的 sha256：* 不检查直接覆盖，- 表示应当还不存在；现有内容对不上时返回 ErrConflict，什么都不改。
// removeAfter 是写成以后要删掉的旧文件（Subagent 改名用）。返回新内容的 sha256。
func store(path string, content []byte, expected, removeAfter string) (string, error) {
	// 核对和改名之间不让本进程里别的请求插进来（serve 并发处理请求）；和 Claude Code 之间照旧是尽力而为
	storeMu.Lock()
	defer storeMu.Unlock()
	target := path
	if real, err := filepath.EvalSymlinks(path); err == nil {
		target = real
	}
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0o777); err != nil {
		return "", fail("建不了目录或删不掉")
	}
	st, statErr := os.Stat(target)
	if expected != "*" {
		cur := "-"
		if statErr == nil {
			data, err := os.ReadFile(target)
			if err != nil {
				return "", fail("读不了 %s", filepath.Base(target))
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
		return "", fail("写入失败：%s", pathReason(err))
	}
	defer os.Remove(tmp)
	_, err = f.Write(content)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return "", fail("写入失败，可能是存储空间不足")
	}
	if statErr == nil {
		_ = os.Chmod(tmp, st.Mode().Perm())
	}
	if err := os.Rename(tmp, target); err != nil {
		return "", fail("换不上新文件")
	}
	if removeAfter != "" && removeAfter != path {
		_ = os.Remove(removeAfter)
	}
	return sha256Hex(content), nil
}

func pathReason(err error) string {
	switch {
	case errors.Is(err, fs.ErrPermission):
		return "没有权限"
	case errors.Is(err, fs.ErrNotExist):
		return "目录不在"
	}
	return "存储空间不足或目录不可写"
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
		return "", -1, fail("等了太久没有结果")
	}
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		return out.String(), exit.ExitCode(), nil
	}
	if err != nil {
		return "", -1, fail("起不了 bash：%v", err)
	}
	return out.String(), 0, nil
}
