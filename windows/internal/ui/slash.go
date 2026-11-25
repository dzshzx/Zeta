package ui

import (
	"context"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

const permissionsCommand = "permissions"

var settingsCommands = [...]struct {
	name  string
	label func() string
}{
	{"config", locales.ClaudecfgConfigTitle},
	{permissionsCommand, locales.ClaudecfgPermissions},
}

// settingsCommand 只接管单独发送的裸命令；编辑旧消息时，文字仍是那条消息的内容。
func settingsCommand(p *chatPane, text string) string {
	if p.acts.editing == nil {
		text = strings.TrimSpace(text)
		for _, cmd := range settingsCommands {
			if text == "/"+cmd.name {
				return cmd.name
			}
		}
	}
	return ""
}

// 斜杠命令（同 Composer.kt 的 SlashSuggestions）：光标前那个词是「/名字」（在开头或空白后面，见 chat.SlashAt）时，输入框上方列出
// Claude Code 报的命令和 Zeta 的设置命令，按打的字过滤；上下键换选中的那条，回车、Tab 或点一下把那个词换成「/命令 」，Esc 收起（再打字又出来）。
// Claude Code 的命令是这个对话的 conv.state 报的：还没起过进程时核心给它记着的这个工作目录（或这台设备）上次那份，打开对话就有；
// 开始打 / 时让核心先把进程起好（prepareForSlash），起好后换成 Claude Code 这次报的。

// slashMenu 是一个对话输入栏里斜杠命令提示的状态。
type slashMenu struct {
	// chosen 是选中第几条；dismissed 是按 Esc 收起时输入框里的字（字变了再出来）。
	chosen    int
	dismissed string
	// pick 是选了命令以后输入框里的字、pickAt 是光标，下一帧建输入框时填进去；pick 为空是没有要填的。
	pick   string
	pickAt int
	// caret 是上一帧建输入框时的光标（按字符），选中了一段时为 -1；caretText 是那时输入框里的字。
	caret     int
	caretText string
	// reveal：选中的换了，把它滚进视野。
	reveal bool
	// typing：上一帧光标前是「/…」（prepareForSlash 看它从不是变成是）。
	typing bool
}

// suggestions 是输入框里是 text、光标在 caret 时正在打的斜杠命令和要列的命令；不该列时列表为空。
func (a *App) suggestions(p *chatPane, text string, caret int) (chat.Slash, []chat.Command) {
	s, ok := chat.SlashAt(text, caret)
	if !ok || text == p.slash.dismissed || p.src.Conv == nil {
		return s, nil
	}
	if p.acts.editing != nil {
		return s, chat.Suggest(p.state.Commands, s.Prefix)
	}
	var local [len(settingsCommands)]chat.Command
	for i, cmd := range settingsCommands {
		local[i] = chat.Command{Name: cmd.name, Description: cmd.label()}
	}
	return s, chat.Suggest(p.state.Commands, s.Prefix, local[:]...)
}

// caretIn 是输入框里是 text 时的光标：上一帧记下的光标加上这之后多打（或删掉）的字数。
// 列表在输入框上面、比它先建，按键时输入框里的字也可能比绑定的草稿新（见 ui.TextKey.Text），都只拿得到上一帧的光标；
// 打字和删字都在光标处，这样算是准的，只挪了光标时 slashInsert 再建一帧。
func (m *slashMenu) caretIn(text string) int {
	if m.caret < 0 {
		return -1
	}
	return m.caret + utf8.RuneCountInString(text) - utf8.RuneCountInString(m.caretText)
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
	if tk.Key == ui.KeyEnter && settingsCommand(p, text) != "" {
		return false // 已经打完裸命令，回车打开页面，不再填一次补全。
	}
	s, list := a.suggestions(p, text, p.slash.caretIn(text))
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
		m.pick, m.pickAt = s.Complete(text, list[m.chosen].Name)
		m.chosen = 0
	}
	return true
}

// slashInsert 把选了的命令填进输入框（换掉光标处的「/…」），光标放在命令后面的空格之后；再记下这一帧的光标。
func slashInsert(c *ui.Context, in ui.Element, p *chatPane) {
	m := &p.slash
	if m.pick != "" {
		in.ReplaceText(0, utf8.RuneCountInString(p.draft), m.pick)
		in.SetTextSelection(m.pickAt, m.pickAt)
		in.Focus() // 点了列表里的一条，接着在输入框里打参数
		m.pick = ""
	}
	caret := -1
	if start, end := in.TextSelection(); start == end {
		caret = start
	}
	if m.caretIn(p.draft) != caret {
		// 光标只挪了（方向键、点一下）：这一帧的列表是按旧的光标算的，再建一帧
		c.Invalidate()
	}
	m.caret, m.caretText = caret, p.draft
}

// slashCommandHeight 是提示列表最高多少（同 Composer.kt 的 232dp），再多时在里面滚。
const slashCommandHeight = 232

// slashList 是输入框上方的斜杠命令提示，下面一条分隔线；不该列时什么也不画。
func (a *App) slashList(c *ui.Context, p *chatPane) {
	caret := p.slash.caretIn(p.draft)
	a.prepareForSlash(p, p.draft, caret)
	s, list := a.suggestions(p, p.draft, caret)
	if len(list) == 0 {
		p.slash.chosen = 0
		return
	}
	k := widget.Of(c)
	m := &p.slash
	m.chosen = min(max(m.chosen, 0), len(list)-1)
	ui.Scroll(c.Key("slash")).FillWidth().MaxHeight(slashCommandHeight).Margin(0, 0, 4, 0).Label(locales.SlashCommands()).Children(func() {
		ui.Column(c).FillWidth().Children(func() {
			for i, cmd := range list {
				row := slashRow(c.Key("slash/"+cmd.Name), cmd, i == m.chosen)
				if i == m.chosen && m.reveal {
					row.ScrollIntoView()
					m.reveal = false
				}
				row.OnClick(func() { m.pick, m.pickAt = s.Complete(p.draft, cmd.Name); m.chosen = 0 })
			}
		})
	})
	ui.Box(c).Height(1).Margin(0, 6, 4, 6).Background(k.Divider)
}

// slashRow 是一条命令：主题色的「/命令」和参数提示一行，说明在下一行（同 Composer.kt 的 SlashSuggestionRow）；选中的那条有底色。
func slashRow(c *ui.Context, cmd chat.Command, chosen bool) ui.Element {
	k := widget.Of(c)
	row := ui.Column(c).Label("/"+cmd.Name).Role(ui.RoleButton).FillWidth().Padding(7, 8).Radius(12).Gap(1).Cursor(ui.CursorPointer)
	if chosen || row.Hovered() {
		row.Background(k.Hover)
	}
	row.Children(func() {
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Text(c, "/"+cmd.Name).FontSize(widget.SizeBodyM).FontWeight(500).TextColor(k.PrimaryText).SingleLine().Shrink(0)
			if cmd.ArgumentHint != "" {
				ui.Text(c, cmd.ArgumentHint).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).SingleLine().Ellipsis("…").
					Shrink(1).MinWidth(0)
			}
		})
		if cmd.Description != "" {
			ui.Text(c, cmd.Description).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).SingleLine().Ellipsis("…")
		}
	})
	return row
}

// prepareForSlash：开始打斜杠命令（光标前那个词从不是变成「/…」）、对话还没接着 Claude Code 进程时，让核心先起好进程
// （conv.prepare，同手机版 Composer.kt 的 onSlashTyped）：起好后 Claude Code 报的斜杠命令和模型清单随 conv.state 推来，
// 不用等发第一条消息。一个词只问一次，接着打字、已经接着进程时不问；只认打字带来的变化，进程结束时光标正停在「/…」后面也不自己再起
// （放着的草稿不该让进程一直起着）。起不来时核心在列表里加一条「启动失败」，这里不另说。
func (a *App) prepareForSlash(p *chatPane, text string, caret int) {
	m := &p.slash
	_, typing := chat.SlashAt(text, caret)
	start := typing && !m.typing
	m.typing = typing
	if !start || p.src.Conv == nil || p.src.CwdProblem != "" || p.state.Process || p.state.Blocked {
		return
	}
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error { return conv.Prepare(ctx) }, nil)
}
