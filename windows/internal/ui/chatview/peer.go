package chatview

import (
	"strings"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// peerLines：会话消息收着时露出正文的前几行。
const peerLines = 4

// peerMessage 画另一个 Claude Code 会话发来的消息（会话互通，核心给的 Peer 是发信会话的名字）：它和用户消息一样是
// Claude 要回应的输入，所以靠右；但不是用户亲手写的，不用用户的气泡，用步骤卡那样的细边框卡片：顶上一行图标和
// 「来自会话 名字」，下面是正文。正文长时默认收着、露出前几行，点顶上那行展开；对话内查找命中它时展开。右键复制正文。
func (ch *Chat) peerMessage(c *ui.Context, it items.Item) {
	t := c.Theme()
	k := inkOf(t)
	long := peerLong(it.Text)
	open := ch.flag("peer:"+it.ID, false)
	if ms, _ := ch.find.matchesIn(it.ID); len(ms) > 0 {
		*open = true
	}
	text := it.Text
	ui.Column(c).FillWidth().AlignItems(ui.End).Padding(16, 0, 6, 0).Children(func() {
		card := widget.Surface{Shape: widget.ShapeRow, Edge: k.border}.Paint(ui.Column(c)).MaxWidthPercent(bubbleWidthPercent).Clip()
		card.Children(func() {
			title := locales.ChatFromSession(it.Peer)
			var head ui.Element
			if long {
				head = ui.ButtonBase(c).Label(title).Description(locales.ChatToggleFullText()).Cursor(ui.CursorPointer)
				head.OnClick(func() {
					*open = !*open
					ch.list.HoldPlace()
				})
			} else {
				head = ui.Row(c)
			}
			head.MinHeight(34).Padding(0, 14).Gap(8).AlignItems(ui.Center).Children(func() {
				ui.Icon(c, iconAgent).FontSize(15).TextColor(k.icon)
				ui.Text(c, title).FontSize(13.5).TextColor(k.muted).SingleLine().Ellipsis("…").Shrink(1).Grow(1)
				if long {
					progress := float32(0)
					if *open {
						progress = 1
					}
					chevron(c, progress)
				}
			})
			body := ch.bubbleText(c, it).Selectable().FontSize(widget.SizeBodyL).LineHeight(1.44).TextColor(t.Text).
				Padding(0, 14, 10, 14).ContextMenu(func(m *ui.Menu) { widget.CopyItem(c, m, text) })
			if long && !*open {
				body.MaxLines(peerLines).Ellipsis("…")
			}
		})
	})
}

// peerLong：正文长得要收起来（行数多于 peerLines，或一大段字折行后也会超过）。
func peerLong(text string) bool {
	return strings.Count(text, "\n") >= peerLines || len([]rune(text)) > 60*peerLines
}
