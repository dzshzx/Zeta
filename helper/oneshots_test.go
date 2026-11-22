package main

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 核心上做完就答的方法（oneshots.go、endelsewhere.go）：经 serve 发到核心，结果和同名子命令的一样。

func startOneShots(t *testing.T) (*env, *serveClient) {
	t.Helper()
	e := testEnv(t)
	inProcessCore(t, e, fastWatch)
	return e, startServe(t, e, testIdle)
}

// result 是 ok 给的 result（一个对象）。
func result(c *serveClient, method, params string) obj {
	c.t.Helper()
	return asObj(c.ok(method, params))
}

// refused 发请求，要它出错，给出 code。
func refused(c *serveClient, method, params string) string {
	c.t.Helper()
	r := c.call(method, params)
	if r["ok"] != false || r["error"] == "" {
		c.t.Fatalf("%s %s 该出错：%v", method, params, r)
	}
	return asString(r["code"])
}

func quoted(s string) string { return strconv.Quote(s) }

func TestStorageMethodsOverServe(t *testing.T) {
	e, c := startOneShots(t)
	t.Setenv("ZETA_STORAGE_TEST_TMP", filepath.Join(t.TempDir(), "zeta-test-tmp"))
	writeSession(t, e, sessionA, "你好")
	writeFile(t, e.claude+"/settings.json", `{"cleanupPeriodDays": 7}`)
	debug := e.claude + "/debug/dddddddd-dddd-dddd-dddd-dddddddddddd.txt"
	writeFile(t, debug, strings.Repeat("d", 5000))
	age(t, debug, 2*time.Hour)
	keep := `"keep":["` + sessionA + `"]`

	report := result(c, "storage.scan", keep)
	var keys []string
	for _, k := range asList(report["kinds"]) {
		keys = append(keys, asString(asObj(k)["key"]))
	}
	if len(keys) != len(storagePlans) || keys[0] != storagePlans[0].key || num(t, report["cleanupPeriodDays"]) != 7 {
		t.Fatalf("scan %v", report)
	}
	projects := asList(report["projects"])
	if len(projects) != 1 || asObj(projects[0])["dir"] != "-w" || asObj(projects[0])["cwd"] != "/w" || num(t, asObj(projects[0])["sessions"]) != 1 {
		t.Fatalf("projects %v", projects)
	}
	usage := result(c, "storage.sessions", `"project":"-w",`+keep)
	sessions := asList(usage["sessions"])
	if len(sessions) != 1 || asObj(sessions[0])["id"] != sessionA || asObj(sessions[0])["live"] != true {
		t.Fatalf("storage.sessions %v", usage)
	}
	cleaned := result(c, "storage.clean", `"kind":"claude-debug"`)
	if cleaned["key"] != "claude-debug" || num(t, cleaned["removed"]) != 1 || cleaned["busy"] != nil {
		t.Fatalf("clean %v", cleaned)
	}
	if _, err := os.Stat(debug); !os.IsNotExist(err) {
		t.Fatalf("没删掉：%v", err)
	}
	refused(c, "storage.clean", `"kind":"apt"`)
	refused(c, "storage.sessions", `"project":".."`)
	refused(c, "storage.scan", `"keep":["x y"]`)
}

func TestFileMethodsOverServe(t *testing.T) {
	_, c := startOneShots(t)
	dir := t.TempDir()
	writeFile(t, dir+"/b.txt", "你好\n")
	writeFile(t, dir+"/sub/x", "x")
	if err := os.Symlink(dir+"/gone", dir+"/broken"); err != nil {
		t.Fatal(err)
	}
	l := result(c, "files.ls", `"path":`+quoted(dir+"/sub/.."))
	var names []string
	for _, it := range asList(l["entries"]) {
		names = append(names, asString(asObj(it)["name"]))
	}
	if l["path"] != dir || num(t, l["total"]) != 3 || !slices.Equal(names, []string{"sub", "b.txt", "broken"}) {
		t.Fatalf("ls %v", l)
	}
	if broken := asObj(asList(l["entries"])[2]); broken["kind"] != "link" || broken["broken"] != true {
		t.Fatalf("断了的链接 %v", broken)
	}
	if code := refused(c, "files.ls", `"path":`+quoted(dir+"/missing")); code != "missing" {
		t.Fatalf("ls missing：code %q", code)
	}
	if code := refused(c, "files.ls", `"path":`+quoted(dir+"/b.txt")); code != "not_dir" {
		t.Fatalf("ls 文件：code %q", code)
	}
	refused(c, "files.ls", `"path":"relative"`)

	if got := result(c, "files.read", `"path":`+quoted(dir+"/b.txt")); got["text"] != "你好\n" || num(t, got["size"]) != 7 || got["truncated"] != false {
		t.Fatalf("read %v", got)
	}
	if got := result(c, "files.read", `"path":`+quoted(dir+"/b.txt")+`,"bytes":3`); got["text"] != "你" || got["truncated"] != true {
		t.Fatalf("read 前 3 字节 %v", got)
	}
	if code := refused(c, "files.read", `"path":`+quoted(dir+"/sub")); code != "not_file" {
		t.Fatalf("read 目录：code %q", code)
	}
	if code := refused(c, "files.read", `"path":`+quoted(dir+"/missing")); code != "missing" {
		t.Fatalf("read missing：code %q", code)
	}

	png := []byte("\x89PNG\r\n\x1a\nfake")
	if err := os.WriteFile(dir+"/p.png", png, 0o644); err != nil {
		t.Fatal(err)
	}
	if got := result(c, "images.file", `"path":`+quoted(dir+"/p.png")); got["media_type"] != "image/png" || got["data"] != base64.StdEncoding.EncodeToString(png) {
		t.Fatalf("images.file %v", got)
	}
	refused(c, "images.file", `"path":`+quoted(dir+"/b.txt"))
}

// sessions.end_elsewhere 不管那边闲不闲都结束（同 zeta-helper end）；别的会话的不动。
func TestEndElsewhereOverServe(t *testing.T) {
	e, c := startOneShots(t)
	const id = "aaaaaaaa-1111-4222-8333-444444444444"
	busy, busyDone := fakeClaude(t, false)
	register(t, e, busy, id, "", `,"status":"busy"`)
	other, otherDone := fakeClaude(t, false)
	register(t, e, other, "bbbbbbbb-1111-4222-8333-444444444444", "")

	if got := result(c, "sessions.end_elsewhere", `"session":"`+id+`"`); num(t, got["ended"]) != 1 {
		t.Fatalf("end_elsewhere %v", got)
	}
	if !exited(busyDone) {
		t.Fatal("忙着的那个没结束")
	}
	if !stillUp(otherDone) {
		t.Fatal("别的会话的进程被结束了")
	}
	refused(c, "sessions.end_elsewhere", `"session":"../x"`)
}
