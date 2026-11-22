package claudecfg

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestAgentsSaveKeepsUnknownKeysAndRenames(t *testing.T) {
	home, c := newHost(t)
	dir := filepath.Join(home, ".claude", "agents")
	write(t, filepath.Join(dir, "team", "reviewer.md"),
		"---\n# 注释\nname: reviewer\ndescription: 审查改动\\n逐条列出\ncolor: blue\ntools: Read, Grep\nskills:\n  - pdf\n---\n\n你是审查员。\n")
	write(t, filepath.Join(dir, "plain.md"), "没有前言\n")
	list := c.Agents()
	if len(list) != 2 || list[0].File != "plain.md" || list[1].File != "team/reviewer.md" {
		t.Fatalf("agents %+v", list)
	}
	a := list[1]
	if a.Description != "审查改动\n逐条列出" || !slices.Equal(a.Tools, []string{"Read", "Grep"}) || a.Prompt != "你是审查员。" ||
		!slices.Equal(a.ExtraKeys, []string{"color", "skills"}) || list[0].Tools != nil {
		t.Fatalf("parsed %+v", a)
	}

	// 改名：放在原来的子目录里，旧文件删掉，不认识的键和注释原样留着
	file, err := c.SaveAgent(&a, SubagentDraft{Name: "code-reviewer", Description: "审查: 改动", Model: "opus", Tools: []string{}, Prompt: "新提示词"})
	if err != nil || file != "team/code-reviewer.md" {
		t.Fatal(file, err)
	}
	want := "---\nname: code-reviewer\ndescription: \"审查: 改动\"\nmodel: opus\ntools: []\n# 注释\ncolor: blue\nskills:\n  - pdf\n---\n\n新提示词\n"
	if got := read(t, filepath.Join(dir, file)); got != want {
		t.Errorf("saved:\n%s\nwant:\n%s", got, want)
	}
	if exists(filepath.Join(dir, "team", "reviewer.md")) {
		t.Error("old file kept")
	}

	// 新建：同名的已经有了
	if _, err := c.SaveAgent(nil, SubagentDraft{Name: "plain", Description: "x"}); err == nil || !strings.Contains(err.Error(), "已经有一个叫 plain") {
		t.Errorf("duplicate: %v", err)
	}
	// 编辑期间被别人改过
	stale := c.Agents()[0]
	write(t, filepath.Join(dir, "plain.md"), "改过了\n")
	if _, err := c.SaveAgent(&stale, SubagentDraft{Name: "plain", Description: "x"}); err == nil || !strings.Contains(err.Error(), "被改过了") {
		t.Errorf("stale: %v", err)
	}
	for _, d := range []SubagentDraft{{Name: "Bad Name", Description: "x"}, {Name: "ok"}} {
		if _, err := c.SaveAgent(nil, d); err == nil {
			t.Errorf("draft %+v accepted", d)
		}
	}
	if err := c.DeleteAgent("plain.md"); err != nil || exists(filepath.Join(dir, "plain.md")) {
		t.Errorf("delete: %v", err)
	}
	for _, bad := range []string{"../x.md", "/etc/x.md", "a/.hidden.md", "x.txt"} {
		if err := c.DeleteAgent(bad); err == nil {
			t.Errorf("delete %q accepted", bad)
		}
	}
	if _, err := os.Stat(home); err != nil {
		t.Fatal(err)
	}
}
