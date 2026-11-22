package settings

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// composeDrafts 是 Compose 版（DraftRecords.encode）存的样子，带一条坏的、一条重复的、一条空的。
const composeDrafts = `{"version":1,"drafts":[` +
	`{"key":"s1","text":"还没写完😀","selection":[2,3],"images":[{"id":"6f1c-aa","type":"image/png","bytes":12}],` +
	`"files":[{"entry":"/home/zeta/.cache/zeta/files/x/a.txt","label":"a.txt"},{"entry":"@/srv/logs/"},{"entry":"相对路径"}],"at":5},` +
	`{"key":"s1","text":"重复的","at":9},{"key":"","text":"没有 key"},{"key":"s2","text":"  "},"坏的",` +
	`{"key":"new","text":"新对话","at":1}]}`

func TestDraftsReadComposeFormat(t *testing.T) {
	list := DecodeDrafts(composeDrafts)
	if len(list) != 2 {
		t.Fatalf("解出 %+v", list)
	}
	r := list[0]
	if r.Key != "s1" || r.Text != "还没写完😀" || r.Selection != [2]int{2, 3} || len(r.Images) != 1 || r.Images[0].ID != "6f1c-aa" {
		t.Fatalf("s1：%+v", r)
	}
	if len(r.Files) != 2 || r.Files[1].Label != "logs" {
		t.Fatalf("附件：%+v", r.Files)
	}
	if list[1].Key != DraftNew || list[1].Selection != [2]int{3, 3} {
		t.Fatalf("new：%+v", list[1])
	}
	again := DecodeDrafts(EncodeDrafts(list))
	if len(again) != 2 || again[0].Text != r.Text || again[0].Files[0] != r.Files[0] {
		t.Fatalf("写回再读：%+v", again)
	}
	if !strings.Contains(EncodeDrafts(list[1:]), `"images":[],"files":[]`) {
		t.Error("空的图片、附件要写成空数组，同 Kotlin")
	}
}

func TestFitDraftsKeepsNewestWithinLimits(t *testing.T) {
	var list []Draft
	for i := range MaxDrafts + 5 {
		list = append(list, Draft{Key: string(rune('a' + i)), Text: strings.Repeat("字", 1000), At: int64(i),
			Images: []DraftImage{{ID: "i", Bytes: 600 << 10}}})
	}
	list = append(list, Draft{Key: "empty", Text: " ", At: 1000})
	kept := FitDrafts(list)
	if len(kept) != MaxDrafts || kept[0].At != int64(MaxDrafts+4) {
		t.Fatalf("留下 %d 份，最新的 %d", len(kept), kept[0].At)
	}
	text, images := 0, 0
	for _, r := range kept {
		text += utf16Len(r.Text)
		images += len(r.Images)
	}
	if text != MaxDrafts*1000 || images != 27 {
		t.Fatalf("字 %d，图 %d", text, images)
	}
	if long := FitDrafts([]Draft{{Key: "x", Text: strings.Repeat("a", 25_000)}}); utf16Len(long[0].Text) != MaxDraftText {
		t.Errorf("一份最多存 %d 字：%d", MaxDraftText, utf16Len(long[0].Text))
	}
	if got := clipUTF16("a😀b", 2); got != "a" {
		t.Errorf("不切开一个字：%q", got)
	}
}

func TestDraftsSaveWritesAndPrunesImages(t *testing.T) {
	dir := t.TempDir()
	d, err := OpenDrafts(dir)
	if err != nil {
		t.Fatal(err)
	}
	stale := filepath.Join(dir, "drafts", "old")
	if err := os.MkdirAll(filepath.Dir(stale), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(stale, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	list := []Draft{{Key: "s1", Text: "字", Images: []DraftImage{{ID: "img-1", Type: "image/jpeg", Bytes: 3}, {ID: "../bad", Bytes: 1}}}}
	if _, err := d.Save(list, map[string][]byte{"img-1": []byte("abc")}); err != nil {
		t.Fatal(err)
	}
	if data, err := d.Image("img-1"); err != nil || string(data) != "abc" {
		t.Fatalf("图片：%q %v", data, err)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Error("不再用的图片文件没删")
	}
	if _, err := d.Image("../bad"); err == nil {
		t.Error("不像文件名的编号也读了")
	}
	again, _ := OpenDrafts(dir)
	if got := again.Load(); len(got) != 1 || got[0].Text != "字" {
		t.Fatalf("读回 %+v", got)
	}
}
