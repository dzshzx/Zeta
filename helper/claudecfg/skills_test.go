package claudecfg

import (
	"archive/zip"
	"bytes"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type zipItem struct {
	name, body string
	mode       fs.FileMode
}

func makeZip(t *testing.T, items ...zipItem) []byte {
	t.Helper()
	var b bytes.Buffer
	w := zip.NewWriter(&b)
	for _, it := range items {
		h := &zip.FileHeader{Name: it.name, Method: zip.Deflate}
		if it.mode != 0 {
			h.SetMode(it.mode)
		}
		f, err := w.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := f.Write([]byte(it.body)); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

const skillMD = "---\nname: PDF Tools\ndescription: 处理 PDF\n---\n用法\n"

func TestImportSkillAndToggle(t *testing.T) {
	home, c := newHost(t)
	write(t, filepath.Join(home, ".claude", "settings.json"), "{\n  \"model\": \"opus\"\n}\n")
	data := makeZip(t,
		zipItem{name: "__MACOSX/pdf/._SKILL.md", body: "junk"},
		zipItem{name: "pdf/", mode: fs.ModeDir | 0o755},
		zipItem{name: "pdf/SKILL.md", body: skillMD},
		zipItem{name: "pdf/scripts/run.sh", body: "#!/bin/sh\necho hi\n", mode: 0o755},
		zipItem{name: "pdf/参考.md", body: "中文文件名"},
	)
	res, err := c.ImportSkill("pdf-tools-v2.zip", data, false)
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(home, ".claude", "skills", "pdf-tools")
	if res.Name != "pdf-tools" || res.Exists || res.Replaced || !res.Enabled {
		t.Fatalf("import %+v", res)
	}
	if read(t, filepath.Join(dir, "SKILL.md")) != skillMD || read(t, filepath.Join(dir, "参考.md")) != "中文文件名" {
		t.Error("files not unpacked")
	}
	if st, err := os.Stat(filepath.Join(dir, "scripts", "run.sh")); err != nil || st.Mode()&0o100 == 0 {
		t.Errorf("script not executable: %v %v", st, err)
	}
	if exists(filepath.Join(home, ".claude", "skills", "__MACOSX")) {
		t.Error("macOS junk installed")
	}
	// 同名的：先问，确认后替换
	res, err = c.ImportSkill("x.zip", data, false)
	if err != nil || !res.Exists || res.ExistingDescription != "处理 PDF" {
		t.Fatalf("second import %+v %v", res, err)
	}
	if res, err = c.ImportSkill("x.zip", data, true); err != nil || !res.Replaced {
		t.Fatalf("replace %+v %v", res, err)
	}

	// 账号同步的只列出来；文件夹里没有 SKILL.md 的标成无效
	write(t, filepath.Join(home, ".claude", "skills", "synced", "acct", "manifest.json"),
		`{"skills":[{"name":"docx","description":"Word 文档"},{"name":"pdf-tools","description":"同名"}]}`)
	if err := os.MkdirAll(filepath.Join(home, ".claude", "skills", "empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	list := c.Skills()
	if len(list.User) != 2 || list.User[0].Name != "empty" || list.User[0].Valid || list.User[1].Title != "PDF Tools" {
		t.Fatalf("user skills %+v", list.User)
	}
	if len(list.Synced) != 2 || list.Synced[0].Name != "docx" || !list.Synced[0].Enabled {
		t.Fatalf("synced %+v", list.Synced)
	}

	// 停用写 skillOverrides，启用去掉（按 SKILL.md 里的 name 写的也去掉）
	settings := filepath.Join(home, ".claude", "settings.json")
	if err := c.SetSkillEnabled(list.User[1], false); err != nil {
		t.Fatal(err)
	}
	if got := read(t, settings); got != "{\n  \"model\": \"opus\",\n  \"skillOverrides\": {\n    \"pdf-tools\": \"off\"\n  }\n}\n" {
		t.Errorf("disabled settings %q", got)
	}
	if list = c.Skills(); list.User[1].Enabled {
		t.Error("still enabled")
	}
	write(t, settings, `{"skillOverrides":{"PDF Tools":"off","pdf-tools":"off","other":"name-only"}}`)
	if err := c.SetSkillEnabled(list.User[1], true); err != nil {
		t.Fatal(err)
	}
	if got := read(t, settings); got != "{\n  \"skillOverrides\": {\n    \"other\": \"name-only\"\n  }\n}\n" {
		t.Errorf("enabled settings %q", got)
	}

	// 删掉文件夹，顺带清掉它的 skillOverrides 项
	write(t, settings, `{"skillOverrides":{"pdf-tools":"off"}}`)
	if err := c.DeleteSkill("pdf-tools"); err != nil {
		t.Fatal(err)
	}
	if exists(dir) || strings.Contains(read(t, settings), "pdf-tools") {
		t.Error("skill not deleted")
	}
	if err := c.DeleteSkill("pdf-tools"); err == nil {
		t.Error("deleting a missing skill succeeded")
	}
	for _, bad := range []string{"../x", ".hidden", "synced", ""} {
		if err := c.DeleteSkill(bad); err == nil {
			t.Errorf("delete %q accepted", bad)
		}
	}
}

func TestSkillZipChecks(t *testing.T) {
	cases := map[string][]byte{
		"没有 SKILL.md":     makeZip(t, zipItem{name: "a/readme.md", body: "x"}),
		"不安全的路径":          makeZip(t, zipItem{name: "SKILL.md", body: skillMD}, zipItem{name: "../evil", body: "x"}),
		"符号链接":            makeZip(t, zipItem{name: "SKILL.md", body: skillMD}, zipItem{name: "link", body: "/etc/passwd", mode: fs.ModeSymlink | 0o777}),
		"缺少带 description": makeZip(t, zipItem{name: "SKILL.md", body: "# 没有前言\n"}),
		"不是一个有效的 ZIP":     []byte("not a zip"),
	}
	for want, data := range cases {
		if _, err := openSkillZip(data, "s.zip"); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: got %v", want, err)
		}
	}
	// 名字依次取 name、文件夹名、ZIP 文件名
	pkg, err := openSkillZip(makeZip(t, zipItem{name: "SKILL.md", body: "---\ndescription: d\n---\n"}), "My Skill.zip")
	if err != nil || pkg.name != "my-skill" {
		t.Errorf("name from file %+v %v", pkg, err)
	}
	// 声明的大小和实际不符（压缩炸弹）在送出去之前就发现
	data := makeZip(t, zipItem{name: "SKILL.md", body: skillMD}, zipItem{name: "big", body: strings.Repeat("x", 4096)})
	i := bytes.LastIndex(data, []byte("big"))
	cd := bytes.LastIndex(data[:i], []byte("PK\x01\x02"))
	data[cd+24] = 10 // 中央目录里的解压后大小改成 10
	if _, err := openSkillZip(data, "s.zip"); err == nil || !strings.Contains(err.Error(), "损坏") {
		t.Errorf("size mismatch: %v", err)
	}
}
