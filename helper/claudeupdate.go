package main

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"regexp"
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

type cliTool struct {
	mu           sync.Mutex
	script       string
	argv0        string
	busyErr      func() error
	parseVersion func(string) string
}

var (
	agySemverRe = regexp.MustCompile(`\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?`)
	claudeCLI   = cliTool{
		script:  `PATH="$PATH:$HOME/.local/bin"; exec claude "$@"`,
		argv0:   "zeta-claude",
		busyErr: func() error { return locales.Err("errClaudeUpdating") },
		parseVersion: func(out string) string {
			version, _, _ := strings.Cut(out, " ")
			return version
		},
	}
	agyCLI = cliTool{
		script:       `PATH="$PATH:$HOME/.local/bin"; exec "${ZETA_AGY_BIN:-agy}" "$@"`,
		argv0:        "zeta-agy",
		busyErr:      func() error { return locales.Err("errAgyUpdating") },
		parseVersion: parseAgyVersion,
	}
)

func (t *cliTool) run(e *env, timeout time.Duration, args ...string) (string, int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "bash", append([]string{"-lc", t.script, t.argv0}, args...)...)
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

func (t *cliTool) version(e *env) string {
	out, code, err := t.run(e, claudeVersionTimeout, "--version")
	if err != nil || code != 0 || out == "" {
		return ""
	}
	return t.parseVersion(out)
}

func (t *cliTool) update(e *env) (any, error) {
	if !t.mu.TryLock() {
		return nil, t.busyErr()
	}
	defer t.mu.Unlock()
	before := t.version(e)
	out, code, err := t.run(e, claudeUpdateTimeout, "update")
	if err != nil {
		return nil, err
	}
	lines := strings.Split(out, "\n")
	reply := versionReply(t.version(e))
	reply["before"] = versionReply(before)["version"]
	reply["ok"] = code == 0
	reply["output"] = strings.Join(lines[max(0, len(lines)-claudeOutputLines):], "\n")
	return reply, nil
}

// versionReply 是 system.claude / system.agy 的结果：version 没装时为 null。
func versionReply(version string) obj {
	if version == "" {
		return obj{"version": nil}
	}
	return obj{"version": version}
}

func parseAgyVersion(out string) string {
	if m := agySemverRe.FindString(out); m != "" {
		return m
	}
	for _, field := range strings.Fields(out) {
		if strings.ToLower(field) != "agy" {
			return strings.Trim(field, "(),")
		}
	}
	return ""
}

func systemClaudeMethod(e *env, _ oneShotParams) (any, error) {
	return versionReply(claudeCLI.version(e)), nil
}

func systemClaudeUpdateMethod(e *env, _ oneShotParams) (any, error) { return claudeCLI.update(e) }

func systemAgyMethod(e *env, _ oneShotParams) (any, error) {
	return versionReply(agyCLI.version(e)), nil
}

func systemAgyUpdateMethod(e *env, _ oneShotParams) (any, error) { return agyCLI.update(e) }
