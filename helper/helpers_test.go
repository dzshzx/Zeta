package main

import (
	"bytes"
	"encoding/json"
	"os"
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
