package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// config.*：参数和 id、method 平级写在请求那一行里，结果编成 JSON 就是回复的 result。在临时 HOME 里真读写。

// configCall 发一行请求，返回 result 编成的 JSON。
func configCall(t *testing.T, line string) (string, error) {
	t.Helper()
	var req serveRequest
	if err := json.Unmarshal([]byte(line), &req); err != nil {
		t.Fatal(err)
	}
	result, err := handleConfig(context.Background(), req.Method, json.RawMessage(line))
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	return string(out), nil
}

func mustConfig(t *testing.T, line string) string {
	t.Helper()
	out, err := configCall(t, line)
	if err != nil {
		t.Fatalf("%s: %v", line, err)
	}
	return out
}

func TestServeConfigMethods(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	writeFile(t, filepath.Join(home, ".claude", "settings.json"), `{"theme":"dark"}`)
	writeFile(t, filepath.Join(home, ".claude", "CLAUDE.md"), "回答用中文\n")

	if out := mustConfig(t, `{"id":1,"method":"config.defaults.setModel","value":"opus"}`); !strings.Contains(out, `"model":"opus"`) {
		t.Errorf("defaults %s", out)
	}
	if out := mustConfig(t, `{"id":2,"method":"config.defaults.setEffort","model":"claude-opus-5-5","value":"max"}`); !strings.Contains(out, `"efforts":[{"model":"claude-opus-5-5","level":"max"}]`) {
		t.Errorf("effort %s", out)
	}
	if _, err := configCall(t, `{"id":3,"method":"config.defaults.setPermissionMode","value":"bypassPermissions"}`); err == nil ||
		!strings.Contains(err.Error(), "完全放行") {
		t.Errorf("bypass: %v", err)
	}

	out := mustConfig(t, `{"id":4,"method":"config.memory.read","cwd":"`+home+`"}`)
	var mem struct {
		Instructions struct {
			Path   string
			Exists bool
		}
		Projects []struct{ Key string }
	}
	if err := json.Unmarshal([]byte(out), &mem); err != nil || !mem.Instructions.Exists || len(mem.Projects) != 1 {
		t.Fatalf("memory %s %v", out, err)
	}
	doc := mustConfig(t, `{"id":5,"method":"config.memory.readFile","path":"`+mem.Instructions.Path+`"}`)
	var d struct{ Content, SHA string }
	_ = json.Unmarshal([]byte(doc), &d)
	if d.Content != "回答用中文\n" {
		t.Fatalf("file %s", doc)
	}
	write := func(sha string) string {
		line, _ := json.Marshal(obj{"id": 6, "method": "config.memory.writeFile", "path": mem.Instructions.Path, "content": "新的", "sha": sha})
		return mustConfig(t, string(line))
	}
	if out := write("stale"); out != `{"conflict":true}` {
		t.Errorf("conflict %s", out)
	}
	if out := write(d.SHA); !strings.HasPrefix(out, `{"sha":"`) {
		t.Errorf("write %s", out)
	}

	// Skill 的 ZIP 以 base64 放在 zip 里
	var zb bytes.Buffer
	zw := zip.NewWriter(&zb)
	f, _ := zw.Create("pdf/SKILL.md")
	_, _ = f.Write([]byte("---\nname: pdf\ndescription: 处理 PDF\n---\n"))
	_ = zw.Close()
	line := `{"id":7,"method":"config.skills.import","fileName":"pdf.zip","zip":"` + base64.StdEncoding.EncodeToString(zb.Bytes()) + `"}`
	if out := mustConfig(t, line); !strings.Contains(out, `"name":"pdf"`) || !strings.Contains(out, `"enabled":true`) {
		t.Errorf("import %s", out)
	}
	mustConfig(t, `{"id":8,"method":"config.skills.setEnabled","name":"pdf","enabled":false}`)
	if out := mustConfig(t, `{"id":9,"method":"config.skills.list"}`); !strings.Contains(out, `"enabled":false`) {
		t.Errorf("skills %s", out)
	}

	if out := mustConfig(t, `{"id":10,"method":"config.agents.save","original":null,"draft":{"name":"rev","description":"审查","tools":null,"prompt":"p"}}`); out != `{"file":"rev.md"}` {
		t.Errorf("agent %s", out)
	}
	if out := mustConfig(t, `{"id":11,"method":"config.agents.list"}`); !strings.Contains(out, `"file":"rev.md"`) || !strings.Contains(out, `"tools":null`) {
		t.Errorf("agents %s", out)
	}

	out = mustConfig(t, `{"id":12,"method":"config.permissions.add","behavior":"deny","rule":"Bash(rm:*)"}`)
	if !strings.Contains(out, `"rules":[{"behavior":"deny","rule":"Bash(rm:*)","malformed":false}]`) {
		t.Errorf("permissions %s", out)
	}
	out = mustConfig(t, `{"id":13,"method":"config.permissions.bulk","group":{"tools":["Bash"]},"enable":true}`)
	if !strings.Contains(out, `{"behavior":"allow","rule":"Bash","malformed":false}`) || strings.Contains(out, "rm:*") {
		t.Errorf("bulk %s", out)
	}
	if out := mustConfig(t, `{"id":14,"method":"config.mcp.list"}`); out != `[]` {
		t.Errorf("mcp %s", out)
	}

	for _, bad := range []string{
		`{"id":1,"method":"config.nope"}`,
		`{"id":1,"method":"config.memory.readFile","path":"` + home + `/.bashrc"}`,
		`{"id":1,"method":"config.permissions.add","behavior":"maybe","rule":"Bash"}`,
		`{"id":1,"method":"config.defaults.setCompactWindow","value":"big"}`,
	} {
		if _, err := configCall(t, bad); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
	// settings.json 里别的键一直留着
	if !strings.Contains(readFileString(t, filepath.Join(home, ".claude", "settings.json")), `"theme": "dark"`) {
		t.Error("theme lost")
	}
}

// 同时改 settings.json 的请求各自按校验值写回，撞上了重做，一个都不丢。
func TestServeConfigConcurrentWrites(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	rules := []string{"Bash(a:*)", "Bash(b:*)", "Bash(c:*)", "Bash(d:*)"}
	var wg sync.WaitGroup
	for _, r := range rules {
		wg.Go(func() {
			_, _ = configCall(t, `{"method":"config.permissions.add","behavior":"ask","rule":"`+r+`"}`)
		})
	}
	wg.Wait()
	out := mustConfig(t, `{"method":"config.permissions.read"}`)
	for _, r := range rules {
		if !strings.Contains(out, r) {
			t.Errorf("%s lost: %s", r, out)
		}
	}
}

func readFileString(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

// 经 serve：config. 的请求各自回复，结果在 result 里。
func TestServeRoutesConfig(t *testing.T) {
	e := testEnv(t)
	c := startServe(t, e, fastServe)
	c.send(`{"id":"a","method":"config.defaults.setModel","value":"sonnet"}`)
	r := c.next("zeta_reply")
	res, _ := r["result"].(obj)
	if r["id"] != "a" || r["ok"] != true || res["model"] != "sonnet" {
		t.Fatalf("reply %v", r)
	}
	if !strings.Contains(readFileString(t, filepath.Join(e.home, ".claude", "settings.json")), `"model": "sonnet"`) {
		t.Error("settings not written under the serve's HOME")
	}
	c.send(`{"id":2,"method":"config.memory.readFile","path":"/etc/passwd"}`)
	if r := c.next("zeta_reply"); r["ok"] != false || !strings.Contains(asString(r["error"]), "只能编辑") {
		t.Errorf("error reply %v", r)
	}
}
