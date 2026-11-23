package main

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/helper/locales"
)

// 这台主机上的 Claude Code：查版本（system.claude）和更新（system.claude_update），设置页每台主机一组（docs/core.md「主机」）。
// 和对话起的是同一个 claude：登录 shell 的环境，PATH 末尾补 ~/.local/bin（同 helper/host 的 Start）。

const (
	claudeVersionTimeout = 20 * time.Second
	claudeUpdateTimeout  = 5 * time.Minute
	claudeOutputLines    = 8 // 更新的输出交回最后几行
)

// claudeUpdating：同一个核心里同时只跑一次 claude update，几个客户端一起点时后到的答「正在更新」。
var claudeUpdating sync.Mutex

// claudeCommand 在登录 shell 里跑 claude 加上 args，返回合起来的输出（去掉首尾空白）和退出码；起不来或超时时 err 非空。
func claudeCommand(e *env, timeout time.Duration, args ...string) (string, int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bash", append([]string{"-lc", `PATH="$PATH:$HOME/.local/bin"; exec claude "$@"`, "zeta-claude"}, args...)...)
	home := e.home
	if home == "" {
		home = "/"
	}
	cmd.Dir = home
	cmd.Env = append(os.Environ(), "HOME="+home)
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	err := cmd.Run()
	text := strings.TrimSpace(out.String())
	if ctx.Err() != nil {
		return text, -1, locales.Err("errClaudeUpdateTimeout")
	}
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		return text, exit.ExitCode(), nil
	}
	return text, 0, err
}

// claudeVersion 是 claude --version 的版本号（「2.1.330 (Claude Code)」取第一个词）；没装或跑不起来时为空串。
func claudeVersion(e *env) string {
	out, code, err := claudeCommand(e, claudeVersionTimeout, "--version")
	if err != nil || code != 0 {
		return ""
	}
	version, _, _ := strings.Cut(out, " ")
	return version
}

// versionReply 是 system.claude 的结果：version 没装时为 null。
func versionReply(version string) obj {
	if version == "" {
		return obj{"version": nil}
	}
	return obj{"version": version}
}

func systemClaudeMethod(e *env, _ oneShotParams) (any, error) {
	return versionReply(claudeVersion(e)), nil
}

// systemClaudeUpdateMethod 做 system.claude_update：跑 claude update，结果是 {ok, before, version, output}：
// ok 是它退出码为 0，before、version 是更新前后的版本（没装为 null），output 是输出的最后几行。
// 已在跑的 Claude Code 进程不受影响，下次起的用新版本。
func systemClaudeUpdateMethod(e *env, _ oneShotParams) (any, error) {
	if !claudeUpdating.TryLock() {
		return nil, locales.Err("errClaudeUpdating")
	}
	defer claudeUpdating.Unlock()
	before := claudeVersion(e)
	out, code, err := claudeCommand(e, claudeUpdateTimeout, "update")
	if err != nil {
		return nil, err
	}
	lines := strings.Split(out, "\n")
	reply := versionReply(claudeVersion(e))
	reply["before"] = versionReply(before)["version"]
	reply["ok"] = code == 0
	reply["output"] = strings.Join(lines[max(0, len(lines)-claudeOutputLines):], "\n")
	return reply, nil
}
