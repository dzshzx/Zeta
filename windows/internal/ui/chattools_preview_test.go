package ui

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// chatToolScenes 是对话内查找、草稿、斜杠命令的预览场景（TestChatToolPreviews 画）。
var chatToolScenes = map[string]func(t *testing.T, f *chatFixture){
	"chat-slash": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.push(slashCommands)
		f.click(t, "消息")
		f.tt.Type("/c")
		f.tt.Key(0, ui.KeyDown)
	},
}

// TestChatToolPreviews 在设了 ZETA_PREVIEW_DIR 时把 chatToolScenes 画成 PNG（浅色、深色），同 TestPreviews。
func TestChatToolPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	for name, setup := range chatToolScenes {
		for _, dark := range []bool{false, true} {
			f := newChatFixture(t)
			setup(t, f)
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			suffix := map[bool]string{false: "light", true: "dark"}[dark]
			out, err := os.Create(filepath.Join(dir, name+"-"+suffix+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, withCaptionButtons(f.tt.Image(), dark)); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}
