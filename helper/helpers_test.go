package main

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 测试用的小工具：在临时家目录里写文件、跑命令、解析输出。

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func writeLines(t *testing.T, path string, lines ...string) {
	t.Helper()
	writeFile(t, path, strings.Join(lines, "\n")+"\n")
}

// age 把 path 的修改时刻改到 ago 之前。
func age(t *testing.T, path string, ago time.Duration) {
	t.Helper()
	when := time.Now().Add(-ago)
	if err := os.Chtimes(path, when, when); err != nil {
		t.Fatal(err)
	}
}

func testEnv(t *testing.T) *env {
	t.Helper()
	return newEnvAt(t.TempDir(), "zeta")
}

// call 跑一个命令，返回解析后的输出行。
func call(t *testing.T, e *env, args ...string) ([]obj, error) {
	t.Helper()
	var buf bytes.Buffer
	o := newEmitter(&buf)
	err := dispatch(e, o, args)
	o.flush()
	var out []obj
	for _, line := range strings.Split(strings.TrimSpace(buf.String()), "\n") {
		if line == "" {
			continue
		}
		dec := json.NewDecoder(strings.NewReader(line))
		dec.UseNumber()
		var m obj
		if err := dec.Decode(&m); err != nil {
			t.Fatalf("output line is not a JSON object: %q", line)
		}
		out = append(out, m)
	}
	return out, err
}

func mustCall(t *testing.T, e *env, args ...string) []obj {
	t.Helper()
	out, err := call(t, e, args...)
	if err != nil {
		t.Fatalf("%v: %v", args, err)
	}
	return out
}

func num(t *testing.T, v any) int64 {
	t.Helper()
	n, ok := v.(json.Number)
	if !ok {
		t.Fatalf("not a number: %#v", v)
	}
	i, err := n.Int64()
	if err != nil {
		t.Fatal(err)
	}
	return i
}

func textOf(e obj) string {
	var parts []string
	for _, b := range asList(asObj(e["message"])["content"]) {
		if m := asObj(b); m["type"] == "text" {
			parts = append(parts, asString(m["text"]))
		}
	}
	return strings.Join(parts, "|")
}

// stub 起一个命令行里带 claude 的脚本 body（第一个参数是 dir），另加环境变量 env，返回 pid 和它退出后关闭的通道。
// 测试本身跑在 Zeta 托管的 Claude Code 里时会继承它的记号（hostMark），这里去掉，只留 env 里给的。
func stub(t *testing.T, body, dir string, env ...string) (int, <-chan struct{}) {
	t.Helper()
	script := filepath.Join(t.TempDir(), "claude")
	writeFile(t, script, "#!/bin/bash\n"+body)
	if err := os.Chmod(script, 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(script, dir)
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, hostMark+"=") {
			cmd.Env = append(cmd.Env, kv)
		}
	}
	cmd.Env = append(cmd.Env, env...)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); close(done) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-done })
	return cmd.Process.Pid, done
}

// trapped 起一个设了 trap 的 stub，一直跑着；trap 设好后才返回。
func trapped(t *testing.T, trap string, env ...string) (int, <-chan struct{}) {
	t.Helper()
	ready := filepath.Join(t.TempDir(), "ready")
	pid, done := stub(t, trap+"\n: > \"$1\"\nwhile :; do sleep 0.05; done\n", ready, env...)
	waitFile(t, ready)
	return pid, done
}

// tui 是终端里的 Claude Code：收到 SIGINT 就退出（照 /exit），[stubborn] 时不理它；env 是另加的环境变量。
func tui(t *testing.T, stubborn bool, env ...string) (int, <-chan struct{}) {
	t.Helper()
	if stubborn {
		return trapped(t, "trap '' INT", env...)
	}
	return trapped(t, "trap 'exit 0' INT", env...)
}

func waitFile(t *testing.T, path string) {
	t.Helper()
	for deadline := time.Now().Add(3 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s did not appear", path)
		}
	}
}

// registerStatus 像 Claude Code 那样登记，带上忙闲状态（空串时不写这一项）。
func registerStatus(t *testing.T, e *env, pid int, session, status, waitingFor string) {
	t.Helper()
	extra := ""
	if status != "" {
		extra += `,"status":"` + status + `"`
	}
	if waitingFor != "" {
		extra += `,"waitingFor":"` + waitingFor + `"`
	}
	register(t, e, pid, session, "", extra)
}

func stillUp(done <-chan struct{}) bool {
	select {
	case <-done:
		return false
	default:
		return true
	}
}
