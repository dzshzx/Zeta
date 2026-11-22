package ui

import (
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
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
	// 查找：提问里两处、回答里一处（行内代码），停在提问里的第二处
	"chat-find": func(t *testing.T, f *chatFixture) {
		f.showTurn(t, false)
		f.tt.Key(ui.Cmd, ui.KeyF)
		f.tt.Type("gradle")
		f.tt.Key(0, ui.KeyEnter)
		f.tt.Key(0, ui.KeyEnter)
	},
	// 草稿：在「修登录页」里写了一半、带一张图和一个文件，切走、重开 Zeta（内存里的丢掉）再回来，从存盘的恢复
	"chat-draft": func(t *testing.T, f *chatFixture) {
		dir := t.TempDir()
		shot, doc := filepath.Join(dir, "登录页.png"), filepath.Join(dir, "设计稿.pdf")
		if err := os.WriteFile(shot, picture(320, 200, color.NRGBA{0x28, 0x78, 0xDC, 255}, color.NRGBA{0xFA, 0xD2, 0x78, 255}), 0o644); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(doc, []byte("%PDF-1.7"), 0o644); err != nil {
			t.Fatal(err)
		}
		f.devices.Paths = map[string]hostlink.PathKind{}
		f.click(t, "修登录页")
		f.pickReturns(shot, doc)
		f.attach(t, "文件")
		for _, x := range f.app.pane.att.files {
			f.devices.Paths[x.entry] = hostlink.PathFile
		}
		f.click(t, "消息")
		f.tt.Type("登录按钮在窄屏上被挤到第二行了，照设计稿改一下")
		f.newChat(t)
		f.app.drafts = nil
		f.click(t, "修登录页")
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
