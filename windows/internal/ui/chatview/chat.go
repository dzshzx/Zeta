// Package chatview 画聊天列表：用户消息、回答（Markdown，流式时逐字显现）、提示、思考与工具调用的折叠卡、
// 文件改动的红绿对比和图片，以及点图打开的看大图（Viewer，由调用方画在窗口最上层）。条目和各个回调由调用方给，
// 组件自己不碰网络和文件；都在界面线程上用。
package chatview

import (
	"math"
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/markdown"
)

// Earlier 是还没载入的更早记录的情况：长对话打开时只载入最近的一段，往上翻到顶时再接上更早的。
type Earlier struct {
	// Skipped 是还没载入的条数，0 表示没有更早的了。
	Skipped int
	// Loading：正在载入。
	Loading bool
	// Failed 是上次没读到的原因；Retry 表示点一下可以重试。
	Failed string
	Retry  bool
}

func (e Earlier) shown() bool { return e.Skipped > 0 || e.Loading || e.Failed != "" }

// idle：可以开始载入（有更早的、没在载入、没出错或出错了能重试）。
func (e Earlier) idle() bool {
	return e.Skipped > 0 && !e.Loading && (e.Failed == "" || e.Retry)
}

// Chat 是一个对话的聊天列表。调用方给条目（SetItems 整份替换，Splice 换掉其中一段）和回调，在窗口的视图里调 View。
type Chat struct {
	// LoadEarlier 在用户往上翻到列表顶上附近、或点「载入更早的」那一行时调；调用方开始载入，并照进度改 Earlier。
	LoadEarlier func()
	// LoadImage 取会话记录里的一张图（session 图片），返回图片文件的内容。它在单独的 goroutine 里调，不同的图
	// 可能同时在取；同一张图只调一次。
	// 没给时这些图显示为取不到。
	LoadImage func(file, ref string) ([]byte, error)
	// LoadInlineImage 取实时收到、条目里只带了 key 没带原文的图（核心按 options.image_refs 推来的），返回图片文件的内容。
	// 同 LoadImage 在单独的 goroutine 里调、同一张图只调一次；没给时这些图显示为取不到。
	LoadInlineImage func(key string) ([]byte, error)
	// OpenSubagent：给了时，发起子代理的那一步点了调它（参数是那一步的 tool_use id），不在原地展开。
	OpenSubagent func(toolUseID string)
	// ExpandWork：思考与工具调用的卡片默认展开（子代理页看它的步骤时）。
	ExpandWork bool
	// OpenLink：回答里的链接点了调它；没给时 http、https、mailto 的链接交给系统打开。
	OpenLink func(url string)
	// OpenImages：点了图时调它（同一组的图、点的是第几张），由调用方打开看大图（NewViewer）；没给时图点不开。
	OpenImages func(list []items.Image, index int)
	// LoadMarkdownImage 取回答的 Markdown 里写的图片（answerimages.go 从正文里取出来的）：url 是主机上的路径（绝对路径，
	// 或 ~ 开头）或 http、https 的网址，返回图片文件的内容。在单独的 goroutine 里调，同一个地址只调一次。
	// 没给时这些图显示为取不到。显示前设好，之后不再换。
	LoadMarkdownImage func(url string) ([]byte, error)
	// Cwd 是对话的工作目录：回答里图片的相对路径按它解析。为空时相对路径的图留在正文里。
	Cwd string
	// Earlier 是更早记录的情况。
	Earlier Earlier
	// Top 是列表顶上被浮着的东西（标题栏）盖住的高度：内容从它下面开始排，往上翻时从它底下滚过去。
	Top float32
	// 消息操作（见 actions.go）：用户消息右键菜单里「复制」后面的项、挂在一轮最后一项（用户消息的 RoundLast）下面的操作排、
	// 正在编辑的那条。都拿到这一轮的用户消息和之后还有几轮（later，0 是最后一轮）。
	UserMenu func(c *ui.Context, m *ui.Menu, user items.Item, later int)
	Actions  func(c *ui.Context, user items.Item, later int)
	Editing  string
	// Header 画在第一条上面，和条目一起滚动（没有条目时也画）；「更早的记录」画在它下面。Footer 画在最后一条下面。
	// Framed：条目连成一张卡片，只有第一段画上面的圆角、最后一段画下面的圆角（Footer 也在卡片里）。
	// Width 是内容最宽多少，没设时是 contentWidth。子代理页用它们在步骤上面画概要、把步骤装进卡片。
	Header, Footer func(c *ui.Context)
	Framed         bool
	Width          float32

	items []items.Item
	// users 是各条用户消息的下标，按先后；rounds 是各轮操作排挂在哪一项下面（用户消息的 RoundLast → 它在 users 里是第几条）。
	// 条目一换就重算（indexRounds），画的时候不再往前往后找。
	users  []int
	rounds map[string]int
	list   ui.ListState
	// answer、thinking 是回答和思考的 Markdown 设置；thinkingTheme 是思考用的淡色字。
	answer, thinking           markdown.Options
	answerTheme, thinkingTheme markdown.Theme
	// open 记着各张折叠卡展开没有（按「种类:id」），条目换掉后照样留着。
	open map[string]*bool
	// changes 是各步文件改动的对比（按步骤的 id），条目换掉时丢掉对应的。
	changes map[string]*changeView
	images  imageCache
	// allowed 是用户点过「加载图片」的网络图（按缓存键）。
	allowed map[string]bool
	// parts 是各段回答拆出的正文和图（按条目 id），原文或工作目录变了时重拆。
	parts  map[string]*answerParts
	paging paging
	// find 是对话内查找（find.go）。
	find finder
	// scrolled：上一帧内容往上滚过了（最上面一行不是第一条，或第一条的顶端到了内容区上沿以上）。
	scrolled bool
}

// loadAhead：往上翻到离顶这么多行以内时载入更早的。
const loadAhead = 3

// New 返回一个空的聊天列表：一打开就在底部，跟着新内容走。每个对话用一个，换对话时换一个新的
// （列表的位置、折叠卡的展开与否、取过的图都跟着对话走）。
func New() *Chat {
	ch := &Chat{open: map[string]*bool{}, changes: map[string]*changeView{}, rounds: map[string]int{}, allowed: map[string]bool{},
		parts: map[string]*answerParts{}}
	ch.list.FollowEnd = true
	ch.list.FollowAnimation = &ui.Smooth
	ch.list.Key = func(row int) any { return ch.items[row].ID }
	ch.thinking.Theme = &ch.thinkingTheme
	ch.answer.Theme = &ch.answerTheme
	ch.answer.CodeBlock = codeBlock
	ch.thinking.CodeBlock = codeBlock
	setMath(&ch.answer, &ch.answerTheme, answerMathScale)
	setMath(&ch.thinking, &ch.thinkingTheme, thinkingMathScale)
	return ch
}

// answerParts 是回答 it 拆出的正文和图（splitAnswerImages），原文和工作目录没变时用上次拆的。
func (ch *Chat) answerParts(it items.Item) *answerParts {
	if p := ch.parts[it.ID]; p != nil && p.src == it.Text && p.cwd == ch.Cwd {
		return p
	}
	p := splitAnswerImages(it.Text, ch.Cwd)
	ch.parts[it.ID] = &p
	return &p
}

// shownAnswer 是回答 it 照常排版的那部分：拿掉了取出来直接显示的图。
func (ch *Chat) shownAnswer(it items.Item) items.Item {
	it.Text = ch.answerParts(it).text
	return it
}

// Items 返回现在显示的条目。
func (ch *Chat) Items() []items.Item { return ch.items }

// SetItems 整份换掉条目（打开会话、重新取了一份时）。折叠卡展开与否按 id 留着。
func (ch *Chat) SetItems(list []items.Item) {
	ch.items = list
	ch.indexRounds()
	ch.find.dirty = true
	clear(ch.changes)
	clear(ch.parts)
}

// Splice 把从下标 at 起的 remove 项换成 add（serve 推来的增量）。越界时返回错误、不改列表：本地和推送对不上了，
// 调用方该整份重取。
func (ch *Chat) Splice(at, remove int, add []items.Item) error {
	next, err := items.Splice(ch.items, at, remove, add)
	if err != nil {
		return err
	}
	for _, it := range ch.items[at : at+remove] {
		ch.forget(it)
	}
	for _, it := range add {
		ch.forget(it)
	}
	ch.find.dirty = true
	ch.items = next
	ch.indexRounds()
	return nil
}

// forget 丢掉一个条目算好的东西（各步的对比、回答拆出的图），下次显示时照新内容重算。
func (ch *Chat) forget(it items.Item) {
	delete(ch.parts, it.ID)
	for _, s := range it.Steps {
		delete(ch.changes, s.ID)
	}
}

// Scrolled 说内容是不是往上滚过了：顶上浮着的标题栏这时才透出底下模糊的内容（Compose 的 TopAppBarGlow）。
func (ch *Chat) Scrolled() bool { return ch.scrolled }

// ScrollToEnd 回到底部并跟着新内容走（发消息、点「回到底部」时）。
func (ch *Chat) ScrollToEnd() { ch.list.ScrollToEnd() }

// AtEnd 报告上一帧显示着底部；不在底部时调用方显示「回到底部」。还没排过版（刚打开的第一帧）、没有条目时也算在底部。
func (ch *Chat) AtEnd() bool {
	if first, last := ch.list.Visible(); last < first {
		return true
	}
	return ch.list.AtEnd()
}

// listTop 是列表内容上面留的空（Top 之外）。
const listTop = 12

// 内容最宽这么多，窗口再宽也居中，免得一行字太长。
const contentWidth = 768

// View 画聊天列表，返回列表的元素，由调用方定大小（通常 Grow(1)）。
func (ch *Chat) View(c *ui.Context) ui.Element {
	rows := ch.shownRows()
	if ch.paging.next(rows) && ch.Earlier.idle() && ch.LoadEarlier != nil {
		ch.LoadEarlier()
	}
	ch.findFrame()
	ch.anchorFrame(c)
	ch.answer.OnLink = ch.OpenLink
	ch.thinking.OnLink = ch.OpenLink
	styleMarkdown(c, &ch.answerTheme, &ch.thinkingTheme)
	top := float32(math.Inf(1)) // 第一条上一帧的顶端（窗口坐标），没建它时是无穷大
	list := ui.List(c, &ch.list, rows, func(row int) {
		if ch.paging.built(row) && ch.Earlier.idle() {
			c.Invalidate()
		}
		e := ch.column(c)
		if row == 0 {
			top = e.Bounds().Y
		}
		e.Children(func() { ch.row(c, row, rows) })
	})
	list.Children(func() {
		if rows == 0 && (ch.Header != nil || ch.Earlier.shown()) {
			ch.column(c).Children(func() { ch.header(c) })
		}
	})
	first, last := ch.list.Visible()
	ch.scrolled = last >= first && (first > 0 || top < list.Bounds().Y+listTop+ch.Top-0.5)
	// 照 Compose 从顶上往下排；内容多了跟到底部靠 FollowEnd
	return list.Padding(listTop+ch.Top, 24, 20, 24).Label("对话内容")
}

// paging 认出用户往上翻到了列表顶上附近，好自动载入更早的记录。列表只建看得见的行（和上下几行），所以比较相邻两帧
// 建的最上面一行：行数没变、最上面一行往上走了，就是往上翻了（滚轮、触控板、滚动条、翻页键都算）；数据插进来时行数变了，
// 跟随底部只往下走，都不算。认出来的那一帧在建列表，不能当场让调用方改条目，所以记下、要一帧，在下一帧开头载入。
type paging struct {
	top, rows         int // 这一帧建的最上面一行，这一帧的行数
	lastTop, lastRows int // 上一帧的；lastTop 为 -1 表示上一帧没建行
	due               bool
}

// next 开始新的一帧：返回上一帧是否认出了往上翻到顶。
func (p *paging) next(rows int) bool {
	due := p.due
	p.due = false
	p.lastTop, p.lastRows = p.top, p.rows
	if p.lastTop == math.MaxInt {
		p.lastTop = -1
	}
	p.top, p.rows = math.MaxInt, rows
	return due
}

// built 记下这一帧建了第 row 行；它报告刚认出往上翻到了顶上附近，要下一帧来载入。
func (p *paging) built(row int) bool {
	p.top = min(p.top, row)
	if p.due || row > loadAhead || p.rows != p.lastRows || row >= p.lastTop {
		return false
	}
	p.due = true
	return true
}

// earlierRow 是列表顶上「更早的记录」那一行：平时是一句可以点的「载入更早的 N 条记录」（往上翻到顶附近时会自己载入，
// 内容不满一屏、翻不动时点它），载入中是转圈，没读到时说明原因，能重试的点一下重试。
func (ch *Chat) earlierRow(c *ui.Context) {
	t := c.Theme()
	e := ch.Earlier
	row := ui.Row(c).FillWidth().Height(44).Justify(ui.Center).AlignItems(ui.Center).Gap(8)
	row.Children(func() {
		switch {
		case e.Loading:
			ui.Spinner(c).Size(16, 16)
			ui.Text(c, "正在载入更早的记录").FontSize(12.5).TextColor(t.TextMuted)
		case e.Failed != "":
			text := e.Failed
			if e.Retry {
				text += "，点按重试"
			}
			ui.Text(c, text).FontSize(12.5).TextColor(t.TextMuted).MaxLines(2).TextAlign(ui.Center)
		default:
			ui.Text(c, "载入更早的 "+strconv.Itoa(e.Skipped)+" 条记录").FontSize(12.5).TextColor(t.Accent)
		}
	})
	if e.idle() && ch.LoadEarlier != nil {
		// 点了在这一帧建完之后再载入：调用方可能当场改条目，不能改在列表建到一半时
		row.Cursor(ui.CursorPointer).Role(ui.RoleButton).OnClick(ch.LoadEarlier)
	}
}

// item 画第 row 个条目；不认识的种类不画。
func (ch *Chat) item(c *ui.Context, row int) {
	it := ch.items[row]
	switch it.Kind {
	case items.User:
		if it.Peer != "" {
			ch.peerMessage(c, it)
		} else {
			ch.userMessage(c, it, row)
		}
	case items.Answer:
		ch.answerBlock(c, it, row)
	case items.Thought:
		ch.thoughtBlock(c, it, row)
	case items.Notice:
		noticeRow(c, it)
		ch.actionRow(c, row)
	case items.Work:
		ch.workGroup(c, it)
		ch.actionRow(c, row)
	}
}

// flag 是一张折叠卡展开与否，第一次问时是 def。
func (ch *Chat) flag(key string, def bool) *bool {
	p, ok := ch.open[key]
	if !ok {
		p = &def
		ch.open[key] = p
	}
	return p
}
