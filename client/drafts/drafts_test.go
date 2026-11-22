package drafts

import (
	"strings"
	"testing"
)

// composeDrafts 是 Compose 版（DraftRecords.encode）存的样子，带一条坏的、一条重复的、一条空的。
const composeDrafts = `{"version":1,"drafts":[` +
	`{"key":"s1","text":"还没写完😀","selection":[2,3],"images":[{"id":"6f1c-aa","type":"image/png","bytes":12}],` +
	`"files":[{"entry":"/home/zeta/.cache/zeta/files/x/a.txt","label":"a.txt"},{"entry":"@/srv/logs/"},{"entry":"相对路径"}],"at":5},` +
	`{"key":"s1","text":"重复的","at":9},{"key":"","text":"没有 key"},{"key":"s2","text":"  "},"坏的",` +
	`{"key":"new","text":"新对话","at":1}]}`

func TestReadComposeFormat(t *testing.T) {
	list := Decode(composeDrafts)
	if len(list) != 2 {
		t.Fatalf("解出 %+v", list)
	}
	r := list[0]
	if r.Key != "s1" || r.Text != "还没写完😀" || r.Selection != [2]int{2, 3} || len(r.Images) != 1 || r.Images[0].ID != "6f1c-aa" {
		t.Fatalf("s1：%+v", r)
	}
	if len(r.Files) != 2 || r.Files[0].Label != "a.txt" || r.Files[1] != (File{Entry: "@/srv/logs/"}) {
		t.Fatalf("附件：%+v", r.Files)
	}
	if list[1].Key != NewKey || list[1].Selection != [2]int{3, 3} {
		t.Fatalf("new：%+v", list[1])
	}
	again := Decode(Encode(list))
	if len(again) != 2 || again[0].Text != r.Text || again[0].Files[0] != r.Files[0] {
		t.Fatalf("写回再读：%+v", again)
	}
	if !strings.Contains(Encode(list[1:]), `"images":[],"files":[]`) {
		t.Error("空的图片、附件要写成空数组，同 Kotlin")
	}
}

func TestFitKeepsNewestWithinLimits(t *testing.T) {
	var list []Draft
	for i := range MaxCount + 5 {
		list = append(list, Draft{Key: string(rune('a' + i)), Text: strings.Repeat("字", 1000), At: int64(i),
			Images: []Image{{ID: "i", Bytes: 600 << 10}}})
	}
	list = append(list, Draft{Key: "empty", Text: " ", At: 1000})
	kept := Fit(list)
	if len(kept) != MaxCount || kept[0].At != int64(MaxCount+4) {
		t.Fatalf("留下 %d 份，最新的 %d", len(kept), kept[0].At)
	}
	text, images := 0, 0
	for _, r := range kept {
		text += UTF16Len(r.Text)
		images += len(r.Images)
	}
	if text != MaxCount*1000 || images != 27 {
		t.Fatalf("字 %d，图 %d", text, images)
	}
	if long := Fit([]Draft{{Key: "x", Text: strings.Repeat("a", 25_000)}}); UTF16Len(long[0].Text) != MaxText {
		t.Errorf("一份最多存 %d 字：%d", MaxText, UTF16Len(long[0].Text))
	}
	if got := clipUTF16("a😀b", 2); got != "a" {
		t.Errorf("不切开一个字：%q", got)
	}
	if got := UTF16Len("a😀字"); got != 4 {
		t.Errorf("UTF-16 长度：%d", got)
	}
}
