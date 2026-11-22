package sessionproto

import (
	"strings"
	"testing"
	"time"
)

func TestExportFileName(t *testing.T) {
	at := time.Date(2026, 11, 22, 9, 5, 0, 0, time.UTC)
	for title, want := range map[string]string{
		"  a/b:c*?\"<>|\t d\x01 ":     "Zeta-abc d-20261122-0905.md",
		"":                            "Zeta-对话-20261122-0905.md",
		"/:*":                         "Zeta-对话-20261122-0905.md",
		"多行\n\n标题":                    "Zeta-多行 标题-20261122-0905.md",
		strings.Repeat("长", 50) + "x": "Zeta-" + strings.Repeat("长", ExportTitleMax) + "-20261122-0905.md",
	} {
		if got := ExportFileName(title, at); got != want {
			t.Errorf("%q：%q，要 %q", title, got, want)
		}
	}
}

// 文件名里的时刻照客户端的时区（utc_offset），跨日也跟着换。
func TestExportFileNameAtOffset(t *testing.T) {
	at := time.Date(2026, 11, 22, 20, 30, 0, 0, time.UTC)
	for offset, want := range map[int]string{
		480:  "Zeta-t-20261123-0430.md", // 东八区，已是第二天
		-300: "Zeta-t-20261122-1530.md", // 西五区
		0:    "Zeta-t-20261122-2030.md",
		330:  "Zeta-t-20261123-0200.md", // 半小时的时区
	} {
		if got := ExportFileName("t", AtOffset(at, offset)); got != want {
			t.Errorf("utc_offset %d：%q，要 %q", offset, got, want)
		}
	}
}

func TestCleanTitle(t *testing.T) {
	for title, want := range map[string]string{
		"  天气\t预报 \n":                   "天气 预报",
		" \t ":                          "",
		strings.Repeat("字", TitleMax+5): strings.Repeat("字", TitleMax),
	} {
		if got := CleanTitle(title); got != want {
			t.Errorf("%q：%q，要 %q", title, got, want)
		}
	}
}

func TestHandsOver(t *testing.T) {
	for status, want := range map[string]bool{
		StatusIdle: true, StatusWaiting: true, StatusUnknown: true,
		StatusBusy: false, StatusShell: false, StatusStuck: false, "": false,
	} {
		if HandsOver(status) != want {
			t.Errorf("%q：要 %v", status, want)
		}
	}
}

func TestCombineExports(t *testing.T) {
	if title, md := CombineExports([]ExportPart{{Title: "一", Markdown: "# 一\n\n正文\n\n"}}); title != "一" || md != "# 一\n\n正文\n\n" {
		t.Fatalf("一个：%q %q", title, md)
	}
	title, md := CombineExports([]ExportPart{{Title: "一", Markdown: "# 一\n \n"}, {Title: "二", Markdown: "# 二"}, {Title: "三", Markdown: "# 三\n"}})
	if title != "3个对话" || md != "# 一\n\n---\n\n# 二\n\n---\n\n# 三\n" {
		t.Fatalf("几个：%q %q", title, md)
	}
}
