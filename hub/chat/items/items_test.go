package items

import (
	"os"
	"path/filepath"
	"testing"
)

// fixtures 是 helper 的归约器对照用例产出的条目（helper/testdata/transcript），和 serve 推来的同一种 JSON。
func fixtures(t *testing.T) map[string][]Item {
	t.Helper()
	// 经 hub/testdata 里指向 helper/ 的符号链接读：模块目录外的路径 go test 的缓存不认
	paths, err := filepath.Glob("../../testdata/helper/testdata/transcript/*.items.json")
	if err != nil {
		t.Fatal(err)
	}
	if len(paths) == 0 {
		t.Fatal("找不到 helper/testdata/transcript/*.items.json")
	}
	out := map[string][]Item{}
	for _, p := range paths {
		data, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		list, err := Decode(data)
		if err != nil {
			t.Fatalf("%s: %v", p, err)
		}
		out[filepath.Base(p)] = list
	}
	return out
}

func TestDecodeFixtures(t *testing.T) {
	all := fixtures(t)
	history := all["history.items.json"]
	if len(history) == 0 {
		t.Fatal("history.items.json 是空的")
	}
	user := history[0]
	if user.Kind != User || user.Text != "看看这些文件" || len(user.Files) != 3 || user.UUID != "uuid-1" {
		t.Errorf("第一条用户消息：%+v", user)
	}
	read := stepsOf(history, "Read")[0]
	if read.Str("file_path") != "/work/x.png" || read.Status != ToolSuccess || read.Result != "读到图片" {
		t.Errorf("Read 那一步：%+v", read)
	}
	if len(read.Images) != 2 || read.Images[0].Kind != Session || read.Images[1].Kind != Inline || read.Images[1].Data != "AAAA" {
		t.Errorf("Read 的图：%+v", read.Images)
	}
	if read.Patch != nil || read.Running() {
		t.Errorf("Read 不该有改动、不该在跑：%+v", read)
	}
	// 有改动的那几步：行号对上
	var patched []Step
	for _, list := range all {
		for _, s := range append(stepsOf(list, "Edit"), stepsOf(list, "Write")...) {
			if s.Patch != nil && len(s.Patch.Hunks) > 0 {
				patched = append(patched, s)
			}
		}
	}
	if len(patched) == 0 {
		t.Error("用例里没有带改动的工具调用")
	}
	for _, s := range patched {
		for _, l := range s.Patch.Hunks[0].Lines {
			if l.Kind == Added && l.New == 0 || l.Kind == Removed && l.Old == 0 {
				t.Errorf("%s 的改动行缺行号：%+v", s.Name, l)
			}
		}
	}
}

// stepsOf 是列表里某个工具的各次调用。
func stepsOf(list []Item, name string) []Step {
	var out []Step
	for _, it := range list {
		for _, s := range it.Steps {
			if s.Name == name {
				out = append(out, s)
			}
		}
	}
	return out
}

// 字段省略（omitempty）或写成 null 时按零值。
func TestDecodeMissingFields(t *testing.T) {
	list, err := Decode([]byte(`[
		{"kind":"user","id":"u"},
		{"kind":"answer","id":"a","text":"好"},
		{"kind":"work","id":"w","steps":[{"kind":"thinking","id":"t","seconds":0},{"kind":"tool","id":"x","name":"Bash","input":null,"result":null,"status":"running"}]},
		{"kind":"notice","id":"n","text":"已停止","error":null}
	]`))
	if err != nil {
		t.Fatal(err)
	}
	if list[0].Text != "" || list[0].Images != nil || list[0].UUID != "" {
		t.Errorf("用户消息：%+v", list[0])
	}
	if list[1].Streaming {
		t.Error("没写 streaming 的回答当成了流式")
	}
	think := list[2].Steps[0]
	if think.Seconds == nil || *think.Seconds != 0 || think.Running() {
		t.Errorf("思考：%+v", think)
	}
	tool := list[2].Steps[1]
	if tool.Input != nil || tool.Result != "" || !tool.Running() || !list[2].Running() {
		t.Errorf("工具：%+v", tool)
	}
	if list[3].Error {
		t.Error("error 为 null 的提示当成了出错")
	}
}

func TestDecodeError(t *testing.T) {
	if _, err := Decode([]byte(`{"kind":"user"}`)); err == nil {
		t.Error("不是数组也解码成功了")
	}
}

func TestSplice(t *testing.T) {
	list := []Item{{ID: "a"}, {ID: "b"}, {ID: "c"}}
	got, err := Splice(list, 1, 1, []Item{{ID: "x"}, {ID: "y"}})
	if err != nil {
		t.Fatal(err)
	}
	if ids(got) != "axyc" || ids(list) != "abc" {
		t.Errorf("替换后 %s，原列表 %s", ids(got), ids(list))
	}
	got, err = Splice(list, 3, 0, []Item{{ID: "d"}})
	if err != nil || ids(got) != "abcd" {
		t.Errorf("追加：%s %v", ids(got), err)
	}
	got, err = Splice(list, 0, 3, nil)
	if err != nil || ids(got) != "" {
		t.Errorf("清空：%s %v", ids(got), err)
	}
	for _, c := range [][2]int{{-1, 0}, {2, 2}, {4, 0}, {0, -1}} {
		if _, err := Splice(list, c[0], c[1], nil); err == nil {
			t.Errorf("越界的替换 %v 没报错", c)
		}
	}
}

func ids(list []Item) string {
	s := ""
	for _, it := range list {
		s += it.ID
	}
	return s
}

func TestImageCacheKey(t *testing.T) {
	a := Image{Kind: Inline, Key: "k"}
	b := Image{Kind: Session, File: "f", Ref: "1.0"}
	if a.CacheKey() == b.CacheKey() || b.CacheKey() != (Image{Kind: Session, File: "f", Ref: "1.0"}).CacheKey() {
		t.Errorf("缓存键：%q %q", a.CacheKey(), b.CacheKey())
	}
	c, d := Image{Kind: Linked, URL: "/a.png"}, Image{Kind: Linked, URL: "https://h/a.png"}
	if c.CacheKey() == d.CacheKey() || c.CacheKey() == a.CacheKey() || c.Remote() || !d.Remote() || b.Remote() {
		t.Errorf("回答里写的图：%q %q，网络图 %v %v", c.CacheKey(), d.CacheKey(), c.Remote(), d.Remote())
	}
}

// 用户条目上核心算好的这一轮能做什么照原样解出来。
func TestRounds(t *testing.T) {
	list, err := Decode([]byte(`[{"kind":"notice","id":"n0"},
		{"kind":"user","id":"u1","uuid":"x1","rewindable":true,"round_last":"a1"},{"kind":"work","id":"w1"},{"kind":"answer","id":"a1"},
		{"kind":"user","id":"u2","forkable":true,"round_last":"e2"},{"kind":"notice","id":"e2","error":true},
		{"kind":"user","id":"u3","uuid":"x3","rewindable":true,"forkable":true}]`))
	if err != nil {
		t.Fatal(err)
	}
	if u := list[1]; !u.Rewindable || u.Forkable || u.RoundLast != "a1" {
		t.Fatalf("第一轮：%+v", u)
	}
	if u := list[4]; u.Rewindable || !u.Forkable || u.RoundLast != "e2" {
		t.Fatalf("第二轮：%+v", u)
	}
}
