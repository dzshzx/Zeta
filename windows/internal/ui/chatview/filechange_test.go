package chatview

import (
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/chat/items"
)

func step(t *testing.T, js string) items.Step {
	t.Helper()
	list := decodeItems(t, `[{"kind":"work","id":"w","steps":[`+js+`]}]`)
	return list[0].Steps[0]
}

// lineKinds 把各段对比写成一串记号，方便比较：+ 加、- 删、空格没变，段与段之间是 |。
func lineKinds(hunks []items.Hunk) string {
	var b strings.Builder
	for k, h := range hunks {
		if k > 0 {
			b.WriteByte('|')
		}
		for _, l := range h.Lines {
			b.WriteByte(map[items.LineKind]byte{items.Added: '+', items.Removed: '-', items.Context: ' '}[l.Kind])
		}
	}
	return b.String()
}

func TestChangeOfEditStrings(t *testing.T) {
	s := step(t, `{"kind":"tool","id":"e","name":"Edit","status":"success","input":{"file_path":"/w/a.go",
		"old_string":"a\nb\nc","new_string":"a\nB\nc","replace_all":true}}`)
	f := changeOf(s)
	if f == nil || f.numbered || f.kind != editChange || !f.replaceAll || f.label() != "全部替换" {
		t.Fatalf("Edit：%+v", f)
	}
	if got := lineKinds(f.hunks); got != " -+ " {
		t.Errorf("对比 %q", got)
	}
	added, removed, total := f.counts()
	if added != 1 || removed != 1 || total != 4 {
		t.Errorf("增删 %d %d 共 %d", added, removed, total)
	}
	// 行号相对这两段文字
	l := f.hunks[0].Lines
	if l[1].Old != 2 || l[2].New != 2 || l[3].Old != 3 || l[3].New != 3 {
		t.Errorf("行号：%+v", l)
	}
}

func TestChangeOfPatch(t *testing.T) {
	s := step(t, `{"kind":"tool","id":"e","name":"Edit","status":"success","input":{"file_path":"/w/a.go","old_string":"x","new_string":"y"},
		"patch":{"created":false,"hunks":[{"lines":[{"kind":"context","text":"a","old":10,"new":10},{"kind":"removed","text":"x","old":11},{"kind":"added","text":"y","new":11}]}]}}`)
	f := changeOf(s)
	if f == nil || !f.numbered || f.kind != editChange || f.label() != "" {
		t.Fatalf("带 patch 的 Edit：%+v", f)
	}
	p := toPatch(f.hunks)
	h := p.Hunks[0]
	if h.OldStart != 10 || h.OldLines != 2 || h.NewStart != 10 || h.NewLines != 2 || !slices.Equal(h.Lines, []string{" a", "-x", "+y"}) {
		t.Errorf("补丁：%+v", h)
	}
	if startsAtTop(f.hunks[0]) {
		t.Error("从第 10 行起的一段当成了从头起")
	}
	// Write 覆盖已有文件
	w := step(t, `{"kind":"tool","id":"w","name":"Write","status":"success","input":{"file_path":"/w/a.go","content":"y\n"},
		"patch":{"created":false,"hunks":[{"lines":[{"kind":"removed","text":"x","old":1},{"kind":"added","text":"y","new":1}]}]}}`)
	if f := changeOf(w); f == nil || f.kind != overwriteChange || f.label() != "整份重写" {
		t.Errorf("覆盖：%+v", f)
	}
}

func TestChangeOfWrite(t *testing.T) {
	s := step(t, `{"kind":"tool","id":"w","name":"Write","status":"running","input":{"file_path":"/w/new.txt","content":"一\n二\n"}}`)
	f := changeOf(s)
	if f == nil || f.kind != createChange || !f.numbered || lineKinds(f.hunks) != "++" || f.hunks[0].Lines[1].New != 2 {
		t.Fatalf("新建：%+v", f)
	}
	if !startsAtTop(f.hunks[0]) || sideText(f.hunks[0], items.Removed) != "一\n二\n" || sideText(f.hunks[0], items.Added) != "" {
		t.Error("新建文件的两边文字不对")
	}
	empty := step(t, `{"kind":"tool","id":"w","name":"Write","status":"success","input":{"file_path":"/w/e.txt","content":""},"patch":{"created":true,"hunks":[]}}`)
	if f := changeOf(empty); f == nil || len(f.hunks) != 0 {
		t.Errorf("空文件：%+v", f)
	}
}

func TestChangeOfAgyEditAndWrite(t *testing.T) {
	ed := step(t, `{"kind":"tool","id":"e1","name":"edit_file","status":"success","input":{"Path":"/w/main.go","Target":"old\nline","Replacement":"new\nline"}}`)
	if f := changeOf(ed); f == nil || f.path != "/w/main.go" || f.kind != editChange || lineKinds(f.hunks) != "-+ " {
		t.Fatalf("edit_file：%+v", f)
	}
	wr := step(t, `{"kind":"tool","id":"w1","name":"write_file","status":"running","input":{"Path":"/w/add.go","Content":"a\nb\n"}}`)
	if f := changeOf(wr); f == nil || f.path != "/w/add.go" || f.kind != createChange || lineKinds(f.hunks) != "++" {
		t.Fatalf("write_file：%+v", f)
	}
}

func TestChangeOfMultiEditAndTruncated(t *testing.T) {
	js := `{"kind":"tool","id":"m","name":"MultiEdit","status":"success","clipped":true,"input":{"file_path":"/w/a.go","edits":[
		{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d\n…"}]}}`
	f := changeOf(step(t, js))
	if f == nil || len(f.hunks) != 2 || !f.truncated || lineKinds(f.hunks) != "-+|-+" {
		t.Fatalf("MultiEdit：%+v %q", f, lineKinds(f.hunks))
	}
	if f.hunks[1].Lines[1].Text != "d" {
		t.Errorf("截断记号没去掉：%q", f.hunks[1].Lines[1].Text)
	}
	// 核心没标 clipped 的照原样画：正文自己以 … 结尾不算截断
	whole := changeOf(step(t, strings.Replace(js, `"clipped":true,`, "", 1)))
	if whole == nil || whole.truncated || len(whole.hunks[1].Lines) != 3 {
		t.Fatalf("没截断的：%+v", whole)
	}
}

func TestChangeOfNotDrawn(t *testing.T) {
	for name, js := range map[string]string{
		"失败的":   `{"kind":"tool","id":"e","name":"Edit","status":"failed","input":{"file_path":"/a","old_string":"x","new_string":"y"},"result":"找不到"}`,
		"参数还没到": `{"kind":"tool","id":"e","name":"Edit","status":"running"}`,
		"只到一半":  `{"kind":"tool","id":"e","name":"Edit","status":"running","input":{"file_path":"/a","old_string":"x"}}`,
		"不改文件的": `{"kind":"tool","id":"r","name":"Read","status":"success","input":{"file_path":"/a"}}`,
		"没有改动的": `{"kind":"tool","id":"m","name":"MultiEdit","status":"success","input":{"file_path":"/a","edits":[]}}`,
	} {
		if f := changeOf(step(t, js)); f != nil {
			t.Errorf("%s 也画成了对比：%+v", name, f)
		}
	}
}

func TestGroupLines(t *testing.T) {
	// 两处改动相隔 7 行（超过 2×3）：分成两段；相隔 6 行：并成一段
	build := func(gap int) []items.Line {
		var lines []items.Line
		add := func(kind items.LineKind, n int) {
			for range n {
				lines = append(lines, items.Line{Kind: kind})
			}
		}
		add(items.Context, 5)
		add(items.Removed, 1)
		add(items.Context, gap)
		add(items.Added, 1)
		add(items.Context, 5)
		return lines
	}
	if got := lineKinds(groupLines(build(7), 3)); got != "   -   |   +   " {
		t.Errorf("相隔 7 行：%q", got)
	}
	if got := lineKinds(groupLines(build(6), 3)); got != "   -      +   " {
		t.Errorf("相隔 6 行：%q", got)
	}
	if groupLines([]items.Line{{Kind: items.Context}}, 3) != nil {
		t.Error("没有改动也分出了段")
	}
}

func TestTakeLines(t *testing.T) {
	hunks := []items.Hunk{{Lines: make([]items.Line, 5)}, {Lines: make([]items.Line, 5)}}
	got := takeLines(hunks, 7)
	if len(got) != 2 || len(got[0].Lines) != 5 || len(got[1].Lines) != 2 {
		t.Errorf("取 7 行：%+v", got)
	}
	if len(takeLines(hunks, 5)) != 1 || len(takeLines(hunks, 100)) != 2 {
		t.Error("取整段时多取或少取了")
	}
}

func TestDisplayLineAndHunkStart(t *testing.T) {
	long := strings.Repeat("字", maxLineRunes+10)
	if got := displayLine(long); !strings.HasSuffix(got, " …") || len([]rune(got)) != maxLineRunes+2 {
		t.Errorf("长行没截断：%d 字", len([]rune(got)))
	}
	if displayLine("a\r") != "a" {
		t.Error("回车没去掉")
	}
	if hunkStart("@@ -12,7 +14,8 @@") != 14 || hunkStart("不是标题") != 0 {
		t.Error("读不出分隔条的起始行")
	}
}
