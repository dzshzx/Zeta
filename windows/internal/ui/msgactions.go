package ui

import (
	"context"
	"net/http"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 聊天页的消息操作（同手机版 MessageActions.kt）：
//   - 用户消息右键：复制、收藏为常用指令、编辑、还原代码，删除单独一组。编辑时原文和附件放回输入栏，被编辑的消息描边、之后的内容先藏起来，
//     发出后替换（conv.rewrite）；输入栏上方一条提示，× 取消编辑，输入栏放回开始编辑前的样子。
//   - 一轮最后一段回答下面：复制、重新生成、删除、分支；最后一轮还在进行时不出现，别的事在进行时（can_rewind 为假）变灰。
// 各轮能做什么（能不能编辑、分支，操作排挂在哪一项下面）由核心算好放在用户条目上，这里照着显示。
// 重新生成、删除、还原代码先确认；这条消息之后改过文件时（conv.code_changes），重新生成、删除可以同时还原代码，
// 还原代码可以同时删除这一轮。重新生成由核心照原消息重发（resend）。分支由中枢做（hub.Fork），聊天窗格换到新对话。
// 核心没做成时照它的提示显示在输入栏上方。

// msgActions 是一个对话的消息操作状态，随聊天窗格换。
type msgActions struct {
	editing *edit // 正在编辑的那一轮
	confirm *confirm
}

// edit 是正在编辑的那条用户消息、它之后还有几轮，和开始编辑前输入栏里的（取消编辑时放回去）。
type edit struct {
	user  items.Item
	later int
	saved composerSnapshot
}

// confirmKind 是等确认的是哪样操作。
type confirmKind int

const (
	confirmRegenerate confirmKind = iota
	confirmDrop
	confirmRestore
)

// confirm 是等确认的重新生成、删除或还原代码：对哪条用户消息，之后还有几轮。changes 是还原代码会动到的文件（问过核心才知道）。
type confirm struct {
	kind  confirmKind
	user  items.Item
	later int
	open  bool
	// asked：核心答过了（changes 为 nil 是没有可还原的）；failed 是没问成的原因。
	asked   bool
	changes *coreapi.CodeChanges
	failed  string
	// code：重新生成、删除时同时还原代码；drop：还原代码时同时删除这一轮。
	code, drop bool
}

// syncMessageActions 在每一帧开头把编辑中的那条交给列表。列表的菜单和操作排在建窗格时接上（messageCallbacks）。
func (a *App) syncMessageActions(p *chatPane) {
	p.list.Editing = ""
	if x := p.acts.editing; x != nil {
		p.list.Editing = x.user.ID
	}
}

// messageCallbacks 给新窗格的列表接上用户消息的菜单和一轮下面的操作排。
func (a *App) messageCallbacks(p *chatPane) {
	p.list.UserMenu = func(c *ui.Context, m *ui.Menu, user items.Item, later int) { a.userMenu(c, m, p, user, later) }
	p.list.Actions = func(c *ui.Context, user items.Item, later int) { a.roundActions(c, p, user, later) }
}

// canRewind：现在能改写、删除、还原代码、分支（核心说能，连接在，也没在编辑别的）。
func (p *chatPane) canRewind() bool {
	return p.connected && p.state.CanRewind && p.acts.editing == nil
}

// userMenu 是用户消息右键菜单里「复制」后面的项：收藏为常用指令；编辑、还原代码、删除在 Claude Code 认得这条消息时才有。
// later 是它之后还有几轮。
func (a *App) userMenu(c *ui.Context, m *ui.Menu, p *chatPane, it items.Item, later int) {
	if m.Item(locales.MessageSaveAsPrompt()).Chosen() {
		c.Toast(a.saveMessage(it.Text))
	}
	if !it.Rewindable {
		return
	}
	off := !p.canRewind()
	if m.Item(locales.MessageEdit()).Disabled(off).Chosen() {
		a.beginEdit(p, it, later)
	}
	if m.Item(locales.MessageRestoreCode()).Disabled(off).Chosen() {
		a.ask(p, confirmRestore, it, later)
	}
	m.Separator()
	if m.Item(locales.CommonDelete()).Disabled(off).Danger().Chosen() {
		a.ask(p, confirmDrop, it, later)
	}
}

// saveMessage 把自己发过的一条消息收藏为常用指令（同手机版 PromptsUi.saveMessage）：直接存、放最前面，不弹编辑框；
// 同样的内容存过就不再存。返回要告诉用户的一句（弹个 toast）。
func (a *App) saveMessage(text string) string {
	list := a.hub.Prompts()
	if settings.HasSamePrompt(list, text) {
		return locales.MessagePromptExists()
	}
	if why := settings.PromptProblem(list, "", text, ""); why != "" {
		return why
	}
	if err := a.hub.SetPrompts(settings.AddPrompt(list, newPromptID(), "", text)); err != nil {
		return locales.MessagePromptSaveFailed(err.Error())
	}
	return locales.MessagePromptSaved()
}

// roundActions 是用户消息 user 开始的那一轮的操作键：重新生成、删除、分支（同手机版 TurnActionButtons）。
// later 是之后还有几轮：最后一轮（0）还在进行时不出现。
func (a *App) roundActions(c *ui.Context, p *chatPane, user items.Item, later int) {
	if later == 0 && (p.state.Busy || p.state.Rewinding) {
		return
	}
	on := p.canRewind()
	if user.Rewindable {
		chatview.ActionButton(c, widget.IconRefresh, locales.MessageRegenerate(), on).OnClick(func() { a.ask(p, confirmRegenerate, user, later) })
		chatview.ActionButton(c, widget.IconDelete, locales.MessageDeleteRound(), on).OnClick(func() { a.ask(p, confirmDrop, user, later) })
	}
	if user.Forkable {
		chatview.ActionButton(c, widget.IconBranch, locales.MessageFork(), on).OnClick(func() {
			p.failed = ""
			a.hub.Fork(user.RoundLast, p.fail)
		})
	}
}

// ask 弹出确认框（later 是这一轮之后还有几轮），同时问核心还原代码会动到哪些文件。
func (a *App) ask(p *chatPane, kind confirmKind, user items.Item, later int) {
	cf := &confirm{kind: kind, user: user, later: later, open: true, code: true}
	p.acts.confirm = cf
	a.hub.RunChatThen(func(ctx context.Context, conv *chat.Conversation) func() {
		changes, err := conv.CodeChanges(ctx, user.ID)
		return func() {
			cf.asked, cf.changes = true, changes
			if err != nil {
				cf.failed = err.Error()
			}
		}
	})
}

// confirmed 照确认框里选的做。
func (a *App) confirmed(cf *confirm) {
	target := cf.user.ID
	withCode := cf.changes != nil && cf.code
	switch cf.kind {
	case confirmRestore:
		drop := cf.drop
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			return conv.RestoreCode(ctx, target, drop)
		})
	case confirmDrop:
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			if withCode {
				return conv.RestoreCode(ctx, target, true)
			}
			return conv.Drop(ctx, target)
		})
	case confirmRegenerate:
		// 原样重发这一轮的提问：文字、图片和附件路径都由核心从那条消息取
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			return conv.Regenerate(ctx, target, withCode)
		})
	}
}

// reattach 把消息里的图还原成能再发出去的附件（几张同时取，顺序照旧），返回取回来的和取不回来的张数。阻塞，在后台调用。
func reattach(list []items.Image, load func(file, ref string) ([]byte, error), loadInline func(key string) ([]byte, error)) ([]coreapi.Image, int) {
	got := make([]*coreapi.Image, len(list))
	var wg sync.WaitGroup
	for i, im := range list {
		wg.Go(func() {
			data, err := chatview.ImageBytes(im, load, loadInline)
			if kind := http.DetectContentType(data); err == nil && strings.HasPrefix(kind, "image/") {
				got[i] = &coreapi.Image{MediaType: kind, Data: data}
			}
		})
	}
	wg.Wait()
	var out []coreapi.Image
	for _, im := range got {
		if im != nil {
			out = append(out, *im)
		}
	}
	return out, len(list) - len(out)
}

// beginEdit 开始编辑一条消息（later 是它之后还有几轮）：输入栏里原有的收起来，换成这条的字、附件路径和图（图在后台取回）。
func (a *App) beginEdit(p *chatPane, user items.Item, later int) {
	if !p.canRewind() {
		return
	}
	x := &p.acts
	x.editing = &edit{user: user, later: later, saved: p.snapshot()}
	p.restore(composerSnapshot{})
	p.putBack(coreapi.Message{Text: user.Text, Files: user.Files})
	if len(user.Images) == 0 {
		return
	}
	editing, load, loadInline := x.editing, a.hub.ImageLoader(p.src.Backend), p.list.LoadInlineImage
	a.async(func() func() {
		images, lost := reattach(user.Images, load, loadInline)
		return func() {
			if x.editing != editing {
				return
			}
			p.putBack(coreapi.Message{Images: images})
			if lost > 0 {
				p.failed = locales.MessageImagesLost()
			}
		}
	})
}

// cancelEdit 不编辑了：输入栏放回开始编辑前的样子。
func (p *chatPane) cancelEdit() {
	x := &p.acts
	if x.editing == nil {
		return
	}
	saved := x.editing.saved
	x.editing = nil
	for _, f := range p.att.files {
		if f.cancel != nil {
			f.cancel()
		}
	}
	p.restore(saved)
}

// submitEdit 发出改好的消息（输入栏的 text 和附件）：剪掉被编辑的那条和之后的，再发它。现在还不能改（这一轮没结束之类）时
// 不发，返回 false，字留在输入栏。
func (a *App) submitEdit(p *chatPane, text string) bool {
	if !p.connected || !p.state.CanRewind {
		p.failed = locales.MessageWaitRound()
		return false
	}
	target := p.acts.editing.user.ID
	m := coreapi.Message{Text: text, Images: p.att.pictures(), Files: p.att.entries()}
	p.att.clear()
	p.acts.editing = nil
	p.list.ScrollToEnd()
	p.failed = ""
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Rewrite(ctx, target, coreapi.Outgoing{Text: m.Text, Images: m.Images, Files: m.Files})
	}, func(err error) {
		p.failed = locales.MessageSendFailed(err.Error())
		p.putBack(m)
	})
	return true
}

// editStrip 是输入栏上方的编辑提示条（同手机版 EditStrip，样子同排队条）：说发出后会替换什么，× 取消编辑。
func (a *App) editStrip(c *ui.Context, p *chatPane) {
	r := p.acts.editing
	if r == nil {
		return
	}
	k := widget.Of(c)
	panel(c, func() {
		ui.Row(c).Padding(0, 4, 0, 14).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Icon(c, widget.IconEdit).Size(14, 14).TextColor(k.PrimaryText)
			ui.Text(c, locales.MessageEditing()).FontSize(widget.SizeBodyS).FontWeight(widget.Medium).TextColor(k.PrimaryText)
			hint := locales.MessageEditHint()
			if r.later > 0 {
				hint = locales.MessageEditHintLater()
			}
			ui.Text(c, hint).FontSize(widget.SizeBodyS).TextColor(k.Secondary).SingleLine().Ellipsis("…").Grow(1).MinWidth(0).
				Margin(0, 0, 0, 2)
			widget.FlatButton(c, widget.IconClose, locales.MessageCancelEdit(), 32, 16).OnClick(p.cancelEdit)
		})
	})
}

// messageDialogs 是等确认的那个对话框。
func (a *App) messageDialogs(c *ui.Context, p *chatPane) {
	cf := p.acts.confirm
	if cf == nil {
		return
	}
	k := widget.Of(c)
	title, summary, button := confirmTexts(cf)
	ok := false
	widget.Dialog(c, &cf.open, func() {
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 8, widget.DialogPadding).Gap(6).
			Children(func() {
				ui.Text(c, title).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
				ui.Text(c, summary).FontSize(widget.SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).Selectable()
				if cf.kind == confirmRestore && cf.changes != nil {
					changedFiles(c, cf.changes, p.state.Cwd)
				}
			})
		switch {
		case cf.changes == nil:
		case cf.kind == confirmRestore:
			label := locales.MessageAlsoDropRound()
			if cf.later > 0 {
				label = locales.MessageAlsoDropLater()
			}
			widget.Choice(c, label, "", widget.MarkCheck, cf.drop).OnClick(func() { cf.drop = !cf.drop })
		default:
			widget.Choice(c, locales.MessageAlsoRestoreCode(), "", widget.MarkCheck, cf.code).OnClick(func() { cf.code = !cf.code })
		}
		widget.ButtonBar(c, func() {
			if cf.kind == confirmRestore && cf.asked && cf.changes == nil {
				if widget.DialogButton(c, locales.MessageOk(), widget.Recommend).Clicked() {
					cf.open = false
				}
				return
			}
			if widget.DialogButton(c, locales.WidgetCancel(), widget.Plain).Clicked() {
				cf.open = false
			}
			style := widget.Destructive
			if cf.kind == confirmRegenerate {
				style = widget.Recommend
			}
			if widget.DialogButton(c, button, style).Disabled(cf.kind == confirmRestore && cf.changes == nil).Clicked() {
				ok, cf.open = true, false
			}
		})
	})
	if ok {
		a.confirmed(cf)
	}
	if !cf.open {
		p.acts.confirm = nil
	}
}

// confirmTexts 是确认框的标题、说明和确认键（同手机版 confirmSummary）：会丢掉哪些对话，这条消息之后改过文件时说改了多少。
func confirmTexts(cf *confirm) (title, summary, button string) {
	later := cf.later
	files := ""
	if ch := cf.changes; ch != nil {
		files = locales.MessageFilesChanged(len(ch.Files), ch.Insertions, ch.Deletions)
	}
	switch cf.kind {
	case confirmRegenerate:
		title, button = locales.MessageRegenerateTitle(), locales.MessageRegenerate()
		summary = locales.MessageRegenerateSummary()
		if later > 0 {
			summary = locales.MessageRegenerateSummaryLater(later)
		}
	case confirmDrop:
		title, button = locales.MessageDropTitle(), locales.CommonDelete()
		summary = locales.MessageDropSummary()
		if later > 0 {
			summary = locales.MessageDropSummaryLater(later)
		}
	case confirmRestore:
		title, button = locales.MessageRestoreTitle(), locales.MessageRestore()
		switch {
		case cf.failed != "":
			return title, locales.MessageRestoreCheckFailed(cf.failed), button
		case !cf.asked:
			return title, locales.MessageRestoreChecking(), button
		case cf.changes == nil:
			return title, locales.MessageRestoreNothing(), button
		}
		return title, locales.MessageRestoreSummary(files), button
	}
	if files != "" {
		summary += "\n" + files
	}
	return title, summary, button
}

// changedFiles 列出还原代码会动到的文件（在工作目录里的写相对路径），多了只列前几个。
func changedFiles(c *ui.Context, ch *coreapi.CodeChanges, cwd string) {
	k := widget.Of(c)
	const most = 6
	ui.Column(c).FillWidth().Margin(6, 0, 0, 0).Padding(8, 12).Radius(12).Background(k.DialogBlock).Gap(2).Children(func() {
		for i, f := range ch.Files {
			if i == most {
				ui.Text(c, locales.MessageMoreFiles(len(ch.Files)-most)).FontSize(widget.SizeBodyXS).TextColor(k.Secondary)
				break
			}
			if cwd != "" && strings.HasPrefix(f, strings.TrimSuffix(cwd, "/")+"/") {
				f = f[len(strings.TrimSuffix(cwd, "/"))+1:]
			}
			ui.Text(c.Key(i), f).Font(widget.Mono).FontSize(widget.SizeBodyXS).TextColor(k.Text).SingleLine().Ellipsis("…").
				FillWidth()
		}
	})
}
