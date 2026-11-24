package conv

import (
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/helper/transcript"
)

func TestOrderImagesPutsShrunkCopiesAfterOthersInFileOrder(t *testing.T) {
	files := []string{"/w/a.jpg", "/w/notes.txt", "/w/b.png"}
	got := orderImages([]Image{
		{Data: "B", Original: "/w/b.png"},
		{Data: "shot"},
		{Data: "A", Original: "/w/a.jpg"},
		{Data: "stray", Original: "/w/gone.jpg"}, // 不在 files 里：当单独的图
	}, files)
	want := []Image{{Data: "shot"}, {Data: "stray"}, {Data: "A", Original: "/w/a.jpg"}, {Data: "B", Original: "/w/b.png"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("排好的：%v", got)
	}
}

// 发出去的那行按 orderImages 排；条目和撤回的消息里的图都带着 original，客户端照它配回原文件。
func TestSendCarriesOriginals(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w"})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "看图",
		"images": []any{
			map[string]any{"media_type": "image/png", "data": pngData, "original": "/w/a.png"},
			map[string]any{"media_type": "image/png", "data": pngData},
		},
		"files": []any{"/w/a.png"}})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	blocks := sentUser(t, l)
	if len(blocks) != 3 || blocks[0]["type"] != "image" || blocks[1]["type"] != "image" {
		t.Fatalf("发出去的：%v", blocks)
	}
	user := h.item(1, "user", "看图")
	imgs := user["images"].([]any)
	if len(imgs) != 2 || imgs[0].(map[string]any)["original"] != nil || imgs[1].(map[string]any)["original"] != "/w/a.png" {
		t.Fatalf("条目里的图：%v", imgs)
	}
}

// 会话记录里的消息：末尾那几张图对上图片附件（引用的、别的文件不算）；图不够时不对。
func TestPairOriginalsFromHistory(t *testing.T) {
	files := []string{"/w/a.txt", "/w/IMG_1.JPG", `@/sdcard/x.png`, "/w/b.webp"}
	images := []transcript.Image{{Ref: "0"}, {Ref: "1"}, {Ref: "2"}}
	transcript.PairOriginals(images, files)
	if images[0].Original != "" || images[1].Original != "/w/IMG_1.JPG" || images[2].Original != "/w/b.webp" {
		t.Fatalf("对上的：%v", images)
	}
	few := []transcript.Image{{Ref: "0"}}
	transcript.PairOriginals(few, files)
	if few[0].Original != "" {
		t.Fatalf("图不够时不该对：%v", few)
	}
}
