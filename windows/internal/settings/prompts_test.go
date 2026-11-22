package settings

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestPromptsFileFormat(t *testing.T) {
	dir := t.TempDir()
	// 设置文件里的 savedPrompts 是字符串类型的 JSON 数组（一条坏的、一条重复的 id 跳过）
	file := `{"savedPrompts":{"t":"s","v":` +
		`"[{\"id\":\"a\",\"name\":\"\",\"text\":\"看看日志\"},{\"id\":\"b\",\"name\":\"审查\",\"text\":\"审查这次改动\"},` +
		`{\"id\":\"c\",\"text\":\"  \"},{\"id\":\"a\",\"text\":\"重复\"}]"}}`
	if err := os.WriteFile(filepath.Join(dir, "zeta.json"), []byte(file), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	want := []SavedPrompt{{ID: "a", Text: "看看日志"}, {ID: "b", Name: "审查", Text: "审查这次改动"}}
	if got := s.Prompts(); !reflect.DeepEqual(got, want) {
		t.Fatalf("Prompts = %+v, want %+v", got, want)
	}
	if err := s.SetPrompts(AddPrompt(s.Prompts(), "n", "", "新的")); err != nil {
		t.Fatal(err)
	}
	again, _ := Load(dir)
	if got := again.Prompts(); len(got) != 3 || got[0].ID != "n" {
		t.Errorf("存了再读 = %+v", got)
	}
	if v, ok := again.store.String(keyPrompts); !ok || !strings.HasPrefix(v, `[{"id":"n"`) {
		t.Errorf("savedPrompts 应存成字符串类型的 JSON 数组：%q", v)
	}
}

func TestPromptOperations(t *testing.T) {
	list := AddPrompt(nil, "1", "看看日志", "看看日志")
	if list[0].Name != "" {
		t.Errorf("名字和默认名一样时应存成空的：%+v", list[0])
	}
	list = AddPrompt(list, "2", " 审查 ", "审查这次改动")
	list = AddPrompt(list, "3", "", "第三条")
	ids := func(l []SavedPrompt) string {
		var s []string
		for _, p := range l {
			s = append(s, p.ID)
		}
		return strings.Join(s, "")
	}
	if ids(list) != "321" {
		t.Fatalf("新加的放最前面：%s", ids(list))
	}
	if got := ids(MovePrompt(list, "3", 1)); got != "231" {
		t.Errorf("下移 = %s", got)
	}
	if got := ids(MovePrompt(list, "3", -1)); got != "321" {
		t.Errorf("第一条上移不动 = %s", got)
	}
	if got := ids(RemovePrompt(list, "2")); got != "31" {
		t.Errorf("删除 = %s", got)
	}
	list = UpdatePrompt(list, "2", "", "改过的内容")
	if list[1].Text != "改过的内容" || list[1].DisplayName() != "改过的内容" {
		t.Errorf("编辑 = %+v", list[1])
	}
	if got := DefaultPromptName("\n  先  读一遍   README，再列出所有要改的地方\n第二行"); got != "先 读一遍 README…" {
		t.Errorf("DefaultPromptName = %q", got)
	}
}

func TestPromptProblem(t *testing.T) {
	full := make([]SavedPrompt, MaxPrompts)
	for _, c := range []struct {
		list             []SavedPrompt
		name, text, edit string
		want             string
	}{
		{nil, "", "  ", "", "内容不能为空"},
		{nil, strings.Repeat("名", 41), "x", "", "名字太长了（最多 40 个字）"},
		{nil, "", strings.Repeat("字", MaxPromptText+1), "", "内容太长了（最多 20000 个字）"},
		{full, "", "x", "", "最多存 100 条，先删掉一些"},
		{full, "", "x", "id", ""},
	} {
		if got := PromptProblem(c.list, c.name, c.text, c.edit); got != c.want {
			t.Errorf("PromptProblem(%q, %d 字) = %q, want %q", c.name, len(c.text), got, c.want)
		}
	}
}
