package ui

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// TestHandoffPreviews 在设了 ZETA_PREVIEW_DIR 时把「别处运行」卡片的几种样子画成 PNG（浅色、深色）：
// 别的电脑经 SSH 开的终端里正在回答、另一台设备的 Zeta 测试版闲着、只知道在别处。
func TestHandoffPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	scenes := map[string]string{
		"handoff-terminal-busy": `"blocked":true,"occupant":{"status":"busy","waiting_for":null,"hands_over":false,"app":"terminal","device":"work-pc"}`,
		"handoff-zeta-idle":     `"blocked":true,"occupant":{"status":"idle","waiting_for":null,"hands_over":true,"app":"zeta-dev","device":"Xiaomi 17"}`,
		"handoff-unknown":       `"blocked":true,"occupant":{"status":"waiting","waiting_for":"Bash","hands_over":true}`,
	}
	for name, state := range scenes {
		for _, dark := range []bool{false, true} {
			f := newChatFixture(t)
			f.tt = ui.NewTester(f.app.View, 1280, 860)
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			f.click(t, "修登录页")
			f.push(state)
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			suffix := "light"
			if dark {
				suffix = "dark"
			}
			out, err := os.Create(filepath.Join(dir, name+"-"+suffix+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, withCaptionButtons(f.tt.Image(), dark || f.app.buttonsOverDark)); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}
