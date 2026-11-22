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

// roundAt 是挂在第 row 个条目下面的那一轮：这一项是那一轮用户消息的 round_last、调用方给了 Actions 时 ok 为真，
// user 是那条用户消息，later 是之后还有几轮。
func (ch *Chat) roundAt(row int) (user items.Item, later int, ok bool) {
	if ch.Actions == nil {
		return items.Item{}, 0, false
	}
	k, ok := ch.rounds[ch.items[row].ID]
	if !ok {
		return items.Item{}, 0, false
	}
	return ch.items[ch.users[k]], len(ch.users) - 1 - k, true
}

// laterThan 是第 row 个条目（一条用户消息）之后还有几轮。
func (ch *Chat) laterThan(row int) int {
	k, _ := slices.BinarySearch(ch.users, row)
	return len(ch.users) - 1 - k
}

// actions 画挂在第 row 个条目下面的那一轮的操作（回答的画在复制键后面）。
func (ch *Chat) actions(c *ui.Context, row int) {
	if user, later, ok := ch.roundAt(row); ok {
		ch.Actions(c, user, later)
	}
}

// actionRow 是不以回答结尾的一轮（停在工作过程或出错提示上）的操作排：单占一行，和回答下面的对齐，下面留 actionGap。
// 有消息操作的列表里，不挂操作的工作过程、提示下面也留这么多，不为它建一行空的操作排。
func (ch *Chat) actionRow(c *ui.Context, row int) {
	if ch.Actions == nil {
		return
	}
	user, later, ok := ch.roundAt(row)
	if !ok {
		ui.Box(c).Height(actionGap)
		return
	}
	ui.Row(c).AlignItems(ui.Center).Margin(0, 0, actionGap, 0).Children(func() { ch.Actions(c, user, later) })
}

// actionGap 是操作排下面留的空。
const actionGap = 4

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
