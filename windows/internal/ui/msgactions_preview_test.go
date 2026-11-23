package ui

import (
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// TestMessageActionPreviews 在设了 ZETA_PREVIEW_DIR 时把消息操作的几个场景画成 PNG（浅色、深色）：回答下面的操作排、
// 用户消息的右键菜单（这里画成窗口里的菜单，真窗口里是系统的）、编辑中、还原代码的确认、看大图。
func TestMessageActionPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	scenes := map[string]func(t *testing.T, f *chatFixture){
		"msgactions-answer-row": func(*testing.T, *chatFixture) {},
		"msgactions-user-menu": func(t *testing.T, f *chatFixture) {
			if err := f.tt.RightClick("先说重点"); err != nil {
				t.Fatal(err)
			}
		},
		"msgactions-editing": func(t *testing.T, f *chatFixture) {
			_ = f.tt.RightClick("说慢点")
			if err := f.tt.ChooseMenuItem("编辑"); err != nil {
				t.Fatal(err)
			}
		},
		"msgactions-restore": func(t *testing.T, f *chatFixture) {
			f.answers["conv.code_changes"] = `{"files":["/home/zeta/web/login.go","/home/zeta/web/login_test.go","/etc/nginx/sites-enabled/web.conf"],"insertions":42,"deletions":7}`
			_ = f.tt.RightClick("说慢点")
			if err := f.tt.ChooseMenuItem("还原代码"); err != nil {
				t.Fatal(err)
			}
		},
		"msgactions-regenerate": func(t *testing.T, f *chatFixture) {
			f.answers["conv.code_changes"] = `{"files":["/home/zeta/web/login.go"],"insertions":3,"deletions":1}`
			f.clickButton(t, "重新生成", 3)
		},
		"msgactions-viewer": func(t *testing.T, f *chatFixture) {
			f.devices.ImageData = picture(1600, 1000, color.NRGBA{R: 70, G: 120, B: 210, A: 255}, color.NRGBA{R: 230, G: 170, B: 90, A: 255})
			f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":[{"kind":"user","id":"u-1","uuid":"x1","text":"这几张截图里登录页哪里不对？",
				"images":[{"kind":"session","file":"/s.jsonl","ref":"1"},{"kind":"session","file":"/s.jsonl","ref":"2"},{"kind":"session","file":"/s.jsonl","ref":"3"}]}]}`)
			waitFor(t, f, func() bool { _, ok := f.tt.Access("图片"); return ok })
			f.click(t, "图片")
			f.tt.Key(0, ui.KeyRight)
			waitFor(t, f, func() bool { return f.tt.HasText("2 / 3") })
		},
	}
	for name, setup := range scenes {
		for _, look := range previewLooks {
			i18n.SetLocaleFunc(nil) // 场景按源语言的文字点控件，画之前再换语言
			dark := look.dark
			f := openedForActions(t)
			// 右键菜单画在窗口里，预览里看得到
			f.tt = ui.NewTester(func(c *ui.Context) { c.SetMenuPresentation(ui.PopoverMenus); f.app.View(c) }, 1280, 860)
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			setup(t, f)
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			look.speak(t, f.tt)
			f.tt.Frame()
			f.tt.Frame()
			out, err := os.Create(filepath.Join(dir, name+"-"+look.suffix()+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, withCaptionButtons(f.tt.Image(), dark || f.app.buttonsOverDark)); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}
