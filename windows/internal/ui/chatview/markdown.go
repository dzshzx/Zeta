package chatview

import (
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// styleMarkdown 照手机版的 MarkdownStyle 设回答和思考的排版：正文 16、行高 25，标题加粗；行内代码淡灰底；
// 思考用 14 的次要文字色。每一帧跟着深浅色设一次。
func styleMarkdown(c *ui.Context, answer, thinking *markdown.Theme) {
	k := coui.Of(c)
	*answer = markdown.Theme{
		FontSize: coui.SizeBodyXL, LineHeight: 25.0 / 16 / 1.17, CodeFont: coui.Mono, CodeLineHeight: 20.0 / 13 / 1.17,
		Text: k.Text, Muted: k.Secondary, Link: k.PrimaryText, Strong: k.Text, StrongWeight: 700,
		CodeBackground: k.CodeEdge, CodeSize: 0.85, BlockSpacing: 0.7, ItemSpacing: 0.3,
		Rule: k.Divider, QuoteBar: k.Divider, QuoteText: k.Secondary, TableBorder: k.Divider, TableHeader: k.CodeBody,
		CodeBlockBackground: k.CodeBody, CodeBlockBorder: k.CodeEdge, CodeBlockRadius: 12, CodeBlockPadding: 0.875,
		Checkbox: k.Primary,
	}
	*thinking = *answer
	thinking.FontSize, thinking.Text, thinking.Strong = coui.SizeBodyM, k.Secondary, k.Secondary
}

// codeBlock 是代码块（同手机版 MarkdownContent 的 CodeBlock）：12 的圆角、细边；顶上一栏写语言、右边复制，
// 底色比正文深一点，下面一条细线；正文 13 的等宽字，不折行、横着滚。
func codeBlock(c *ui.Context, b *markdown.CodeBlockView) {
	k := coui.Of(c)
	box := coui.Surface{Shape: coui.ShapeBlock, Fill: k.CodeBody, Edge: k.CodeEdge}.Paint(ui.Column(c)).AlignSelf(ui.Stretch).Clip().MarginY(2)
	box.Role(ui.RoleGroup).Label("代码")
	box.Children(func() {
		ui.Row(c).MinHeight(34).Padding(0, 4, 0, 14).AlignItems(ui.Center).Background(k.CodeHeader).Children(func() {
			ui.Text(c, b.Language).Font(coui.Mono).FontSize(11.5).FontWeight(coui.Medium).TextColor(k.Secondary).Unselectable()
			ui.Spacer(c)
			if coui.FlatButton(c, coui.IconCopy, "复制代码", 32, 14).Disabled(b.Open).Clicked() {
				c.Services().WriteClipboard(b.Code())
			}
		})
		ui.Box(c).Height(1).Background(k.CodeEdge)
		b.Body(c).FontSize(13).Padding(11, 14)
	})
}
