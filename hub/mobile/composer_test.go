package mobile

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/dzshzx/Zeta/client/drafts"
	"github.com/dzshzx/Zeta/hub/hubtest"
	"github.com/dzshzx/Zeta/hub/settings"
)

// composerChat 在设置目录 dir 上起核心、加一台假设备、开一个新对话；answer 先看每个请求（返回 handled 为假时照默认答）。
// 答 config.models.read 一份没有中转的目录。返回核心、假设备、变化通道、对话名（conv）。
type composerChat struct {
	c       *Core
	d       *hubtest.Devices
	changed chan struct{}
	link    *hubtest.ChatLink
	mu      sync.Mutex
	conv    string
	calls   []hubtest.Call
}

func openComposer(t *testing.T, dir string) *composerChat {
	t.Helper()
	s, err := settings.Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	d := hubtest.NewDevices()
	x := &composerChat{d: d, changed: make(chan struct{}, 1)}
	x.c = New(s, d, func() {
		select {
		case x.changed <- struct{}{}:
		default:
		}
	})
	x.c.Do(x.c.hub.Start)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			x.mu.Lock()
			x.calls = append(x.calls, call)
			x.mu.Unlock()
			switch call.Method {
			case "conv.open":
				x.mu.Lock()
				x.conv = call.Conv()
				x.mu.Unlock()
				for _, line := range hubtest.Recorded("open", call.Conv()) {
					l.Push(line)
				}
			case "config.models.read":
				return json.RawMessage(`{"relay":"","model":"","models":[]}`), nil
			}
			return nil, nil
		})
	}
	p := call[Probed](t, x.c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, x.c, "add", map[string]string{"token": p.Token})
	id := call[State](t, x.c, "state", nil).Devices[0].ID
	call[map[string]bool](t, x.c, "new", map[string]string{"backend": id})
	until(t, x.c, x.changed, func() bool { ch := x.c.hub.Chat(); return ch != nil && ch.Conv != nil })
	x.link = d.Chat(id)
	return x
}

// push 推一行 conv.state 的增量。
func (x *composerChat) push(t *testing.T, fields string) {
	t.Helper()
	x.mu.Lock()
	conv := x.conv
	x.mu.Unlock()
	x.link.Push(`{"type":"conv.state","conv":"` + conv + `",` + fields + `}`)
	x.c.Do(func() {})
}

// waitCall 等到发过方法 method 的请求，答最后一个。
func (x *composerChat) waitCall(t *testing.T, method string) hubtest.Call {
	t.Helper()
	var found hubtest.Call
	until(t, x.c, x.changed, func() bool {
		x.mu.Lock()
		defer x.mu.Unlock()
		for _, c := range x.calls {
			if c.Method == method {
				found = c
			}
		}
		return found.Method != ""
	})
	return found
}

func composerOf(t *testing.T, c *Core) *Composer {
	t.Helper()
	st := call[State](t, c, "state", nil)
	if st.Chat == nil || st.Chat.Composer == nil {
		t.Fatalf("没有输入栏：%+v", st.Chat)
	}
	return st.Chat.Composer
}

// 新对话的草稿存盘，换一个核心（重开 App）再开新对话时放回来，rev 变了。
func TestComposerDraftSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	x := openComposer(t, dir)
	before := composerOf(t, x.c).Rev
	call[SlashView](t, x.c, "composer.draft", map[string]any{"text": "没写完的话", "caret": 5})
	call[map[string]bool](t, x.c, "composer.flush", nil)
	if _, err := os.Stat(filepath.Join(dir, "zeta_drafts.json")); err != nil {
		t.Fatal(err)
	}
	y := openComposer(t, dir)
	got := composerOf(t, y.c)
	if got.Text != "没写完的话" || got.Rev == before {
		t.Fatalf("恢复的草稿 %q rev %d", got.Text, got.Rev)
	}
}

// 传好了、还没发就移除的附件：设备上那份删掉。
func TestComposerRemoveDeletesUploaded(t *testing.T) {
	x := openComposer(t, t.TempDir())
	file := filepath.Join(t.TempDir(), "a.txt")
	if err := os.WriteFile(file, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	call[map[string]bool](t, x.c, "composer.attach", map[string]any{"files": []map[string]string{{"path": file}}})
	until(t, x.c, x.changed, func() bool { return !x.c.comp().uploading() })
	got := composerOf(t, x.c)
	if len(got.Attachments) != 1 {
		t.Fatalf("附件 %+v", got.Attachments)
	}
	call[map[string]bool](t, x.c, "composer.remove", map[string]int{"id": got.Attachments[0].ID})
	if removed := x.d.WaitRemoved(1); len(removed) != 1 || !strings.HasSuffix(removed[0], "/a.txt") {
		t.Fatalf("删掉的 %v", removed)
	}
	if left := composerOf(t, x.c).Attachments; len(left) != 0 {
		t.Fatalf("输入栏里还有 %+v", left)
	}
}

// 选的文件传到设备上，图片另有缩略图；发消息时带上传好的路径，之后输入栏空了。
func TestComposerAttachAndSend(t *testing.T) {
	x := openComposer(t, t.TempDir())
	dir := t.TempDir()
	file := filepath.Join(dir, "a.txt")
	if err := os.WriteFile(file, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	call[map[string]bool](t, x.c, "composer.attach", map[string]any{"files": []map[string]string{
		{"path": file}, {"path": filepath.Join(dir, "missing.txt")}}})
	until(t, x.c, x.changed, func() bool { return !x.c.comp().uploading() })
	got := composerOf(t, x.c)
	if len(got.Attachments) != 1 || got.Attachments[0].Name != "a.txt" || got.Attachments[0].Uploading || len(got.Problems) != 1 {
		t.Fatalf("附件 %+v，问题 %v", got.Attachments, got.Problems)
	}
	call[map[string]bool](t, x.c, "composer.problems", nil)
	sent := call[map[string]bool](t, x.c, "composer.send", map[string]string{"text": "看看"})
	if !sent["sent"] {
		t.Fatal("没发")
	}
	send := x.waitCall(t, "conv.send")
	files := send.Strs("files")
	if send.Str("text") != "看看" || len(files) != 1 || !strings.HasSuffix(files[0], "/a.txt") {
		t.Fatalf("发的 %s", send.JSON())
	}
	if got := composerOf(t, x.c); len(got.Attachments) != 0 || len(got.Problems) != 0 {
		t.Fatalf("发完还有 %+v", got)
	}
}

// 打 / 时让核心起进程、列核心报的命令和 Zeta 的两条设置命令；选了只换掉那个词。
func TestComposerSlash(t *testing.T) {
	x := openComposer(t, t.TempDir())
	x.push(t, `"commands":[{"name":"compact","description":"压缩"},{"name":"clear"}]`)
	got := call[SlashView](t, x.c, "composer.draft", map[string]any{"text": "先 /co", "caret": 5})
	if len(got.Commands) != 2 || got.Commands[0].Name != "compact" || got.Commands[1].Name != "config" {
		t.Fatalf("提示 %+v", got.Commands)
	}
	x.waitCall(t, "conv.prepare")
	done := call[Completed](t, x.c, "composer.complete", map[string]any{"text": "先 /co", "caret": 5, "name": "compact"})
	if done.Text != "先 /compact " || done.Caret != 11 {
		t.Fatalf("补全 %+v", done)
	}
	if got := call[SlashView](t, x.c, "composer.draft", map[string]any{"text": "a/co", "caret": 4}); len(got.Commands) != 0 {
		t.Fatalf("紧挨着字也提示了 %+v", got.Commands)
	}
}

// 单独发 /config、/permissions 不发给 Claude Code，答要打开的页；输入框的字清掉。
func TestComposerSettingsCommands(t *testing.T) {
	x := openComposer(t, t.TempDir())
	for text, want := range map[string]string{" /config ": "config", "/permissions": "permissions"} {
		call[SlashView](t, x.c, "composer.draft", map[string]any{"text": text, "caret": 0})
		got := call[Sent](t, x.c, "composer.send", map[string]string{"text": text})
		if !got.Sent || got.Open != want {
			t.Fatalf("%q：%+v", text, got)
		}
		if c := composerOf(t, x.c); c.Text != "" {
			t.Fatalf("字还在 %q", c.Text)
		}
	}
	if got := call[Sent](t, x.c, "composer.send", map[string]string{"text": "/config 看看"}); !got.Sent || got.Open != "" {
		t.Fatalf("带参数的照常发 %+v", got)
	}
	if send := x.waitCall(t, "conv.send"); send.Str("text") != "/config 看看" {
		t.Fatalf("发的 %s", send.JSON())
	}
}

// 模型菜单：Claude Code 报的清单分两层；选模型、思考强度、权限模式各发给核心。
func TestComposerModelsAndChoices(t *testing.T) {
	x := openComposer(t, t.TempDir())
	x.push(t, `"process":true,"models":[{"value":"default","displayName":"Default"},{"value":"opus","resolvedModel":"claude-opus-5-5"},`+
		`{"value":"claude-opus-4-6"}],"mode":{"user":"","process":"plan"}`)
	composerOf(t, x.c) // 头一次画输入栏时去读模型目录
	until(t, x.c, x.changed, func() bool {
		var ready bool
		m := x.c.comp()
		for _, s := range m.models {
			ready = ready || s.ready
		}
		return ready
	})
	got := composerOf(t, x.c)
	if !got.Model.Available || !got.Model.Harness || len(got.Model.Latest) != 1 || got.Model.Latest[0].Value != "opus" ||
		len(got.Model.Older) != 1 || got.Mode != "plan" || len(got.Modes) != 5 {
		t.Fatalf("模型 %+v 模式 %q", got.Model, got.Mode)
	}
	call[map[string]bool](t, x.c, "composer.model", map[string]any{"value": "opus"})
	if m := x.waitCall(t, "conv.model"); !strings.Contains(m.JSON(), `"value":"opus"`) {
		t.Fatalf("模型 %s", m.JSON())
	}
	call[map[string]bool](t, x.c, "composer.effort", map[string]any{"value": nil})
	x.waitCall(t, "conv.effort")
	call[map[string]bool](t, x.c, "composer.mode", map[string]any{"value": "auto"})
	x.waitCall(t, "conv.mode")
}

// 排队的消息显示出来，能插话、撤回；用量写成给人看的。
func TestComposerQueueAndUsage(t *testing.T) {
	x := openComposer(t, t.TempDir())
	x.push(t, `"busy":true,"queued":[{"uuid":"q1","text":"再  看看","images":[],"files":["/h/a.txt"]}],`+
		`"usage":{"context_tokens":50000,"context_window":200000,"fraction":0.25,"level":"normal","turns":[{"tokens":{"input":10,"output":5,"cache_read":0,"cache_write":0},"duration_ms":3000}],"total":{"input":10,"output":5,"cache_read":0,"cache_write":0}}`)
	got := composerOf(t, x.c)
	if len(got.Queued) != 1 || got.Queued[0].Preview != "再 看看" || got.Queued[0].Extra != 1 {
		t.Fatalf("排队 %+v", got.Queued)
	}
	if got.Usage.Context == "" || len(got.Usage.Turns) != 1 || got.Usage.Turns[0].N != 1 || got.Usage.Turns[0].Duration == "" {
		t.Fatalf("用量 %+v", got.Usage)
	}
	call[map[string]bool](t, x.c, "composer.steer", nil)
	x.waitCall(t, "conv.steer")
	call[map[string]bool](t, x.c, "composer.cancel", map[string]any{"uuid": "q1", "recall": true})
	if m := x.waitCall(t, "conv.cancel"); m.Str("uuid") != "q1" {
		t.Fatalf("撤回 %s", m.JSON())
	}
	call[map[string]bool](t, x.c, "composer.compact", nil)
	x.waitCall(t, "conv.compact")
}

// 常用指令：加在最前面、同样内容的收藏不重复、能挪、能删。
func TestPrompts(t *testing.T) {
	c, _ := newCore(t)
	call[Prompts](t, c, "prompts.add", map[string]string{"text": "第一条"})
	got := call[Prompts](t, c, "prompts.add", map[string]any{"name": "二", "text": "第二条"})
	if len(got.List) != 2 || got.List[0].Title != "二" {
		t.Fatalf("清单 %+v", got.List)
	}
	raw, _ := json.Marshal(map[string]any{"text": " 第一条 ", "dedupe": true})
	if out := string(c.Call("prompts.add", raw)); !strings.Contains(out, "error") {
		t.Fatalf("重复的也加了 %s", out)
	}
	got = call[Prompts](t, c, "prompts.move", map[string]any{"id": got.List[0].ID, "delta": 1})
	if got.List[1].Name != "二" {
		t.Fatalf("挪了以后 %+v", got.List)
	}
	got = call[Prompts](t, c, "prompts.remove", map[string]any{"id": got.List[1].ID})
	if len(got.List) != 1 || !slices.ContainsFunc(got.List, func(p Prompt) bool { return p.Text == "第一条" }) {
		t.Fatalf("删了以后 %+v", got.List)
	}
}

// 会话行上的草稿：存着草稿的会话答一行预览；输入栏正跟着的那个、没存草稿的不标。
func TestRowDraft(t *testing.T) {
	x := openComposer(t, t.TempDir())
	x.c.Do(func() {
		b := x.c.comp().drafts()
		b.records["s1"] = drafts.Draft{Key: "s1", Text: "改一下标题"}
		b.records["s2"] = drafts.Draft{Key: "s2", Text: "正在写的"}
		b.key = "s2"
	})
	var got1, got2, got3 bool
	var preview string
	x.c.Do(func() {
		m := x.c.comp()
		preview, got1 = m.rowDraft("s1")
		_, got2 = m.rowDraft("s2")
		_, got3 = m.rowDraft("s3")
	})
	if !got1 || !strings.Contains(preview, "改一下标题") || got2 || got3 {
		t.Fatalf("s1 %v %q，s2 %v，s3 %v", got1, preview, got2, got3)
	}
}
