package chat

import (
	"fmt"
	"strings"
	"testing"
)

func TestSlashAt(t *testing.T) {
	// | 是光标；want 是「前缀 起-止」，不提示时是 -
	for typed, want := range map[string]string{
		"/|": " 0-1", "/Com|": "com 0-4", "/compact x|": "-", "x/|": "-", "|": "-", "/a\nb|": "-",
		"hello /co|": "co 6-9", "你好 /co|": "co 3-6", "你好，/co|": "-", "hello/co|": "-",
		"hello /co| world": "co 6-9", "hello /c|o world": "c 6-9", "a\n/c|": "c 2-4", "hello /copy x|": "-", "hello |": "-",
		"/compact| foo": "compact 0-8", "/sim|已有的话 还有": "sim 0-4", "/si|mp已有的话": "si 0-5", "/a|b:c-d_e.f，后面": "a 0-11",
	} {
		caret := len([]rune(typed[:strings.Index(typed, "|")]))
		text := strings.Replace(typed, "|", "", 1)
		got := "-"
		if s, ok := SlashAt(text, caret); ok {
			got = fmt.Sprintf("%s %d-%d", s.Prefix, s.Start, s.End)
		}
		if got != want {
			t.Errorf("%q: %q，要 %q", typed, got, want)
		}
	}
	if _, ok := SlashAt("/x", 3); ok {
		t.Error("光标越界也提示了")
	}
}

func TestSlashComplete(t *testing.T) {
	for _, c := range []struct{ text, name, want string }{
		{"/co|", "compact", "/compact |"},
		{"hello /co|", "copy", "hello /copy |"},
		{"hello /co| world", "copy", "hello /copy |world"},
		{"hello /c|o world", "copy", "hello /copy |world"},
		{"你好 /c|x", "clear", "你好 /clear |"},
		{"/co|\n下一行", "copy", "/copy\n|下一行"},
		{"/simplify|已有的话，没有空格", "simplify", "/simplify |已有的话，没有空格"},
		{"/sim|已有的话 还有", "simplify", "/simplify |已有的话 还有"},
		{"前面 /si|mp已有的话", "simplify", "前面 /simplify |已有的话"},
		{"/周|报", "周报", "/周报 |"},
		{"/周|报后面的话", "周报", "/周报 |后面的话"},
		{"/周|报 后面", "周报", "/周报 |后面"},
		{"/周|末的事", "周报", "/周报 |末的事"},
	} {
		caret := len([]rune(c.text[:strings.Index(c.text, "|")]))
		text := strings.Replace(c.text, "|", "", 1)
		s, ok := SlashAt(text, caret)
		if !ok {
			t.Fatalf("%q 没认出", c.text)
		}
		out, at := s.Complete(text, c.name)
		r := []rune(out)
		if got := string(r[:at]) + "|" + string(r[at:]); got != c.want {
			t.Errorf("%q 选 %s：%q，要 %q", c.text, c.name, got, c.want)
		}
	}
}

func TestSuggestOrdersPrefixFirst(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","commands":[{"name":"review"},{"name":"compact","description":"压缩"},{"name":"Code"}]}`))
	list := conv.Take().State.Commands
	if len(list) != 3 || list[1].Description != "压缩" {
		t.Fatalf("解出 %+v", list)
	}
	var names []string
	for _, c := range Suggest(list, "c") {
		names = append(names, c.Name)
	}
	if len(names) != 2 || names[0] != "compact" || names[1] != "Code" {
		t.Fatalf("提示 %v", names)
	}
	if got := Suggest(list, "e"); len(got) != 2 || got[0].Name != "review" || got[1].Name != "Code" {
		t.Fatalf("含 e：%+v", got)
	}
	if got := Suggest(list, ""); len(got) != 3 {
		t.Fatalf("前缀是空的全列：%+v", got)
	}
}
