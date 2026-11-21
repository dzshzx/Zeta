package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// command 运行一个程序，返回去掉首尾空白的标准输出；失败时带上它的标准错误。测试里替换掉。
var command = func(name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	if home := os.Getenv("JAVA_HOME"); home != "" {
		cmd.Env = append(os.Environ(), "PATH="+filepath.Join(home, "bin")+string(os.PathListSeparator)+os.Getenv("PATH"))
	}
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("%s %s: %w: %s", filepath.Base(name), firstArg(args), err, quietStderr(stderr.String()))
	}
	return strings.TrimSpace(string(out)), nil
}

func firstArg(args []string) string {
	if len(args) == 0 {
		return ""
	}
	return args[0]
}

// quietStderr 去掉 JDK 每次都打的 native access 警告，只留真正的错误。
func quietStderr(s string) string {
	var keep []string
	for _, line := range strings.Split(s, "\n") {
		if line = strings.TrimSpace(line); line != "" && !strings.HasPrefix(line, "WARNING:") {
			keep = append(keep, line)
		}
	}
	return strings.Join(keep, "; ")
}

func gh(args ...string) (string, error)  { return command("gh", args...) }
func git(args ...string) (string, error) { return command("git", args...) }

// actionsEnv 读 GitHub Actions 给每一步设的环境变量。
func actionsEnv(names ...string) (map[string]string, error) {
	env := map[string]string{}
	for _, name := range names {
		if env[name] = os.Getenv(name); env[name] == "" {
			return nil, errors.New(name + " 没设：这条命令在 GitHub Actions 里跑")
		}
	}
	return env, nil
}

// repoRoot 从当前目录往上找仓根：有 tools/release/go.mod 的那一层。
func repoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for d := dir; ; d = filepath.Dir(d) {
		if _, err := os.Stat(filepath.Join(d, "tools", "release", "go.mod")); err == nil {
			return d, nil
		}
		if filepath.Dir(d) == d {
			return "", fmt.Errorf("%s 不在 Zeta 的仓里", dir)
		}
	}
}

func fileHash(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func copyFile(from, to string) error {
	data, err := os.ReadFile(from)
	if err != nil {
		return err
	}
	return os.WriteFile(to, data, 0o644)
}

// emptyDir 建好目录，并要求它是空的：里面只放这次的资产。
func emptyDir(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	if len(entries) > 0 {
		return fmt.Errorf("%s 要是空目录：里面只放这次的资产", dir)
	}
	return nil
}

func appendFile(path, text string) error {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := f.WriteString(text); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// stepSummary 把一段 Markdown 追加到 GitHub Actions 的运行摘要；不在 Actions 里时什么也不做。
func stepSummary(text string) error {
	if path := os.Getenv("GITHUB_STEP_SUMMARY"); path != "" {
		return appendFile(path, text)
	}
	return nil
}
