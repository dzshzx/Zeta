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

// hook 附加上下文这类提示和出错提示都能右键复制，复制的是全文：出错的那块最多显示三行，选中只选得到看得见的。
func TestNoticeCopiesFullText(t *testing.T) {
	hook := "SessionStart hook 附加上下文：\n第一行\n第二行"
	failure := "API Error: 529\n第二行\n第三行\n第四行\n第五行"
	ch := New()
	ch.SetItems(decodeItems(t, `[
		{"kind":"notice","id":"n1","text":"SessionStart hook 附加上下文：\n第一行\n第二行"},
		{"kind":"notice","id":"n2","text":"API Error: 529\n第二行\n第三行\n第四行\n第五行","error":true}]`))
	tt := newTester(ch, 900, 600, false)
	tt.Frame()
	for _, want := range []string{hook, failure} {
		tt.SetClipboard("")
		if err := tt.RightClick(want); err != nil {
			t.Fatal(err)
		}
		if err := tt.ChooseMenuItem("复制"); err != nil {
			t.Fatal(err)
		}
		if got := tt.Clipboard(); got != want {
			t.Errorf("复制了 %q，要 %q", got, want)
		}
	}
}
