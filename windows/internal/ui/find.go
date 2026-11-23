package ui

import (
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 对话内查找的查找栏（同手机版的 ChatFindBar）：Ctrl+F 打开，查找时顶替输入栏。搜索胶囊后面是「第几个/共几个」和上一个、
// 下一个、关闭；下面一行说明查的范围。查的是整个对话：打开时把还没载入的更早记录一次载完（conv.earlier 带 rest），
// 载不下去时只查载入了的，说明原因。回车跳到下一个，Shift+回车上一个，Esc 关掉。命中的高亮和跳转在 chatview（find.go）。

// findBar 是一个对话查找栏的状态。
type findBar struct {
	open  bool
	input string
	// focus：下一帧把焦点放进输入框（刚打开、又按了 Ctrl+F）。
	focus bool
	// asked：已经要过一次载完更早的记录（核心读完才回话，那时更早的已经推来了）。在载、没载成的原因记在 chatPane.earlier。
	asked bool
}

// findShortcuts 是聊天页上查找的快捷键：Ctrl+F 打开查找栏（开着时把焦点放回输入框），开着时 Esc 关掉。
func (a *App) findShortcuts(c *ui.Context, p *chatPane) {
	c.OnWindowShortcut(ui.Cmd, ui.KeyF, func() { p.find.open, p.find.focus = true, true })
	if p.find.open {
		c.OnWindowShortcut(0, ui.KeyEscape, p.closeFind)
	}
}

// closeFind 关掉查找栏，高亮跟着去掉。
func (p *chatPane) closeFind() {
	p.find = findBar{}
	p.list.SetFind("")
}

// findBar 是查找栏（照 ChatGPT、浏览器）：浮在对话区右上角、顶栏下面的一张小卡片，输入栏不动。Enter、Shift+Enter 下一个、
// 上一个，Esc 关掉。
func (a *App) findBar(c *ui.Context, p *chatPane) {
	f := &p.find
	p.list.SetFind(f.input)
	// 还有没载入的更早记录时一次载完（同 ChatScreen.kt 的 LoadEarlierWhileFinding）
	if !f.asked && p.state.Earlier > 0 && p.src.Conv != nil {
		f.asked = a.loadEarlier(p, true)
	}
	k := widget.Of(c)
	index, count := p.list.FindCount()
	left := p.state.Earlier
	box := ui.Column(c.Key("find")).Absolute().Top(headerHeight+4).Right(20).Width(400).Gap(4).Padding(6, 6, 8, 6)
	widget.Shadow(widget.Surface{Shape: widget.ShapePopup, Fill: k.PopupGlass, Edge: k.Divider}.Paint(box), k)
	box.Children(func() {
		ui.Row(c).FillWidth().Gap(2).AlignItems(ui.Center).Children(func() {
			in := widget.SearchField(c, &f.input, locales.FindPlaceholder()).OnTextKey(func(tk ui.TextKey) bool {
				switch {
				case tk.Key == ui.KeyEnter && tk.Mods == 0:
					p.list.FindStep(1)
				case tk.Key == ui.KeyEnter && tk.Mods == ui.Shift:
					p.list.FindStep(-1)
				case tk.Key == ui.KeyEscape:
					p.closeFind()
				default:
					return false
				}
				return true
			})
			if f.focus {
				in.Focus()
				f.focus = false
			}
			if p.list.FindQuery() != "" {
				color := k.Secondary
				if count == 0 {
					color = k.Tertiary
				}
				ui.Text(c, matchCount(index, count, left, locales.ErrorText(p.earlier.failed))).FontSize(widget.SizeBodyS).TextColor(color).SingleLine().
					Margin(0, 2, 0, 8).Shrink(0)
			}
			widget.FlatButton(c, widget.IconChevronUp, locales.FindPrevious(), 30, 18).Disabled(count == 0).OnClick(func() { p.list.FindStep(-1) })
			widget.FlatButton(c, widget.IconChevronDown, locales.FindNext(), 30, 18).Disabled(count == 0).OnClick(func() { p.list.FindStep(1) })
			widget.FlatButton(c, widget.IconClose, locales.FindClose(), 30, 18).OnClick(p.closeFind)
		})
		ui.Text(c, findScope(left, locales.ErrorText(p.earlier.failed))).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).Padding(0, 10)
	})
}

// matchCount 是查找栏里的计数：第几个/共几个；没有命中时看更早的记录还载不载。
func matchCount(index, count, left int, failed string) string {
	switch {
	case count > 0:
		return strconv.Itoa(index+1) + "/" + strconv.Itoa(count)
	case left > 0 && failed == "":
		return locales.FindSearching()
	}
	return locales.FindNoResults()
}

// findScope 是查找栏下面那行说明。
func findScope(left int, failed string) string {
	switch {
	case failed != "" && left > 0:
		return locales.FindScopeFailed(failed, left)
	case left > 0:
		return locales.FindScopeLoading(left)
	}
	return locales.FindScope()
}
