package hub

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 回答里写的图：主机上的路径经核心的 images.file 取，网址从这台电脑下载；下载要 2xx，太大的不要。
func TestLinkedImages(t *testing.T) {
	f := newChatFixture(t)
	f.devices.FileImages = map[string][]byte{"~/out/a.png": []byte("png!")}
	load := f.hub.LinkedImageLoader(f.id)
	if data, err := load("~/out/a.png"); err != nil || string(data) != "png!" {
		t.Fatalf("主机上的图：%q、%v", data, err)
	}
	if _, err := load("/没有.png"); err == nil {
		t.Error("没有的图也取到了")
	}
	big := strings.Repeat("x", maxDownload+1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/a.png":
			if r.Header.Get("Accept") != "image/*" {
				t.Errorf("Accept：%q", r.Header.Get("Accept"))
			}
			w.Write([]byte("net!"))
		case "/big.png":
			w.Write([]byte(big))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	if data, err := load(srv.URL + "/a.png"); err != nil || string(data) != "net!" {
		t.Fatalf("网络图：%q、%v", data, err)
	}
	for _, p := range []string{"/big.png", "/gone.png"} {
		if _, err := load(srv.URL + p); err == nil {
			t.Errorf("%s 也取到了", p)
		}
	}
	if calls := f.devices.SessionCalls(); len(calls) != 0 {
		t.Errorf("取图算进了会话请求：%q", calls)
	}
}
