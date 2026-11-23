// Package host 起和接托管脚本 zeta_host.sh 托管的 Claude Code 进程。
//
// 核心跑在主机本机上，以 Linux 用户身份直接起 bash 跑脚本，不经 su、ssh，所以没有断线重连：
// 连接（Link）就是一个 zeta_host.sh start 或 attach 进程，它的标准输入写进托管进程的输入管道，标准输出是跟读的输出日志。
// 脚本的子命令、托管目录里各文件的规矩见脚本开头。脚本里的 ZETA_NAME（包的目录名）取自本进程的环境变量。
package host

import (
	"context"
	_ "embed"
	"maps"
	"os/exec"
	"regexp"
	"slices"
	"strconv"
	"time"
)

// Script 是托管脚本 zeta_host.sh 的内容。手机版、桌面版也带着同一份，交给各自那头的 bash 跑。
//
//go:embed zeta_host.sh
var Script string

// StartOptions 是起一个 Claude Code 进程要的东西。
type StartOptions struct {
	// Key 是托管标识，也就是托管目录 ~/.cache/<ZETA_NAME>/hosts/<Key> 的名字。
	Key string
	// Cwd、Title 是工作目录和标题，脚本记进托管目录。
	Cwd   string
	Title string
	// Args 是交给 claude 的参数。接着旧会话（--resume，不带 --fork-session）而本包已有进程在跑这个会话时，
	// 脚本不另起、接上那一个（Attached.Joined），那个进程不是按这些参数起的。
	Args []string
	// Env 是只给这个 Claude Code 进程的环境变量，进程脱离出去后也一直带着。名字不像环境变量名的丢掉。
	Env map[string]string
	// OnJoin 是接上本包现成的进程（Attached.Joined）时要先写的几行（不带换行），排在别的还没写的消息前面，
	// 用来把那个进程调成这边起进程时要的样子。没接上现成的进程时不写。
	OnJoin [][]byte
}

// envName 是环境变量名的样子。
var envName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// closeTimeout 是 stop 命令最多跑多久；脚本自己立即返回，结束进程的等待在它另起的会话里做。
const closeTimeout = 20 * time.Second

// Start 起 zeta_host.sh start：新起一个 Claude Code 进程（或接上本包已在跑同一个会话的那个），并接上它。
// 用登录 shell 的环境（PATH 里的 claude、代理等），PATH 再补上 ~/.local/bin（Claude Code 的安装程序放在那里）。
// 参数和环境变量都经 argv 交给 env 和 bash，不拼进命令字符串，不用加引号。
func Start(opts StartOptions) (*Link, error) {
	args := []string{"-lc", `PATH="$PATH:$HOME/.local/bin"; exec env "$@"`, "zeta-start"}
	for _, name := range slices.Sorted(maps.Keys(opts.Env)) {
		if envName.MatchString(name) {
			args = append(args, name+"="+opts.Env[name])
		}
	}
	args = append(args, "bash", "-c", Script, "zeta-host", "start", opts.Key, opts.Cwd, opts.Title)
	args = append(args, opts.Args...)
	return open(exec.Command("bash", args...), opts.Key, opts.OnJoin)
}

// Attach 起 zeta_host.sh attach：接上一个已在运行的进程，从输出日志的第 from 行（从 1 起）读起。
// 只用 bash 和 coreutils，不读登录配置。
func Attach(key string, from int) (*Link, error) {
	return open(scriptCommand(context.Background(), "attach", key, strconv.Itoa(from)), key, nil)
}

// scriptCommand 是用 bash 跑脚本子命令的进程（不读登录配置）。
func scriptCommand(ctx context.Context, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, "bash", append([]string{"-c", Script, "zeta-host"}, args...)...)
}

// runScript 在后台跑一次脚本子命令，最多 closeTimeout；返回的通道在它跑完（或超时被结束）时关上。
func runScript(args ...string) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		ctx, cancel := context.WithTimeout(context.Background(), closeTimeout)
		defer cancel()
		cmd := scriptCommand(ctx, args...)
		cmd.WaitDelay = time.Second
		_ = cmd.Run()
	}()
	return done
}
