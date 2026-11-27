package hub

import (
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// 只发了图的会话，侧栏的叫法按界面语言出字；有标题或第一句是用户原话时照原文。
func TestSessionLabelSaysTheUILanguage(t *testing.T) {
	image := hostlink.Session{Preview: "[图片]", PreviewCode: "previewImage"}
	if SessionLabel(image, "en") != "[Image]" || SessionLabel(image, "zh-Hans") != "[图片]" {
		t.Fatalf("%q %q", SessionLabel(image, "en"), SessionLabel(image, "zh-Hans"))
	}
	titled := hostlink.Session{Title: "修 bug", Preview: "[图片]", PreviewCode: "previewImage"}
	said := hostlink.Session{Preview: "说说天气"}
	if SessionLabel(titled, "en") != "修 bug" || SessionLabel(said, "en") != "说说天气" {
		t.Fatalf("%q %q", SessionLabel(titled, "en"), SessionLabel(said, "en"))
	}
}
