package mobile

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
)

// openChat 加一台假设备、在上面开新对话并等它打开；answer 答对话连接上的请求（nil 是都答空）。
func openChat(t *testing.T, answer func(hubtest.Call) (json.RawMessage, error)) (*Core, *hubtest.Devices, *hubtest.ChatLink, chan struct{}) {
	t.Helper()
	c, d, changed := newWatchedCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(x hubtest.Call) (json.RawMessage, error) {
			if x.Method == "conv.open" {
				for _, line := range hubtest.Recorded("open", x.Conv()) {
					l.Push(line)
				}
				return nil, nil
			}
			if answer != nil {
				return answer(x)
			}
			return nil, nil
		})
	}
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	id := call[State](t, c, "state", nil).Devices[0].ID
	call[map[string]bool](t, c, "new", map[string]string{"backend": id})
	until(t, c, changed, func() bool { ch := c.hub.Chat(); return ch != nil && ch.Conv != nil && !ch.Opening })
	for !call[State](t, c, "state", nil).Chat.Connected {
		<-changed
	}
	return c, d, d.Chat(id), changed
}

// tookCall 等到对话连接上收到 method，返回它。
func tookCall(t *testing.T, c *Core, changed chan struct{}, l *hubtest.ChatLink, method string) hubtest.Call {
	t.Helper()
	var got *hubtest.Call
	until(t, c, changed, func() bool {
		for _, x := range l.Took() {
			if x.Method == method {
				got = &x
			}
		}
		return got != nil
	})
	return *got
}

// 改写、重新生成、删除一轮、还原代码各交给核心对应的请求。
func TestRoundActions(t *testing.T) {
	c, _, l, changed := openChat(t, nil)
	st := call[State](t, c, "state", nil)
	if !st.Chat.CanRewind {
		t.Fatalf("打开后 can_rewind 为假：%+v", st.Chat)
	}
	call[map[string]bool](t, c, "rewrite", map[string]any{"target": "u1", "text": "改过的"})
	if x := tookCall(t, c, changed, l, "conv.rewrite"); x.Str("target") != "u1" || x.Str("text") != "改过的" {
		t.Fatalf("改写 %s", x.JSON())
	}
	call[map[string]bool](t, c, "drop", map[string]any{"target": "u2"})
	if x := tookCall(t, c, changed, l, "conv.drop"); x.Str("target") != "u2" {
		t.Fatalf("删除 %s", x.JSON())
	}
	call[map[string]bool](t, c, "restorecode", map[string]any{"target": "u3", "drop": true})
	if x := tookCall(t, c, changed, l, "conv.restore_code"); x.Str("target") != "u3" || x.Params["drop"] != true {
		t.Fatalf("还原代码 %s", x.JSON())
	}
	call[map[string]bool](t, c, "regenerate", map[string]any{"target": "u4"})
	until(t, c, changed, func() bool {
		for _, x := range l.Took() {
			if x.Method == "conv.rewrite" && x.Str("target") == "u4" && x.Params["resend"] == true {
				return true
			}
		}
		return false
	})
}

// codechanges 答核心说的会改动的文件；没有可还原的时答 null。
func TestCodeChanges(t *testing.T) {
	c, _, _, _ := openChat(t, func(x hubtest.Call) (json.RawMessage, error) {
		if x.Method == "conv.code_changes" && x.Str("target") == "u1" {
			return json.RawMessage(`{"files":["/h/a.go"],"insertions":3,"deletions":1}`), nil
		}
		return nil, nil
	})
	got := call[*struct {
		Files      []string `json:"files"`
		Insertions int      `json:"insertions"`
	}](t, c, "codechanges", map[string]string{"target": "u1"})
	if got == nil || len(got.Files) != 1 || got.Insertions != 3 {
		t.Fatalf("改动 %+v", got)
	}
	if none := call[*struct{}](t, c, "codechanges", map[string]string{"target": "u2"}); none != nil {
		t.Fatalf("没有可还原的却答了 %+v", none)
	}
}

// fork 交 conv.fork，分成后聊天页换成新对话。
func TestFork(t *testing.T) {
	c, _, l, changed := openChat(t, nil)
	var before any
	c.Do(func() { before = c.hub.Chat().Conv })
	call[map[string]bool](t, c, "fork", map[string]string{"last": "a1"})
	if x := tookCall(t, c, changed, l, "conv.fork"); x.Str("last") != "a1" {
		t.Fatalf("分支 %s", x.JSON())
	}
	until(t, c, changed, func() bool { ch := c.hub.Chat(); return ch != nil && ch.Conv != nil && any(ch.Conv) != before })
}

// 会话被别处占着时 state 带着那边的样子；handover 请核心把会话接过来。
func TestHandover(t *testing.T) {
	c, d, l, changed := openChat(t, nil)
	l.Push(`{"type":"conv.state","conv":"w1","session":"s1","blocked":true,"occupant":{"status":"idle","hands_over":true,"app":"terminal"}}`)
	until(t, c, changed, func() bool { return c.hub.Chat().Conv.State().Blocked })
	st := call[State](t, c, "state", nil)
	if !st.Chat.Blocked || st.Chat.Occupant == nil || !st.Chat.Occupant.HandsOver || st.Chat.Occupant.App != "terminal" {
		t.Fatalf("占着时 %+v", st.Chat)
	}
	call[map[string]bool](t, c, "handover", nil)
	until(t, c, changed, func() bool {
		for _, s := range d.SessionCalls() {
			if s == l.Host+` sessions.handoff {"session":"s1"}` {
				return true
			}
		}
		return false
	})
}

// elicit：表单填得不对时答各项的原因，填对了答空。
func TestElicit(t *testing.T) {
	c, _, l, changed := openChat(t, func(x hubtest.Call) (json.RawMessage, error) {
		if x.Method == "conv.elicit" && fmt.Sprint(x.Params["action"]) == "accept" {
			return json.RawMessage(`{"errors":{"port":"要填整数"}}`), nil
		}
		return nil, nil
	})
	got := call[Elicited](t, c, "elicit", map[string]any{"request_id": "e1", "action": "accept", "values": map[string][]string{"port": {"x"}}})
	if got.Problems["port"] != "要填整数" {
		t.Fatalf("表单的问题 %+v", got)
	}
	if got := call[Elicited](t, c, "elicit", map[string]any{"request_id": "e1", "action": "decline"}); len(got.Problems) != 0 {
		t.Fatalf("拒绝时 %+v", got)
	}
	if x := tookCall(t, c, changed, l, "conv.elicit"); x.Str("request_id") != "e1" {
		t.Fatalf("交给核心的 %s", x.JSON())
	}
}

// image 按图的种类取：会话记录里的经 sessions.image，回答里写的主机路径经 images.file。
func TestImage(t *testing.T) {
	c, d, _, _ := openChat(t, nil)
	d.ImageData = []byte("png1")
	d.FileImages = map[string][]byte{"~/a.png": []byte("png2")}
	got := call[Picture](t, c, "image", map[string]string{"kind": "session", "file": "/p/s.jsonl", "ref": "3.0"})
	if data, _ := base64.StdEncoding.DecodeString(got.Data); string(data) != "png1" {
		t.Fatalf("会话记录里的图 %q", data)
	}
	got = call[Picture](t, c, "image", map[string]string{"kind": "linked", "url": "~/a.png"})
	if data, _ := base64.StdEncoding.DecodeString(got.Data); string(data) != "png2" {
		t.Fatalf("主机上的图 %q", data)
	}
}

// savemessage 把消息存成常用指令，同样的不再存。
func TestSaveMessage(t *testing.T) {
	c, _ := newCore(t)
	if got := call[SavedMessage](t, c, "savemessage", map[string]string{"text": "跑一遍测试"}); !got.Saved {
		t.Fatalf("第一次 %+v", got)
	}
	if got := call[SavedMessage](t, c, "savemessage", map[string]string{"text": "跑一遍测试"}); !got.Exists {
		t.Fatalf("第二次 %+v", got)
	}
	var n int
	c.Do(func() { n = len(c.hub.Prompts()) })
	if n != 1 {
		t.Fatalf("存了 %d 条", n)
	}
}
