package about

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/update"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把关于页的几个样子画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设：不画预览")
	}
	failed := update.Status{Phase: update.Available, Manifest: manifest(), Step: update.Failed, Reason: "从 github.com 下的文件 sha256 和清单不一致，已删掉"}
	for name, st := range map[string]update.Status{
		"idle":        {},
		"failed":      {Phase: update.CheckFailed, Reason: "连 github.com 超时；连 gh-proxy.com 超时"},
		"available":   {Phase: update.Available, Manifest: manifest()},
		"downloading": {Phase: update.Available, Manifest: manifest(), Step: update.Downloading, Done: 5 << 20},
		"retry":       failed,
	} {
		for _, dark := range []bool{false, true} {
			u := &updates{enabled: true, status: st}
			p := &Page{Version: release, Updates: u, Prefs: &prefs{auto: true}}
			tt := ui.NewTesterWith(func(c *ui.Context) {
				ui.Scroll(c).Fill().Padding(24).Background(c.Theme().Background).Children(func() { p.View(c) })
			}, 640, 900, ui.TesterOptions{Dark: dark})
			tt.Frame()
			look := "light"
			if dark {
				look = "dark"
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
