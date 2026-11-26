package widget

import (
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui"
)

// Tone 是提示条的语气。
type Tone int

const (
	// Info 是一般的说明：淡灰底、次要文字色。
	Info Tone = iota
	// Caution 是要留意的（正在重连这类）：淡黄底、黄字。
	Caution
	// Failure 是出错了：淡红底、红字（同手机版聊天里的出错提示，ChatItems.kt）。
	Failure
)

// Notice 是一条提示条：8 的圆角、内边距 14/10，字 13，最多三行。文字能选中，右键「复制」复制全文：
// 超过三行的报错选中只选得到看得见的。
func Notice(c *ui.Context, text string, tone Tone) ui.Element {
	k := Of(c)
	ink, fill := k.Secondary, k.Tint
	switch tone {
	case Caution:
		ink, fill = k.Warning, k.Warning.Alpha(0.1)
	case Failure:
		ink, fill = k.Error, k.Error.Alpha(0.08)
	}
	t := ui.Text(c, text).FontSize(SizeBodyS).LineHeight(1.45).TextColor(ink).MaxLines(3).FillWidth().Padding(10, 14).Selectable().
		ContextMenu(func(m *ui.Menu) { CopyItem(c, m, text) })
	return Surface{Shape: Rounded(8, 2.5), Fill: fill}.Paint(t)
}

// CopyItem 往右键菜单里加「复制」，选了把 text 写进剪贴板。它取代可选中文字自带的「复制」「全选」，复制的是整段。
func CopyItem(c *ui.Context, m *ui.Menu, text string) {
	if m.Item(locales.CommonCopy()).Chosen() {
		c.Services().WriteClipboard(text)
	}
}
