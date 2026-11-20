package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestSafeChildOnlyAcceptsTheExpectedLayer(t *testing.T) {
	root := t.TempDir()
	writeFile(t, root+"/file", "x")
	writeFile(t, root+"/dir/inner", "x")
	if err := os.Symlink(root+"/dir", root+"/linkdir"); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(root, root+"-link"); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		root, name string
		dir, ok    bool
	}{
		{root, "file", false, true},
		{root, "dir", true, true},
		{root, "file", true, false},          // 种类不对
		{root, "dir", false, false},          // 种类不对
		{root, "linkdir", true, false},       // 自己是符号链接
		{root + "-link", "dir", true, false}, // 根是符号链接
		{root, "..", true, false},
		{root, "dir/inner", false, false}, // 不是直接下一层
		{root, "", true, false},
		{root, "missing", false, false},
	}
	for _, c := range cases {
		if _, ok := safeChild(c.root, c.name, c.dir); ok != c.ok {
			t.Errorf("safeChild(%s, %q, %v) = %v", filepath.Base(c.root), c.name, c.dir, ok)
		}
	}
}

func TestRemoveCandidateRechecksBeforeDeleting(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	writeFile(t, outside+"/precious", "keep")
	writeFile(t, root+"/victim/a", "x")
	c := candidate{root: root, name: "victim", dir: true, size: 100}
	// 列出候选之后被人换成了指向外面的链接：不删，也不跟进去
	if err := os.RemoveAll(root + "/victim"); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, root+"/victim"); err != nil {
		t.Fatal(err)
	}
	if _, ok := removeCandidate(c); ok {
		t.Error("removed a symlink candidate")
	}
	if _, err := os.Stat(outside + "/precious"); err != nil {
		t.Error("followed the symlink")
	}
	writeFile(t, root+"/real/sub/f", "x")
	if err := os.Symlink(outside, root+"/real/sub/escape"); err != nil {
		t.Fatal(err)
	}
	if freed, ok := removeCandidate(candidate{root: root, name: "real", dir: true, size: 42}); !ok || freed != 42 || lexists(root+"/real") {
		t.Errorf("real dir: %d %v", freed, ok)
	}
	if _, err := os.Stat(outside + "/precious"); err != nil {
		t.Error("deleted through a symlink inside the tree")
	}
}

func TestStorageTmpNeedsTheTestPrefix(t *testing.T) {
	cases := map[string]string{
		"/x/zeta-test-tmp-1":     "/x/zeta-test-tmp-1",
		"/x/zeta-test-tmp/":      "/x/zeta-test-tmp/",
		"relative/zeta-test-tmp": "/tmp",
		"/x/other":               "/tmp",
		"/":                      "/tmp",
		"":                       "/tmp",
	}
	for v, want := range cases {
		t.Setenv("ZETA_STORAGE_TEST_TMP", v)
		if got := storageTmp(); got != want {
			t.Errorf("storageTmp(%q) = %q", v, got)
		}
	}
}

func cleanedKeys(t *testing.T, e *env, kind string, keep ...string) map[string]bool {
	t.Helper()
	p := findPlan(kind)(newStorageCtx(e, keep))
	names := map[string]bool{}
	for _, c := range p.items {
		names[c.name] = true
	}
	return names
}

func TestStoragePlansKeepLiveFreshAndForeignThings(t *testing.T) {
	e := testEnv(t)
	tmp := filepath.Join(t.TempDir(), "zeta-test-tmp-unit")
	t.Setenv("ZETA_STORAGE_TEST_TMP", tmp)
	old, fresh := 72*time.Hour, time.Minute
	keep := "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	gone := "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
	newer := "cccccccc-cccc-cccc-cccc-cccccccccccc"
	withLog := "dddddddd-dddd-dddd-dddd-dddddddddddd"
	writeLines(t, e.root+"/-p/"+withLog+".jsonl", `{"type":"user"}`)
	for _, id := range []string{keep, gone, newer, withLog} {
		writeFile(t, e.claude+"/debug/"+id+".txt", "d")
		age(t, e.claude+"/debug/"+id+".txt", old)
		writeFile(t, e.claude+"/file-history/"+id+"/f", "f")
		age(t, e.claude+"/file-history/"+id+"/f", old)
		age(t, e.claude+"/file-history/"+id, old)
	}
	age(t, e.claude+"/debug/"+newer+".txt", fresh)
	age(t, e.claude+"/file-history/"+newer, fresh)
	debug := cleanedKeys(t, e, "claude-debug", keep)
	if !debug[gone+".txt"] || !debug[withLog+".txt"] || debug[keep+".txt"] || debug[newer+".txt"] {
		t.Errorf("claude-debug candidates %v", debug)
	}
	residue := cleanedKeys(t, e, "claude-residue", keep)
	if !residue[gone] || residue[withLog] || residue[keep] || residue[newer] {
		t.Errorf("claude-residue candidates %v", residue)
	}
	claude := fmt.Sprintf("%s/claude-%d/-w", tmp, os.Getuid())
	for _, name := range []string{keep, gone} {
		writeFile(t, claude+"/"+name+"/x", "x")
		age(t, claude+"/"+name+"/x", old)
		age(t, claude+"/"+name, old)
	}
	writeFile(t, tmp+"/oldfile", "o")
	age(t, tmp+"/oldfile", old)
	writeFile(t, tmp+"/tmux-1000/default", "s")
	age(t, tmp+"/tmux-1000/default", old)
	age(t, tmp+"/tmux-1000", old)
	writeFile(t, tmp+"/piped/readme", "r")
	if err := syscall.Mkfifo(tmp+"/piped/fifo", 0o600); err != nil {
		t.Fatal(err)
	}
	age(t, tmp+"/piped/readme", old)
	age(t, tmp+"/piped", old)
	tmpItems := cleanedKeys(t, e, "tmp", keep)
	if !tmpItems["oldfile"] || !tmpItems[gone] || tmpItems[keep] || tmpItems["tmux-1000"] || tmpItems["piped"] {
		t.Errorf("tmp candidates %v", tmpItems)
	}
}

func TestStorageSessionsAndCleanOutputs(t *testing.T) {
	e := testEnv(t)
	id := "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
	writeLines(t, e.root+"/-p/"+id+".jsonl", `{"type":"user","cwd":"/work/\"q\""}`)
	writeFile(t, e.root+"/-p/"+id+"/tool-results/r", "r")
	writeFile(t, e.root+"/-p/memory/MEMORY.md", "m")
	out := mustCall(t, e, "storage-sessions", "-p", id)
	if len(out) != 2 || out[0]["id"] != id || out[0]["live"] != true || num(t, out[0]["bytes"]) <= 0 || out[1]["type"] != "zeta_storage_rest" {
		t.Errorf("storage-sessions %v", out)
	}
	if _, err := call(t, e, "storage-sessions", ".."); err == nil {
		t.Error("accepted ..")
	}
	if got := projectCwd(e.root+"/-p", []string{id + ".jsonl"}); got != `/work/"q"` {
		t.Errorf("projectCwd %q", got)
	}
	t.Setenv("ZETA_STORAGE_TEST_TMP", filepath.Join(t.TempDir(), "zeta-test-tmp"))
	cleaned := mustCall(t, e, "clean", "claude-debug")
	if len(cleaned) != 1 || cleaned[0]["type"] != "zeta_cleaned" || cleaned[0]["busy"] != nil {
		t.Errorf("clean %v", cleaned)
	}
	if _, err := call(t, e, "clean", "apt"); err != errUsage {
		t.Errorf("clean apt: %v", err)
	}
}
