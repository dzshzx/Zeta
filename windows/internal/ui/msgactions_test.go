package ui

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"image/color"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// openedForActions 打开侧栏里的会话（重连那次录的历史：五轮），核心说现在能改。
func openedForActions(t *testing.T) *chatFixture {
	t.Helper()
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.push(`"can_rewind":true`)
	f.link().Took()
	return f
}

// failOn 让核心对 method 答出错 why，别的照常。
func (f *chatFixture) failOn(method, why string) {
	l := f.link()
	l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		if c.Method == method {
			return nil, errors.New(why)
		}
		if r := f.answers[c.Method]; r != "" {
			return json.RawMessage(r), nil
		}
		return nil, nil
	})
}

// buttons 是名叫 name 的各个按钮。
func (f *fixture) buttons(name string) []ui.AccessInfo {
	var out []ui.AccessInfo
	for _, a := range f.tt.AccessAll() {
		if a.Role == ui.RoleButton && a.Name == name {
			out = append(out, a)
		}
	}
	return out
}

// 回答下面的操作排：能改时各轮都有重新生成、删除这一轮、分支；最后一轮在回答时不出现，别的变灰。
func TestRoundActionsUnderAnswers(t *testing.T) {
	f := openedForActions(t)
	// 五轮里四轮以回答结尾挂着操作排，最后一轮只有「已停止」，从用户消息的菜单操作
	if n := len(f.buttons("重新生成")); n != 4 {
		t.Fatalf("重新生成：%d 个", n)
	}
	for _, name := range []string{"重新生成", "删除这一轮", "分支"} {
		for _, b := range f.buttons(name) {
			if b.Disabled {
				t.Fatalf("%s 是灰的", name)
			}
		}
	}
	// 这一轮还没结束：别的轮的变灰
	f.push(`"can_rewind":false`)
	for _, b := range f.buttons("分支") {
		if !b.Disabled {
			t.Fatal("不能改时分支没变灰")
		}
	}
	// 用户消息的菜单：复制、编辑、还原代码、删除；不能改时后三项灰着
	if err := f.tt.RightClick("说慢点"); err != nil {
		t.Fatal(err)
	}
	if got := f.tt.Menu(); !slices.Equal(got, []string{"复制", "编辑", "还原代码", "-", "删除"}) {
		t.Fatalf("菜单：%q", got)
	}
	if err := f.tt.ChooseMenuItem("编辑"); err == nil && f.app.pane.acts.editing != nil {
		t.Fatal("不能改时开始了编辑")
	}
	f.tt.CloseMenu()
}

// 编辑：原文放回输入栏，之后的内容藏起来，发出去是 conv.rewrite；取消时输入栏放回原样。
func TestEditAndResend(t *testing.T) {
	f := openedForActions(t)
	f.click(t, "消息")
	f.tt.Type("还没发的草稿")
	if err := f.tt.RightClick("看看有什么文件"); err != nil {
		t.Fatal(err)
	}
	if err := f.tt.ChooseMenuItem("编辑"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	if f.app.pane.draft != "看看有什么文件" {
		t.Fatalf("输入栏：%q", f.app.pane.draft)
	}
	f.see(t, "编辑消息", "发送后将替换这条消息及之后的内容")
	f.notSee(t, "说慢点", "有一个 a.txt")
	// 编辑时操作排都灰着
	for _, b := range f.buttons("重新生成") {
		if !b.Disabled {
			t.Fatal("编辑时重新生成没变灰")
		}
	}
	f.click(t, "取消编辑")
	if f.app.pane.draft != "还没发的草稿" || !f.shows("说慢点") || f.shows("编辑消息") {
		t.Fatalf("取消编辑后：输入栏 %q", f.app.pane.draft)
	}

	_ = f.tt.RightClick("看看有什么文件")
	_ = f.tt.ChooseMenuItem("编辑")
	f.tt.Frame()
	f.app.pane.draft = "看看有什么 Go 文件"
	f.tt.Frame()
	f.click(t, "发送")
	if got := f.sent("conv.rewrite"); len(got) != 1 || got[0] != `{"conv":"w1","target":"u-2","text":"看看有什么 Go 文件"}` {
		t.Fatalf("改写：%v", got)
	}
	if f.app.pane.draft != "" || f.shows("编辑消息") {
		t.Fatalf("发出后：输入栏 %q", f.app.pane.draft)
	}

	// 这一轮还在进行时不发，字留着；核心拒绝时照它的话说、字放回输入栏
	_ = f.tt.RightClick("说慢点")
	_ = f.tt.ChooseMenuItem("编辑")
	f.tt.Frame()
	f.push(`"can_rewind":false`)
	f.click(t, "发送")
	if f.sent("conv.rewrite") != nil || f.app.pane.draft != "说慢点" {
		t.Fatalf("不能改时发出去了：%q", f.app.pane.draft)
	}
	f.see(t, "等这一轮结束再发")
	f.push(`"can_rewind":true`)
	f.failOn("conv.rewrite", "现在不能改：等这一轮结束、回应完等着的请求再试")
	f.click(t, "发送")
	f.see(t, "没发出去：现在不能改：等这一轮结束、回应完等着的请求再试")
}

// 编辑带图的消息：会话记录里的图取回来放进输入栏，发出时一起带上。
func TestEditBringsBackImages(t *testing.T) {
	f := openedForActions(t)
	shot := testImagePNG(t)
	f.devices.ImageData = shot
	f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":[{"kind":"user","id":"u-1","uuid":"x1","text":"看这张图",
		"images":[{"kind":"session","file":"/s.jsonl","ref":"3.0"}]},{"kind":"answer","id":"a1","text":"是一张渐变图"}]}`)
	f.tt.Frame()
	_ = f.tt.RightClick("看这张图")
	if err := f.tt.ChooseMenuItem("编辑"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	if n := len(f.app.pane.att.images); n != 1 {
		t.Fatalf("输入栏里 %d 张图", n)
	}
	f.click(t, "发送")
	got := f.link().Took()
	i := slices.IndexFunc(got, func(c hubtest.Call) bool { return c.Method == "conv.rewrite" })
	if i < 0 || !strings.Contains(got[i].JSON(), `"media_type":"image/png"`) || got[i].Str("target") != "u-1" {
		t.Fatalf("改写：%v", got)
	}
}

// 重新生成：先问会改到的文件，勾着「同时还原代码」时经 conv.restore_code 带上原消息，否则 conv.rewrite。
func TestRegenerate(t *testing.T) {
	f := openedForActions(t)
	f.answers["conv.code_changes"] = `{"files":["/home/zeta/a.go"],"insertions":3,"deletions":1}`
	f.clickButton(t, "重新生成", 0)
	f.see(t, "重新生成回复？", "将删除之后 4 轮对话，并从这条消息重新生成。\n这条消息之后改过 1 个文件（+3 −1）。", "同时还原代码")
	if got := f.sent("conv.code_changes"); len(got) != 1 || got[0] != `{"conv":"w1","target":"u-1"}` {
		t.Fatalf("问改动：%v", got)
	}
	f.clickButton(t, "重新生成", 0) // 对话框里的：开着对话框时后面的都不算
	if got := f.sent("conv.restore_code"); len(got) != 1 || got[0] != `{"conv":"w1","target":"u-1","text":"你好，介绍一下自己"}` {
		t.Fatalf("重新生成：%v", got)
	}
	f.notSee(t, "重新生成回复？")

	// 不还原代码
	f.clickButton(t, "重新生成", 1)
	f.click(t, "同时还原代码")
	f.clickButton(t, "重新生成", 0)
	if got := f.sent("conv.rewrite"); len(got) != 1 || got[0] != `{"conv":"w1","target":"u-2","text":"看看有什么文件"}` {
		t.Fatalf("重新生成：%v", got)
	}
}

// 删除这一轮：没有可还原的改动时直接 conv.drop；核心拒绝时照它的话说。
func TestDropRound(t *testing.T) {
	f := openedForActions(t)
	f.clickButton(t, "删除这一轮", 2)
	f.see(t, "删除这一轮对话？", "这条消息及之后 2 轮对话将被删除。")
	f.notSee(t, "同时还原代码")
	f.click(t, "删除")
	if got := f.sent("conv.drop"); len(got) != 1 || got[0] != `{"conv":"w1","target":"u-3"}` {
		t.Fatalf("删除：%v", got)
	}
	// 取消不发
	f.clickButton(t, "删除这一轮", 0)
	f.click(t, "取消")
	if got := f.sent("conv.drop"); got != nil {
		t.Fatalf("取消后发了：%v", got)
	}
	f.failOn("conv.drop", "现在不能改：等这一轮结束、回应完等着的请求再试")
	_ = f.tt.RightClick("先说重点")
	if err := f.tt.ChooseMenuItem("删除"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	f.click(t, "删除")
	f.see(t, "现在不能改：等这一轮结束、回应完等着的请求再试")
}

// 还原代码：列出会改回的文件，可以同时删除这一轮；没有可还原的时只说一声。
func TestRestoreCode(t *testing.T) {
	f := openedForActions(t)
	f.answers["conv.code_changes"] = `{"files":["/home/zeta/web/login.go","/etc/hosts"],"insertions":12,"deletions":4}`
	_ = f.tt.RightClick("说慢点")
	if err := f.tt.ChooseMenuItem("还原代码"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	f.see(t, "还原代码？", "把文件改回这条消息之前的样子，对话不动。这条消息之后改过 2 个文件（+12 −4）。", "web/login.go", "/etc/hosts",
		"同时删除这一轮及之后的")
	f.click(t, "同时删除这一轮及之后的")
	f.click(t, "还原")
	if got := f.sent("conv.restore_code"); len(got) != 1 || got[0] != `{"conv":"w1","drop":true,"target":"u-3"}` {
		t.Fatalf("还原：%v", got)
	}

	f.answers["conv.code_changes"] = `null`
	_ = f.tt.RightClick("说慢点")
	_ = f.tt.ChooseMenuItem("还原代码")
	f.tt.Frame()
	f.see(t, "这条消息之后没有可还原的文件改动。")
	if len(f.buttons("还原")) != 0 {
		t.Fatal("没有改动时还能点还原")
	}
	f.click(t, "好")
	if got := f.sent("conv.restore_code"); got != nil {
		t.Fatalf("没有改动时发了：%v", got)
	}
}

// 分支：conv.fork 让这个客户端拿着新对话，聊天窗格换过去；原对话放开。
func TestForkFromAnAnswer(t *testing.T) {
	f := openedForActions(t)
	l := f.link()
	l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		if c.Method == "conv.fork" {
			as := c.Str("as")
			l.Push(`{"type":"conv.state","conv":"` + as + `","full":true,"key":"c2","title":"分支 · 假 Claude 起的标题","cwd":"/home/zeta","can_rewind":true}`)
			l.Push(`{"type":"conv.items","conv":"` + as + `","full":true,"items":[{"kind":"user","id":"u-1","text":"你好，介绍一下自己","uuid":"x1"},{"kind":"answer","id":"a1","text":"你好，我是按剧本回话的假 Claude"}]}`)
		}
		return nil, nil
	})
	f.clickButton(t, "分支", 0)
	got := l.Took()
	if len(got) != 2 || got[0].Method != "conv.fork" || got[0].JSON() != `{"as":"w2","conv":"w1","last":"msg_fake_104149c223c84749adf81d5a:1"}` ||
		got[1].Method != "conv.close" {
		t.Fatalf("分支：%v", got)
	}
	f.see(t, "分支 · 假 Claude 起的标题")
	f.notSee(t, "看看有什么文件")
	// 还没有会话号：新对话上没有分支键
	if n := len(f.buttons("分支")); n != 0 {
		t.Fatalf("新分支上有 %d 个分支键", n)
	}
}

// 点用户消息里的图看大图（盖住整个窗口，系统按钮换成深色底的画法），保存把原图交给保存对话框；换了对话时一起关上。
func TestViewAndSaveImage(t *testing.T) {
	f := openedForActions(t)
	shot := testImagePNG(t)
	f.devices.ImageData = shot
	type saved struct {
		title, name string
		data        []byte
	}
	var got []saved
	f.app.saveFile = func(title, name string, filters []mygo.FileFilter, data []byte) (string, error) {
		got = append(got, saved{title, name, data})
		return `C:\Users\me\Pictures\` + name, nil
	}
	f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":[{"kind":"user","id":"u-1","uuid":"x1","text":"看这张图",
		"images":[{"kind":"session","file":"/s.jsonl","ref":"3.0"}]}]}`)
	f.tt.Frame()
	waitFor(t, f, func() bool { _, ok := f.tt.Access("图片"); return ok })
	f.click(t, "图片")
	if f.app.viewer == nil {
		t.Fatal("没打开看大图")
	}
	if !slices.Equal(f.win.buttons, []bool{true}) {
		t.Fatalf("看大图时系统按钮没换成深色底的画法：%v", f.win.buttons)
	}
	f.click(t, "保存")
	waitFor(t, f, func() bool {
		return slices.ContainsFunc(f.tt.Texts(), func(s string) bool { return strings.HasPrefix(s, "已保存到 ") })
	})
	if len(got) != 1 || got[0].title != "保存图片" || !strings.HasPrefix(got[0].name, "Zeta_") || !bytes.Equal(got[0].data, shot) {
		t.Fatalf("保存：%+v", got)
	}
	// 关上以后系统按钮回到跟着主题
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if f.app.viewer != nil || !slices.Equal(f.win.buttons, []bool{true, false}) {
		t.Fatalf("关上后：开着 %v，系统按钮 %v", f.app.viewer != nil, f.win.buttons)
	}
	// 再打开，换了对话：看大图一起关上，系统按钮回来
	f.click(t, "图片")
	f.hub.CloseChat()
	f.tt.Frame()
	if f.app.viewer != nil || !slices.Equal(f.win.buttons, []bool{true, false, true, false}) {
		t.Fatalf("换了对话：开着 %v，系统按钮 %v", f.app.viewer != nil, f.win.buttons)
	}
}

// reattach 同时取几张图，顺序照旧；取不到的、不是图的算丢了。
func TestReattach(t *testing.T) {
	shot := testImagePNG(t)
	list := []items.Image{
		{Kind: items.Session, File: "/s.jsonl", Ref: "1"},
		{Kind: items.Session, File: "/s.jsonl", Ref: "gone"},
		{Kind: items.Inline, Data: base64.StdEncoding.EncodeToString([]byte("不是图"))},
		{Kind: items.Inline, Data: base64.StdEncoding.EncodeToString(shot)},
	}
	load := func(file, ref string) ([]byte, error) {
		if ref == "gone" {
			return nil, errors.New("没有")
		}
		return shot, nil
	}
	got, lost := reattach(list, load)
	if lost != 2 || len(got) != 2 || got[0].MediaType != "image/png" || got[1].Data != base64.StdEncoding.EncodeToString(shot) {
		t.Fatalf("取回 %d 张，丢了 %d 张", len(got), lost)
	}
}

// waitFor 等后台做完的事，最多几秒。
func waitFor(t *testing.T, f *chatFixture, done func() bool) {
	t.Helper()
	for range 500 {
		f.tt.Frame()
		if done() {
			return
		}
		sleepBriefly()
	}
	t.Fatalf("没等到；界面上：%q", f.tt.Texts())
}

// testImagePNG 是一张 64×48 的渐变 PNG。
func testImagePNG(t *testing.T) []byte {
	t.Helper()
	return picture(64, 48, color.NRGBA{R: 60, G: 120, B: 200, A: 255}, color.NRGBA{R: 220, G: 160, B: 90, A: 255})
}

func sleepBriefly() { time.Sleep(10 * time.Millisecond) }
