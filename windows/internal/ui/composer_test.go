package ui

import (
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// 输入框空着时就有三行高，随内容长高，到上限（窗口高度的三分之一）后不再长、在框里滚动；窗口高了上限跟着高。
// 内容用拉丁字母：框的行高按正文字体算，中文落到后备字体上时一行更高，那是字体的事，各机器装的中文字体也不同。
func TestComposerHeight(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	const pad = 10 // 输入框上下的内边距
	empty := f.draftHeight(t)
	line := (empty - pad) / composerMinLines // 实际排出来的一行有多高
	if line < composerFontSize || line > 2*composerFontSize {
		t.Fatalf("empty input %.1f: a line of %.1f", empty, line)
	}
	// 五行：比空着时高出两行
	f.app.pane.draft = strings.Repeat("line\n", 4) + "line"
	f.tt.Frame()
	if five := f.draftHeight(t); max(five-(empty+2*line), empty+2*line-five) > 0.5 { // 行高不是整数，允许取整的误差
		t.Errorf("five lines %.1f, empty %.1f, line %.1f", five, empty, line)
	}
	// 一百行：停在窗口高度的三分之一（差不到一行），不把对话挤没
	f.app.pane.draft = strings.Repeat("line\n", 99) + "line"
	for _, winH := range []float32{860, 1400} {
		f.tt.SetSize(1280, int(winH))
		f.tt.Frame()
		capped := f.draftHeight(t) - pad
		if capped > winH/3 || capped < winH/3-line {
			t.Errorf("window %.0f: input %.1f, want just under a third", winH, capped)
		}
	}
	// 到上限后在框里滚动：在最后一行打字，光标仍在框里看得见
	f.click(t, "消息")
	f.tt.Key(ui.Ctrl, ui.KeyEnd)
	f.tt.Type("末")
	box, _ := f.tt.Access("消息")
	if caret, ok := f.tt.TextCaret(); !ok || caret.Y < box.Bounds.Y || caret.Y+caret.H > box.Bounds.Y+box.Bounds.H {
		t.Errorf("caret %v outside the input %v", caret, box.Bounds)
	}
	// 小窗口里也不少于手机版的 6 行
	if got := composerMaxLines(300, line); got != 6 {
		t.Errorf("small window max %d", got)
	}
}

// draftHeight 是输入框的高度（DIP）。
func (f *chatFixture) draftHeight(t *testing.T) float32 {
	t.Helper()
	a, ok := f.tt.Access("消息")
	if !ok {
		t.Fatalf("no input in %q", f.tt.Texts())
	}
	return a.Bounds.H
}
