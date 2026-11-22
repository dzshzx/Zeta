package ui

import (
	"context"
	"encoding/base64"
	"errors"
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// 聊天页的消息操作（同手机版 MessageActions.kt）：
//   - 用户消息右键：复制、编辑、还原代码，删除单独一组。编辑时原文和附件放回输入栏，被编辑的消息描边、之后的内容先藏起来，
//     发出后替换（conv.rewrite）；输入栏上方一条提示，× 取消编辑，输入栏放回开始编辑前的样子。
//   - 一轮最后一段回答下面：复制、重新生成、删除、分支；最后一轮还在进行时不出现，别的事在进行时（can_rewind 为假）变灰。
// 重新生成、删除、还原代码先确认；这条消息之后改过文件时（conv.code_changes），重新生成、删除可以同时还原代码，
// 还原代码可以同时删除这一轮。分支由中枢做（hub.Fork），聊天窗格换到新对话。核心没做成时照它的提示显示在输入栏上方。

// msgActions 是一个对话的消息操作状态，随聊天窗格换。
type msgActions struct {
	rounds  chat.Rounds // 这一帧列表里的各轮
	editing *edit       // 正在编辑的那一轮
	confirm *confirm
}

// edit 是正在编辑的那一轮，和开始编辑前输入栏里的（取消编辑时放回去）。
type edit struct {
	chat.Round
	saved composerSnapshot
}

// confirmKind 是等确认的是哪样操作。
type confirmKind int

const (
	confirmRegenerate confirmKind = iota
	confirmDrop
	confirmRestore
)

// confirm 是等确认的重新生成、删除或还原代码。changes 是还原代码会动到的文件（问过核心才知道）。
type confirm struct {
	kind  confirmKind
	round chat.Round
	open  bool
	// asked：核心答过了（changes 为 nil 是没有可还原的）；failed 是没问成的原因。
	asked   bool
	changes *chat.CodeChanges
	failed  string
	// code：重新生成、删除时同时还原代码；drop：还原代码时同时删除这一轮。
	code, drop bool
}

// syncMessageActions 在每一帧开头跟上列表：算各轮，把编辑中的那条交给列表；第一次时给列表接上菜单、操作排和保存图片。
func (a *App) syncMessageActions(p *chatPane) {
	x := &p.acts
	x.rounds = chat.RoundsOf(p.list.Items())
	p.list.Editing = ""
	if x.editing != nil {
		p.list.Editing = x.editing.User.ID
	}
	if p.list.Actions != nil {
		return
	}
	p.list.UserMenu = func(m *ui.Menu, it items.Item) { a.userMenu(m, p, it) }
	p.list.Actions = func(c *ui.Context, id string) { a.roundActions(c, p, id) }
	p.list.SaveImage = a.saveImage
}

// canRewind：现在能改写、删除、还原代码、分支（核心说能，连接在，也没在编辑别的）。
func (p *chatPane) canRewind() bool {
	return p.connected && p.state.CanRewind && p.acts.editing == nil
}

// userMenu 是用户消息右键菜单里「复制」后面的项：Claude Code 认得这条消息时才有。
func (a *App) userMenu(m *ui.Menu, p *chatPane, it items.Item) {
	r, ok := p.acts.rounds.ByUser(it.ID)
	if !ok || !r.Rewindable() {
		return
	}
	off := !p.canRewind()
	if m.Item("编辑").Disabled(off).Chosen() {
		a.beginEdit(p, r)
	}
	if m.Item("还原代码").Disabled(off).Chosen() {
		a.ask(p, confirmRestore, r)
	}
	m.Separator()
	if m.Item("删除").Disabled(off).Danger().Chosen() {
		a.ask(p, confirmDrop, r)
	}
}

// roundActions 是挂在条目 id 下面的那一轮的操作键：重新生成、删除、分支（同手机版 TurnActionButtons）。
func (a *App) roundActions(c *ui.Context, p *chatPane, id string) {
	rs := p.acts.rounds
	r, ok := rs.ByLast(id)
	if !ok || rs.IsLast(r) && (p.state.Busy || p.state.Rewinding) {
		return
	}
	on := p.canRewind()
	if r.Rewindable() {
		chatview.ActionButton(c, coui.IconRefresh, "重新生成", on).OnClick(func() { a.ask(p, confirmRegenerate, r) })
		chatview.ActionButton(c, coui.IconDelete, "删除这一轮", on).OnClick(func() { a.ask(p, confirmDrop, r) })
	}
	if r.Branchable() && p.state.Session != "" {
		chatview.ActionButton(c, coui.IconBranch, "分支", on).OnClick(func() {
			p.failed = ""
			a.hub.Fork(r.Last, p.fail)
		})
	}
}

// ask 弹出确认框，同时问核心还原代码会动到哪些文件。
func (a *App) ask(p *chatPane, kind confirmKind, r chat.Round) {
	cf := &confirm{kind: kind, round: r, open: true, code: true}
	p.acts.confirm = cf
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		changes, err := conv.CodeChanges(ctx, r.User.ID)
		a.post(func() {
			cf.asked, cf.changes = true, changes
			if err != nil {
				cf.failed = err.Error()
			}
		})
		return nil
	}, nil)
}

// confirmed 照确认框里选的做。
func (a *App) confirmed(p *chatPane, cf *confirm) {
	target := cf.round.User.ID
	withCode := cf.changes != nil && cf.code
	switch cf.kind {
	case confirmRestore:
		drop := cf.drop
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			return conv.RestoreCode(ctx, target, drop, nil)
		})
	case confirmDrop:
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			if withCode {
				return conv.RestoreCode(ctx, target, true, nil)
			}
			return conv.Drop(ctx, target)
		})
	case confirmRegenerate:
		// 原样重发这一轮的提问：文字、附件路径，还有图片（会话记录里的要再取回来）
		load := a.hub.ImageLoader(p.src.Backend)
		user := cf.round.User
		a.act(func(ctx context.Context, conv *chat.Conversation) error {
			m, err := resend(user, load)
			if err != nil {
				return err
			}
			if withCode {
				return conv.RestoreCode(ctx, target, false, &m)
			}
			return conv.Rewrite(ctx, target, m)
		})
	}
}

// errLostImages：消息里有图取不回来，没法原样重发。
var errLostImages = errors.New("有图片取不回来，没法原样重发")

// resend 是原样重发用户消息 it 要带的：文字、附件路径和图（会话记录里的经 load 取回）。阻塞，在后台调用。
func resend(it items.Item, load func(file, ref string) ([]byte, error)) (chat.Outgoing, error) {
	images, lost := reattach(it.Images, load)
	if lost > 0 {
		return chat.Outgoing{}, errLostImages
	}
	return chat.Outgoing{Text: it.Text, Images: images, Files: it.Files}, nil
}

// reattach 把消息里的图还原成能再发出去的附件，返回取回来的和取不回来的张数。阻塞，在后台调用。
func reattach(list []items.Image, load func(file, ref string) ([]byte, error)) ([]chat.Image, int) {
	var out []chat.Image
	lost := 0
	for _, im := range list {
		var data []byte
		var err error
		switch im.Kind {
		case items.Inline:
			data, err = base64.StdEncoding.DecodeString(im.Data)
		case items.Session:
			data, err = load(im.File, im.Ref)
		default:
			err = errors.New("不认识的图片")
		}
		kind := http.DetectContentType(data)
		if err != nil || !strings.HasPrefix(kind, "image/") {
			lost++
			continue
		}
		out = append(out, chat.Image{MediaType: kind, Data: base64.StdEncoding.EncodeToString(data)})
	}
	return out, lost
}

// beginEdit 开始编辑一条消息：输入栏里原有的收起来，换成这条的字、附件路径和图（图在后台取回）。
func (a *App) beginEdit(p *chatPane, r chat.Round) {
	if !p.canRewind() {
		return
	}
	x := &p.acts
	x.editing = &edit{Round: r, saved: p.snapshot()}
	p.restore(composerSnapshot{})
	p.putBack(chat.Message{Text: r.User.Text, Files: r.User.Files})
	if len(r.User.Images) == 0 {
		return
	}
	editing, load := x.editing, a.hub.ImageLoader(p.src.Backend)
	a.async(func() func() {
		images, lost := reattach(r.User.Images, load)
		return func() {
			if x.editing != editing {
				return
			}
			p.putBack(chat.Message{Images: images})
			if lost > 0 {
				p.failed = "有图片取不回来，没有放进输入框"
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
		p.failed = "等这一轮结束再发"
		return false
	}
	r := p.acts.editing
	m := chat.Message{Text: text, Images: p.att.pictures(), Files: p.att.entries()}
	p.att.clear()
	p.acts.editing = nil
	p.list.ScrollToEnd()
	p.failed = ""
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Rewrite(ctx, r.User.ID, chat.Outgoing{Text: m.Text, Images: m.Images, Files: m.Files})
	}, func(err error) {
		p.failed = "没发出去：" + err.Error()
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
	k := coui.Of(c)
	panel(c, func() {
		ui.Row(c).Padding(0, 4, 0, 14).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Icon(c, coui.IconEdit).Size(14, 14).TextColor(k.PrimaryText)
			ui.Text(c, "编辑消息").FontSize(coui.SizeBodyS).FontWeight(coui.Medium).TextColor(k.PrimaryText)
			hint := "发送后将替换这条消息和它的回复"
			if r.Later > 0 {
				hint = "发送后将替换这条消息及之后的内容"
			}
			ui.Text(c, hint).FontSize(coui.SizeBodyS).TextColor(k.Secondary).SingleLine().Ellipsis("…").Grow(1).MinWidth(0).
				Margin(0, 0, 0, 2)
			coui.FlatButton(c, coui.IconClose, "取消编辑", 32, 16).OnClick(p.cancelEdit)
		})
	})
}

// messageDialogs 是等确认的那个对话框。
func (a *App) messageDialogs(c *ui.Context, p *chatPane) {
	cf := p.acts.confirm
	if cf == nil {
		return
	}
	k := coui.Of(c)
	title, summary, button := confirmTexts(cf)
	ok := false
	coui.Dialog(c, &cf.open, func() {
		ui.Column(c).FillWidth().Padding(coui.DialogPadding, coui.DialogPadding, 8, coui.DialogPadding).Gap(6).AlignItems(ui.Center).
			Children(func() {
				ui.Text(c, title).FontSize(coui.SizeHeadlineS).FontWeight(600).TextColor(k.Text).TextAlign(ui.Center)
				ui.Text(c, summary).FontSize(coui.SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).TextAlign(ui.Center).Selectable()
				if cf.kind == confirmRestore && cf.changes != nil {
					changedFiles(c, cf.changes, p.state.Cwd)
				}
			})
		switch {
		case cf.changes == nil:
		case cf.kind == confirmRestore:
			label := "同时删除这一轮"
			if cf.round.Later > 0 {
				label = "同时删除这一轮及之后的"
			}
			coui.Choice(c, label, "", coui.MarkCheck, cf.drop).OnClick(func() { cf.drop = !cf.drop })
		default:
			coui.Choice(c, "同时还原代码", "", coui.MarkCheck, cf.code).OnClick(func() { cf.code = !cf.code })
		}
		coui.ButtonBar(c, func() {
			if cf.kind == confirmRestore && cf.asked && cf.changes == nil {
				if coui.DialogButton(c, "好", coui.Recommend).Grow(1).Basis(0).Clicked() {
					cf.open = false
				}
				return
			}
			if coui.DialogButton(c, "取消", coui.Plain).Grow(1).Basis(0).Clicked() {
				cf.open = false
			}
			style := coui.Destructive
			if cf.kind == confirmRegenerate {
				style = coui.Recommend
			}
			if coui.DialogButton(c, button, style).Grow(1).Basis(0).Disabled(cf.kind == confirmRestore && cf.changes == nil).Clicked() {
				ok, cf.open = true, false
			}
		})
	})
	if ok {
		a.confirmed(p, cf)
	}
	if !cf.open {
		p.acts.confirm = nil
	}
}

// confirmTexts 是确认框的标题、说明和确认键（同手机版 confirmSummary）：会丢掉哪些对话，这条消息之后改过文件时说改了多少。
func confirmTexts(cf *confirm) (title, summary, button string) {
	later := cf.round.Later
	files := ""
	if ch := cf.changes; ch != nil {
		files = "这条消息之后改过 " + strconv.Itoa(len(ch.Files)) + " 个文件（+" + strconv.Itoa(ch.Insertions) + " −" +
			strconv.Itoa(ch.Deletions) + "）。"
	}
	switch cf.kind {
	case confirmRegenerate:
		title, button = "重新生成回复？", "重新生成"
		summary = "这一轮的回复将换成新生成的。"
		if later > 0 {
			summary = "将删除之后 " + strconv.Itoa(later) + " 轮对话，并从这条消息重新生成。"
		}
	case confirmDrop:
		title, button = "删除这一轮对话？", "删除"
		summary = "这条消息和它的回复将被删除。"
		if later > 0 {
			summary = "这条消息及之后 " + strconv.Itoa(later) + " 轮对话将被删除。"
		}
	case confirmRestore:
		title, button = "还原代码？", "还原"
		switch {
		case cf.failed != "":
			return title, "没查到改过哪些文件：" + cf.failed, button
		case !cf.asked:
			return title, "正在查这条消息之后改过哪些文件…", button
		case cf.changes == nil:
			return title, "这条消息之后没有可还原的文件改动。", button
		}
		return title, "把文件改回这条消息之前的样子，对话不动。" + files, button
	}
	if files != "" {
		summary += "\n" + files
	}
	return title, summary, button
}

// changedFiles 列出还原代码会动到的文件（在工作目录里的写相对路径），多了只列前几个。
func changedFiles(c *ui.Context, ch *chat.CodeChanges, cwd string) {
	k := coui.Of(c)
	const most = 6
	ui.Column(c).FillWidth().Margin(6, 0, 0, 0).Padding(8, 12).Radius(12).Background(k.DialogBlock).Gap(2).Children(func() {
		for i, f := range ch.Files {
			if i == most {
				ui.Text(c, "还有 "+strconv.Itoa(len(ch.Files)-most)+" 个").FontSize(coui.SizeBodyXS).TextColor(k.Secondary)
				break
			}
			if cwd != "" && strings.HasPrefix(f, strings.TrimSuffix(cwd, "/")+"/") {
				f = f[len(strings.TrimSuffix(cwd, "/"))+1:]
			}
			ui.Text(c.Key(i), f).Font(coui.Mono).FontSize(coui.SizeBodyXS).TextColor(k.Text).SingleLine().Ellipsis("…").
				FillWidth()
		}
	})
}

// saveImage 是看大图的保存：系统的保存对话框选好位置，写进去，返回存到哪（取消了是空的）。在后台调用。
func (a *App) saveImage(data []byte, name string) (string, error) {
	saveAs := a.saveAs
	if saveAs == nil {
		saveAs = systemSaveAs
	}
	path, err := saveAs(name)
	if path == "" || err != nil {
		return "", err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return "", err
	}
	return path, nil
}

// systemSaveAs 弹系统的保存对话框，默认文件名 name；取消了是空的。阻塞，在后台调用。测试里换掉。
func systemSaveAs(name string) (string, error) {
	return mygo.Dialog.Save(mygo.SaveDialogOptions{Title: "保存图片", DefaultPath: name})
}

// syncWindowButtons 在看大图（黑底）时让系统按钮画浅色的字形，关上后回到跟着主题；只在变了时交代。
func (a *App) syncWindowButtons() {
	on := false
	if p := a.pane; p != nil {
		_, _, on = p.list.Viewing()
	}
	if on == a.buttonsOverDark || a.WindowButtonsOverDark == nil {
		return
	}
	a.buttonsOverDark = on
	a.WindowButtonsOverDark(on)
}
