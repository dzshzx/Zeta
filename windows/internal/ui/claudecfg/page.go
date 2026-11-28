// Package claudecfg 是设置里一台设备上和 Claude Code 有关的界面，做成能嵌进设置窗口的组件：设备页上的三组（新对话默认、
// Claude 的自动压缩与标题、Claude Code 配置的入口），和点进去的各页（默认思考强度、记忆、Skills、MCP 服务器、Subagent、
// 工具权限）。只画、把操作经 helper/coreapi 交给那台的核心（config.*、system.idle），要等设备的事放到后台，结果交回主线程再改状态。
package claudecfg

import (
	"context"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	cfg "github.com/dzshzx/Zeta/hub/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// Options 是嵌进来时要给的。
type Options struct {
	Core    *coreapi.Client // 这台设备的核心：配置（config.*）、闲置超时（system.idle）
	Harness string          // "claude"（默认）或 "agy"
	Cwd     string          // 这台设备的默认工作目录（记忆的默认项目、MCP 的本地配置按它找），空是那边的家目录
	Device  cfg.Device      // 这台设备的自动压缩、自动标题（客户端本地存的）
	Run     func(func())    // 在后台跑
	Post    func(func())    // 交回主线程跑（窗口的 Update）
	// OpenZip 让用户选一个 Skill 的 ZIP，返回文件名和内容；取消时 name 为空。nil 时用系统的文件对话框。
	OpenZip        func() (name string, data []byte, err error)
	OnDefaultModel func(string) // 成功保存默认模型后通知聊天菜单；可以为 nil
}

// Model 是一个可选的模型：Value 写进 settings.json，Label 给人看；Older 是旧版本，收在「更多模型」里。
// ID 是思考强度按它设的完整模型 ID（别名 opus 对应 claude-opus-5-5，中转就是 Value）；不知道时为空，思考强度页不列。
type Model struct {
	Value, Label, ID string
	Older            bool
}

// ModelSourceKind 是这台设备的模型从哪来，决定默认模型下拉和思考强度页怎么列。
type ModelSourceKind int

const (
	ModelSourceUnknown ModelSourceKind = iota // 还不知道（没连上、正在读、读不到）：默认模型不能改
	ModelSourceHarness                        // Claude Code（账号）：清单是 Claude Code 报的或常用的别名，「默认」是它内置的默认
	ModelSourceRelay                          // 中转：清单是中转报的具体 ID
)

// SetModelChoices 换上默认模型下拉的选项（Older 的收进「更多模型」）、模型来源和是否还在读清单。
func (p *Page) SetModelChoices(models []Model, src ModelSourceKind, pending bool) {
	p.models, p.modelSource, p.modelsPending = models, src, pending
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
	viewAgySettings
	viewPlugins
	viewHooks
	viewSidecars
)

// Page 是一台设备上的这部分界面的状态。
type Page struct {
	opts  Options
	stack []view // 点进去的子页，最后一个在显示

	defaults      *coreapi.Defaults
	defaultsErr   string
	defaultsRound uint64
	models        []Model         // 默认模型下拉的选项（SetModelChoices）
	modelSource   ModelSourceKind // 模型从哪来
	modelsPending bool            // 还在读设备的模型清单
	working       bool            // 正在改新对话默认
	idle          *int            // 这台主机的闲置超时（分钟），还没读到时是 nil
	claude        claudeState

	notice *notice

	memory   memoryState
	doc      documentState
	skills   skillsState
	mcp      mcpState
	agents   agentsState
	perm     permState
	agySet   agySettingsState
	plugins  pluginsState
	hooks    hooksState
	sidecars sidecarsState
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
	return &Page{opts: opts, modelSource: ModelSourceHarness}
}

// Load 读这台的新对话默认（可能在那边的终端里改过）和闲置超时：打开设备页时调。读不到闲置超时（那边的 Zeta 服务比这个旧）时那一项灰着。
func (p *Page) Load() {
	p.RefreshDefaults()
	p.loadClaude()
	p.async(func(ctx context.Context) func() {
		m, err := p.opts.Core.IdleMinutes(ctx)
		return func() {
			if err == nil {
				p.idle = &m
			}
		}
	})
}

// RefreshDefaults 重读生效的默认值；较早的读取或写入回包不能覆盖它。
func (p *Page) RefreshDefaults() {
	p.defaults, p.defaultsErr = nil, ""
	p.defaultsRound++
	round := p.defaultsRound
	p.async(func(ctx context.Context) func() {
		d, err := p.opts.Core.Defaults(ctx)
		return func() {
			if p.defaultsRound != round {
				return
			}
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

func (p *Page) isAgy() bool {
	return sessionproto.NormalizeHarness(p.opts.Harness) == sessionproto.HarnessAgy
}

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
	case viewAgySettings:
		p.agySettingsPage(c)
	case viewPlugins:
		p.pluginsPage(c)
	case viewHooks:
		p.hooksPage(c)
	case viewSidecars:
		p.sidecarsPage(c)
	}
	p.noticeDialog(c)
}

func (p *Page) noticeDialog(c *ui.Context) {
	n := p.notice
	if n == nil {
		return
	}
	widget.Alert(c, &n.open, n.title, n.message, widget.Action{Label: locales.ClaudecfgOK(), Style: widget.Recommend})
	if !n.open {
		p.notice = nil
	}
}

// ---------- 和设备页同样子的小部件（COUI 的设置页：顶栏、卡片分组、列表行） ----------

// pageTitle 是子页顶上的标题（widget.Header 的样子，同时是标题栏），左边是浮起的圆形返回键，右边是 actions 画的按钮
// （用 widget.SmallButton 这类小按钮）。
func pageTitle(c *ui.Context, title string, back func(), actions func()) {
	k := widget.Of(c)
	widget.TitleRow(c).Children(func() {
		widget.IconButton(c, widget.IconBack, locales.WidgetBack()).OnClick(back)
		ui.Text(c, title).FontSize(widget.SizeHeadlineS).FontWeight(widget.Medium).TextColor(k.Text).SingleLine().Ellipsis("…").
			Shrink(1)
		ui.Spacer(c)
		if actions != nil {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Shrink(0).Children(actions)
		}
	})
}

// body 是子页的内容：卡片后面的浅灰底，能滚动，一组组卡片。
func body(c *ui.Context, key string, fn func()) {
	widget.Body(c, key, fn)
}

// item 是一组里的一行：左边名字（summary 不空时下面一行小字），右边 fn 画的东西。
func item(l *widget.List, label, summary string, fn func()) {
	l.Row(widget.Row{Title: label, Summary: summary, End: fn})
}

// valueItem 是右边只是一段字的一行。
func valueItem(l *widget.List, label, value string) {
	l.Row(widget.Row{Title: label, Value: value})
}

// entry 是点了进下一页的一行：右边是 value 和箭头，整行能点，读屏名字是「打开」加名字。key 是不随语言变的元素名。
func entry(l *widget.List, key, label, summary, value string, enabled bool, open func()) {
	r := widget.Row{Key: key, Title: label, Summary: summary, Value: value, Arrow: true}
	if enabled {
		r.OnClick = open
	}
	l.Row(r).Label(locales.ClaudecfgOpenEntry(label))
}

// emptyRow 是一组里没有东西时的一行。
func emptyRow(l *widget.List, title, summary string) {
	l.Row(widget.Row{Title: title, Summary: summary})
}

// loadingRow 是“正在读取”的一行，右边转圈。
func loadingRow(l *widget.List, text string) {
	c := l.Context()
	l.Row(widget.Row{Title: text, End: func() { ui.Spinner(c).Size(18, 18).Label(text) }})
}

// choice 是行右边的下拉（COUI 的下拉行）：labels 是给人看的，选了第 i 个交给 pick。key 是不随语言变的元素名，name 是读屏名字。
func choice(c *ui.Context, key, name string, labels []string, selected int, disabled bool, pick func(int)) {
	cur := ""
	if selected >= 0 && selected < len(labels) {
		cur = labels[selected]
	}
	widget.MenuButton(c.Key(key), func(t ui.Element) {
		widget.Dropdown(c, t, cur)
		t.Label(name).Disabled(disabled)
	}, func(m *widget.Menu) {
		for i, l := range labels {
			if m.Choice(l, i == selected) && i != selected {
				pick(i)
			}
		}
	})
}

// muted 是卡片外面一段说明（和分组下面的说明一样：12，次要文字色，离两边 32）。
func muted(c *ui.Context, text string) ui.Element {
	return ui.Text(c, text).FontSize(widget.SizeBodyXS).LineHeight(1.4).TextColor(widget.Of(c).Secondary).Padding(0, 32, 12, 32)
}

// field 是卡片里的一个输入框（一行），占满一行。
func field(l *widget.List, key string, value *string, label, placeholder string, disabled bool) ui.Element {
	c := l.Context()
	var in ui.Element
	l.Custom(func() {
		in = widget.Input(c.Key(key), value).Placeholder(placeholder).Label(label).Disabled(disabled).Grow(1)
	})
	return in
}

// area 是多行的输入框（widget.Input 的样子：淡灰底、平滑圆角，有焦点时主题色细边），最少 min 行、最多 max 行。
func area(c *ui.Context, value *string, minLines, maxLines int) ui.Element {
	k := widget.Of(c)
	in := ui.TextAreaBase(c, value).Lines(minLines, maxLines).Padding(8, 12).FontSize(widget.SizeBodyM).LineHeight(1.5).
		PlaceholderColor(k.Tertiary).FocusRing(false)
	surface := widget.Surface{Shape: widget.ShapeField, Fill: k.Edge}
	if in.Focused() {
		surface.Edge = k.Primary
	}
	return surface.Paint(in)
}

// dialog 是填表的对话框（widget.Dialog）：标题、fn 排的内容（离两边 24），底下 buttons 画的一排按钮
// （widget.DialogButton，靠右，主按钮最后）。
func dialog(c *ui.Context, open *bool, title string, fn, buttons func()) {
	widget.Dialog(c, open, func() {
		k := widget.Of(c)
		ui.Column(c).FillWidth().Padding(widget.DialogPadding, widget.DialogPadding, 8, widget.DialogPadding).Gap(12).Children(func() {
			ui.Text(c, title).FontSize(widget.SizeHeadlineXS).FontWeight(600).TextColor(k.Text)
			fn()
		})
		widget.ButtonBar(c, buttons)
	})
}

// labeled 是对话框里带名字的一行：左边 label，右边 fn 画的（下拉这类）。
func labeled(c *ui.Context, label string, fn func()) {
	ui.Row(c).FillWidth().MinHeight(40).Gap(12).AlignItems(ui.Center).Children(func() {
		ui.Text(c, label).FontSize(widget.SizeHeadlineXS).TextColor(widget.Of(c).Text).Grow(1)
		fn()
	})
}

// problemText 是对话框、编辑页里没做成或还差什么的一句（12，红）。
func problemText(c *ui.Context, text string) ui.Element {
	return ui.Text(c, text).FontSize(widget.SizeBodyXS).LineHeight(1.4).TextColor(widget.Of(c).Error)
}

// alert 是确认对话框（widget.Alert）：最后一个按钮是 last 的样子，前面的普通。
func alert(c *ui.Context, open *bool, title, message string, last widget.Style, labels ...string) int {
	actions := make([]widget.Action, len(labels))
	for i, l := range labels {
		actions[i] = widget.Action{Label: l}
	}
	if len(actions) > 0 {
		actions[len(actions)-1].Style = last
	}
	return widget.Alert(c, open, title, message, actions...)
}
