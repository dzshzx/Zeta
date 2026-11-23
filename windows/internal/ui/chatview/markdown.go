package chatview

import (
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// styleMarkdown 照手机版的 MarkdownStyle 设回答和思考的排版：正文 16、行高 25，标题加粗；行内代码淡灰底；
// 思考用 14 的次要文字色。每一帧跟着深浅色设一次。
func styleMarkdown(c *ui.Context, answer, thinking *markdown.Theme) {
	k := widget.Of(c)
	*answer = markdown.Theme{
		FontSize: widget.SizeBodyXL, LineHeight: 25.0 / 16 / 1.17, CodeFont: widget.Mono, CodeLineHeight: 20.0 / 13 / 1.17,
		Text: k.Text, Muted: k.Secondary, Link: k.PrimaryText, Strong: k.Text, StrongWeight: 700,
		CodeBackground: k.CodeEdge, CodeSize: 0.85, BlockSpacing: 0.7, ItemSpacing: 0.3,
		Rule: k.Divider, QuoteBar: k.Divider, QuoteText: k.Secondary, TableBorder: k.Divider, TableHeader: k.CodeBody,
		CodeBlockBackground: k.CodeBody, CodeBlockBorder: k.CodeEdge, CodeBlockRadius: 12, CodeBlockPadding: 0.875,
		Checkbox: k.Primary,
	}
	*thinking = *answer
	thinking.FontSize, thinking.Text, thinking.Strong = widget.SizeBodyM, k.Secondary, k.Secondary
}

// codeBlock 是代码块（同手机版 MarkdownContent 的 CodeBlock）：12 的圆角、细边；顶上一栏写语言、右边复制，
// 底色比正文深一点，下面一条细线；正文 13 的等宽字，不折行、横着滚。
func codeBlock(c *ui.Context, b *markdown.CodeBlockView) {
	k := widget.Of(c)
	box := widget.Surface{Shape: widget.ShapeBlock, Fill: k.CodeBody, Edge: k.CodeEdge}.Paint(ui.Column(c)).AlignSelf(ui.Stretch).Clip().MarginY(2)
	box.Role(ui.RoleGroup).Label(locales.ChatCode())
	box.Children(func() {
		ui.Row(c).MinHeight(34).Padding(0, 4, 0, 14).AlignItems(ui.Center).Background(k.CodeHeader).Children(func() {
			ui.Text(c, b.Language).Font(widget.Mono).FontSize(11.5).FontWeight(widget.Medium).TextColor(k.Secondary).Unselectable()
			ui.Spacer(c)
			if widget.FlatButton(c, widget.IconCopy, locales.ChatCopyCode(), 32, 14).Disabled(b.Open).Clicked() {
				c.Services().WriteClipboard(b.Code())
			}
		})
		ui.Box(c).Height(1).Background(k.CodeEdge)
		b.Body(c).FontSize(13).Padding(11, 14)
	})
}

// Document 是照回答的样子排的一段 Markdown（同一套字号、代码块、公式），给聊天以外要这样排的地方用：记忆、CLAUDE.md 的预览。
// 一段文字一个，只在界面线程上用。链接照 mygo 的默认，网址用系统浏览器打开。
type Document struct {
	opts          markdown.Options
	theme, unused markdown.Theme
}

// NewDocument 建一个。
func NewDocument() *Document {
	d := &Document{}
	d.opts.Theme = &d.theme
	d.opts.CodeBlock = codeBlock
	setMath(&d.opts, &d.theme, answerMathScale)
	return d
}

// View 排 text，配色每帧跟着深浅色换。
func (d *Document) View(c *ui.Context, text string) ui.Element {
	styleMarkdown(c, &d.theme, &d.unused)
	return markdown.View(c, mathSource(text), &d.opts)
}
