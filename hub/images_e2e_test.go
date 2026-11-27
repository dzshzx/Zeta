package hub

import (
	"bytes"
	"image"
	"image/png"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
)

// 实时收到的图经真核心推来时只有 key（options.image_refs），画到时经取大块数据的连接用 conv.image 取回原样的图。
func TestInlineImagesByKeyThroughCore(t *testing.T) {
	e := newE2E(t, `{"steps":[{"text":"看到了"}]}
`)
	h := e.hub
	h.NewChat()
	e.until(t, "打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening })
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 3, 2))); err != nil {
		t.Fatal(err)
	}
	shot := buf.Bytes()
	e.do(t, func(conv *chat.Conversation) error {
		return conv.Send(e.ctx(), coreapi.Outgoing{Text: "看这张图", Images: []coreapi.Image{{MediaType: "image/png", Data: shot}}})
	})
	e.until(t, "这一轮答完", func() bool { return e.finished(chat.OutcomeDone) })
	var im items.Image
	for _, it := range e.shown {
		if it.Kind == items.User && len(it.Images) == 1 {
			im = it.Images[0]
		}
	}
	if im.Kind != items.Inline || im.Key == "" || im.Data != "" {
		t.Fatalf("推来的图：%+v（条目 %v）", im, e.texts())
	}
	c := h.Chat()
	load := h.InlineImageLoader(c.Backend, c.Conv)
	got, err := load(im.Key)
	if err != nil || !bytes.Equal(got, shot) {
		t.Fatalf("取回 %d 字节：%v", len(got), err)
	}
	if _, err := load("没有这张"); err == nil {
		t.Error("没有的图也取到了")
	}
}
