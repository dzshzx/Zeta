package chatview

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// previewScene 是预览图里的一个场景：先把条目摆上，prepare 在画之前点开些东西。
type previewScene struct {
	name    string
	items   func(t *testing.T) []items.Item
	earlier Earlier
	prepare func(t *testing.T, tt *ui.Tester)
}

func previewScenes(t *testing.T) []previewScene {
	fixtures := helperFixtures(t)
	scenes := []previewScene{
		{name: "demo", items: demoItems},
		{name: "demo-expanded", items: demoItems, prepare: func(t *testing.T, tt *ui.Tester) {
			mustClick(t, tt, "已完成 5 个步骤")
			mustClick(t, tt, "跑配置包的测试")
			mustClick(t, tt, "读取 screenshot.png")
		}},
		{name: "many-edits", items: func(t *testing.T) []items.Item { return manyEdits(t, 5) }},
		{name: "answer-images", items: answerImageItems},
		{name: "math", items: mathItems},
		{name: "peer", items: peerItems},
		{name: "earlier", items: func(*testing.T) []items.Item { return longConversation("", 2) }, earlier: Earlier{Skipped: 120}},
		{name: "earlier-loading", items: func(*testing.T) []items.Item { return longConversation("", 2) }, earlier: Earlier{Skipped: 120, Loading: true}},
	}
	for name, list := range fixtures {
		scenes = append(scenes, previewScene{name: "helper-" + name, items: func(*testing.T) []items.Item { return list }})
	}
	return scenes
}

func mustClick(t *testing.T, tt *ui.Tester, text string) {
	t.Helper()
	if err := tt.Click(text); err != nil {
		t.Fatalf("点 %q：%v；屏上的字：%q", text, err, tt.Texts())
	}
}

// TestPreviews 把各场景按浅色、深色画成 PNG，写到 $ZETA_PREVIEW_DIR，人工看样子（不做像素比对）。
// 没设这个变量时不画。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设：不画预览图")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	shot := testPNG(t, 640, 400)
	for _, scene := range previewScenes(t) {
		for _, dark := range []bool{false, true} {
			ch := New()
			ch.LoadImage = func(file, ref string) ([]byte, error) { return shot, nil }
			ch.LoadMarkdownImage = func(string) ([]byte, error) { return shot, nil }
			ch.Cwd = "/w"
			ch.LoadEarlier = func() {}
			ch.Earlier = scene.earlier
			ch.SetItems(scene.items(t))
			tt := newTester(ch, 960, 1500, dark)
			settleImages(ch, tt)
			if scene.prepare != nil {
				scene.prepare(t, tt)
				settleImages(ch, tt)
			}
			look := "light"
			if dark {
				look = "dark"
			}
			writePNG(t, filepath.Join(dir, scene.name+"-"+look+".png"), tt)
		}
	}
}

func writePNG(t *testing.T, path string, tt *ui.Tester) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, tt.Image()); err != nil {
		t.Fatal(err)
	}
}

// answerImageItems：一条长的用户消息（带三张图），回答里引用了两张主机上的图和一张网络图。
func answerImageItems(t *testing.T) []items.Item {
	return decodeItems(t, `[
		{"kind":"user","id":"u","text":"把这三张截图里的门店数据整理成区域汇总表和蚌马区域的明细表，做成两张样图给我看看，字号要能在手机上看清。",
			"images":[{"kind":"session","file":"/s.jsonl","ref":"1"},{"kind":"session","file":"/s.jsonl","ref":"2"},{"kind":"session","file":"/s.jsonl","ref":"3"}]},
		{"kind":"answer","id":"a","text":"样图出来了，用的是今天上午的数据：\n\n![区域汇总样图](/tmp/p/page-0.png)\n\n![蚌马区域样图](out/page-4.png)\n\n**改了什么：** 字号放大到 14。\n\n![参考](https://example.com/ref.png)"}
	]`)
}
