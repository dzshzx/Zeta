package about

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把关于页的几个样子画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
// 简体中文画浅色、深色两套，英文（文件名带 -en）画浅色一套。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设：不画预览")
	}
	t.Cleanup(func() { i18n.SetLocaleFunc(nil) })
	failed := update.Status{Phase: update.Available, Manifest: manifest(), Step: update.Failed, Reason: "从 github.com 下的文件 sha256 和清单不一致，已删掉"}
	for name, st := range map[string]update.Status{
		"idle":        {},
		"failed":      {Phase: update.CheckFailed, Reason: "连 github.com 超时；连 gh-proxy.com 超时"},
		"available":   {Phase: update.Available, Manifest: manifest()},
		"downloading": {Phase: update.Available, Manifest: manifest(), Step: update.Downloading, Done: 5 << 20},
		"retry":       failed,
	} {
		for _, run := range []struct {
			lang string
			dark bool
		}{{"zh-Hans", false}, {"zh-Hans", true}, {"en", false}} {
			dark, lang := run.dark, run.lang
			i18n.SetLocaleFunc(func() string { return lang })
			u := &updates{enabled: true, status: st}
			p := &Page{Version: release, Updates: u, Prefs: &prefs{auto: true}}
			tt := ui.NewTesterWith(func(c *ui.Context) {
				// 和设置页里一样：COUI 的主题，卡片后面是浅灰的页面底
				c.SetTheme(widget.Theme(c.Theme().Dark))
				ui.Scroll(c).Fill().Padding(16, 0).Background(widget.Of(c).Page).Children(func() { p.View(c) })
			}, 800, 900, ui.TesterOptions{Dark: dark})
			tt.SetLocale(lang)
			tt.Frame()
			look := "light"
			if dark {
				look = "dark"
			}
			if lang != "zh-Hans" {
				look += "-" + lang
			}
			f, err := os.Create(filepath.Join(dir, "about-"+name+"-"+look+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(f, tt.Image()); err != nil {
				t.Fatal(err)
			}
			f.Close()
		}
	}
}
