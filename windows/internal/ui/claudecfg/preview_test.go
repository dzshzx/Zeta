package claudecfg

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把设备页上的三组和点进去的各页画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
// 简体中文画浅色、深色两套，英文（文件名带 -en）画浅色一套，看译文有没有挤爆。
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
		dark, look := run.dark, "light"
		if dark {
			look = "dark"
		}
		if run.lang != "zh-Hans" {
			look += "-" + run.lang
		}
		lang := run.lang
		i18n.SetLocaleFunc(func() string { return lang })
		f := newFixture(t, Options{})
		f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet","permissions":{"allow":["Bash(npm test)"]}}`)
		f.serve.Write(t, ".claude/skills/tidy/SKILL.md", "---\nname: tidy\ndescription: 整理代码，统一格式和命名\n---\n正文\n")
		f.serve.Write(t, ".claude/agents/code-reviewer.md", "---\nname: code-reviewer\ndescription: 改完代码后审查改动\nmodel: sonnet\n---\n你是审查员\n")
		f.page.Load()
		page := f.page
		// 和设置窗口里一样：COUI 的主题；设备页的几组画在 widget.Body 里，子页自己带顶栏和内容
		f.tt = ui.NewTesterWith(func(c *ui.Context) {
			c.SetTheme(widget.Theme(c.Theme().Dark))
			ui.Column(c).Fill().Background(widget.Of(c).Page).Children(func() {
				if page.Open() {
					page.SubPage(c)
					return
				}
				widget.Body(c, "device", func() { page.Sections(c) })
			})
		}, 900, 1000, ui.TesterOptions{Dark: dark})
		f.tt.SetLocale(lang)
		shot := func(name string) {
			t.Helper()
			f.tt.Frame()
			out, err := os.Create(filepath.Join(dir, "claudecfg-"+name+"-"+look+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, f.tt.Image()); err != nil {
				t.Fatal(err)
			}
			out.Close()
		}
		shot("device")
		f.press(locales.ClaudecfgDefaultMode())
		shot("menu")
		f.tt.Key(0, ui.KeyEscape)

		f.press(locales.ClaudecfgOpenEntry(locales.ClaudecfgDefaultEffort()))
		shot("effort")
		f.press(locales.WidgetBack())

		f.press(locales.ClaudecfgOpenEntry(locales.ClaudecfgMemory()))
		shot("memory")
		f.press(locales.ClaudecfgOpenEntry("CLAUDE.md"))
		f.typeInto("CLAUDE.md", "# 规矩\n\n- 回答用中文\n- **先跑测试**，再说做完了\n\n```go\nfmt.Println(\"hi\")\n```\n")
		shot("document")
		f.press(locales.ClaudecfgDocumentPreview())
		shot("document-preview")
		f.click(locales.ClaudecfgSave())
		f.press(locales.WidgetBack())
		f.press(locales.WidgetBack())

		f.press(locales.ClaudecfgOpenEntry("Skills"))
		shot("skills")
		f.press(locales.WidgetBack())

		f.press(locales.ClaudecfgOpenEntry(locales.ClaudecfgMcpServers()))
		f.click(locales.ClaudecfgMcpAdd())
		shot("mcp-add")
		f.typeInto(locales.ClaudecfgName(), "docs")
		f.typeInto(locales.ClaudecfgMcpURL(), "https://mcp.invalid/mcp")
		f.click(locales.ClaudecfgMcpAddButton())
		shot("mcp")
		f.press(locales.ClaudecfgOpenEntry("docs"))
		shot("mcp-server")
		f.press(locales.WidgetBack())
		f.press(locales.WidgetBack())

		f.press(locales.ClaudecfgOpenEntry(locales.ClaudecfgSubagents()))
		shot("agents")
		f.press(locales.ClaudecfgAgentNew())
		f.typeInto(locales.ClaudecfgName(), "code-reviewer")
		f.press(locales.ClaudecfgAllTools())
		f.press(locales.ClaudecfgToolRead())
		shot("agent")
		f.press(locales.WidgetBack())
		f.click(locales.ClaudecfgDiscard())
		f.press(locales.WidgetBack())

		f.press(locales.ClaudecfgOpenEntry(locales.ClaudecfgPermissions()))
		shot("permissions")
		f.click(locales.ClaudecfgPermAddRule())
		shot("rule")
	}
}
