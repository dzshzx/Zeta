package mdmarks

import (
	"encoding/json"
	"os"
	"reflect"
	"slices"
	"strings"
	"testing"
)

// cases.json 是手机版、桌面版共用的用例：每段文字扫出的全部区间，at 按码位数算，text 是那段原文（方便看）。
type fixture struct {
	Text  string `json:"text"`
	Spans []struct {
		Kind string `json:"kind"`
		At   int    `json:"at"`
		Text string `json:"text"`
	} `json:"spans"`
}

func TestScanCases(t *testing.T) {
	b, err := os.ReadFile("testdata/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []fixture
	if err := json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) == 0 {
		t.Fatal("cases.json 里没有用例")
	}
	for _, c := range cases {
		runes := []rune(c.Text)
		want := []Span{}
		for _, s := range c.Spans {
			end := s.At + len([]rune(s.Text))
			if end > len(runes) || string(runes[s.At:end]) != s.Text {
				t.Fatalf("%q：用例里 %s 的 at=%d 对不上原文 %q", c.Text, s.Kind, s.At, s.Text)
			}
			want = append(want, Span{Kind(s.Kind), s.At, end})
		}
		got := append([]Span{}, Scan(c.Text)...)
		sortSpans(got)
		sortSpans(want)
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Scan(%q)\n got %v\nwant %v", c.Text, got, want)
		}
	}
}

// sortSpans 按起点、终点、样式排：Scan 不排序（调用方只要求 Marker 最后叠），比较前两边排成同一个顺序。
func sortSpans(s []Span) {
	slices.SortFunc(s, func(a, b Span) int {
		if a.Start != b.Start {
			return a.Start - b.Start
		}
		if a.End != b.End {
			return a.End - b.End
		}
		return strings.Compare(string(a.Kind), string(b.Kind))
	})
}
