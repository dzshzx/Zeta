// Package claudecfg 是设置里一台设备上和 Claude Code 有关的界面，做成能嵌进设置窗口的组件：设备页上的三组（新对话默认、
// Claude 的自动压缩与标题、Claude Code 配置的入口），和点进去的各页（默认思考强度、记忆、Skills、MCP 服务器、Subagent、
// 工具权限）。只画、把操作交给 internal/claudecfg（那台 serve 的 config.*），要等设备的事放到后台，结果交回主线程再改状态。
package claudecfg

import (
	"context"
	"time"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/egoist/mygo/ui"
)

// Options 是嵌进来时要给的。
type Options struct {
	Client *cfg.Client  // 这台设备上的 Claude Code 配置
	Device cfg.Device   // 这台设备的自动压缩、自动标题（客户端本地存的）
	Run    func(func()) // 在后台跑
	Post   func(func()) // 交回主线程跑（窗口的 Update）
	// OpenZip 让用户选一个 Skill 的 ZIP，返回文件名和内容；取消时 name 为空。nil 时用系统的文件对话框。
	OpenZip func() (name string, data []byte, err error)
	// Models 是默认模型下拉里的选项（Claude Code 报的模型清单）；没有时列几个常用的别名。
	Models []Model
}

// Model 是一个可选的模型：Value 写进 settings.json，Label 给人看。
type Model struct {
	Value, Label string
}

// 子页。
type view int

const (
	viewEffort view = iota + 1
	viewMemory
	viewDocument
	viewSkills
	viewMcp
	viewMcpServer
	viewAgents
	viewAgent
	viewPermissions
	viewPermServer
)

// Page 是一台设备上的这部分界面的状态。
type Page struct {
	opts  Options
	stack []view // 点进去的子页，最后一个在显示

	defaults    *cfg.Defaults
	defaultsErr string
	working     bool // 正在改新对话默认

	notice *notice

	memory memoryState
	doc    documentState
	skills skillsState
	mcp    mcpState
	agents agentsState
	perm   permState
}

type notice struct {
	title, message string
	open           bool
}

// New 建这台设备的页面状态；嵌进设备页时先调 Load。
func New(opts Options) *Page {
	if opts.OpenZip == nil {
		opts.OpenZip = openZipDialog
	}
	return &Page{opts: opts}
}

// Load 读这台的新对话默认（可能在那边的终端里改过）：打开设备页时调。
func (p *Page) Load() {
	p.defaultsErr = ""
	p.async(func(ctx context.Context) func() {
		d, err := p.opts.Client.Defaults(ctx)
		return func() {
			if err != nil {
				p.defaultsErr = err.Error()
				return
			}
			p.defaults = &d
		}
	})
}

// Open 是有子页在显示：设备页这时改画 SubPage。
func (p *Page) Open() bool { return len(p.stack) > 0 }

// timeout 是一次操作最多等多久（健康检查、取工具清单要跑 claude 或连服务器，那边自己也有时限）。
const timeout = 3 * time.Minute

// async 在后台跑 work，它返回的函数交回主线程跑。
func (p *Page) async(work func(ctx context.Context) func()) {
	p.opts.Run(func() {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		apply := work(ctx)
		cancel()
		p.opts.Post(apply)
	})
}

func (p *Page) say(title, message string) {
	p.notice = &notice{title: title, message: message, open: true}
}

// failed 弹一句没成功的原因。
func (p *Page) failed(title string, err error) {
	p.say(title, err.Error())
}

func (p *Page) push(v view) { p.stack = append(p.stack, v) }

func (p *Page) back() {
	if len(p.stack) > 0 {
		p.stack = p.stack[:len(p.stack)-1]
	}
}

func (p *Page) top() view {
	if len(p.stack) == 0 {
		return 0
	}
	return p.stack[len(p.stack)-1]
}

// SubPage 画正在显示的子页（带标题和返回），占满设备页的位置。
func (p *Page) SubPage(c *ui.Context) {
	switch p.top() {
	case viewEffort:
		p.effortPage(c)
	case viewMemory:
		p.memoryPage(c)
	case viewDocument:
		p.documentPage(c)
	case viewSkills:
		p.skillsPage(c)
	case viewMcp:
		p.mcpPage(c)
	case viewMcpServer:
		p.mcpServerPage(c)
	case viewAgents:
		p.agentsPage(c)
	case viewAgent:
		p.agentPage(c)
	case viewPermissions:
		p.permissionsPage(c)
	case viewPermServer:
		p.permServerPage(c)
	}
	p.noticeDialog(c)
}

func (p *Page) noticeDialog(c *ui.Context) {
	n := p.notice
	if n == nil {
		return
	}
	ui.AlertDialog(c, &n.open, n.title, n.message, "知道了")
	if !n.open {
		p.notice = nil
	}
}

// ---------- 和设备页同样子的小部件 ----------

// pageTitle 是子页顶上的标题，左边是返回，右边是 actions 画的按钮。
func pageTitle(c *ui.Context, title string, back func(), actions func()) {
	ui.Row(c).Height(56).Padding(0, 24).Gap(8).AlignItems(ui.Center).Children(func() {
		ui.Button(c, "返回").OnClick(back)
		ui.Text(c, title).FontSize(20).Bold().SingleLine()
		ui.Spacer(c)
		if actions != nil {
			actions()
		}
	})
}

// body 是子页的内容：可以滚动，一列分组。
func body(c *ui.Context, key string, fn func()) {
	ui.Scroll(c.Key(key)).Grow(1).Children(func() {
		ui.Column(c).Padding(0, 24, 24, 24).Gap(20).MaxWidth(720).Children(fn)
	})
}

// section 是一组：小标题、内容，下面一行说明（空的不写）。
func section(c *ui.Context, title, footer string, fn func()) {
	t := c.Theme()
	ui.Column(c).Gap(8).Children(func() {
		if title != "" {
			ui.Text(c, title).FontSize(13).Bold().TextColor(t.TextMuted)
		}
		ui.Column(c).Gap(2).Padding(4).Radius(10).Background(t.Surface).Children(fn)
		if footer != "" {
			ui.Text(c, footer).FontSize(12).TextColor(t.TextMuted)
		}
	})
}

// item 是一组里的一行：左边名字（summary 不空时下面一行小字），右边 fn 画的东西。
func item(c *ui.Context, label, summary string, fn func()) {
	t := c.Theme()
	ui.Row(c).MinHeight(40).Padding(6, 12).Gap(12).AlignItems(ui.Center).Children(func() {
		ui.Column(c).Grow(1).Shrink(1).MinWidth(0).Gap(2).Children(func() {
			ui.Text(c, label)
			if summary != "" {
				ui.Text(c, summary).FontSize(12).TextColor(t.TextMuted)
			}
		})
		if fn != nil {
			fn()
		}
	})
}

// valueItem 是右边只是一段字的一行。
func valueItem(c *ui.Context, label, value string) {
	item(c, label, "", func() { ui.Text(c, value).TextColor(c.Theme().TextMuted).SingleLine().MinWidth(0).Shrink(1) })
}

// entry 是点了进下一页的一行：右边是 value 和一个打开按钮。
func entry(c *ui.Context, label, summary, value string, enabled bool, open func()) {
	item(c, label, summary, func() {
		if value != "" {
			ui.Text(c, value).TextColor(c.Theme().TextMuted).SingleLine()
		}
		ui.Button(c.Key(label), "打开").Label("打开" + label).Disabled(!enabled).OnClick(open)
	})
}

// emptyRow 是一组里没有东西时的一行。
func emptyRow(c *ui.Context, title, summary string) {
	item(c, title, summary, nil)
}

// loadingRow 是“正在读取”的一行，右边转圈。
func loadingRow(c *ui.Context, text string) {
	item(c, text, "", func() { ui.Spinner(c) })
}

// choice 是一个下拉：labels 是给人看的，选了第 i 个交给 pick。
func choice(c *ui.Context, key string, labels []string, selected int, disabled bool, pick func(int)) {
	cur := ""
	if selected >= 0 && selected < len(labels) {
		cur = labels[selected]
	}
	chosen := cur
	ui.Select(c.Key(key), &chosen, labels).Label(key).Disabled(disabled)
	if chosen != cur {
		for i, l := range labels {
			if l == chosen {
				pick(i)
				break
			}
		}
	}
}
