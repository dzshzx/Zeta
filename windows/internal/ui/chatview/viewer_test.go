package chatview

import (
	"bytes"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// viewing 是测试窗里照窗口那样接上的看大图：列表发出 OpenImages 时建一个，画在最上层，关上后丢掉。
type viewing struct {
	v    *Viewer
	save func(data []byte, name string) (string, error)
}

// viewerTester 在无窗口的测试窗里画 ch，点了图打开看大图。
func viewerTester(ch *Chat, dark bool, save func(data []byte, name string) (string, error)) (*ui.Tester, *viewing) {
	w := &viewing{save: save}
	ch.OpenImages = func(list []items.Image, index int) {
		w.v = NewViewer(ch, list, index)
		w.v.Save = w.save
	}
	view := func(c *ui.Context) {
		c.SetTheme(coui.Theme(c.Theme().Dark))
		ui.Column(c).Fill().Background(c.Theme().Background).Children(func() {
			ch.View(c).Grow(1).FillWidth()
		})
		if w.v != nil && !w.v.View(c) {
			w.v = nil
		}
	}
	return ui.NewTesterWith(view, 900, 700, ui.TesterOptions{Dark: dark, Preferences: ui.Preferences{ReduceMotion: true}}), w
}

// 点用户消息里的图打开看大图：显示第几张，左右键和两边的键翻页，到头不动；保存交给 Save；Esc、返回关上。
func TestImageViewer(t *testing.T) {
	shot := testPNG(t, 300, 200)
	ch := New()
	ch.LoadImage = func(file, ref string) ([]byte, error) { return shot, nil }
	type saved struct {
		data []byte
		name string
	}
	got := make(chan saved, 1)
	save := func(data []byte, name string) (string, error) {
		got <- saved{data, name}
		return `C:\Users\me\Pictures\` + name, nil
	}
	ch.SetItems(decodeItems(t, `[
		{"kind":"user","id":"u","text":"看图","images":[{"kind":"session","file":"/s.jsonl","ref":"1"},{"kind":"session","file":"/s.jsonl","ref":"2"},{"kind":"session","file":"/s.jsonl","ref":"3"}]}
	]`))
	tt, w := viewerTester(ch, false, save)
	settleImages(ch, tt)
	images := tt.AccessAll()
	var at ui.Rect
	n := 0
	for _, a := range images {
		if a.Role == ui.RoleImage && a.Name == "图片" {
			if n == 1 {
				at = a.Bounds
			}
			n++
		}
	}
	tt.ClickAt(at.X+at.W/2, at.Y+at.H/2)
	tt.Frame()
	if w.v == nil || !tt.HasText("2 / 3") {
		t.Fatalf("点第二张：开着 %v，%q", w.v != nil, tt.Texts())
	}
	tt.Key(0, ui.KeyRight)
	tt.Key(0, ui.KeyRight) // 到头了不动
	if !tt.HasText("3 / 3") {
		t.Fatalf("往后翻：%q", tt.Texts())
	}
	if b, ok := tt.AccessByRole(ui.RoleButton, "下一张"); !ok || !b.Disabled {
		t.Fatalf("最后一张时下一张该是灰的：%+v", b)
	}
	if err := tt.Click("上一张"); err != nil {
		t.Fatal(err)
	}
	if !tt.HasText("2 / 3") {
		t.Fatalf("上一张：%q", tt.Texts())
	}

	if err := tt.Click("保存"); err != nil {
		t.Fatal(err)
	}
	s := <-got
	if !bytes.Equal(s.data, shot) || !strings.HasPrefix(s.name, "Zeta_") || !strings.HasSuffix(s.name, ".png") {
		t.Fatalf("保存：%d 字节，名字 %q", len(s.data), s.name)
	}
	waitText(t, tt, "已保存到 ")

	tt.Key(0, ui.KeyEscape)
	tt.Frame()
	if w.v != nil {
		t.Fatal("Esc 没关上")
	}
	tt.ClickAt(at.X+at.W/2, at.Y+at.H/2)
	tt.Frame()
	if err := tt.Click("返回"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if w.v != nil {
		t.Fatal("返回没关上")
	}
}

// 工具结果里的图也能看大图；没给 Save 时保存是灰的；取不到时说一声。
func TestImageViewerOfToolImages(t *testing.T) {
	ch := New()
	ch.LoadImage = func(file, ref string) ([]byte, error) { return nil, errors.New("记录里没有这张图") }
	ch.SetItems(decodeItems(t, `[{"kind":"work","id":"w","steps":[{"kind":"tool","id":"r","name":"Read","status":"success",
		"input":{"file_path":"/a.png"},"images":[{"kind":"session","file":"/s.jsonl","ref":"2.0"}]}]}]`))
	tt, w := viewerTester(ch, true, nil)
	settleImages(ch, tt)
	if err := tt.Click("已完成 1 个步骤"); err != nil {
		t.Fatal(err)
	}
	if err := tt.Click("读取 a.png"); err != nil {
		t.Fatal(err)
	}
	settleImages(ch, tt)
	if err := tt.Click("图片无法显示"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if w.v == nil || len(w.v.images) != 1 {
		t.Fatal("没打开")
	}
	if tt.HasText("1 / 1") {
		t.Error("只有一张时不该有页码")
	}
	if b, ok := tt.AccessByRole(ui.RoleButton, "保存"); !ok || !b.Disabled {
		t.Fatalf("取不到的图，保存该是灰的：%+v", b)
	}
}

// 没接 OpenImages 的列表里，图点不开。
func TestImagesNeedOpener(t *testing.T) {
	ch := New()
	ch.LoadImage = func(file, ref string) ([]byte, error) { return testPNG(t, 30, 20), nil }
	ch.SetItems(decodeItems(t, `[{"kind":"user","id":"u","text":"看图","images":[{"kind":"session","file":"/s.jsonl","ref":"1"}]}]`))
	tt := newTester(ch, 900, 700, false)
	settleImages(ch, tt)
	if err := tt.Click("图片"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if _, ok := tt.Access("看大图"); ok {
		t.Fatal("没接 OpenImages 却打开了看大图")
	}
}

func TestImageBytes(t *testing.T) {
	shot := testPNG(t, 2, 2)
	load := func(file, ref string) ([]byte, error) { return shot, nil }
	if got, err := ImageBytes(items.Image{Kind: items.Session, File: "/s.jsonl", Ref: "1"}, load); err != nil || !bytes.Equal(got, shot) {
		t.Fatalf("会话记录里的：%d 字节 %v", len(got), err)
	}
	if got, err := ImageBytes(items.Image{Kind: items.Inline, Data: "aGk="}, nil); err != nil || string(got) != "hi" {
		t.Fatalf("带着的：%q %v", got, err)
	}
	if _, err := ImageBytes(items.Image{Kind: items.Session}, nil); !errors.Is(err, errNoLoader) {
		t.Fatalf("没给 load：%v", err)
	}
	if _, err := ImageBytes(items.Image{Kind: "什么"}, load); err == nil {
		t.Fatal("不认识的种类该出错")
	}
}

func TestImageFileName(t *testing.T) {
	now := time.Date(2026, 11, 22, 15, 4, 5, 0, time.Local)
	if got := imageFileName(testPNG(t, 2, 2), now); got != "Zeta_20261122_150405.png" {
		t.Errorf("png：%q", got)
	}
	if got := imageFileName([]byte("\xff\xd8\xff\xe0 jpeg"), now); got != "Zeta_20261122_150405.jpg" {
		t.Errorf("jpeg：%q", got)
	}
	if got := imageFileName([]byte("不是图"), now); got != "Zeta_20261122_150405.png" {
		t.Errorf("认不出：%q", got)
	}
}

// waitText 等后台做完的事让界面出现以 prefix 开头的字，最多 5 秒。
func waitText(t *testing.T, tt *ui.Tester, prefix string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		tt.Frame()
		for _, s := range tt.Texts() {
			if strings.HasPrefix(s, prefix) {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("没等到 %q：%q", prefix, tt.Texts())
}
