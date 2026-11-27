package chatview

import (
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/hub/mathtex"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// userMessage 画用户发的一条消息（第 row 个条目），靠右：带的图（最多四张缩略图，只有一张时大些）、附带的文件和文字气泡。
// 缩略图画出来了的图片文件（图的 Original）不再画文件小标签。
func (ch *Chat) userMessage(c *ui.Context, it items.Item, row int) {
	shown := it.Images[:min(len(it.Images), 4)]
	ui.Column(c).FillWidth().AlignItems(ui.End).Gap(6).Padding(16, 0, 6, 0).Children(func() {
		if len(it.Images) > 0 {
			size := float32(76)
			if len(it.Images) == 1 {
				size = 148
			}
			// 窗口窄得排不下时折行，不伸出聊天区
			ui.Row(c).Gap(6).Wrap().Justify(ui.End).MaxWidthPercent(100).Children(func() {
				for k, im := range shown {
					ch.opensImage(ui.Box(c.Key(k)), it.Images, k).Children(func() { ch.thumbnail(c, im, size) })
				}
			})
		}
		for _, f := range it.Files {
			if !slices.ContainsFunc(shown, func(im items.Image) bool { return im.Original == f }) {
				fileChip(c, f)
			}
		}
		if it.Text == "" {
			return
		}
		k := widget.Of(c)
		text := it.Text
		// 同手机版的气泡（ChatItems.kt）：淡蓝灰，三个角 22、右下角 4，字 16
		bubble := widget.Surface{Shape: widget.ShapeBubble, Fill: k.Bubble, Edge: ch.editingEdge(c, it)}
		bubble.Paint(ch.bubbleText(c, it)).Selectable().FontSize(widget.SizeBodyXL).LineHeight(1.44).TextColor(k.Text).MaxWidthPercent(bubbleWidthPercent).
			Padding(10.5, 16).ContextMenu(func(m *ui.Menu) {
			widget.CopyItem(c, m, text)
			if ch.UserMenu != nil {
				ch.UserMenu(c, m, it, ch.laterThan(row))
			}
		})
	})
}

// fileChip 是消息附带的一个路径：图标加名字。写法见 Kotlin 的 AttachedPaths：「@路径」是引用，「@路径/」是文件夹，
// 别的是传上去的附件。
func fileChip(c *ui.Context, entry string) {
	t := c.Theme()
	folder := strings.HasPrefix(entry, "@") && strings.HasSuffix(entry, "/")
	path := strings.TrimPrefix(entry, "@")
	name := baseName(path)
	if name == "" {
		name = "/"
	}
	ic := iconFile
	if folder {
		ic = iconFolder
	}
	ui.Row(c).Gap(6).AlignItems(ui.Center).Padding(6, 12, 6, 10).Radius(12).MaxWidth(320).
		Background(t.Text.Alpha(0.05)).Tooltip(path).Children(func() {
		ui.Icon(c, ic).FontSize(15).TextColor(t.TextMuted)
		ui.Text(c, name).FontSize(13).SingleLine().Ellipsis("…").Shrink(1)
	})
}

// answerBlock 画一段回答（第 row 个条目）：还在送来时逐字显现，正文里引用的图画在下面（answerimages.go），
// 送完、显现完以后最下面有复制键（复制的是原文）。
func (ch *Chat) answerBlock(c *ui.Context, it items.Item, row int) {
	parts := ch.answerParts(it)
	shown := ch.shownAnswer(it)
	ui.Column(c).FillWidth().Padding(4, 0, 6, 0).Children(func() {
		if it.Streaming && strings.TrimSpace(it.Text) == "" {
			typingDots(c)
			return
		}
		var md ui.Element
		switch {
		case strings.TrimSpace(shown.Text) == "":
			md = ui.Box(c) // 只有图
		case it.Streaming:
			md = markdown.Stream(c, mathtex.Source(shown.Text), &ch.answer)
		default:
			md = markdown.View(c, mathtex.Source(ch.answerSource(c, shown)), &ch.answer)
		}
		md.FillWidth()
		ch.answerImages(c, parts)
		if !it.Streaming && strings.TrimSpace(it.Text) != "" && markdown.Revealed(md) {
			ui.Row(c).AlignItems(ui.Center).Children(func() {
				copyButton(c, it.Text)
				ch.actions(c, row)
			})
		}
	})
}

// thoughtBlock 是一段中间说明（Claude 在工具调用之间说它在做什么）：同回答一样排正文，不带复制键和回答的操作；
// 一轮的操作排挂在它下面时照样画（核心定的 round_last）。
func (ch *Chat) thoughtBlock(c *ui.Context, it items.Item, row int) {
	ui.Column(c).FillWidth().Padding(4, 0, 6, 0).Children(func() {
		var md ui.Element
		if it.Streaming {
			md = markdown.Stream(c, mathtex.Source(it.Text), &ch.answer)
		} else {
			md = markdown.View(c, mathtex.Source(ch.answerSource(c, it)), &ch.answer)
		}
		md.FillWidth()
	})
	ch.actionRow(c, row)
}

// copyButton 是回答下面的复制键：平的圆形图标按钮，30 的圆、15 的图标（同手机版 MessageActions.kt）。
func copyButton(c *ui.Context, text string) {
	b := widget.FlatButton(c, iconCopy, locales.CommonCopy(), 30, 15).Margin(6, 0, 0, -8)
	if b.Clicked() {
		c.Services().WriteClipboard(text)
	}
}

// typingDots 是回答还没送来字时的三个跳动的点（6 的圆、间隔 4），一圈 1.2 秒分 24 步，只重画这三个点（widget.Step）。
func typingDots(c *ui.Context) {
	muted := c.Theme().TextMuted
	ui.Box(c).Size(26, 6).Margin(8, 0).Label(locales.ChatAnswering()).Draw(func(p *ui.Painter, r ui.Rect) {
		phase, next := widget.Step(p.Now(), 1200*time.Millisecond, 24)
		for k := range 3 {
			at := phase - float32(k)*0.15
			if at < 0 {
				at++
			}
			p.FillPath(new(ui.Path).Circle(r.X+3+float32(k)*10, r.Y+3, 3), muted.Alpha(0.3+0.7*triangle(at)))
		}
		p.After(next)
	})
}

// triangle 把 0 到 1 的相位换成先升后降的 0 到 1，用来做呼吸一样的明暗。
func triangle(p float32) float32 {
	if p < 0.5 {
		return p * 2
	}
	return (1 - p) * 2
}

// noticeRow 画 Zeta 自己加的提示：出错的是淡红底的一块，别的是居中的灰字（hook 附加上下文这类可能有好几行）。
// 都能选中，右键「复制」复制全文。
func noticeRow(c *ui.Context, it items.Item) {
	t := c.Theme()
	said := it.Said(c.Locale())
	if it.Error {
		widget.Notice(c, said, widget.Failure).MarginY(6)
		return
	}
	ui.Row(c).FillWidth().Justify(ui.Center).PaddingY(8).Children(func() {
		ui.Text(c, said).Selectable().FontSize(12.5).TextColor(t.TextMuted).ContextMenu(func(m *ui.Menu) { widget.CopyItem(c, m, said) })
	})
}

// bubbleWidthPercent 是用户消息的气泡最宽占聊天区内容宽度的几成。手机版定的是 316dp（UserTextBubble），在手机上约是
// 内容宽度的九成多；桌面的窗口宽窄差得多，按比例算：窗口宽时气泡跟着宽，窄时也不伸出去。比手机略窄，宽窗口上一行不至于太长。
const bubbleWidthPercent = 80

// answerImages 画回答正文下面的图：每组一行（放不下折行），组和组之间、和正文之间隔开；点了看大图，左右翻的是这段回答里的全部图。
func (ch *Chat) answerImages(c *ui.Context, parts *answerParts) {
	if len(parts.groups) == 0 {
		return
	}
	all := parts.images()
	at := 0
	for g, group := range parts.groups {
		ui.Row(c.Key("images:"+strconv.Itoa(g))).Wrap().Gap(8).Margin(10, 0, 0, 0).MaxWidthPercent(100).Children(func() {
			for _, im := range group {
				if ch.waitsForTap(im) {
					ui.Box(c.Key(im.CacheKey())).Children(func() { ch.remotePrompt(c, im) })
				} else {
					ch.opensImage(ui.Box(c.Key(im.CacheKey())), all, at).Children(func() { ch.picture(c, im) })
				}
				at++
			}
		})
	}
}
