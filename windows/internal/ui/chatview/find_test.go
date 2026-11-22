package chatview

import (
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

func TestOccurrencesIgnoreCase(t *testing.T) {
	got := occurrences("Gradle gradlew GRADLE 构建 gradle", "gradle")
	if len(got) != 4 || got[1] != [2]int{7, 13} || got[3][0] != len("Gradle gradlew GRADLE 构建 ") {
		t.Fatalf("%v", got)
	}
	if got := occurrences("aaaa", "aa"); len(got) != 2 {
		t.Fatalf("互不重叠：%v", got)
	}
	// 小写后字节数会变的字（İ）走逐个比的那条路
	if got := occurrences("İx ix", "ix"); len(got) != 1 || got[0] != [2]int{len("İx "), len("İx ix")} {
		t.Fatalf("İ：%v", got)
	}
}

func TestMarkdownSegmentsAreTheShownText(t *testing.T) {
	src := "# 标题\n\n改成 **粗体** 和 [链接](https://example.com) 再加 `tools/gradle`\n\n```bash\ngradle build\n```\n\n| 列 |\n|---|\n| 格子 |\n"
	var texts []string
	for _, s := range markdownSegments(src) {
		texts = append(texts, s.text)
	}
	want := []string{"标题", "改成 粗体 和 链接 再加 tools/gradle", "列", "格子"}
	if len(texts) != len(want) {
		t.Fatalf("段：%q", texts)
	}
	for i := range want {
		if texts[i] != want[i] {
			t.Errorf("第 %d 段 %q，要 %q", i, texts[i], want[i])
		}
	}
}

func TestAnswerSourceMarksHits(t *testing.T) {
	ch := New()
	answer := items.Item{Kind: items.Answer, ID: "a1", Text: "改成 **粗体** 和 `tools/gradle`，粗体就好"}
	ch.SetItems([]items.Item{{Kind: items.User, ID: "u1", Text: "粗体还是斜体"}, answer})
	ch.SetFind(" 粗体 ")
	index, count := ch.FindCount()
	if count != 3 || index != 2 {
		t.Fatalf("第 %d 个，共 %d 个", index, count)
	}
	tt := ui.NewTester(func(c *ui.Context) {
		got := ch.answerSource(c, answer)
		want := "改成 **<mark>粗体</mark>** 和 `tools/gradle`，<mark><u>粗体</u></mark>就好"
		if got != want {
			t.Errorf("标出的原文：\n%s\n要\n%s", got, want)
		}
	}, 400, 300)
	tt.Frame()
	// 行内代码整个包起来；查找词变长时停在原来那处后面最近的一处
	ch.SetFind("gradle")
	if index, count := ch.FindCount(); count != 1 || index != 0 {
		t.Fatalf("gradle：%d/%d", index, count)
	}
	tt = ui.NewTester(func(c *ui.Context) {
		if got := ch.answerSource(c, answer); got != "改成 **粗体** 和 <mark><u>`tools/gradle`</u></mark>，粗体就好" {
			t.Errorf("代码：%s", got)
		}
	}, 400, 300)
	tt.Frame()
	ch.SetFind("")
	if index, count := ch.FindCount(); count != 0 || index != -1 {
		t.Fatalf("停止后：%d/%d", index, count)
	}
}

func TestReselectKeepsPlace(t *testing.T) {
	order := map[string]int{"u1": 0, "a1": 1, "u2": 2}
	before := Match{Item: "a1", Block: 1, Start: 6, End: 9}
	matches := []Match{{Item: "u1", Start: 0, End: 4}, {Item: "a1", Block: 1, Start: 2, End: 6}, {Item: "a1", Block: 3, Start: 0, End: 4}, {Item: "u2", End: 4}}
	if got := reselect(&before, matches, order); got != 2 {
		t.Errorf("停在后面最近的一处：%d", got)
	}
	if got := reselect(nil, matches, order); got != 3 {
		t.Errorf("没停过的从最后一处开始：%d", got)
	}
	if got := reselect(&matches[1], matches, order); got != 1 {
		t.Errorf("还在的不动：%d", got)
	}
}
