package main

import (
	"bytes"
	"os"
	"strings"
	"testing"
)

func TestBadArgumentsPrintUsageAndExit1(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	bad := [][]string{
		{}, {"nope"}, {"history"}, {"history", "/etc/passwd"}, {"rename", "x"}, {"archive", "1"}, {"archive", "abc", "1"},
		{"image", "x"}, {"file-image"}, {"search"}, {"lookup", "x"}, {"export"}, {"ls", "rel"}, {"read", "/x", "-1"},
		{"read", "/x", "１"}, {"storage", "bad!"}, {"storage-sessions"}, {"clean", "apt"}, {"clean"},
		{"subagent", "0", "0", "t", "-"}, {"subagent", "0", "x", "t", "-", "aaaaaaaa"},
	}
	for _, args := range bad {
		var stdout, stderr bytes.Buffer
		if code := run(args, &stdout, &stderr); code != 1 || !strings.HasPrefix(stderr.String(), "usage: list |") || stdout.Len() != 0 {
			t.Errorf("%q: exit %d, stderr %q, stdout %q", args, code, stderr.String(), stdout.String())
		}
	}
	var stdout, stderr bytes.Buffer
	if code := run([]string{"list", "ignored"}, &stdout, &stderr); code != 0 || stdout.Len() != 0 {
		t.Errorf("list: %d %q %q", code, stdout.String(), stderr.String())
	}
}

func TestRenameAppendsCompactCustomTitle(t *testing.T) {
	e := testEnv(t)
	id := "77777777-7777-7777-7777-777777777777"
	file := e.root + "/-p/" + id + ".jsonl"
	writeFile(t, file, `{"type":"user","message":{"content":"hi"}}`) // 末尾没有换行
	out := mustCall(t, e, "rename", file, "  新\t标题 \"q\" \\ \x01  ")
	if out[0]["title"] != "新 标题 \"q\" \\ \x01" {
		t.Errorf("title %q", out[0]["title"])
	}
	data, _ := os.ReadFile(file)
	want := `{"type":"user","message":{"content":"hi"}}` + "\n" +
		`{"type":"custom-title","customTitle":"新 标题 \"q\" \\ \u0001","sessionId":"` + id + `"}` + "\n"
	if string(data) != want {
		t.Errorf("file:\n%s\nwant:\n%s", data, want)
	}
	if _, err := call(t, e, "rename", file, " \n "); err == nil {
		t.Error("empty title accepted")
	}
	list := mustCall(t, e, "lookup", id)
	if len(list) != 1 || list[0]["title"] != "新 标题 \"q\" \\ \x01" {
		t.Errorf("lookup after rename %v", list)
	}
}

func TestDeleteRemovesSessionDirWithoutFollowingLinks(t *testing.T) {
	e := testEnv(t)
	outside := t.TempDir()
	writeFile(t, outside+"/precious", "keep")
	id := "88888888-8888-8888-8888-888888888888"
	file := e.root + "/-p/" + id + ".jsonl"
	writeLines(t, file, `{"type":"user","message":{"content":"x"}}`)
	writeFile(t, e.root+"/-p/"+id+"/subagents/agent-a.jsonl", "{}")
	if err := os.Symlink(outside, e.root+"/-p/"+id+"/escape"); err != nil {
		t.Fatal(err)
	}
	out := mustCall(t, e, "delete", file, e.root+"/-p/99999999-9999-9999-9999-999999999999.jsonl")
	if out[0]["dir"] != true || out[1]["dir"] != false || lexists(file) || lexists(e.root+"/-p/"+id) {
		t.Errorf("delete %v", out)
	}
	if _, err := os.Stat(outside + "/precious"); err != nil {
		t.Error("deleted through a symlink")
	}
	if _, err := call(t, e, "delete", "/etc/passwd"); err != errUsage {
		t.Error("deleted outside ~/.claude/projects")
	}
}

func TestArchiveKeepsOnlyExistingSessions(t *testing.T) {
	e := testEnv(t)
	a, b := "aaaaaaaa-0000-0000-0000-000000000000", "bbbbbbbb-0000-0000-0000-000000000000"
	writeLines(t, e.root+"/-p/"+a+".jsonl", `{}`)
	writeFile(t, e.archive, "stale-id\n  "+a+"  \n")
	out := mustCall(t, e, "archive", b, "1")
	data, _ := os.ReadFile(e.archive)
	if string(data) != a+"\n"+b+"\n" || out[0]["archived"] != true {
		t.Errorf("archive file %q, out %v", data, out)
	}
	// 取消归档时顺手清掉记录已经不在的（b 没有记录文件）
	mustCall(t, e, "archive", a, "0")
	if data, _ := os.ReadFile(e.archive); string(data) != "" {
		t.Errorf("after unarchive %q", data)
	}
}
