package chatview

import (
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown/parser"
)

// 对话内查找（同手机版的 ChatFind、ChatFindBar、TextFind）：查提问的正文和写完了的回答排版后的文字，工具步骤和思考不查；
// 不分大小写。每一处命中都有淡淡的底色，当前那一处另加下划线（回答是 Markdown，命中处在原文里包一层 <mark>，
// 一个回答里分不出两种底色）；跳到一处时把那一条滚进视野。

// Match 是一处命中：条目 Item 的第 Block 段文字里 [Start, End) 这几个字节。Block 是 Markdown 块在回答里的编号，提问是 0。
type Match struct {
	Item       string
	Block      int
	Start, End int
}

// finder 是聊天列表的查找状态。
type finder struct {
	query   string
	lower   string // query 的小写（见 lowerSameWidth）
	matches []Match
	index   int
	// byItem 是各条目的命中在 matches 里的区间 [lo, hi)（同一条目的命中挨在一起）。
	byItem map[string][2]int
	// dirty：条目换了，命中要重算；jump：当前那一处换了，要滚过去。
	dirty, jump bool
	// 当前那一处在回答里时，回答的 Markdown 还要滚到它所在的块：marked 是那个回答标好的原文，at 是当前那一处在里面的位置；
	// anchor 是还要给 Markdown 递几帧定位（列表先滚到那一条、建出来以后它才滚得到块），jumps 是第几次跳。
	marked     string
	at, anchor int
	jumps      uint64
	// texts 是各条目能查的段（按条目 id 记着，原文没变就不重新解析）。
	texts map[string]*findText
}

// findText 是一个条目能查的文字：src 是它的原文，segments 是切好的段。mark 是上次标好命中的原文（回答用），
// 查找词和当前那一处没变时照用，不每帧重拼。
type findText struct {
	src      string
	segments []segment
	mark     markedSource
}

// markedSource 是一个回答标好命中的原文：查找词是 query、当前那一处是这个回答里的第 cur 处（不在这个回答里是 -1）时，
// 标好的是 text，当前那一处的 <mark><u> 在 text 的 at（没有时 -1）。
type markedSource struct {
	query   string
	cur, at int
	text    string
}

// segment 是一段能查的文字：Markdown 的一个段落、标题或表格单元格里的字（提问是整段正文）。pieces 记着这段里各截字在原文里的位置，
// 好在原文里给命中处包上 <mark>。
type segment struct {
	block  int
	text   string
	lower  string // text 的小写，字节位置和 text 一一对应（见 lowerSameWidth）
	pieces []piece
}

// piece 是段里的一截：段里 [at, at+n) 这几个字节是原文 [src, src+n) 那几个（exact），或者是一个行内代码 [src, srcEnd)
// （code：命中时整个包起来），或者对不上原文（转义、实体），命中了也不加底色。
type piece struct {
	at, n       int
	src, srcEnd int
	exact, code bool
}

// SetFind 换查找词：空的停止查找（高亮都去掉）。词变了，跳到最近的一处。
func (ch *Chat) SetFind(query string) {
	query = strings.TrimSpace(query)
	if query == ch.find.query {
		return
	}
	f := &ch.find
	f.query, f.lower = query, lowerSameWidth(query)
	f.dirty = true
	if query == "" {
		// 停止查找：命中和切好的段都不要了，不查的时候不花工夫
		f.matches, f.byItem, f.index, f.texts = nil, nil, -1, nil
	}
}

// FindStep 跳到下一处（delta 为 1）或上一处（-1），两头接上。
func (ch *Chat) FindStep(delta int) {
	f := &ch.find
	if len(f.matches) == 0 {
		return
	}
	f.index = ((f.index+delta)%len(f.matches) + len(f.matches)) % len(f.matches)
	f.jump = true
}

// FindCount 是当前那一处是第几个（从 0 数，没有时 -1）和一共几处；查找词为空时是 (-1, 0)。
func (ch *Chat) FindCount() (index, count int) {
	ch.refind()
	return ch.find.index, len(ch.find.matches)
}

// FindQuery 是已经算出命中的查找词（整理过的）。
func (ch *Chat) FindQuery() string { return ch.find.query }

// refind 在条目或查找词变了以后重算命中，停在原来那一处（还在的话），不在了停在它后面最近的一处，再没有停在最后一处；
// 之前没停过的从最后一处开始：聊天里最新的在底部（同 ChatFind.reselect）。
func (ch *Chat) refind() {
	f := &ch.find
	if !f.dirty || f.query == "" {
		return
	}
	f.dirty = false
	if f.texts == nil {
		f.texts = map[string]*findText{}
	}
	var before *Match
	if f.index >= 0 && f.index < len(f.matches) {
		m := f.matches[f.index]
		before = &m
	}
	order := map[string]int{}
	var found []Match
	kept := map[string]*findText{}
	byItem := map[string][2]int{}
	for row, it := range ch.items {
		t := ch.findable(it)
		if t == nil {
			continue
		}
		kept[it.ID] = t
		order[it.ID] = row
		lo := len(found)
		for _, s := range t.segments {
			found = appendOccurrences(found, it.ID, s, f.lower)
		}
		if len(found) > lo {
			byItem[it.ID] = [2]int{lo, len(found)}
		}
	}
	f.texts = kept // 删掉、剪掉的条目跟着丢掉
	f.matches, f.byItem = found, byItem
	f.index = reselect(before, found, order)
	if f.index >= 0 && (before == nil || *before != found[f.index]) {
		f.jump = true
	}
}

// findable 是条目能查的文字：提问的正文，和写完了的回答；别的为 nil。
// 回答查的是照常排版的那部分（取出来画在下面的图的写法不算）。
func (ch *Chat) findable(it items.Item) *findText {
	if it.Kind == items.Answer && !it.Streaming {
		it = ch.shownAnswer(it)
	}
	switch {
	case it.Kind == items.User && it.Text != "":
	case it.Kind == items.Answer && !it.Streaming && strings.TrimSpace(it.Text) != "":
	default:
		return nil
	}
	if t := ch.find.texts[it.ID]; t != nil && t.src == it.Text {
		return t
	}
	t := &findText{src: it.Text}
	if it.Kind == items.User {
		t.segments = []segment{{text: it.Text, lower: lowerSameWidth(it.Text), pieces: []piece{{n: len(it.Text), srcEnd: len(it.Text), exact: true}}}}
	} else {
		t.segments = markdownSegments(it.Text)
	}
	return t
}

// markdownSegments 把回答的 Markdown 切成能查的段：段落、标题、表格单元格各一段，里面是排版后的字（**粗体** 只算「粗体」，
// 链接只算链接文字，行内代码算它的字）。代码块、公式、HTML 不查。
func markdownSegments(src string) []segment {
	d := parser.Parse(src)
	var out []segment
	for i := 1; i < d.Len(); i++ {
		switch d.Block(i).Kind {
		case parser.Paragraph, parser.Heading, parser.TableCell:
		default:
			continue
		}
		var s segment
		s.block = i
		var text strings.Builder
		for _, in := range d.Inlines(i) {
			at := text.Len()
			switch in.Kind {
			case parser.Text:
				text.WriteString(in.Text)
				exact := in.End-in.Start == len(in.Text) && src[in.Start:in.End] == in.Text
				s.pieces = append(s.pieces, piece{at: at, n: len(in.Text), src: in.Start, srcEnd: in.End, exact: exact})
			case parser.Code:
				text.WriteString(in.Text)
				s.pieces = append(s.pieces, piece{at: at, n: len(in.Text), src: in.Start, srcEnd: in.End, code: true})
			case parser.SoftBreak, parser.LineBreak:
				text.WriteString(" ")
			}
		}
		if s.text = text.String(); strings.TrimSpace(s.text) != "" {
			s.lower = lowerSameWidth(s.text)
			out = append(out, s)
		}
	}
	return out
}

// appendOccurrences 把段 s 里查找词出现的地方（字节区间，从前往后，互不重叠）作为条目 item 的命中接在 found 后面。
// lower 是查找词的小写：比的是两边的小写，所以不分大小写。
func appendOccurrences(found []Match, item string, s segment, lower string) []Match {
	if lower == "" {
		return found
	}
	for from := 0; ; {
		i := strings.Index(s.lower[from:], lower)
		if i < 0 {
			return found
		}
		from += i
		found = append(found, Match{Item: item, Block: s.block, Start: from, End: from + len(lower)})
		from += len(lower)
	}
}

// lowerSameWidth 是 s 的小写，字节数和 s 一样、每个字的位置不变：小写以后字节数会变的字（少见，如 İ）保持原样，
// 这样小写里找到的位置就是原文里的位置。
func lowerSameWidth(s string) string {
	for i, r := range s {
		if l := unicode.ToLower(r); l != r && utf8.RuneLen(l) == utf8.RuneLen(r) {
			return s[:i] + strings.Map(lowerRune, s[i:])
		}
	}
	return s // 没有要变的字：不另分配
}

func lowerRune(r rune) rune {
	if l := unicode.ToLower(r); utf8.RuneLen(l) == utf8.RuneLen(r) {
		return l
	}
	return r
}

// reselect 是命中换了以后停在哪（下标，没有命中是 -1），见 refind。order 是各条目在列表里的位置。
func reselect(before *Match, matches []Match, order map[string]int) int {
	if len(matches) == 0 {
		return -1
	}
	if before == nil {
		return len(matches) - 1
	}
	if i := slices.Index(matches, *before); i >= 0 {
		return i
	}
	row, ok := order[before.Item]
	if !ok {
		return len(matches) - 1
	}
	key := func(m Match) [3]int { return [3]int{order[m.Item], m.Block, m.Start} }
	from := [3]int{row, before.Block, before.Start}
	for i, m := range matches {
		if k := key(m); k[0] > from[0] || k[0] == from[0] && (k[1] > from[1] || k[1] == from[1] && k[2] >= from[2]) {
			return i
		}
	}
	return len(matches) - 1
}

// current 是当前那一处。
func (f *finder) current() (Match, bool) {
	if f.index < 0 || f.index >= len(f.matches) {
		return Match{}, false
	}
	return f.matches[f.index], true
}

// findColors 是命中处的底色：其余几处淡淡的主题色，当前那一处深一些（同 TextFind.kt 的 findColors）。
func findColors(k *coui.Colors) (soft, strong ui.Color) {
	if k.Dark {
		return k.Primary.Alpha(0.3), k.Primary.Alpha(0.62)
	}
	return k.Primary.Alpha(0.2), k.Primary.Alpha(0.42)
}

// matchesIn 是条目 id 里的命中，和当前那一处是其中第几个（不在其中是 -1）。
func (f *finder) matchesIn(id string) ([]Match, int) {
	r, ok := f.byItem[id]
	if !ok {
		return nil, -1
	}
	cur := -1
	if r[0] <= f.index && f.index < r[1] {
		cur = f.index - r[0]
	}
	return f.matches[r[0]:r[1]], cur
}

// bubbleText 是提问气泡里的字：查找时命中处加底色，当前那一处更深。
func (ch *Chat) bubbleText(c *ui.Context, it items.Item) ui.Element {
	ms, cur := ch.find.matchesIn(it.ID)
	if len(ms) == 0 {
		return ui.Text(c, it.Text)
	}
	soft, strong := findColors(coui.Of(c))
	var spans []ui.Span
	at := 0
	for i, m := range ms {
		if m.Start > at {
			spans = append(spans, ui.Span{Text: it.Text[at:m.Start]})
		}
		bg := soft
		if i == cur {
			bg = strong
		}
		spans = append(spans, ui.Span{Text: it.Text[m.Start:m.End], Background: bg})
		at = m.End
	}
	if at < len(it.Text) {
		spans = append(spans, ui.Span{Text: it.Text[at:]})
	}
	return ui.RichText(c, spans...)
}

// answerSource 是回答要显示的 Markdown：查找时命中处包上 <mark>（当前那一处再加 <u>），没在查或没有命中时是原文。
// 当前那一处在这个回答里时，顺带让 Markdown 滚到它所在的块。标好的按查找词和当前那一处记在条目上，没变时不重拼。
func (ch *Chat) answerSource(c *ui.Context, it items.Item) string {
	f := &ch.find
	ms, cur := f.matchesIn(it.ID)
	t := f.texts[it.ID]
	if len(ms) == 0 || t == nil || t.src != it.Text {
		return it.Text
	}
	soft, _ := findColors(coui.Of(c))
	ch.answerTheme.Mark = soft
	if m := t.mark; m.text == "" || m.query != f.query || m.cur != cur {
		text, at := markSource(t, ms, cur)
		t.mark = markedSource{query: f.query, cur: cur, text: text, at: at}
	}
	if t.mark.at >= 0 {
		f.marked, f.at = t.mark.text, t.mark.at
	}
	return t.mark.text
}

// markSource 在回答 t 的原文里给命中 ms 包上 <mark>（第 cur 处再加 <u>），返回标好的原文和第 cur 处的 <mark><u> 在里面的位置（没有时 -1）。
func markSource(t *findText, ms []Match, cur int) (string, int) {
	type insert struct {
		at   int
		text string
		open bool
	}
	var ins []insert
	wrapped := map[int]bool{} // 包过的行内代码（按原文的起点）
	wrap := func(from, to int, current bool) {
		open, end := "<mark>", "</mark>"
		if current {
			open, end = "<mark><u>", "</u></mark>"
		}
		ins = append(ins, insert{from, open, true}, insert{to, end, false})
	}
	for i, m := range ms {
		s := segmentOf(t.segments, m.Block)
		if s == nil {
			continue
		}
		for _, p := range s.pieces {
			lo, hi := max(m.Start, p.at), min(m.End, p.at+p.n)
			switch {
			case lo >= hi:
			case p.code && !wrapped[p.src]:
				wrapped[p.src] = true
				wrap(p.src, p.srcEnd, i == cur)
			case p.exact:
				wrap(p.src+lo-p.at, p.src+hi-p.at, i == cur)
			}
		}
	}
	// 按位置从前往后写；同一处先收尾再开头，得到 </mark><mark>，不会搅在一起
	slices.SortStableFunc(ins, func(a, b insert) int {
		if a.at != b.at {
			return a.at - b.at
		}
		switch {
		case a.open == b.open:
			return 0
		case a.open:
			return 1
		}
		return -1
	})
	src := t.src
	var b strings.Builder
	b.Grow(len(src) + len(ins)*len("</u></mark>"))
	at, from := -1, 0
	for _, x := range ins {
		b.WriteString(src[from:x.at])
		from = x.at
		if x.text == "<mark><u>" && at < 0 {
			at = b.Len()
		}
		b.WriteString(x.text)
	}
	b.WriteString(src[from:])
	return b.String(), at
}

func segmentOf(segments []segment, block int) *segment {
	for i := range segments {
		if segments[i].block == block {
			return &segments[i]
		}
	}
	return nil
}

// findFrame 在每一帧开头跟上查找：条目或查找词变了就重算，当前那一处换了就把它所在的那一条滚进视野。
func (ch *Chat) findFrame() {
	ch.refind()
	f := &ch.find
	m, ok := f.current()
	if !f.jump || !ok {
		return
	}
	f.jump = false
	row := slices.IndexFunc(ch.items, func(it items.Item) bool { return it.ID == m.Item })
	if row < 0 {
		return
	}
	if ch.items[row].Kind != items.Answer {
		ch.list.ScrollTo(row, ui.Center)
		return
	}
	// 回答可能很长：先滚到这一条，再让它的 Markdown 滚到命中处所在的块
	ch.list.ScrollTo(row, ui.Start)
	f.jumps++
	f.anchor = 3
}

// anchorFrame 给回答的 Markdown 递定位：跳完以后的几帧里 Anchor 指向当前那一处所在的块，之后撤掉
// （开着时别的回答换了原文也会去找这个定位，找不到不滚，但没必要一直开着）。
func (ch *Chat) anchorFrame(c *ui.Context) {
	f := &ch.find
	if f.anchor <= 0 {
		ch.answer.Anchor = ""
		return
	}
	f.anchor--
	ch.answer.Anchor, ch.answer.AnchorVersion = findAnchor, f.jumps
	ch.answer.AnchorBlock = f.anchorBlock
	c.Invalidate()
}

// findAnchor 是查找跳转用的定位名。
const findAnchor = "zeta-find"

// anchorBlock 是 Markdown 的 AnchorBlock：当前那一处所在的回答里，包着它的最里层的块；别的回答是 -1。
func (f *finder) anchorBlock(d *parser.Document, name string) int {
	if name != findAnchor || d.Source() != f.marked {
		return -1
	}
	found := -1
	for i := 1; i < d.Len(); i++ {
		if b := d.Block(i); b.Start <= f.at && f.at < b.End {
			found = i
		}
	}
	return found
}
