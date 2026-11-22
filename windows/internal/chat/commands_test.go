package chat

import (
	"encoding/json"
	"testing"
)

func TestSlashQuery(t *testing.T) {
	for typed, want := range map[string]string{"/": "", "/Com": "com", "/compact x": "-", "x/": "-", "": "-", "/a\nb": "-"} {
		got, ok := SlashQuery(typed)
		if !ok {
			got = "-"
		}
		if got != want {
			t.Errorf("%q: %q", typed, got)
		}
	}
}

func TestSuggestOrdersPrefixFirst(t *testing.T) {
	list := ParseCommands(json.RawMessage(`[{"name":"review"},{"name":"compact","description":"压缩"},{"name":""},{"name":"__x"},{"name":"Code"}]`))
	if len(list) != 4 || list[1].Description != "压缩" {
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
	if ParseCommands(json.RawMessage(`null`)) != nil || ParseCommands(json.RawMessage(`{}`)) != nil {
		t.Error("不是数组时应为空")
	}
}
