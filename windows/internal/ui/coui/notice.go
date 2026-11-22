package coui

import "github.com/egoist/mygo/ui"

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

// Notice 是一条提示条：14 的圆角、内边距 14/10，字 13，最多三行。
func Notice(c *ui.Context, text string, tone Tone) ui.Element {
	k := Of(c)
	ink, fill := k.Secondary, k.Tint
	switch tone {
	case Caution:
		ink, fill = k.Warning, k.Warning.Alpha(0.1)
	case Failure:
		ink, fill = k.Error, k.Error.Alpha(0.08)
	}
	t := ui.Text(c, text).FontSize(SizeBodyS).LineHeight(1.45).TextColor(ink).MaxLines(3).FillWidth().Padding(10, 14).Selectable()
	return Surface{Shape: Rounded(14, 2.5), Fill: fill}.Paint(t)
}
