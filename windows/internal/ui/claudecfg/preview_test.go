package claudecfg

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// TestPreviews 把设备页上的三组和点进去的各页画成 PNG 写到 $ZETA_PREVIEW_DIR，改界面时人工看；没设时不画。
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
		f.press("默认权限模式")
		shot("menu")
		f.tt.Key(0, ui.KeyEscape)

		f.press("打开默认思考强度")
		shot("effort")
		f.press("返回")

		f.press("打开记忆")
		shot("memory")
		f.press("打开CLAUDE.md")
		f.typeInto("CLAUDE.md", "# 规矩\n\n- 回答用中文\n- **先跑测试**，再说做完了\n\n```go\nfmt.Println(\"hi\")\n```\n")
		shot("document")
		f.press("预览")
		shot("document-preview")
		f.click("保存")
		f.press("返回")
		f.press("返回")

		f.press("打开Skills")
		shot("skills")
		f.press("返回")

		f.press("打开MCP 服务器")
		f.click("添加服务器")
		shot("mcp-add")
		f.typeInto("名称", "docs")
		f.typeInto("服务器地址", "https://mcp.invalid/mcp")
		f.click("添加")
		shot("mcp")
		f.press("打开docs")
		shot("mcp-server")
		f.press("返回")
		f.press("返回")

		f.press("打开Subagent")
		shot("agents")
		f.press("新建 Subagent")
		f.typeInto("名称", "code-reviewer")
		f.press("全部工具")
		f.press("读文件")
		shot("agent")
		f.press("返回")
		f.click("放弃")
		f.press("返回")

		f.press("打开工具权限")
		shot("permissions")
		f.click("添加规则")
		shot("rule")
	}
}
