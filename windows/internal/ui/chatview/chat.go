// Package chatview 画聊天列表：用户消息、回答（Markdown，流式时逐字显现）、提示、思考与工具调用的折叠卡、
// 文件改动的红绿对比和图片。条目和各个回调由调用方给，组件自己不碰网络和文件；都在界面线程上用。
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
	// OpenSubagent：给了时，发起子代理的那一步点了调它（参数是那一步的 tool_use id），不在原地展开。
	OpenSubagent func(toolUseID string)
	// OpenLink：回答里的链接点了调它；没给时 http、https、mailto 的链接交给系统打开。
	OpenLink func(url string)
	// LoadMarkdownImage 取回答的 Markdown 里写的图片（原文里的网址或路径），返回图片文件的内容；在单独的 goroutine 里调，
	// 同一个地址只调一次。没给时这些图显示为它们的说明文字。显示前设好，之后不再换。
	LoadMarkdownImage func(url string) ([]byte, error)
	// Earlier 是更早记录的情况。
	Earlier Earlier
	// Top 是列表顶上被浮着的东西（标题栏）盖住的高度：内容从它下面开始排，往上翻时从它底下滚过去。
	Top float32
	// 消息操作（见 actions.go）：用户消息右键菜单里「复制」后面的项、挂在一轮最后一项下面的操作排、正在编辑的那条、
	// 看大图时的保存（viewer.go）。
	UserMenu  func(m *ui.Menu, it items.Item)
	Actions   func(c *ui.Context, id string)
	Editing   string
	SaveImage func(data []byte, name string) (saved string, err error)
	viewer    viewer

	items []items.Item
	list  ui.ListState
	// answer、thinking 是回答和思考的 Markdown 设置；thinkingTheme 是思考用的淡色字。
	answer, thinking           markdown.Options
	answerTheme, thinkingTheme markdown.Theme
	// open 记着各张折叠卡展开没有（按「种类:id」），条目换掉后照样留着。
	open map[string]*bool
	// changes 是各步文件改动的对比（按步骤的 id），条目换掉时丢掉对应的。
	changes map[string]*changeView
	images  imageCache
	paging  paging
	// scrolled：上一帧内容往上滚过了（最上面一行不是第一条，或第一条的顶端到了内容区上沿以上）。
	scrolled bool
}

// loadAhead：往上翻到离顶这么多行以内时载入更早的。
const loadAhead = 3

// New 返回一个空的聊天列表：一打开就在底部，跟着新内容走。每个对话用一个，换对话时换一个新的
// （列表的位置、折叠卡的展开与否、取过的图都跟着对话走）。
func New() *Chat {
	ch := &Chat{open: map[string]*bool{}, changes: map[string]*changeView{}}
	ch.list.FollowEnd = true
	ch.list.FollowAnimation = &ui.Smooth
	ch.list.Key = func(row int) any { return ch.items[row].ID }
	ch.thinking.Theme = &ch.thinkingTheme
	ch.answer.Theme = &ch.answerTheme
	ch.answer.CodeBlock = codeBlock
	ch.thinking.CodeBlock = codeBlock
	ch.answer.LoadImage = ch.markdownImage
	return ch
}

// markdownImage 是回答里 Markdown 图片的载入：交给调用方取数据，在这里解码。
func (ch *Chat) markdownImage(url string) (ui.ImageSource, error) {
	if ch.LoadMarkdownImage == nil {
		return nil, errNoLoader
	}
	data, err := ch.LoadMarkdownImage(url)
	if err != nil {
		return nil, err
	}
	return ui.DecodeBitmap(data)
}

// Items 返回现在显示的条目。
func (ch *Chat) Items() []items.Item { return ch.items }

// SetItems 整份换掉条目（打开会话、重新取了一份时）。折叠卡展开与否按 id 留着。
func (ch *Chat) SetItems(list []items.Item) {
	ch.items = list
	clear(ch.changes)
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
	ch.items = next
	return nil
}

// forget 丢掉一个条目里各步算好的对比，下次显示时照新内容重算。
func (ch *Chat) forget(it items.Item) {
	for _, s := range it.Steps {
		delete(ch.changes, s.ID)
	}
}

// Scrolled 说内容是不是往上滚过了：顶上浮着的标题栏这时才透出底下模糊的内容（Compose 的 TopAppBarGlow）。
func (ch *Chat) Scrolled() bool { return ch.scrolled }

// ScrollToEnd 回到底部并跟着新内容走（发消息、点「回到底部」时）。
func (ch *Chat) ScrollToEnd() { ch.list.ScrollToEnd() }

// AtEnd 报告上一帧显示着底部；不在底部时调用方可以显示「回到底部」。
func (ch *Chat) AtEnd() bool { return ch.list.AtEnd() }

// listTop 是列表内容上面留的空（Top 之外）。
const listTop = 12

// 内容最宽这么多，窗口再宽也居中，免得一行字太长。
const contentWidth = 860

// View 画聊天列表，返回列表的元素，由调用方定大小（通常 Grow(1)）。
func (ch *Chat) View(c *ui.Context) ui.Element {
	rows := ch.shownRows()
	if ch.paging.next(rows) && ch.Earlier.idle() && ch.LoadEarlier != nil {
		ch.LoadEarlier()
	}
	ch.answer.OnLink = ch.OpenLink
	ch.thinking.OnLink = ch.OpenLink
	styleMarkdown(c, &ch.answerTheme, &ch.thinkingTheme)
	top := float32(math.Inf(1)) // 第一条上一帧的顶端（窗口坐标），没建它时是无穷大
	list := ui.List(c, &ch.list, rows, func(row int) {
		if ch.paging.built(row) && ch.Earlier.idle() {
			c.Invalidate()
		}
		e := ui.Column(c).FillWidth().MaxWidth(contentWidth).AlignSelf(ui.Center)
		if row == 0 {
			top = e.Bounds().Y
		}
		e.Children(func() {
			// 「更早的记录」画在第一条的上面、同一行里，不单占一行：列表按看得见的第一行记位置，
			// 它要是单占一行，翻到最顶上时就是它，接上的一页会把正看着的那条往下推走
			if row == 0 && ch.Earlier.shown() {
				ch.earlierRow(c)
			}
			ch.item(c, ch.items[row])
		})
	})
	list.Children(func() {
		if len(ch.items) == 0 && ch.Earlier.shown() {
			ui.Column(c).FillWidth().Children(func() { ch.earlierRow(c) })
		}
	})
	ch.viewImages(c)
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

// item 画一个条目；不认识的种类不画。
func (ch *Chat) item(c *ui.Context, it items.Item) {
	switch it.Kind {
	case items.User:
		ch.userMessage(c, it)
	case items.Answer:
		ch.answerBlock(c, it)
	case items.Notice:
		noticeRow(c, it)
		ch.actionRow(c, it.ID)
	case items.Work:
		ch.workGroup(c, it)
		ch.actionRow(c, it.ID)
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
