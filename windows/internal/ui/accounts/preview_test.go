package accounts

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把账号页和额度明细的几个样子画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设：不画预览")
	}
	for _, dark := range []bool{false, true} {
		look := "light"
		if dark {
			look = "dark"
		}
		shot := func(name string, tt *ui.Tester) {
			t.Helper()
			tt.Frame()
			f, err := os.Create(filepath.Join(dir, "accounts-"+name+"-"+look+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(f, tt.Image()); err != nil {
				t.Fatal(err)
			}
			f.Close()
		}
		page := func(f *fixture) *ui.Tester {
			return ui.NewTesterWith(func(c *ui.Context) {
				// 和设置页里一样：COUI 的主题，卡片后面是浅灰的页面底
				c.SetTheme(widget.Theme(c.Theme().Dark))
				ui.Scroll(c).Fill().Padding(16, 0).Background(widget.Of(c).Page).Children(func() { f.page.View(c) })
			}, 640, 720, ui.TesterOptions{Dark: dark})
		}

		f := newFixture()
		tt := page(f)
		f.until(t, tt, "读完", f.loaded)
		shot("loaded", tt)

		f = newFixture()
		f.convs.busy = true
		tt = page(f)
		f.until(t, tt, "读完", f.loaded)
		click(t, tt, "切换")
		shot("ask", tt)
		click(t, tt, "切换")
		f.until(t, tt, "没切成", func() bool { return f.page.Accounts.Problem() != "" })
		shot("problem", tt)

		f = newFixture()
		f.host.current = ""
		f.host.unknown = &coreapi.Account{Email: "x@example.com", Subscription: "pro"}
		tt = page(f)
		f.until(t, tt, "读完", f.loaded)
		shot("unknown", tt)
	}
}
