package chatview

import (
	"slices"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 消息操作在列表里的落脚处（同手机版 MessageActions.kt、ChatItems.kt）：做什么由调用方给（UserMenu、Actions），
// 这里只管画在哪、编辑时藏起哪些。

// shownRows 是列表显示几行：编辑一条消息时只显示到它为止，它之后的发出后会被替换（照手机版）。
func (ch *Chat) shownRows() int {
	if ch.Editing == "" {
		return len(ch.items)
	}
	if i := slices.IndexFunc(ch.items, func(it items.Item) bool { return it.ID == ch.Editing }); i >= 0 {
		return i + 1
	}
	return len(ch.items)
}

// editingEdge 是用户消息气泡的描边：正在编辑的那条描一圈主题色，别的没有。
func (ch *Chat) editingEdge(c *ui.Context, it items.Item) ui.Color {
	if ch.Editing != "" && it.ID == ch.Editing {
		return coui.Of(c).Primary
	}
	return ui.Color{}
}

// actions 画挂在第 row 个条目下面的那一轮的操作（回答的画在复制键后面）：这一项是它那一轮用户消息的 round_last 时才有。
func (ch *Chat) actions(c *ui.Context, row int) {
	u := items.RoundOf(ch.items, row)
	if ch.Actions == nil || u < 0 || ch.items[u].RoundLast != ch.items[row].ID {
		return
	}
	ch.Actions(c, ch.items[u], items.LastRound(ch.items, row))
}

// actionRow 是不以回答结尾的一轮（停在工作过程或出错提示上）的操作排：单占一行，和回答下面的对齐。
func (ch *Chat) actionRow(c *ui.Context, row int) {
	if ch.Actions == nil {
		return
	}
	ui.Row(c).AlignItems(ui.Center).Margin(0, 0, 4, 0).Children(func() { ch.actions(c, row) })
}

// ActionButton 是回答下面那排的一个操作键，和复制键一个样子：30 的平圆钮、15 的次要色图标。
func ActionButton(c *ui.Context, icon *ui.SVG, label string, enabled bool) ui.Element {
	return coui.FlatButton(c, icon, label, 30, 15).Margin(6, 0, 0, 0).Disabled(!enabled)
}

// opensImage 让 e 点了交给 OpenImages 打开看大图：从 list 的第 index 张看起，左右能翻到同一组的别的图。返回 e。
func (ch *Chat) opensImage(e ui.Element, list []items.Image, index int) ui.Element {
	open := ch.OpenImages
	if open == nil {
		return e
	}
	return e.Cursor(ui.CursorPointer).OnClick(func() { open(slices.Clone(list), index) })
}
