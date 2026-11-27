package lastline

import (
	"strings"
	"testing"
)

// 要找的那行跨在两块的边界上、后面还跟着比一块还长的行，也找得到；交给 match 的行不带换行；[lo, hi) 之外的不算。
func TestFindAcrossChunks(t *testing.T) {
	want := `{"want":1}`
	big := `{"x":"` + strings.Repeat("x", chunk+100) + `"}`
	pad := `{"y":"` + strings.Repeat("y", chunk-len(want)/2-10) + `"}`
	for name, lines := range map[string][]string{
		"跨块":   {big, want, pad},
		"后面大行": {want, big, big},
	} {
		data := strings.Join(lines, "\n") + "\n"
		found, err := Find(strings.NewReader(data), 0, int64(len(data)), func(line []byte) bool { return string(line) == want })
		if err != nil || !found {
			t.Errorf("%s：%v %v", name, found, err)
		}
	}
	data := want + "\n" + `{"other":1}` + "\n"
	match := func(line []byte) bool { return string(line) == want }
	if found, _ := Find(strings.NewReader(data), int64(len(want)+1), int64(len(data)), match); found {
		t.Error("lo 之前的行不该算")
	}
}

// 第 n 行开头的位置；不到 n 行时是全长。
func TestLineStart(t *testing.T) {
	data := "a\nbb\nccc\n"
	for n, want := range map[int]int64{1: 0, 2: 2, 3: 5, 4: 9, 9: 9} {
		if got, err := LineStart(strings.NewReader(data), n); err != nil || got != want {
			t.Errorf("第 %d 行：%d %v，要 %d", n, got, err, want)
		}
	}
}
