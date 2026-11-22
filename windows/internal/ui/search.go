package ui

import (
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// searchField 是侧栏底下的搜索框（同 Compose 的 SearchCapsule）：框里的字一变就交给中枢去搜。
func (a *App) searchField(c *ui.Context) {
	in := coui.SearchField(c, &a.query, "搜索对话")
	in.OnSubmit(func() {}) // 回车不换行、不做别的，结果已经在出了
	if a.query != a.hub.Query() {
		a.hub.SetQuery(a.query)
	}
}

// searchResults 是搜索时的列表（同 Compose 的 searchItems）：「搜索结果」下面各个会话（带内容里命中的片段），
// 内容还在搜或没搜全时末尾说一句；一个都没有时说正在搜或没有匹配的。
func (a *App) searchResults(c *ui.Context) {
	h := a.hub
	results := h.Results(a.currentSession(), a.now())
	progress := h.SearchProgress()
	if len(results) == 0 {
		text := "没有匹配的对话"
		if progress == hub.SearchRunning {
			text = "正在搜索…"
		}
		ui.Text(c, text).FontSize(coui.SizeBodyM).TextColor(coui.Of(c).Secondary).TextAlign(ui.Center).FillWidth().Padding(36, 24)
		return
	}
	sectionTitle(c, "搜索结果", true)
	for _, s := range results {
		a.sessionRow(c, s, h.Snippet(s))
	}
	if text := searchFooter(progress); text != "" {
		ui.Text(c, text).FontSize(coui.SizeBodyXS).TextColor(coui.Of(c).Secondary).FillWidth().Padding(12, 32)
	}
}

func searchFooter(p hub.SearchProgress) string {
	switch p {
	case hub.SearchRunning:
		return "正在搜索对话内容…"
	case hub.SearchPartial:
		return "结果较多，只列出了一部分，换个更具体的词试试"
	case hub.SearchFailed:
		return "对话内容没搜成，只比对了标题"
	}
	return ""
}

// highlighted 是一段字，其中每处 needle（不分大小写）标成主题色、中等字重（同 Compose 的 highlighted）。weight 是 0 时用常规字重。
func highlighted(c *ui.Context, text, needle string, size float32, weight int, color ui.Color) ui.Element {
	k := coui.Of(c)
	var spans []ui.Span
	for _, part := range matches(text, needle) {
		span := ui.Span{Text: part.text, Size: size, Weight: weight, Color: color}
		if part.hit {
			span.Color, span.Weight = k.PrimaryText, coui.Medium
		}
		spans = append(spans, span)
	}
	return ui.RichText(c, spans...).Label(text)
}

type textPart struct {
	text string
	hit  bool
}

// matches 把 text 切成几段，标出哪些是 needle（不分大小写）；needle 是空的时整段不标。
func matches(text, needle string) []textPart {
	if needle == "" {
		return []textPart{{text: text}}
	}
	lower, low := []rune(strings.ToLower(text)), []rune(strings.ToLower(needle))
	runes := []rune(text)
	if len(lower) != len(runes) { // 大小写换了字数的（极少见）：不标
		return []textPart{{text: text}}
	}
	var parts []textPart
	from := 0
	for i := 0; i+len(low) <= len(lower); {
		if string(lower[i:i+len(low)]) != string(low) {
			i++
			continue
		}
		if i > from {
			parts = append(parts, textPart{text: string(runes[from:i])})
		}
		parts = append(parts, textPart{text: string(runes[i : i+len(low)]), hit: true})
		i += len(low)
		from = i
	}
	if from < len(runes) {
		parts = append(parts, textPart{text: string(runes[from:])})
	}
	return parts
}

// statusText 是会话状态的那两三个字（同 Compose 的 statusText）。
func statusText(st hub.Status) string {
	switch st.Activity {
	case hub.Waiting:
		return "等你确认"
	case hub.Answering:
		return "回答中"
	case hub.Background:
		return "后台 " + strconv.Itoa(st.Tasks)
	case hub.Unread:
		return "新回答"
	case hub.Idle:
		return "闲置"
	}
	return ""
}

// statusColor：等确认是警告色，在回答、后台任务是主题色，有新回答是绿色，闲着是淡灰。
func statusColor(k *coui.Colors, a hub.Activity) ui.Color {
	switch a {
	case hub.Waiting:
		return k.Warning
	case hub.Answering, hub.Background:
		return k.PrimaryText
	case hub.Unread:
		return k.Success
	}
	return k.Tertiary
}

// statusLabel 是会话行尾的状态（同 Compose 的 SessionStatusLabel）：12 的圆形记号加两三个字，颜色跟着状态走。
func statusLabel(c *ui.Context, st hub.Status) {
	k := coui.Of(c)
	color := statusColor(k, st.Activity)
	ui.Row(c).Gap(5).AlignItems(ui.Center).Shrink(0).Children(func() {
		ui.Box(c).Size(markSize, markSize).Shrink(0).Draw(func(p *ui.Painter, r ui.Rect) { drawMark(p, r, st.Activity, color) })
		ui.Text(c, statusText(st)).FontSize(coui.SizeBodyXS).TextColor(color).SingleLine()
	})
}

const (
	markSize   = 12
	markStroke = 1.4
)

// drawMark 画状态记号（同 Compose 的 StatusMark）：同一个圆，在回答的缺口转着（一秒一圈），其余的圆里各画一样东西——
// 等确认是感叹号、后台任务是钟面的两根针、闲着是暂停的两竖；有新回答是实心的。
func drawMark(p *ui.Painter, r ui.Rect, a hub.Activity, color ui.Color) {
	cx, cy := r.X+r.W/2, r.Y+r.H/2
	radius := (min(r.W, r.H) - markStroke) / 2
	ring := func(c ui.Color) { p.StrokePath(new(ui.Path).Circle(cx, cy, radius), markStroke, c) }
	bar := func(x1, y1, x2, y2 float32) {
		p.StrokePath(new(ui.Path).MoveTo(cx+x1, cy+y1).LineTo(cx+x2, cy+y2), markStroke, color)
	}
	switch a {
	case hub.Unread:
		p.FillPath(new(ui.Path).Circle(cx, cy, radius+markStroke/2), color)
	case hub.Answering:
		ring(color.Alpha(0.25))
		const turn = time.Second
		start := float64(p.Now().UnixMilli()%turn.Milliseconds())/float64(turn.Milliseconds())*2*math.Pi - math.Pi/2
		arc := new(ui.Path)
		const steps = 24
		for i := 0; i <= steps; i++ {
			t := start + float64(i)/steps*250*math.Pi/180
			x, y := cx+radius*float32(math.Cos(t)), cy+radius*float32(math.Sin(t))
			if i == 0 {
				arc.MoveTo(x, y)
			} else {
				arc.LineTo(x, y)
			}
		}
		p.StrokePath(arc, markStroke, color)
		p.After(16 * time.Millisecond) // 转着：画完接着画下一帧（只重画这个记号，不重建界面）
	case hub.Waiting:
		ring(color)
		bar(0, -2.6, 0, 0.4)
		p.FillPath(new(ui.Path).Circle(cx, cy+2.6, markStroke/2), color)
	case hub.Background:
		ring(color)
		bar(0, -2.8, 0, 0)
		bar(0, 0, 2.2, 0)
	case hub.Idle:
		ring(color)
		bar(-1.4, -2, -1.4, 2)
		bar(1.4, -2, 1.4, 2)
	}
}
