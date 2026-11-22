package ui

import (
	"unicode/utf8"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 斜杠命令（同 Composer.kt 的 SlashSuggestions）：输入框里只有「/名字」、还没打空白时，输入框上方列出 Claude Code 报的命令，
// 按打的字过滤；上下键换选中的那条，回车、Tab 或点一下把「/命令 」填进输入框，Esc 收起（再打字又出来）。
// 命令是这个对话的 conv.state 报的：还没起过进程时核心给它记着的这个工作目录（或这台设备）上次那份，打开对话就有。

// slashMenu 是一个对话输入栏里斜杠命令提示的状态。
type slashMenu struct {
	// chosen 是选中第几条；dismissed 是按 Esc 收起时输入框里的字（字变了再出来）。
	chosen    int
	dismissed string
	// pick 是点了的那条，下一帧建输入框时填进去。
	pick string
	// reveal：选中的换了，把它滚进视野。
	reveal bool
}

// suggestions 是输入框里是 text 时要列的命令；不该列时为空。
func (a *App) suggestions(p *chatPane, text string) []chat.Command {
	prefix, ok := chat.SlashQuery(text)
	if !ok || text == p.slash.dismissed || p.src.Conv == nil {
		return nil
	}
	return chat.Suggest(p.state.Commands, prefix)
}

// slashKey 是输入框里按下的键先给斜杠命令提示看：列着命令时上下键换选中的、回车和 Tab 填进去、Esc 收起，返回 true 表示用掉了。
func (a *App) slashKey(p *chatPane, tk ui.TextKey) bool {
	if tk.Mods != 0 {
		return false
	}
	switch tk.Key {
	case ui.KeyUp, ui.KeyDown, ui.KeyEnter, ui.KeyTab, ui.KeyEscape:
	default:
		return false
	}
	text := tk.Text()
	list := a.suggestions(p, text)
	if len(list) == 0 {
		return false
	}
	m := &p.slash
	m.chosen = min(max(m.chosen, 0), len(list)-1)
	switch tk.Key {
	case ui.KeyUp:
		m.chosen, m.reveal = (m.chosen+len(list)-1)%len(list), true
	case ui.KeyDown:
		m.chosen, m.reveal = (m.chosen+1)%len(list), true
	case ui.KeyEscape:
		m.dismissed = text
	default:
		// 下一帧建输入框时填进去：那时才能把光标放到最后
		m.pick, m.chosen = commandText(list[m.chosen]), 0
	}
	return true
}

// commandText 是选了命令后输入框里的字：「/命令 」，接着打参数。
func commandText(cmd chat.Command) string { return "/" + cmd.Name + " " }

// slashInsert 把点了的那条命令填进输入框（换掉原来的「/…」），光标放在最后。
func slashInsert(in ui.Element, p *chatPane) {
	if p.slash.pick == "" {
		return
	}
	in.ReplaceText(0, utf8.RuneCountInString(p.draft), p.slash.pick)
	n := utf8.RuneCountInString(p.slash.pick)
	in.SetTextSelection(n, n)
	in.Focus() // 点了列表里的一条，接着在输入框里打参数
	p.slash.pick = ""
}

// slashCommandHeight 是提示列表最高多少（同 Composer.kt 的 232dp），再多时在里面滚。
const slashCommandHeight = 232

// slashList 是输入框上方的斜杠命令提示，下面一条分隔线；不该列时什么也不画。
func (a *App) slashList(c *ui.Context, p *chatPane) {
	list := a.suggestions(p, p.draft)
	if len(list) == 0 {
		p.slash.chosen = 0
		return
	}
	k := coui.Of(c)
	m := &p.slash
	m.chosen = min(max(m.chosen, 0), len(list)-1)
	ui.Scroll(c.Key("slash")).FillWidth().MaxHeight(slashCommandHeight).Margin(0, 0, 4, 0).Label("斜杠命令").Children(func() {
		ui.Column(c).FillWidth().Children(func() {
			for i, cmd := range list {
				row := slashRow(c.Key("slash/"+cmd.Name), cmd, i == m.chosen)
				if i == m.chosen && m.reveal {
					row.ScrollIntoView()
					m.reveal = false
				}
				row.OnClick(func() { m.pick, m.chosen = commandText(cmd), 0 })
			}
		})
	})
	ui.Box(c).Height(1).Margin(0, 6, 4, 6).Background(k.Divider)
}

// slashRow 是一条命令：主题色的「/命令」和参数提示一行，说明在下一行（同 Composer.kt 的 SlashSuggestionRow）；选中的那条有底色。
func slashRow(c *ui.Context, cmd chat.Command, chosen bool) ui.Element {
	k := coui.Of(c)
	row := ui.Column(c).Label("/"+cmd.Name).Role(ui.RoleButton).FillWidth().Padding(7, 8).Radius(12).Gap(1).Cursor(ui.CursorPointer)
	if chosen || row.Hovered() {
		row.Background(k.Hover)
	}
	row.Children(func() {
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Text(c, "/"+cmd.Name).FontSize(coui.SizeBodyM).FontWeight(500).TextColor(k.PrimaryText).SingleLine().Shrink(0)
			if cmd.ArgumentHint != "" {
				ui.Text(c, cmd.ArgumentHint).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).SingleLine().Ellipsis("…").
					Shrink(1).MinWidth(0)
			}
		})
		if cmd.Description != "" {
			ui.Text(c, cmd.Description).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).SingleLine().Ellipsis("…")
		}
	})
	return row
}
