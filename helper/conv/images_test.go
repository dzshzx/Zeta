package conv

import (
	"encoding/json"
	"errors"
	"testing"
)

// 声明了 image_refs 的客户端收到的条目里实时图片只有 key，用 conv.image 从别的连接取原文；没声明的照旧带原文，
// 核心自己那份不变。
func TestImageRefs(t *testing.T) {
	h := newHarness(t)
	h.call(1, "conv.open", map[string]any{"conv": "a", "cwd": "/w", "options": map[string]any{"image_refs": true}})
	h.call(1, "conv.send", map[string]any{"conv": "a", "text": "看这张图",
		"images": []any{map[string]any{"media_type": "image/png", "data": pngData}}})
	c, l := h.conv(1, "a"), h.link(0)
	h.feed(c, l, initLine("s1"))
	// 客户端 2 按会话号打开同一个对话：用户消息里的图随整份推来，工具结果里的随增量
	h.call(2, "conv.open", map[string]any{"conv": "b", "session": "s1", "cwd": "/w"})
	shot, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"content": []any{map[string]any{
		"type": "tool_result", "tool_use_id": "t1", "content": []any{map[string]any{
			"type": "image", "source": map[string]any{"type": "base64", "media_type": "image/png", "data": pngData}}}}}}})
	h.feed(c, l, toolCall("m1", "t1", "Read", `{"file_path":"/w/a.png"}`, "", false)[0], string(shot))
	h.feed(c, l, answerLines("m2", "看到了")...)
	h.feed(c, l, resultOK)

	lean, full := images(t, h, 1), images(t, h, 2)
	if len(lean) != 2 || len(full) != 2 {
		t.Fatalf("图的张数：%d、%d", len(lean), len(full))
	}
	for i := range lean {
		if lean[i]["data"] != nil || lean[i]["key"] == "" || lean[i]["kind"] != "inline" {
			t.Errorf("声明了 image_refs 的收到：%v", lean[i])
		}
		if full[i]["data"] != pngData || full[i]["key"] != lean[i]["key"] {
			t.Errorf("没声明的收到：%v", full[i])
		}
	}

	// 之后再打开（整份推）也只有 key
	h.call(4, "conv.open", map[string]any{"conv": "d", "session": "s1", "options": map[string]any{"image_refs": true}})
	for _, im := range images(t, h, 4) {
		if im["data"] != nil {
			t.Errorf("重新打开时带了原文：%v", im["key"])
		}
	}

	// 从不拿着这个对话的连接（客户端 3）按对话的 key 和图的 key 取
	key := h.state(1, "key")
	for _, im := range lean {
		v := h.call(3, "conv.image", map[string]any{"key": key, "image": im["key"]})
		if got := v.(Image); got.Data != pngData || got.MediaType != "image/png" {
			t.Errorf("取回的 %v：%+v", im["key"], got)
		}
	}
	b, _ := json.Marshal(map[string]any{"key": key, "image": "没有这张"})
	if _, err := h.m.Request(3, "conv.image", b); err == nil {
		t.Error("没有的图也答成了")
	}
	b, _ = json.Marshal(map[string]any{"key": "c999", "image": lean[0]["key"]})
	var coded codedError
	if _, err := h.m.Request(3, "conv.image", b); !errors.As(err, &coded) || coded.Code() != "conversation_gone" {
		t.Errorf("对话不在时：%v", err)
	}
}

// images 是客户端 client 手里条目里的全部图（用户消息的和工具结果的），按先后。
func images(t *testing.T, h *harness, client int) []map[string]any {
	t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []map[string]any
	add := func(v any) {
		list, _ := v.([]any)
		for _, x := range list {
			out = append(out, x.(map[string]any))
		}
	}
	for _, it := range h.client(client).items {
		m := it.(map[string]any)
		add(m["images"])
		steps, _ := m["steps"].([]any)
		for _, s := range steps {
			add(s.(map[string]any)["images"])
		}
	}
	return out
}
