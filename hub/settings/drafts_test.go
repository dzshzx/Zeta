package settings

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/client/drafts"
)

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
	list := []drafts.Draft{{Key: "s1", Text: "字", Images: []drafts.Image{{ID: "img-1", Type: "image/jpeg", Bytes: 3}, {ID: "../bad", Bytes: 1}}}}
	asked := 0
	image := func(id string) []byte {
		asked++
		return map[string][]byte{"img-1": []byte("abc")}[id]
	}
	if err := d.Save(list, image); err != nil {
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
	// 已经写过的图不再要内容
	if err := d.Save(list, image); err != nil || asked != 1 {
		t.Fatalf("再存一次：要了 %d 次图片内容，%v", asked, err)
	}
	again, _ := OpenDrafts(dir)
	if got := again.Load(); len(got) != 1 || got[0].Text != "字" {
		t.Fatalf("读回 %+v", got)
	}
}
