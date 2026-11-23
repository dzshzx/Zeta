package ui

import (
	"testing"
	"time"
)

// 设置「常规」里的「自动加载网络图片」：存进设置（键同手机版），聊天里的网络图跟着不再先问。
func TestAutoLoadRemoteImagesSetting(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	asked := make(chan string, 4)
	f.app.pane.list.LoadMarkdownImage = func(url string) ([]byte, error) {
		asked <- url
		return nil, nil
	}
	f.pushItems(`[{"kind":"answer","id":"a","text":"看这张：\n\n![远处](https://img.example.com/a.png)"}]`)
	f.see(t, "加载图片 · img.example.com")

	f.click(t, "设置")
	f.see(t, "对话", "自动加载网络图片")
	f.press(t, "自动加载网络图片")
	if !f.hub.Settings().AutoLoadRemoteImages() {
		t.Fatal("开关没存上")
	}
	f.click(t, "返回应用")
	select {
	case got := <-asked:
		if got != "https://img.example.com/a.png" {
			t.Errorf("取图：%q", got)
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("打开以后没去取：%q", f.tt.Texts())
	}
	f.tt.Frame()
	f.notSee(t, "加载图片 · img.example.com")
}
