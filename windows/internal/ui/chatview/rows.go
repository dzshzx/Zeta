package chatview

import (
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// userMessage 画用户发的一条消息，靠右：带的图（最多四张缩略图，只有一张时大些）、附带的文件和文字气泡。
func (ch *Chat) userMessage(c *ui.Context, it items.Item) {
	ui.Column(c).FillWidth().AlignItems(ui.End).Gap(6).Padding(16, 0, 6, 0).Children(func() {
		if len(it.Images) > 0 {
			size := float32(76)
			if len(it.Images) == 1 {
				size = 148
			}
			ui.Row(c).Gap(6).Children(func() {
				for k, im := range it.Images[:min(len(it.Images), 4)] {
					ui.Box(c.Key(k)).Children(func() { ch.thumbnail(c, im, size) })
				}
			})
		}
		for _, f := range it.Files {
			fileChip(c, f)
		}
		if it.Text == "" {
			return
		}
		k := coui.Of(c)
		text := it.Text
		// 同手机版的气泡（ChatItems.kt）：淡蓝灰，三个角 22、右下角 4，字 16
		ui.Text(c, text).Selectable().FontSize(coui.SizeBodyXL).LineHeight(1.44).TextColor(k.Text).MaxWidth(bubbleWidth).
			Padding(10.5, 16).Radius(22, 22, 4, 22).Background(k.Bubble).ContextMenu(func(m *ui.Menu) {
			if m.Item("复制").Chosen() {
				c.Services().WriteClipboard(text)
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

// answerBlock 画一段回答：还在送来时逐字显现，送完、显现完以后下面有复制键。
func (ch *Chat) answerBlock(c *ui.Context, it items.Item) {
	ui.Column(c).FillWidth().Padding(4, 0, 6, 0).Children(func() {
		if it.Streaming && strings.TrimSpace(it.Text) == "" {
			typingDots(c)
			return
		}
		var md ui.Element
		if it.Streaming {
			md = markdown.Stream(c, it.Text, &ch.answer)
		} else {
			md = markdown.View(c, it.Text, &ch.answer)
		}
		md.FillWidth()
		if !it.Streaming && strings.TrimSpace(it.Text) != "" && markdown.Revealed(md) {
			copyButton(c, it.Text)
		}
	})
}

// copyButton 是回答下面的复制键：平的圆形图标按钮，30 的圆、15 的图标（同手机版 MessageActions.kt）。
func copyButton(c *ui.Context, text string) {
	b := coui.FlatButton(c, iconCopy, "复制", 30, 15).Margin(6, 0, 0, -8)
	if b.Clicked() {
		c.Services().WriteClipboard(text)
	}
}

// typingDots 是回答还没送来字时的三个跳动的点。
func typingDots(c *ui.Context) {
	t := c.Theme()
	row := ui.Row(c).Gap(4).Padding(8, 0).Label("正在回答")
	phase := row.Loop("typing", 1200*time.Millisecond, ui.Linear)
	row.Children(func() {
		for k := range 3 {
			p := phase - float32(k)*0.15
			if p < 0 {
				p++
			}
			ui.Box(c.Key(k)).Size(6, 6).Radius(3).Background(t.TextMuted).Opacity(0.3 + 0.7*triangle(p))
		}
	})
}

// triangle 把 0 到 1 的相位换成先升后降的 0 到 1，用来做呼吸一样的明暗。
func triangle(p float32) float32 {
	if p < 0.5 {
		return p * 2
	}
	return (1 - p) * 2
}

// noticeRow 画 Zeta 自己加的提示：出错的是淡红底的一块，别的是居中的一行灰字。
func noticeRow(c *ui.Context, it items.Item) {
	t := c.Theme()
	if it.Error {
		coui.Notice(c, it.Text, coui.Failure).MarginY(6)
		return
	}
	ui.Row(c).FillWidth().Justify(ui.Center).PaddingY(8).Children(func() {
		ui.Text(c, it.Text).FontSize(12.5).TextColor(t.TextMuted)
	})
}

// bubbleWidth 是用户消息的气泡最宽多少（手机上 316，桌面上宽一些）。
const bubbleWidth = 480
