package accounts

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把账号页和额度明细的几个样子画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
func TestPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设：不画预览")
	}
	t.Cleanup(func() { i18n.SetLocaleFunc(nil) })
	for _, run := range []struct {
		lang string
		dark bool
	}{{"zh-Hans", false}, {"zh-Hans", true}, {"en", false}} {
		dark := run.dark
		look := "light"
		if dark {
			look = "dark"
		}
		if run.lang != "zh-Hans" {
			look += "-" + run.lang
		}
		// shot 换成这一轮的语言画（场景按源语言的文字点控件），画完换回源语言
		shot := func(name string, tt *ui.Tester) {
			t.Helper()
			i18n.SetLocaleFunc(func() string { return run.lang })
			tt.SetLocale(run.lang)
			defer func() { i18n.SetLocaleFunc(nil); tt.SetLocale("zh-Hans"); tt.Frame() }()
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

		// 中转：账号页的中转一组；在用中转时的样子；编辑页（拉到模型列表、选了一个）
		f = relayFixture()
		f.host.relays = append(f.host.relays, coreapi.Relay{Name: "mine", BaseURL: "https://mine.example.com", HasKey: true})
		tt = page(f)
		f.until(t, tt, "读完", f.loaded)
		shot("relays", tt)

		f = relayFixture()
		f.host.current, f.host.relay = "", "cpa"
		tt = page(f)
		f.until(t, tt, "读完", func() bool { _, ok := f.page.Accounts.State(); return ok && !f.page.Quota.Refreshing() })
		shot("relay-current", tt)

		f = relayFixture()
		tt = page(f)
		f.until(t, tt, "读完", f.loaded)
		click(t, tt, "编辑")
		click(t, tt, "获取模型列表")
		f.until(t, tt, "拉到模型", func() bool { return tt.HasText("Claude Sonnet 4.5") })
		shot("relay-edit", tt)

		f = relayFixture()
		tt = page(f)
		f.until(t, tt, "读完", f.loaded)
		click(t, tt, "添加中转")
		shot("relay-add", tt)
	}
}
