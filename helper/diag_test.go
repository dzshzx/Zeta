package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/helper/redact"
)

// diagHome 在临时家目录里摆好三类日志：两个托管目录的 err.log（一个带标题），stderr/ 下 25 份结束的（只该列 20 份，外加一个
// 不是 .log 的），~/.claude/debug 下 2 个调试日志和一个 latest 符号链接。
func diagHome(t *testing.T) *env {
	t.Helper()
	e := newEnvAt(t.TempDir(), "zeta-dev")
	base := time.Date(2026, 11, 20, 10, 0, 0, 0, time.UTC)
	write := func(path, text string, at time.Time) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(path, at, at); err != nil {
			t.Fatal(err)
		}
	}
	write(e.hosts+"/a1/err.log", "older running", base)
	write(e.hosts+"/a1/title", "修\t好\n登录", base)
	write(e.hosts+"/b2/err.log", "newer running", base.Add(time.Minute))
	for i := range 25 {
		write(e.diagCache()+"/stderr/"+strings.Repeat("x", i+1)+".log", "finished", base.Add(time.Duration(i)*time.Second))
	}
	write(e.diagCache()+"/stderr/notes.txt", "not a log", base)
	write(e.claude+"/debug/one.txt", "debug one", base)
	write(e.claude+"/debug/two.txt", "debug two", base.Add(time.Hour))
	if err := os.Symlink(e.claude+"/debug/two.txt", e.claude+"/debug/latest"); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestDiagFilesListsThreeKindsNewestFirst(t *testing.T) {
	e := diagHome(t)
	v, err := diagFilesMethod(e, oneShotParams{})
	if err != nil {
		t.Fatal(err)
	}
	files := v.(diagFilesReply).Files
	count := map[string]int{}
	for _, f := range files {
		count[f.Kind]++
	}
	if count[diagRunning] != 2 || count[diagFinished] != diagFinishedLimit || count[diagDebug] != 2 {
		t.Fatalf("各类的份数不对：%v", count)
	}
	if files[0].Path != e.hosts+"/b2/err.log" || files[1].Title != "修 好 登录" || files[0].Title != "" {
		t.Errorf("在跑的：%+v %+v", files[0], files[1])
	}
	// 结束的留最新的 20 份：最旧的 5 份（名字最短的）不列
	fin := files[2]
	if fin.Kind != diagFinished || fin.Path != e.diagCache()+"/stderr/"+strings.Repeat("x", 25)+".log" || fin.Size != int64(len("finished")) {
		t.Errorf("结束的第一份：%+v", fin)
	}
	last := files[len(files)-1]
	if last.Kind != diagDebug || last.Path != e.claude+"/debug/one.txt" {
		t.Errorf("最后一份应是旧的调试日志（latest 链接不列）：%+v", last)
	}
	if files[len(files)-2].Modified-last.Modified != time.Hour.Milliseconds() {
		t.Errorf("修改时间是毫秒：%+v", files[len(files)-2:])
	}
}

func TestDiagFilesOnAnEmptyHome(t *testing.T) {
	v, err := diagFilesMethod(newEnvAt(t.TempDir(), "zeta"), oneShotParams{})
	if err != nil || len(v.(diagFilesReply).Files) != 0 {
		t.Fatalf("空家目录：%v %v", v, err)
	}
}

func TestDiagReadableOnlyThoseThreePlaces(t *testing.T) {
	e := newEnvAt("/home/u", "zeta")
	for _, p := range []string{
		"/home/u/.claude/debug/x.txt",
		"/home/u/.cache/zeta/stderr/x.log",
		"/home/u/.cache/zeta/hosts/abc/err.log",
	} {
		if !diagReadable(e, p) {
			t.Errorf("%s 应能读", p)
		}
	}
	for _, p := range []string{
		"",
		"relative/x.log",
		"/home/u/.claude/debug/",
		"/home/u/.claude/debug/sub/x.txt",
		"/home/u/.claude/debug/../settings.json",
		"/home/u/.claude/debug/./x.txt",
		"/home/u/.claude//debug/x.txt",
		"/home/u/.cache/zeta-dev/stderr/x.log", // 别的包的
		"/home/u/.cache/zeta/hosts/abc/out.jsonl",
		"/home/u/.cache/zeta/hosts/err.log",
		"/home/u/.cache/zeta/hosts/a/b/err.log",
		"/home/u/.claude/.credentials.json",
		"/etc/passwd",
	} {
		if diagReadable(e, p) {
			t.Errorf("%s 不该能读", p)
		}
	}
}

func TestDiagTailCutsAtTheEndAndRedacts(t *testing.T) {
	e := newEnvAt(t.TempDir(), "zeta")
	path := e.diagCache() + "/stderr/big.log"
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	// 开头一段要被截掉；截断点落在「汉」字（3 字节）中间；结尾一行有个假 token
	tail := "Authorization: Bearer fake-token-1\nend"
	head := strings.Repeat("a", 10) + "\xff"
	body := head + strings.Repeat("汉", diagTailBytes/3) + tail
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	v, err := diagTailMethod(e, oneShotParams{Path: path})
	if err != nil {
		t.Fatal(err)
	}
	r := v.(diagTailReply)
	if !r.Truncated || r.Size != int64(len(body)) || len(r.Text) > diagTailBytes || !strings.HasSuffix(r.Text, tail) {
		t.Fatalf("截断不对：truncated=%v size=%d len=%d", r.Truncated, r.Size, len(r.Text))
	}
	if strings.ContainsRune(r.Text, utf8.RuneError) || !strings.HasPrefix(r.Text, "汉") {
		t.Errorf("开头应丢掉切开的半个字和前面的内容：%q", r.Text[:12])
	}
	v, _ = diagTailMethod(e, oneShotParams{Path: path, Redact: true})
	if txt := v.(diagTailReply).Text; strings.Contains(txt, "fake-token-1") || !strings.Contains(txt, redact.Mask) {
		t.Errorf("要求打码时没打：%q", txt[len(txt)-60:])
	}
}

func TestDiagTailSmallFileAndBadBytes(t *testing.T) {
	e := newEnvAt(t.TempDir(), "zeta")
	path := e.claude + "/debug/x.txt"
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("ok\xffok"), 0o644); err != nil {
		t.Fatal(err)
	}
	v, err := diagTailMethod(e, oneShotParams{Path: path})
	if err != nil {
		t.Fatal(err)
	}
	if r := v.(diagTailReply); r.Truncated || r.Text != "ok\uFFFDok" || r.Size != 5 {
		t.Errorf("小文件：%+v", r)
	}
}

func TestDiagTailRefusesOtherPathsAndLinks(t *testing.T) {
	e := newEnvAt(t.TempDir(), "zeta")
	if _, err := diagTailMethod(e, oneShotParams{Path: e.claude + "/settings.json"}); !errors.Is(err, errBadRequest) {
		t.Errorf("别处的文件应拒绝：%v", err)
	}
	secret := e.home + "/secret"
	link := e.claude + "/debug/latest"
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(secret, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(secret, link); err != nil {
		t.Fatal(err)
	}
	if _, err := diagTailMethod(e, oneShotParams{Path: link}); err == nil {
		t.Error("符号链接不该跟过去读")
	}
	if _, err := diagTailMethod(e, oneShotParams{Path: e.claude + "/debug/none.txt"}); err == nil {
		t.Error("没有的文件应出错")
	}
}
