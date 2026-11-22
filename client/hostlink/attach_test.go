package hostlink

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNormalizePath(t *testing.T) {
	for _, c := range []struct {
		raw, want string
		ok        bool
	}{
		{" /w/proj/ ", "/w/proj", true},
		{"~", "/home/box", true},
		{"~/src/../notes/./a.md", "/home/box/notes/a.md", true},
		{"/", "/", true},
		{"/..", "", false},
		{"src/a.go", "", false},
		{`/w/"x"`, "", false},
		{"/w/a\tb", "", false},
	} {
		got, ok := NormalizePath(c.raw, "/home/box/")
		if got != c.want || ok != c.ok {
			t.Errorf("NormalizePath(%q) = %q, %v; want %q, %v", c.raw, got, ok, c.want, c.ok)
		}
	}
	if Reference("/w/proj/", true) != "@/w/proj/" || Reference("/w/a.md", false) != "@/w/a.md" {
		t.Error("Reference")
	}
}

func TestCleanName(t *testing.T) {
	for raw, want := range map[string]string{
		"报告.pdf":                 "报告.pdf",
		` a\b/c `:                "a_b_c",
		"..":                     "file",
		"":                       "file",
		strings.Repeat("长", 130): strings.Repeat("长", 120),
	} {
		if got := CleanName(raw); got != want {
			t.Errorf("CleanName(%q) = %q, want %q", raw, got, want)
		}
	}
}

func TestCheckPath(t *testing.T) {
	b := newBox(t)
	file := b.file(t, "notes/a.md", "hi", 0o644)
	secret := b.file(t, "notes/secret.md", "no", 0o000)
	for path, want := range map[string]PathKind{
		file:                                  PathFile,
		filepath.Dir(file):                    PathFolder,
		filepath.Join(b.server.Home, "nope"):  PathMissing,
		secret:                                PathDenied,
		filepath.Join(b.server.Home, "a b'c"): PathMissing, // 引号、空格照样传过去
	} {
		if os.Geteuid() == 0 && want == PathDenied {
			continue // root 什么都读得了
		}
		got, err := b.shell.CheckPath(path)
		if err != nil || got != want {
			t.Errorf("CheckPath(%s) = %v, %v; want %v", path, got, err, want)
		}
	}
}

func TestUpload(t *testing.T) {
	b := newBox(t)
	content := bytes.Repeat([]byte("zeta "), 50_000) // 250 KB：几块，进度报几次
	var reported []int64
	path, err := b.shell.Upload(context.Background(), `报告 v2/最终.txt`, bytes.NewReader(content), 1<<20,
		func(n int64) { reported = append(reported, n) })
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(b.server.Home, ".cache", testName, "files")
	if filepath.Dir(filepath.Dir(path)) != dir || filepath.Base(path) != "报告 v2_最终.txt" {
		t.Fatalf("传到了 %s，应在 %s/<id>/ 下", path, dir)
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, content) {
		t.Fatalf("传上去的内容不对（%d 字节，%v）", len(got), err)
	}
	if len(reported) < 2 || reported[len(reported)-1] != int64(len(content)) {
		t.Errorf("进度 %v，最后应是 %d", reported, len(content))
	}
}

func TestUploadTooLargeLeavesNothing(t *testing.T) {
	b := newBox(t)
	_, err := b.shell.Upload(context.Background(), "big.bin", bytes.NewReader(make([]byte, 300_000)), 100_000, nil)
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("err = %v, want ErrTooLarge", err)
	}
	assertNoUploads(t, b)
}

// stalled 先给一块内容，之后一直等到 ctx 取消（像很慢的网络）。
type stalled struct {
	ctx  context.Context
	sent bool
}

func (s *stalled) Read(p []byte) (int, error) {
	if !s.sent {
		s.sent = true
		return copy(p, "first block"), nil
	}
	<-s.ctx.Done()
	return 0, io.ErrUnexpectedEOF
}

func TestUploadCanceledLeavesNothing(t *testing.T) {
	b := newBox(t)
	ctx, cancel := context.WithCancel(context.Background())
	_, err := b.shell.Upload(ctx, "slow.bin", &stalled{ctx: ctx}, 1<<20, func(int64) { cancel() })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	assertNoUploads(t, b)
}

func TestUploadFolder(t *testing.T) {
	b := newBox(t)
	local := filepath.Join(t.TempDir(), "项目 资料")
	for rel, text := range map[string]string{"a.md": "A", "sub/b.txt": "BB", "sub/deep/c.go": "package c"} {
		path := filepath.Join(local, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.MkdirAll(filepath.Join(local, "empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	if size, err := FolderSize(local); err != nil || size != 1+2+9 {
		t.Errorf("FolderSize = %d, %v; want 12", size, err)
	}
	path, err := b.shell.UploadFolder(context.Background(), local, 1<<20, nil)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(path) != "项目 资料" {
		t.Errorf("文件夹传成了 %s", path)
	}
	for rel, want := range map[string]string{"a.md": "A", "sub/b.txt": "BB", "sub/deep/c.go": "package c"} {
		if got, err := os.ReadFile(filepath.Join(path, rel)); err != nil || string(got) != want {
			t.Errorf("%s = %q, %v; want %q", rel, got, err, want)
		}
	}
	if st, err := os.Stat(filepath.Join(path, "empty")); err != nil || !st.IsDir() {
		t.Errorf("空文件夹没带上：%v", err)
	}
}

// assertNoUploads：传失败的都删掉了，files 下没有留下目录。
func assertNoUploads(t *testing.T, b *box) {
	t.Helper()
	entries, err := os.ReadDir(filepath.Join(b.server.Home, ".cache", testName, "files"))
	if err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	if len(entries) > 0 {
		t.Errorf("留下了传了一半的：%v", entries)
	}
}
