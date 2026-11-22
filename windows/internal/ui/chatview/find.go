package chatview

import (
	"slices"
	"strings"
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
	matches []Match
	index   int
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

// findText 是一个条目能查的文字：src 是它的原文，segments 是切好的段。
type findText struct {
	src      string
	segments []segment
}

// segment 是一段能查的文字：Markdown 的一个段落、标题或表格单元格里的字（提问是整段正文）。pieces 记着这段里各截字在原文里的位置，
// 好在原文里给命中处包上 <mark>。
type segment struct {
	block  int
	text   string
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
	ch.find.query = query
	ch.find.dirty = true
	if query == "" {
		ch.find.matches, ch.find.index = nil, -1
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
	if !f.dirty {
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
	for row, it := range ch.items {
		t := ch.findable(it)
		if t == nil {
			continue
		}
		kept[it.ID] = t
		order[it.ID] = row
		if f.query == "" {
			continue
		}
		for _, s := range t.segments {
			for _, r := range occurrences(s.text, f.query) {
				found = append(found, Match{Item: it.ID, Block: s.block, Start: r[0], End: r[1]})
			}
		}
	}
	f.texts = kept // 删掉、剪掉的条目跟着丢掉
	f.matches = found
	f.index = reselect(before, found, order)
	if f.index >= 0 && (before == nil || *before != found[f.index]) {
		f.jump = true
	}
}

// findable 是条目能查的文字：提问的正文，和写完了的回答；别的为 nil。
func (ch *Chat) findable(it items.Item) *findText {
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
		t.segments = []segment{{text: it.Text, pieces: []piece{{n: len(it.Text), srcEnd: len(it.Text), exact: true}}}}
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
			out = append(out, s)
		}
	}
	return out
}

// occurrences 是 text 里 query 出现的地方（字节区间）：不分大小写，从前往后，互不重叠。
func occurrences(text, query string) [][2]int {
	if query == "" || len(text) < len(query) {
		return nil
	}
	var out [][2]int
	if lt, ok := lowerSameWidth(text); ok {
		lq := strings.ToLower(query)
		for from := 0; ; {
			i := strings.Index(lt[from:], lq)
			if i < 0 {
				return out
			}
			out = append(out, [2]int{from + i, from + i + len(lq)})
			from += i + len(lq)
		}
	}
	// 小写以后字节数会变的（少见）：逐个位置比
	n := utf8.RuneCountInString(query)
	for i := 0; i < len(text); {
		end := i
		for k := 0; k < n && end < len(text); k++ {
			_, w := utf8.DecodeRuneInString(text[end:])
			end += w
		}
		if strings.EqualFold(text[i:end], query) {
			out = append(out, [2]int{i, end})
			i = end
			continue
		}
		_, w := utf8.DecodeRuneInString(text[i:])
		i += w
	}
	return out
}

// lowerSameWidth 是 s 的小写；有字小写后字节数变了时 ok 为假（那样偏移对不上原文）。
func lowerSameWidth(s string) (string, bool) {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		l := lowerRune(r)
		if utf8.RuneLen(l) != utf8.RuneLen(r) {
			return "", false
		}
		b.WriteRune(l)
	}
	return b.String(), true
}

func lowerRune(r rune) rune {
	if r < utf8.RuneSelf {
		if 'A' <= r && r <= 'Z' {
			return r + 'a' - 'A'
		}
		return r
	}
	return []rune(strings.ToLower(string(r)))[0]
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
	if f.query == "" {
		return nil, -1
	}
	var out []Match
	cur := -1
	for i, m := range f.matches {
		if m.Item == id {
			if i == f.index {
				cur = len(out)
			}
			out = append(out, m)
		}
	}
	return out, cur
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
// 当前那一处在这个回答里时，顺带让 Markdown 滚到它所在的块。
func (ch *Chat) answerSource(c *ui.Context, it items.Item) string {
	ms, cur := ch.find.matchesIn(it.ID)
	t := ch.find.texts[it.ID]
	if len(ms) == 0 || t == nil || t.src != it.Text {
		return it.Text
	}
	soft, _ := findColors(coui.Of(c))
	ch.answerTheme.Mark = soft
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
	// 从后往前插，前面的偏移不变；同一处先插开头的再插收尾的（后插的在前面），得到 </mark><mark>，不会搅在一起
	slices.SortStableFunc(ins, func(a, b insert) int {
		if a.at != b.at {
			return b.at - a.at
		}
		if a.open != b.open {
			if a.open {
				return -1
			}
			return 1
		}
		return 0
	})
	src := it.Text
	at := -1
	for _, x := range ins {
		src = src[:x.at] + x.text + src[x.at:]
		if x.text == "<mark><u>" {
			at = x.at
		} else if at >= 0 {
			at += len(x.text) // 插在它前面的把它往后推
		}
	}
	if at >= 0 {
		ch.find.marked, ch.find.at = src, at
	}
	return src
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
