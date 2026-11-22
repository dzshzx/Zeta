package ui

import (
	"context"
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
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
	// loading：正在载完更早的记录，asked：已经要过一次（核心读完才回话，那时更早的已经推来了）；failed 是载不下去的原因。
	loading, asked bool
	failed         string
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

// loadAllEarlier 在查找栏开着、还有没载入的更早记录时一次载完（同 ChatScreen.kt 的 LoadEarlierWhileFinding）。
func (a *App) loadAllEarlier(p *chatPane) {
	f := &p.find
	if f.asked || p.state.Earlier == 0 || p.src.Conv == nil {
		return
	}
	f.loading, f.asked = true, true
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		err := conv.LoadEarlier(ctx, true)
		a.post(func() {
			f.loading = false
			if err != nil {
				f.failed = "没读到更早的记录"
				if chat.IsEarlierStale(err) {
					f.failed = chat.EarlierStale
				}
			}
		})
		return nil
	}, nil)
}

// findBar 是查找栏。
func (a *App) findBar(c *ui.Context, p *chatPane) {
	f := &p.find
	p.list.SetFind(f.input)
	a.loadAllEarlier(p)
	k := coui.Of(c)
	index, count := p.list.FindCount()
	left := p.state.Earlier
	ui.Column(c.Key("find")).FillWidth().Gap(6).Padding(0, 0, 2, 0).Children(func() {
		ui.Row(c).FillWidth().Gap(2).AlignItems(ui.Center).Children(func() {
			in := coui.SearchField(c, &f.input, "在对话中查找").OnTextKey(func(tk ui.TextKey) bool {
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
				ui.Text(c, matchCount(index, count, left, f.failed)).FontSize(coui.SizeBodyS).TextColor(color).SingleLine().
					Margin(0, 2, 0, 8).Shrink(0)
			}
			coui.FlatButton(c, coui.IconChevronUp, "上一个", 36, 20).Disabled(count == 0).OnClick(func() { p.list.FindStep(-1) })
			coui.FlatButton(c, coui.IconChevronDown, "下一个", 36, 20).Disabled(count == 0).OnClick(func() { p.list.FindStep(1) })
			coui.FlatButton(c, coui.IconClose, "关闭查找", 36, 20).OnClick(p.closeFind)
		})
		ui.Text(c, findScope(left, f.failed)).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).Padding(0, 14)
	})
}

// matchCount 是查找栏里的计数：第几个/共几个；没有命中时看更早的记录还载不载。
func matchCount(index, count, left int, failed string) string {
	switch {
	case count > 0:
		return strconv.Itoa(index+1) + "/" + strconv.Itoa(count)
	case left > 0 && failed == "":
		return "查找中"
	}
	return "无结果"
}

// findScope 是查找栏下面那行说明。
func findScope(left int, failed string) string {
	s := "查提问和回答，不含工具步骤和思考"
	switch {
	case failed != "" && left > 0:
		s += "；" + failed + "，更早的 " + strconv.Itoa(left) + " 条没查"
	case left > 0:
		s += "；正在载入更早的 " + strconv.Itoa(left) + " 条…"
	}
	return s
}
