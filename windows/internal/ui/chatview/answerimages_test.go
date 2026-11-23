package chatview

import (
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// 回答里的图从正文里取出来（同手机版 AnswerImagesTest）：路径按工作目录解析，代码块里的、不像图片的、别的协议的留在正文里，
// 同一张只取一次；只隔着空白的连成一组，被正文隔开的另起一组。
func TestSplitAnswerImages(t *testing.T) {
	urls := func(p answerParts) [][]string {
		var out [][]string
		for _, g := range p.groups {
			var row []string
			for _, im := range g {
				if im.Kind == items.Inline {
					row = append(row, "inline")
				} else {
					row = append(row, im.URL)
				}
			}
			out = append(out, row)
		}
		return out
	}
	cases := []struct {
		name, text, cwd string
		shown           string
		groups          [][]string
	}{
		{"截图里那段", "样图出来了：\n\n![区域汇总样图](/tmp/p/page-0.png)\n\n![蚌马区域样图](/tmp/p/page-4.png)\n", "/w",
			"样图出来了：", [][]string{{"/tmp/p/page-0.png", "/tmp/p/page-4.png"}}},
		{"相对路径、家目录、file://", "![a](out/a.png) ![b](./b.JPG) ![c](~/c.webp) ![d](file:///x/d%20e.png)", "/w/",
			"", [][]string{{"/w/out/a.png", "/w/b.JPG", "~/c.webp", "/x/d e.png"}}},
		{"被正文隔开的另起一组", "前\n![a](/a.png)\n中间\n![b](/b.png)", "/w",
			"前\n\n中间", [][]string{{"/a.png"}, {"/b.png"}}},
		{"重复的只显示一次", "![a](/a.png)\n![a 再一次](/a.png)", "/w", "", [][]string{{"/a.png"}}},
		{"网址和 data:", "![x](https://example.com/x.png \"标题\") ![y](data:image/png;base64,aGk=)", "/w",
			"", [][]string{{"https://example.com/x.png", "inline"}}},
		{"认不出的留着", "```\n![code](/c.png)\n```\n![doc](/a.pdf) ![ftp](ftp://h/a.png) [链接](/a.png)", "/w",
			"```\n![code](/c.png)\n```\n![doc](/a.pdf) ![ftp](ftp://h/a.png) [链接](/a.png)", nil},
		{"没有工作目录时不认相对路径", "![a](a.png) ![b](/b.png)", "", "![a](a.png)", [][]string{{"/b.png"}}},
	}
	for _, tc := range cases {
		p := splitAnswerImages(tc.text, tc.cwd)
		if p.text != tc.shown || !slices.EqualFunc(urls(p), tc.groups, slices.Equal) {
			t.Errorf("%s：正文 %q，图 %q", tc.name, p.text, urls(p))
		}
	}
	var many strings.Builder
	for i := range maxAnswerImages + 2 {
		many.WriteString("![p](/" + strings.Repeat("x", i+1) + ".png)\n")
	}
	if p := splitAnswerImages(many.String(), "/w"); len(p.images()) != maxAnswerImages || strings.Count(p.text, "![p]") != 2 {
		t.Errorf("超过上限：显示 %d 张，正文里留下 %q", len(p.images()), p.text)
	}
}

// 回答里写的主机上的图画在回答下面（不再是说明文字的灰块），按工作目录取；点了看大图，左右翻这段回答里的图。
func TestAnswerImagesShowAndOpen(t *testing.T) {
	shot := testPNG(t, 160, 100)
	var mu sync.Mutex
	var asked []string
	ch := New()
	ch.Cwd = "/w"
	ch.LoadMarkdownImage = func(url string) ([]byte, error) {
		mu.Lock()
		asked = append(asked, url)
		mu.Unlock()
		return shot, nil
	}
	ch.SetItems([]items.Item{{Kind: items.Answer, ID: "a",
		Text: "样图出来了：\n\n![区域汇总样图](/tmp/p/page-0.png)\n\n![蚌马区域样图](out/page-4.png)"}})
	tt, w := viewerTester(ch, false, nil)
	settleImages(ch, tt)
	slices.Sort(asked)
	if !slices.Equal(asked, []string{"/tmp/p/page-0.png", "/w/out/page-4.png"}) {
		t.Errorf("取图：%q", asked)
	}
	if tt.HasText("区域汇总样图") || !tt.HasText("样图出来了：") {
		t.Errorf("正文：%q", tt.Texts())
	}
	var shown []ui.Rect
	for _, a := range tt.AccessAll() {
		if a.Role == ui.RoleImage && a.Name == "图片" {
			shown = append(shown, a.Bounds)
		}
	}
	text, _ := tt.Find("样图出来了：")
	if len(shown) != 2 || shown[0].Y < text.Y+text.H {
		t.Fatalf("画出的图 %v，正文在 %v", shown, text)
	}
	at := shown[1]
	tt.ClickAt(at.X+at.W/2, at.Y+at.H/2)
	tt.Frame()
	if w.v == nil || !tt.HasText("2 / 2") {
		t.Fatalf("点第二张：开着 %v，%q", w.v != nil, tt.Texts())
	}
}

// 网络图先是「加载图片 · 网站」，点了才取（访问图片地址会把 IP 交给对方）；取过的在看大图里直接显示。
func TestRemoteAnswerImageWaitsForTap(t *testing.T) {
	asked := make(chan string, 2)
	ch := New()
	ch.LoadMarkdownImage = func(url string) ([]byte, error) {
		asked <- url
		return testPNG(t, 40, 30), nil
	}
	ch.SetItems([]items.Item{{Kind: items.Answer, ID: "a", Text: "看这张：\n\n![远处](https://img.example.com/a/b.png)"}})
	tt := newTester(ch, 700, 600, false)
	settleImages(ch, tt)
	if len(asked) != 0 {
		t.Fatal("没点就取了网络图")
	}
	mustClick(t, tt, "加载图片 · img.example.com")
	settleImages(ch, tt)
	if got := <-asked; got != "https://img.example.com/a/b.png" {
		t.Errorf("取图：%q", got)
	}
	if tt.HasText("加载图片 · img.example.com") {
		t.Error("点过以后还在问")
	}
}

// 查找只查照常排版的正文：取出来画在下面的图，它的说明文字不算命中。
func TestFindSkipsAnswerImages(t *testing.T) {
	ch := New()
	ch.SetItems([]items.Item{{Kind: items.Answer, ID: "a", Text: "样图在下面\n\n![区域汇总样图](/tmp/p/page-0.png)"}})
	if got := ch.findable(ch.Items()[0]); got == nil || strings.Contains(got.src, "区域汇总") {
		t.Fatalf("能查的正文：%+v", got)
	}
}
