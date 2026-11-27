package mobile

import (
	"encoding/json"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// 界面像这样套 state 交来的条目变化：Full 换掉整份，别的按 at、remove 剪接。
func apply(t *testing.T, shown []items.Item, st State) []items.Item {
	t.Helper()
	for _, ch := range st.Chat.Items {
		if ch.Full {
			shown = ch.Items
			continue
		}
		var err error
		if shown, err = items.Splice(shown, ch.At, ch.Remove, ch.Items); err != nil {
			t.Fatal(err)
		}
	}
	return shown
}

// 新对话：打开时 state 交整份条目，之后只交变化，套上以后和整份（items）一样；等确认时 state 带着请求，answer 把回应交给核心。
func TestChatItemsAndAnswer(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			if call.Method == "conv.open" {
				for _, line := range hubtest.Recorded("open", call.Conv()) {
					l.Push(line)
				}
			}
			return nil, nil
		})
	}
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	id := call[map[string]string](t, c, "add", map[string]string{"token": p.Token})["id"]
	call[map[string]bool](t, c, "new", map[string]string{"backend": id})
	until(t, c, changed, func() bool { ch := c.hub.Chat(); return ch != nil && !ch.Opening })
	st := call[State](t, c, "state", nil)
	if st.Chat == nil || st.Chat.Opening || !st.Chat.Connected || len(st.Chat.Items) != 1 || !st.Chat.Items[0].Full {
		t.Fatalf("打开后 %+v", st.Chat)
	}
	shown := apply(t, nil, st)

	l := d.Chat(id)
	push := func(name string) {
		for _, line := range hubtest.Recorded(name, "w1") {
			l.Push(line)
		}
		c.Do(func() {})
	}
	push("turn")
	st = call[State](t, c, "state", nil)
	if len(st.Chat.Items) == 0 || st.Chat.Items[0].Full {
		t.Fatalf("一轮里交的不是变化：%+v", st.Chat.Items)
	}
	shown = apply(t, shown, st)
	var whole []items.Item
	if err := json.Unmarshal(c.Call("items", nil), &whole); err != nil || len(whole) == 0 || !reflect.DeepEqual(shown, whole) {
		t.Fatalf("套出来的 %+v，整份 %+v（%v）", shown, whole, err)
	}

	push("permission")
	st = call[State](t, c, "state", nil)
	if len(st.Chat.Prompts) != 1 || st.Chat.Prompts[0].RequestID == "" {
		t.Fatalf("确认 %+v", st.Chat.Prompts)
	}
	request := st.Chat.Prompts[0].RequestID
	call[map[string]bool](t, c, "answer", map[string]any{"request_id": request, "reply": map[string]string{"kind": "allow"}})
	var answered *hubtest.Call
	until(t, c, changed, func() bool {
		for _, x := range l.Took() {
			if x.Method == "conv.answer" {
				answered = &x
			}
		}
		return answered != nil
	})
	if answered == nil || answered.Str("request_id") != request || replyJSON(answered.Params["reply"]) != `{"kind":"allow"}` {
		t.Fatalf("交给核心的 %+v", answered)
	}
}

func replyJSON(v any) string {
	data, _ := json.Marshal(v)
	return string(data)
}
