package ui

import (
	"github.com/dzshzx/Zeta/client/mdmarks"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// draftMarks 是输入框里 Markdown 的显示样式（规则在 client/mdmarks，和手机版同一套）：标记符号原样留着、调成提示文字的颜色，
// 加粗和标题照回答的加粗（700），代码换颜色，列表记号用主题色，引用用次要色。mygo 的输入框只能设颜色和字重，
// 斜体、删除线只把标记调淡。只改显示，发出去的还是原文。
type draftMarks struct {
	text   string
	k      *widget.Colors
	ranges []ui.TextRange
}

// of 返回 text 的样式区间；文字和配色没变时用上一帧算好的，不每帧重扫（k 不会是 nil，零值第一次总会算）。
func (d *draftMarks) of(k *widget.Colors, text string) []ui.TextRange {
	if d.text == text && d.k == k {
		return d.ranges
	}
	d.text, d.k, d.ranges = text, k, nil
	var markers []ui.TextRange
	for _, s := range mdmarks.Scan(text) {
		r := ui.TextRange{Start: s.Start, End: s.End}
		switch s.Kind {
		case mdmarks.Marker:
			// 标记符号最后给，压过它所在的加粗、标题
			r.Color, r.Weight = k.Tertiary, 400
			markers = append(markers, r)
			continue
		case mdmarks.Bold, mdmarks.Heading:
			r.Weight = 700
		case mdmarks.Code, mdmarks.CodeBlock:
			r.Color = k.Purple
		case mdmarks.Quote:
			r.Color = k.Secondary
		case mdmarks.Bullet:
			r.Color = k.PrimaryText
		default:
			continue // 斜体、删除线画不出，只有标记调淡
		}
		d.ranges = append(d.ranges, r)
	}
	d.ranges = append(d.ranges, markers...)
	return d.ranges
}
