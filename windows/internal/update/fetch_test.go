package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// serve 起一个测试用的 HTTP 服务器，路径到内容；没有的路径回 404。
func serve(t *testing.T, files map[string]string) *httptest.Server {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := files[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(body))
	}))
	t.Cleanup(s.Close)
	return s
}

func digest(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func TestGitHubMirrors(t *testing.T) {
	gh := "https://github.com/dzshzx/Zeta/releases/latest/download/update.json"
	if got := GitHubMirrors(gh); !slices.Equal(got, []string{gh, "https://gh-proxy.com/" + gh}) {
		t.Errorf("GitHub: %q", got)
	}
	if got := GitHubMirrors("http://127.0.0.1:8080/update.json"); !slices.Equal(got, []string{"http://127.0.0.1:8080/update.json"}) {
		t.Errorf("elsewhere: %q", got)
	}
}

func TestManifest(t *testing.T) {
	var agent string
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		agent = r.UserAgent()
		w.Write([]byte(manifestJSON(nil)))
	}))
	defer s.Close()
	c := &Client{URL: s.URL + "/update.json", UserAgent: "Zeta-Desktop/0.7.5"}
	m, err := c.Manifest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if m.VersionCode != 812 || m.MSI == nil {
		t.Errorf("got %+v", m)
	}
	if agent != "Zeta-Desktop/0.7.5" {
		t.Errorf("User-Agent %q", agent)
	}
}

// 前一个地址出错（HTTP 错误、内容不对）就换下一个；都不行时每个地址各一句原因。
func TestManifestMirrors(t *testing.T) {
	bad := serve(t, map[string]string{"/update.json": "<html>blocked</html>"})
	good := serve(t, map[string]string{"/update.json": manifestJSON(nil)})
	missing := serve(t, nil)
	mirrors := func(first ...string) func(string) []string {
		return func(string) []string { return first }
	}

	c := &Client{Mirrors: mirrors(missing.URL+"/update.json", bad.URL+"/update.json", good.URL+"/update.json")}
	if m, err := c.Manifest(context.Background()); err != nil || m.VersionCode != 812 {
		t.Fatalf("fallback: %+v, %v", m, err)
	}

	c = &Client{Mirrors: mirrors(missing.URL+"/update.json", bad.URL+"/update.json")}
	_, err := c.Manifest(context.Background())
	want := host(missing.URL) + " 返回 HTTP 404；" + host(bad.URL) + " 返回的更新清单格式无效：不是 JSON 对象"
	if err == nil || err.Error() != want {
		t.Errorf("error %v, want %s", err, want)
	}
}

func TestManifestTooBig(t *testing.T) {
	s := serve(t, map[string]string{"/update.json": strings.Repeat(" ", maxManifestBytes+1)})
	_, err := (&Client{URL: s.URL + "/update.json"}).Manifest(context.Background())
	if err == nil || !strings.Contains(err.Error(), "可能不是有效的清单文件") {
		t.Errorf("error %v", err)
	}
}

func TestManifestUnreachable(t *testing.T) {
	s := serve(t, nil)
	u := s.URL + "/update.json"
	s.Close()
	_, err := (&Client{URL: u}).Manifest(context.Background())
	if err == nil || err.Error() != "无法连接 "+host(u) {
		t.Errorf("error %v", err)
	}
}

// 连上了却一直不给数据：Stall 到点算超时，换下一个地址。
func TestManifestStalls(t *testing.T) {
	release := make(chan struct{})
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer slow.Close()
	defer close(release)
	good := serve(t, map[string]string{"/update.json": manifestJSON(nil)})

	c := &Client{Stall: 50 * time.Millisecond, Mirrors: func(string) []string { return []string{slow.URL, slow.URL} }}
	_, err := c.Manifest(context.Background())
	want := "连接 " + host(slow.URL) + " 超时；连接 " + host(slow.URL) + " 超时"
	if err == nil || err.Error() != want {
		t.Errorf("error %v, want %s", err, want)
	}
	c.Mirrors = func(string) []string { return []string{slow.URL, good.URL + "/update.json"} }
	if _, err := c.Manifest(context.Background()); err != nil {
		t.Errorf("after a stall: %v", err)
	}
}

func TestDownload(t *testing.T) {
	body := strings.Repeat("msi!", 5000)
	s := serve(t, map[string]string{"/Zeta.msi": body})
	dest := filepath.Join(t.TempDir(), "Zeta.msi")
	var seen []int64
	p := Package{File: "Zeta.msi", URL: s.URL + "/Zeta.msi", Size: int64(len(body)), SHA256: digest(body)}
	if err := (&Client{}).Download(context.Background(), p, dest, func(n int64) { seen = append(seen, n) }); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(dest); string(got) != body {
		t.Error("the file differs from what was served")
	}
	if len(seen) == 0 || seen[len(seen)-1] != p.Size || !slices.IsSorted(seen) {
		t.Errorf("progress %v", seen)
	}
	assertOnly(t, filepath.Dir(dest), "Zeta.msi")
}

// 大小、sha256 对不上清单时删掉，换下一个地址；都不对时不留文件。
func TestDownloadMismatch(t *testing.T) {
	body := "the real msi"
	s := serve(t, map[string]string{
		"/short": body[:4],
		"/long":  body + "and more",
		"/other": strings.Repeat("x", len(body)),
		"/good":  body,
	})
	dir := t.TempDir()
	dest := filepath.Join(dir, "Zeta.msi")
	p := Package{File: "Zeta.msi", Size: int64(len(body)), SHA256: digest(body)}
	try := func(paths ...string) error {
		c := &Client{Mirrors: func(string) []string {
			var us []string
			for _, path := range paths {
				us = append(us, s.URL+path)
			}
			return us
		}}
		return c.Download(context.Background(), p, dest, nil)
	}

	err := try("/short", "/long", "/other")
	h := host(s.URL)
	want := "从 " + h + " 下载的文件仅有 4 字节（清单声明为 12 字节），已删除；" +
		"从 " + h + " 下载的文件超出清单声明的 12 字节，已删除；" +
		"从 " + h + " 下载的文件 SHA-256 校验与清单不一致，已删除"
	if err == nil || err.Error() != want {
		t.Errorf("error %v\nwant %s", err, want)
	}
	assertOnly(t, dir)

	if err := try("/other", "/good"); err != nil {
		t.Fatal(err)
	}
	assertOnly(t, dir, "Zeta.msi")
}

func TestDownloadCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("part"))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer s.Close()
	dir := t.TempDir()
	p := Package{File: "Zeta.msi", URL: s.URL, Size: 100, SHA256: sha}
	err := (&Client{}).Download(ctx, p, filepath.Join(dir, "Zeta.msi"), func(int64) { cancel() })
	if !errors.Is(err, context.Canceled) {
		t.Errorf("error %v", err)
	}
	assertOnly(t, dir)
}

// assertOnly 核对 dir 里只有 names 这几个文件。
func assertOnly(t *testing.T, dir string, names ...string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, e := range entries {
		got = append(got, e.Name())
	}
	if !slices.Equal(got, names) {
		t.Errorf("%s holds %q, want %q", dir, got, names)
	}
}
