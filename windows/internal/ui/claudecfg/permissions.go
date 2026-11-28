package claudecfg

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 工具权限：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow、ask、deny）。每个内置工具、每个 MCP
// 服务器能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在“规则”里，可以改、删、加。每个状态都由那边按规则算好。

type permState struct {
	cwd      string // MCP 查询用的目录，不改变持久权限规则的用户级作用域
	data     *coreapi.Permissions
	loadErr  string
	working  bool
	showMore bool
	bulk     *bulkRequest
	edit     *ruleEdit
	deleting *coreapi.Rule

	server    coreapi.ServerView // 点进去的那个 MCP 服务器
	tools     []coreapi.McpTool
	toolsBusy bool
	toolsErr  string
}

// bulkRequest 是点了“全部开启”或“全部关闭”、等确认的那一组（group.Server 为空是内置工具）。
type bulkRequest struct {
	group  coreapi.PermissionGroup
	enable bool
	change coreapi.BulkChange
	open   bool
}

// ruleEdit 是添加或编辑规则的对话框；original 为 nil 是添加。
type ruleEdit struct {
	original *coreapi.Rule
	behavior coreapi.Behavior
	text     string
	problem  string
	saving   bool
	open     bool
}

// choiceValues 是工具一行右边下拉的四项。
var choiceValues = append([]coreapi.Behavior{""}, behaviors...)

// choiceLabels 是 choiceValues 按界面当前的语言的说法，一一对应。
func choiceLabels() []string {
	var out []string
	for _, b := range choiceValues {
		out = append(out, behaviorLabel(b))
	}
	return out
}

// OpenPermissions 打开工具权限页，按 cwd 查 MCP 服务器和工具。
func (p *Page) OpenPermissions(cwd string) {
	p.push(viewPermissions)
	p.perm = permState{cwd: cwd}
	p.reloadPermissions()
}

// permView 是权限页怎么算：按打开时指定的工作目录列 MCP 服务器；在单个服务器页上时另算它的工具。
func (p *Page) permView() coreapi.PermissionView {
	if p.top() != viewPermServer || p.perm.tools == nil {
		return coreapi.PermissionView{Cwd: p.perm.cwd}
	}
	names := make([]string, 0, len(p.perm.tools))
	for _, t := range p.perm.tools {
		names = append(names, t.Name)
	}
	return coreapi.PermissionView{Cwd: p.perm.cwd, Server: p.perm.server.Name, Tools: names}
}

func (p *Page) reloadPermissions() {
	p.applyPermissions(func(ctx context.Context, pv coreapi.PermissionView) (coreapi.Permissions, error) {
		return p.opts.Core.Permissions(ctx, pv)
	}, nil)
}

// applyPermissions 在后台改规则（或只读），拿回新的页面。done 不为 nil 时成败都交给它（失败时页面不变），为 nil 时出错弹出来。
func (p *Page) applyPermissions(op func(context.Context, coreapi.PermissionView) (coreapi.Permissions, error), done func(error)) {
	s := &p.perm
	pv := p.permView()
	s.working = true
	p.async(func(ctx context.Context) func() {
		v, err := op(ctx, pv)
		return func() {
			s.working = false
			if err == nil {
				s.data, s.loadErr = &v, ""
				p.syncServer()
			}
			switch {
			case done != nil:
				done(err)
			case err == nil:
			case s.data == nil:
				s.loadErr = err.Error()
			default:
				p.failed(locales.ClaudecfgChangeFailed(), err)
			}
		}
	})
}

// syncServer 让点进去的那个服务器跟上新读到的状态。
func (p *Page) syncServer() {
	for _, sv := range p.perm.data.Servers {
		if sv.Name == p.perm.server.Name {
			p.perm.server = sv
		}
	}
}

func (p *Page) setTool(rule string, b coreapi.Behavior) {
	p.applyPermissions(func(ctx context.Context, pv coreapi.PermissionView) (coreapi.Permissions, error) {
		return p.opts.Core.SetToolPermission(ctx, pv, rule, b)
	}, nil)
}

// toolSummary 是一行的说明：有继承来的设置（fromServer 时是从整个服务器）、带参数的规则时写这些，没有时写 fallback。
func toolSummary(fallback string, st coreapi.ToolState, fromServer bool) string {
	var parts []string
	if st.Inherited && st.Behavior != "" {
		inherited := locales.ClaudecfgPermInherited(behaviorLabel(st.Behavior))
		if fromServer {
			inherited = locales.ClaudecfgPermInheritedServer(behaviorLabel(st.Behavior))
		}
		parts = append(parts, inherited)
	}
	if n := len(st.Specific); n > 0 {
		parts = append(parts, locales.ClaudecfgPermSpecific(n))
	}
	if len(parts) == 0 {
		return fallback
	}
	return strings.Join(parts, " · ")
}

// toolRow 是一个工具（或整个服务器）一行，右边下拉选 未设置、允许、询问、拒绝；显示的是它自己的规则。
func (p *Page) toolRow(l *widget.List, title, summary, rule string, own coreapi.Behavior) {
	c := l.Context()
	editable := p.perm.data != nil && !p.perm.working
	ui.Column(c.Key(rule)).Children(func() {
		item(l, title, summary, func() {
			selected := 0
			for i, b := range choiceValues {
				if b == own {
					selected = i
				}
			}
			choice(c, rule, title, choiceLabels(), selected, !editable, func(i int) {
				if choiceValues[i] != own {
					p.setTool(rule, choiceValues[i])
				}
			})
		})
	})
}

func (p *Page) permissionsPage(c *ui.Context) {
	s := &p.perm
	pageTitle(c, locales.ClaudecfgPermissions(), p.back, func() {
		widget.SmallButton(c, locales.ClaudecfgPermReload()).Disabled(s.working).OnClick(p.reloadPermissions)
		widget.SmallPrimaryButton(c, locales.ClaudecfgPermAddRule()).Disabled(s.data == nil || s.working).OnClick(func() { p.openRule(nil) })
	})
	body(c, "permissions", func() {
		intro := locales.ClaudecfgPermissionsIntro()
		if s.loadErr != "" {
			intro += "\n\n" + locales.ClaudecfgLoadFailed(s.loadErr)
		}
		muted(c, intro)
		if s.data == nil {
			if s.loadErr == "" {
				widget.Group(c, "", "", func(l *widget.List) { loadingRow(l, locales.CommonLoading()) })
			}
			return
		}
		p.builtinSection(c)
		p.serversSection(c)
		p.rulesSection(c)
	})
	p.permDialogs(c)
}

func (p *Page) builtinSection(c *ui.Context) {
	s := &p.perm
	d := s.data
	widget.Group(c, locales.ClaudecfgPermBuiltin(), "", func(l *widget.List) {
		p.bulkButtons(l, d.BuiltinGroup(), d.BuiltinBulk)
		more := 0
		for _, t := range d.Builtin {
			if t.More {
				more++
				continue
			}
			p.toolRow(l, t.Name, toolSummary(t.Label, t.State, true), t.Rule, t.Own)
		}
		if !s.showMore && more > 0 {
			item(l, locales.ClaudecfgPermMore(), "", func() {
				ui.Text(c, locales.ClaudecfgPermMoreCount(more)).FontSize(widget.SizeBodyM).TextColor(widget.Of(c).Tertiary)
				widget.SmallButton(c, locales.ClaudecfgPermShowMore()).Label(locales.ClaudecfgPermShowMoreLabel()).OnClick(func() { s.showMore = true })
			})
		}
	})
	if s.showMore {
		widget.Group(c, locales.ClaudecfgPermMore(), "", func(l *widget.List) {
			for _, t := range d.Builtin {
				if t.More {
					p.toolRow(l, t.Name, toolSummary(t.Label, t.State, true), t.Rule, t.Own)
				}
			}
		})
	}
}

// bulkButtons 是一组上面的一对按钮；已经全是那个状态（没什么可加可删）时置灰。
func (p *Page) bulkButtons(l *widget.List, g coreapi.PermissionGroup, pair coreapi.BulkPair) {
	disabled := p.perm.data == nil || p.perm.working
	ask := func(enable bool, change coreapi.BulkChange) {
		p.perm.bulk = &bulkRequest{group: g, enable: enable, change: change, open: true}
	}
	c := l.Context()
	l.Custom(func() {
		widget.SmallButton(c, locales.ClaudecfgPermEnableAll()).Label(bulkLabel(g, true)).Disabled(disabled || pair.Enable.Empty()).OnClick(func() { ask(true, pair.Enable) })
		widget.SmallButton(c, locales.ClaudecfgPermDisableAll()).Label(bulkLabel(g, false)).Disabled(disabled || pair.Disable.Empty()).OnClick(func() { ask(false, pair.Disable) })
	})
}

// bulkLabel 是全部开启（enable）或关闭一组的说法：按钮的读屏名字，加问号是确认框的标题。
func bulkLabel(g coreapi.PermissionGroup, enable bool) string {
	switch {
	case g.Server == "" && enable:
		return locales.ClaudecfgPermEnableAllBuiltin()
	case g.Server == "":
		return locales.ClaudecfgPermDisableAllBuiltin()
	case enable:
		return locales.ClaudecfgPermEnableAllServer(g.Server)
	}
	return locales.ClaudecfgPermDisableAllServer(g.Server)
}

// bulkMessage 是确认框里的说明：按表列出会加、会删的规则；关掉全部内置工具时另外说清后果。
func bulkMessage(change coreapi.BulkChange, warnNoTools bool) string {
	var b strings.Builder
	list := func(line func(count int, behavior, rules string) string, rules []coreapi.Rule) {
		for _, be := range behaviors {
			var texts []string
			for _, r := range rules {
				if r.Behavior == be {
					texts = append(texts, r.Text)
				}
			}
			if len(texts) > 0 {
				b.WriteString(line(len(texts), behaviorLabel(be), strings.Join(texts, locales.ClaudecfgListSeparator())) + "\n")
			}
		}
	}
	list(locales.ClaudecfgPermBulkAdd, change.Add)
	list(locales.ClaudecfgPermBulkRemove, change.Remove)
	if warnNoTools {
		b.WriteString("\n" + locales.ClaudecfgPermNoToolsWarning())
	}
	return strings.TrimRight(b.String(), "\n")
}

func (p *Page) serversSection(c *ui.Context) {
	d := p.perm.data
	widget.Group(c, locales.ClaudecfgMcpServers(), "", func(l *widget.List) {
		if len(d.Servers) == 0 {
			emptyRow(l, locales.ClaudecfgPermNoServers(), "")
		}
		for _, sv := range d.Servers {
			ui.Column(c.Key("server-" + sv.Name)).Children(func() {
				entry(l, sv.Name, sv.Name, toolSummary(sv.Rule, sv.State, false), behaviorLabel(sv.State.Behavior), true, func() { p.openPermServer(sv) })
			})
		}
	})
}

func (p *Page) rulesSection(c *ui.Context) {
	s := &p.perm
	widget.Group(c, locales.ClaudecfgPermRulesCount(len(s.data.Rules)), locales.ClaudecfgPermRulesFooter(), func(l *widget.List) {
		if len(s.data.Rules) == 0 {
			emptyRow(l, locales.ClaudecfgPermRulesEmpty(), locales.ClaudecfgPermRulesEmptyHint())
		}
		for _, r := range s.data.Rules {
			summary := behaviorLabel(r.Behavior)
			if r.Malformed {
				summary = locales.ClaudecfgPermRuleMalformed(summary)
			}
			ui.Column(c.Key(string(r.Behavior) + "/" + r.Text)).Children(func() {
				item(l, r.Text, summary, func() {
					widget.SmallButton(c, locales.CommonEdit()).Label(locales.ClaudecfgPermEditRule(r.Text)).Disabled(s.working).OnClick(func() { p.openRule(&r.Rule) })
					widget.SmallDangerButton(c, locales.CommonDelete()).Label(locales.ClaudecfgDeleteItem(r.Text)).Disabled(s.working).OnClick(func() { s.deleting = &r.Rule })
				})
			})
		}
	})
}

func (p *Page) openRule(r *coreapi.Rule) {
	e := &ruleEdit{original: r, behavior: coreapi.Allow, open: true}
	if r != nil {
		e.behavior, e.text = r.Behavior, r.Text
	}
	p.perm.edit = e
}

func (p *Page) saveRule() {
	e := p.perm.edit
	nw := coreapi.Rule{Behavior: e.behavior, Text: strings.TrimSpace(e.text)}
	if nw.Text == "" {
		e.problem = locales.ClaudecfgPermRuleEmpty()
		return
	}
	original := e.original
	e.saving, e.problem = true, ""
	p.applyPermissions(func(ctx context.Context, pv coreapi.PermissionView) (coreapi.Permissions, error) {
		if original == nil {
			return p.opts.Core.AddPermissionRule(ctx, pv, nw)
		}
		return p.opts.Core.ReplacePermissionRule(ctx, pv, *original, nw)
	}, func(err error) {
		e.saving = false
		if err != nil {
			e.problem = err.Error()
			return
		}
		e.open = false
	})
}

func (p *Page) permDialogs(c *ui.Context) {
	s := &p.perm
	if e := s.edit; e != nil {
		p.ruleDialog(c, e)
		if !e.open {
			s.edit = nil
		}
	}
	if r := s.deleting; r != nil {
		open := true
		if alert(c, &open, locales.ClaudecfgPermDeleteRule(), locales.ClaudecfgPermDeleteRuleMessage(behaviorLabel(r.Behavior), r.Text),
			widget.Destructive, locales.CommonCancel(), locales.CommonDelete()) == 1 {
			rule := *r
			p.applyPermissions(func(ctx context.Context, pv coreapi.PermissionView) (coreapi.Permissions, error) {
				return p.opts.Core.RemovePermissionRule(ctx, pv, rule)
			}, nil)
		}
		if !open {
			s.deleting = nil
		}
	}
	p.bulkDialog(c)
}

func (p *Page) ruleDialog(c *ui.Context, e *ruleEdit) {
	title := locales.ClaudecfgPermAddRule()
	if e.original != nil {
		title = locales.ClaudecfgPermEditRuleTitle()
	}
	dialog(c, &e.open, title, func() {
		labels := []string{}
		selected := 0
		for i, b := range behaviors {
			labels = append(labels, behaviorLabel(b))
			if b == e.behavior {
				selected = i
			}
		}
		labeled(c, locales.ClaudecfgPermList(), func() {
			choice(c, "list", locales.ClaudecfgPermList(), labels, selected, e.saving, func(i int) { e.behavior = behaviors[i] })
		})
		placeholder, help := "Bash(git push:*)", locales.ClaudecfgPermRuleHelp()
		if p.isAgy() {
			placeholder, help = "command(git status)", locales.ClaudecfgAgyPermRuleHelp()
		}
		widget.LineInput(c.Key("rule"), &e.text, 1).Placeholder(placeholder).Label(locales.ClaudecfgPermRule()).Disabled(e.saving).AutoFocus()
		ui.Text(c, help).
			FontSize(widget.SizeBodyXS).LineHeight(1.4).TextColor(widget.Of(c).Secondary)
		if e.problem != "" {
			problemText(c, e.problem)
		}
	}, func() {
		widget.DialogButton(c, locales.CommonCancel(), widget.Plain).Disabled(e.saving).OnClick(func() { e.open = false })
		label := locales.CommonSave()
		if e.saving {
			label = locales.ClaudecfgPermSaving()
		}
		widget.DialogButton(c, label, widget.Recommend).Disabled(e.saving).OnClick(p.saveRule)
	})
}

func (p *Page) bulkDialog(c *ui.Context) {
	s := &p.perm
	r := s.bulk
	if r == nil {
		return
	}
	verb := locales.ClaudecfgPermEnableAll()
	if !r.enable {
		verb = locales.ClaudecfgPermDisableAll()
	}
	warn := !r.enable && r.group.Server == ""
	style := widget.Recommend
	if !r.enable {
		style = widget.Destructive
	}
	if alert(c, &r.open, locales.ClaudecfgConfirmQuestion(bulkLabel(r.group, r.enable)), bulkMessage(r.change, warn), style,
		locales.CommonCancel(), verb) == 1 {
		g, enable := r.group, r.enable
		p.applyPermissions(func(ctx context.Context, pv coreapi.PermissionView) (coreapi.Permissions, error) {
			return p.opts.Core.SetPermissionGroup(ctx, pv, g, enable)
		}, nil)
	}
	if !r.open {
		s.bulk = nil
	}
}

// ---------- 一个 MCP 服务器 ----------

func (p *Page) openPermServer(sv coreapi.ServerView) {
	s := &p.perm
	s.server, s.tools, s.toolsErr = sv, nil, ""
	p.push(viewPermServer)
	// 和 MCP 页一样：项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令
	if sv.Scope != "project" && !sv.OAuth {
		p.loadServerTools()
	}
}

func (p *Page) loadServerTools() {
	s := &p.perm
	server := coreapi.McpServer{Name: s.server.Name, Scope: s.server.Scope}
	cwd := s.cwd
	s.toolsBusy, s.toolsErr = true, ""
	p.async(func(ctx context.Context) func() {
		tools, err := p.opts.Core.McpTools(ctx, cwd, server)
		return func() {
			s.toolsBusy = false
			if err != nil {
				s.toolsErr = err.Error()
				return
			}
			if tools == nil {
				tools = []coreapi.McpTool{}
			}
			s.tools = tools
			p.reloadPermissions() // 算这些工具的状态
		}
	})
}

// serverTool 是新读到的页面里这个工具的状态。
func (p *Page) serverTool(name string) (coreapi.ToolView, bool) {
	if p.perm.data == nil {
		return coreapi.ToolView{}, false
	}
	for _, t := range p.perm.data.ServerTools {
		if t.Name == name {
			return t, true
		}
	}
	return coreapi.ToolView{}, false
}

func (p *Page) permServerPage(c *ui.Context) {
	s := &p.perm
	sv := s.server
	pageTitle(c, sv.Name, p.back, func() {
		widget.SmallButton(c, locales.ClaudecfgMcpRefreshTools()).Disabled(s.toolsBusy || sv.OAuth).OnClick(p.loadServerTools)
	})
	body(c, "perm-server", func() {
		widget.Group(c, "", locales.ClaudecfgPermServerFooter(), func(l *widget.List) {
			p.bulkButtons(l, coreapi.PermissionGroup{Server: sv.Name}, sv.Bulk)
			p.toolRow(l, locales.ClaudecfgPermWholeServer(), sv.Rule, sv.Rule, sv.State.Behavior)
		})
		title := locales.ClaudecfgTools()
		if s.tools != nil {
			title = locales.ClaudecfgToolsCount(len(s.tools))
		}
		footer := ""
		if sv.OAuth {
			footer = locales.ClaudecfgPermServerOAuth(sv.Rule)
		}
		widget.Group(c, title, footer, p.serverToolRows)
	})
	p.permDialogs(c)
}

func (p *Page) serverToolRows(l *widget.List) {
	c := l.Context()
	s := &p.perm
	switch {
	case s.toolsBusy:
		loadingRow(l, locales.CommonConnecting())
	case s.toolsErr != "":
		item(l, locales.ClaudecfgToolsListFailed(), s.toolsErr, func() { widget.SmallButton(c, locales.CommonRetry()).OnClick(p.loadServerTools) })
	case s.tools == nil:
		summary := locales.ClaudecfgToolsConnectHint()
		if s.server.OAuth {
			summary = locales.ClaudecfgToolsOAuth()
		}
		item(l, locales.ClaudecfgToolsList(), summary, func() {
			widget.SmallButton(c, locales.ClaudecfgToolsListButton()).Disabled(s.server.OAuth).OnClick(p.loadServerTools)
		})
	case len(s.tools) == 0:
		emptyRow(l, locales.ClaudecfgToolsNone(), "")
	}
	for _, tool := range s.tools {
		v, ok := p.serverTool(tool.Name)
		if !ok {
			item(l, tool.Name, clipLine(tool.Description, 60), nil)
			continue
		}
		p.toolRow(l, tool.Name, toolSummary(clipLine(tool.Description, 60), v.State, true), v.Rule, v.Own)
	}
}
