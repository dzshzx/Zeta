package chatview

import "testing"

// 核心给了种类码的提示按界面语言出字，换语言跟着换；没有码的（旧核心、Claude Code 的原文）照 text 画。
func TestNoticeSaysTheUILanguage(t *testing.T) {
	ch := New()
	ch.SetItems(decodeItems(t, `[
		{"kind":"notice","id":"n1","text":"已停止","code":"noticeStopped"},
		{"kind":"notice","id":"n2","text":"API Error: 529","error":true}]`))
	tt := newTester(ch, 900, 600, false)
	tt.SetLocale("en")
	tt.Frame()
	for _, s := range []string{"Stopped", "API Error: 529"} {
		if !tt.HasText(s) {
			t.Fatalf("英文界面没有 %q：%q", s, tt.Texts())
		}
	}
	tt.SetLocale("zh-Hans")
	tt.Frame()
	if !tt.HasText("已停止") || tt.HasText("Stopped") {
		t.Fatalf("换回中文后：%q", tt.Texts())
	}
}
