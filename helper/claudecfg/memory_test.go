package claudecfg

import (
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestJSONKeepsOrderAndFormat(t *testing.T) {
	in := `{"b":1,"a":{"z":[],"y":{}},"s":"中文<&>\n\"","n":1.50,"l":[true,null]}`
	o, err := parseObject([]byte(in))
	if err != nil {
		t.Fatal(err)
	}
	o.set("c", "新")
	want := "{\n  \"b\": 2,\n  \"a\": {\n    \"z\": [],\n    \"y\": {}\n  },\n  \"s\": \"中文<&>\\n\\\"\",\n  \"n\": 1.50,\n  \"l\": [\n    true,\n    null\n  ],\n  \"c\": \"新\"\n}\n"
	o.set("b", json.Number("2"))
	if got := renderJSON(o); got != want {
		t.Errorf("render:\n%s\nwant:\n%s", got, want)
	}
	if _, err := parseObject([]byte(`{"a":1} x`)); err == nil {
		t.Error("trailing garbage accepted")
	}
}

func TestProjectKey(t *testing.T) {
	// 期望值是 Kotlin 版 ClaudePathsTest 的（从 Claude Code 的程序里核对过的算法）
	if got := projectKey("/workspace/claude"); got != "-workspace-claude" {
		t.Errorf("plain %q", got)
	}
	// 汉字一个码元一个 -，emoji 是两个码元，两个 -
	if got := projectKey("/home/u/项目😀/x"); got != "-home-u------x" {
		t.Errorf("utf16 %q", got)
	}
	long := projectKey("/data/" + strings.Repeat("a", 250))
	if len(long) != 207 || !strings.HasSuffix(long, "-jdek9i") {
		t.Errorf("long %q", long)
	}
	if neg := projectKey("/data/" + strings.Repeat("b", 240) + "/z"); !strings.HasSuffix(neg, "bbbbbbbbbbbbbbbbbbbbbbb-98slzz") {
		t.Errorf("negative hash %q", neg)
	}
	mixed := "/workspace/" + strings.Repeat("very-long-directory-name-", 10) + "项目😀"
	if got := projectKey(mixed); !strings.HasSuffix(got, "very-long-dire-845x7j") || len(got) != 207 {
		t.Errorf("mixed %q", got)
	}
	exact := "/" + strings.Repeat("c", 199)
	if projectKey(exact) != sanitize(exact) {
		t.Error("200 chars got a hash")
	}
}

func TestMemoryOverviewAndEditing(t *testing.T) {
	home, c := newHost(t)
	work := filepath.Join(home, "work", "app")
	key := projectKey(work)
	other := filepath.Join(home, "other")
	write(t, filepath.Join(home, ".claude", "settings.json"), "{\n  \"theme\": \"dark\",\n  \"autoMemoryEnabled\": false\n}\n")
	write(t, filepath.Join(home, ".claude", "CLAUDE.md"), "回答用中文\n")
	mem := filepath.Join(home, ".claude", "projects", key, "memory")
	write(t, filepath.Join(mem, "MEMORY.md"), "# 记忆\n- [偏好](prefs.md) — 用户的偏好\n- [背景](./background.md)\n")
	write(t, filepath.Join(mem, "prefs.md"), "---\nname: 偏好\ndescription: 喜欢 Go\nmetadata:\n  type: feedback\n---\n正文\n")
	write(t, filepath.Join(mem, "background.md"), "---\nname: 背景\ntype: project\n---\n")
	otherKey := projectKey(other)
	write(t, filepath.Join(home, ".claude", "projects", otherKey, "memory", "a.md"), "x")
	write(t, filepath.Join(home, ".claude", "projects", otherKey, "s.jsonl"), `{"cwd":"`+other+`","type":"user"}`+"\n")
	if err := os.MkdirAll(work, 0o755); err != nil {
		t.Fatal(err)
	}

	m := c.Memory(work, "")
	if m.AutoMemory || m.ForcedOff || m.CustomDir != "" {
		t.Errorf("switches %+v", m)
	}
	if !m.Instructions.Exists || m.Instructions.Size != int64(len("回答用中文\n")) {
		t.Errorf("instructions %+v", m.Instructions)
	}
	if len(m.Projects) != 2 || m.Projects[0].Key != key || !m.Projects[0].Default || m.Projects[0].Count != 2 ||
		m.Projects[1].Label != other || m.Projects[1].Count != 1 {
		t.Fatalf("projects %+v", m.Projects)
	}
	if m.Selected != key || m.Index == nil || !m.Index.Exists || len(m.Files) != 2 {
		t.Fatalf("selected %q index %+v files %+v", m.Selected, m.Index, m.Files)
	}
	if f := m.Files[1]; f.FileName != "prefs.md" || f.Name != "偏好" || f.Description != "喜欢 Go" || f.Type != "feedback" {
		t.Errorf("entry %+v", f)
	}
	if m.Files[0].Type != "project" {
		t.Errorf("entry %+v", m.Files[0])
	}
	if m2 := c.Memory(work, otherKey); m2.Selected != otherKey || len(m2.Files) != 1 {
		t.Errorf("other project %+v", m2)
	}

	// 编辑：读到的版本被别人改过时冲突，强制保存覆盖
	doc, err := c.ReadDocument(filepath.Join(mem, "prefs.md"))
	if err != nil || !doc.Exists {
		t.Fatal(doc, err)
	}
	write(t, filepath.Join(mem, "prefs.md"), "Claude 改过了\n")
	if _, err := c.WriteDocument(filepath.Join(mem, "prefs.md"), "我的版本\n", doc.SHA, false); !errors.Is(err, ErrConflict) {
		t.Errorf("want conflict, got %v", err)
	}
	if _, err := c.WriteDocument(filepath.Join(mem, "prefs.md"), "我的版本\n", doc.SHA, true); err != nil {
		t.Fatal(err)
	}
	if got := read(t, filepath.Join(mem, "prefs.md")); got != "我的版本\n" {
		t.Errorf("content %q", got)
	}
	// 还不存在的文件：期望值为空表示应当不存在
	newPath := filepath.Join(mem, "new.md")
	if _, err := c.WriteDocument(newPath, "新记忆", "", false); err != nil {
		t.Fatal(err)
	}
	if _, err := c.WriteDocument(newPath, "又写", "", false); !errors.Is(err, ErrConflict) {
		t.Errorf("second create: %v", err)
	}
	// 记忆目录之外的不让碰
	for _, p := range []string{filepath.Join(home, ".bashrc"), filepath.Join(home, ".claude", "settings.json"), mem + "/../x.md"} {
		if _, err := c.ReadDocument(p); err == nil {
			t.Errorf("%s readable", p)
		}
	}

	// 删一条记忆连同索引里的那一行
	if err := c.DeleteMemory(filepath.Join(mem, "background.md")); err != nil {
		t.Fatal(err)
	}
	if exists(filepath.Join(mem, "background.md")) || strings.Contains(read(t, filepath.Join(mem, "MEMORY.md")), "background") {
		t.Error("memory not deleted")
	}
	if err := c.DeleteMemory(filepath.Join(mem, "MEMORY.md")); err == nil {
		t.Error("MEMORY.md deletable")
	}

	// 开关只改这一个键，别的键和顺序原样
	if err := c.SetAutoMemory(true); err != nil {
		t.Fatal(err)
	}
	if got := read(t, filepath.Join(home, ".claude", "settings.json")); got != "{\n  \"theme\": \"dark\",\n  \"autoMemoryEnabled\": true\n}\n" {
		t.Errorf("settings %q", got)
	}
}

func TestMemoryCustomDirAndForcedOff(t *testing.T) {
	home, c := newHost(t)
	write(t, filepath.Join(home, ".claude", "settings.json"),
		`{"autoMemoryDirectory":"~/notes/","env":{"CLAUDE_CODE_DISABLE_AUTO_MEMORY":"1"}}`)
	write(t, filepath.Join(home, "notes", "x.md"), "---\nname: X\n---\n")
	m := c.Memory("", "")
	if !m.ForcedOff || m.CustomDir != filepath.Join(home, "notes") || m.Projects != nil || len(m.Files) != 1 {
		t.Fatalf("%+v", m)
	}
	if _, err := c.WriteDocument(filepath.Join(home, "notes", "y.md"), "y", "", false); err != nil {
		t.Errorf("custom dir not writable: %v", err)
	}
}

// 同一个仓库的 worktree 共用主仓库的自动记忆（真建一个 git worktree）。
func TestMemoryWorktreeUsesMainRepo(t *testing.T) {
	home, c := newHost(t)
	repo := filepath.Join(home, "repo")
	tree := filepath.Join(home, "trees", "feature")
	git := func(dir string, args ...string) {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = []string{"HOME=" + home, "PATH=" + os.Getenv("PATH"), "GIT_CONFIG_NOSYSTEM=1",
			"GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@example.invalid", "GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@example.invalid"}
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	if err := os.MkdirAll(repo, 0o755); err != nil {
		t.Fatal(err)
	}
	git(repo, "init", "-q")
	git(repo, "commit", "-q", "--allow-empty", "-m", "init")
	git(repo, "worktree", "add", "-q", tree)
	if err := os.MkdirAll(filepath.Join(tree, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	m := c.Memory(filepath.Join(tree, "sub"), "")
	if len(m.Projects) == 0 || m.Projects[0].Key != projectKey(repo) || m.Projects[0].Label != repo {
		t.Errorf("projects %+v, want main repo %s", m.Projects, repo)
	}
}

func TestInvalidSettingsNotTouched(t *testing.T) {
	home, c := newHost(t)
	path := filepath.Join(home, ".claude", "settings.json")
	write(t, path, "{broken")
	if err := c.SetAutoMemory(false); err == nil || !strings.Contains(err.Error(), "不是有效的 JSON") {
		t.Errorf("err %v", err)
	}
	if read(t, path) != "{broken" {
		t.Error("broken settings rewritten")
	}
}
