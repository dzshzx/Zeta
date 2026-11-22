package claudecfg

import (
	"context"
	"strings"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/egoist/mygo/ui"
)

// 工具权限：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow、ask、deny）。每个内置工具、每个 MCP
// 服务器能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在“规则”里，可以改、删、加。每个状态都由那边按规则算好。

type permState struct {
	data     *cfg.Permissions
	loadErr  string
	working  bool
	showMore bool
	bulk     *bulkRequest
	edit     *ruleEdit
	deleting *cfg.Rule

	server    cfg.ServerView // 点进去的那个 MCP 服务器
	tools     []cfg.McpTool
	toolsBusy bool
	toolsErr  string
}

// bulkRequest 是点了“全部开启”或“全部关闭”、等确认的那一组。
type bulkRequest struct {
	label  string
	group  cfg.PermissionGroup
	enable bool
	change cfg.BulkChange
	open   bool
}

// ruleEdit 是添加或编辑规则的对话框；original 为 nil 是添加。
type ruleEdit struct {
	original *cfg.Rule
	behavior cfg.Behavior
	text     string
	problem  string
	saving   bool
	open     bool
}

// choiceLabels 是工具一行右边下拉的四项，和 choiceValues 一一对应。
var (
	choiceValues = append([]cfg.Behavior{""}, cfg.Behaviors...)
	choiceLabels = func() []string {
		var out []string
		for _, b := range choiceValues {
			out = append(out, b.Label())
		}
		return out
	}()
)

func (p *Page) openPermissions() {
	p.perm = permState{}
	p.reloadPermissions()
}

// scope 是要连带算的：在一个 MCP 服务器的页上时算它的工具。
func (p *Page) permScope() cfg.PermissionScope {
	if p.top() != viewPermServer || p.perm.tools == nil {
		return cfg.PermissionScope{}
	}
	names := make([]string, 0, len(p.perm.tools))
	for _, t := range p.perm.tools {
		names = append(names, t.Name)
	}
	return cfg.PermissionScope{Server: p.perm.server.Name, Tools: names}
}

func (p *Page) reloadPermissions() {
	p.applyPermissions(func(ctx context.Context, scope cfg.PermissionScope) (cfg.Permissions, error) {
		return p.opts.Client.Permissions(ctx, scope)
	}, nil)
}

// applyPermissions 在后台改规则（或只读），拿回新的页面。done 不为 nil 时成败都交给它（失败时页面不变），为 nil 时出错弹出来。
func (p *Page) applyPermissions(op func(context.Context, cfg.PermissionScope) (cfg.Permissions, error), done func(error)) {
	s := &p.perm
	scope := p.permScope()
	s.working = true
	p.async(func(ctx context.Context) func() {
		v, err := op(ctx, scope)
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
				p.failed("没有改成", err)
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

func (p *Page) setTool(rule string, b cfg.Behavior) {
	p.applyPermissions(func(ctx context.Context, scope cfg.PermissionScope) (cfg.Permissions, error) {
		return p.opts.Client.SetToolPermission(ctx, scope, rule, b)
	}, nil)
}

// toolSummary 是一行的说明：有从服务器继承来的设置、带参数的规则时写这些，没有时写 fallback。
func toolSummary(fallback string, st cfg.ToolState, serverLabel string) string {
	var parts []string
	if st.Inherited && st.Behavior != "" {
		parts = append(parts, "跟随"+serverLabel+"："+st.Behavior.Label())
	}
	if n := len(st.Specific); n > 0 {
		parts = append(parts, "另有 "+itoa(n)+" 条带参数的规则")
	}
	if len(parts) == 0 {
		return fallback
	}
	return strings.Join(parts, " · ")
}

// toolRow 是一个工具（或整个服务器）一行，右边下拉选 未设置、允许、询问、拒绝；显示的是它自己的规则。
func (p *Page) toolRow(c *ui.Context, title, summary, rule string, own cfg.Behavior) {
	editable := p.perm.data != nil && !p.perm.working
	ui.Column(c.Key(rule)).Children(func() {
		item(c, title, summary, func() {
			selected := 0
			for i, b := range choiceValues {
				if b == own {
					selected = i
				}
			}
			choice(c, title, choiceLabels, selected, !editable, func(i int) {
				if choiceValues[i] != own {
					p.setTool(rule, choiceValues[i])
				}
			})
		})
	})
}

func (p *Page) permissionsPage(c *ui.Context) {
	s := &p.perm
	pageTitle(c, "工具权限", p.back, func() {
		ui.Button(c, "重新读取").Disabled(s.working).OnClick(p.reloadPermissions)
		ui.Button(c, "添加规则").Disabled(s.data == nil || s.working).OnClick(func() { p.openRule(nil) })
	})
	body(c, "permissions", func() {
		intro := cfg.PermissionSemantics + "改动对所有项目、之后新开始的对话生效。"
		if s.loadErr != "" {
			intro += "\n\n读取失败：" + s.loadErr
		}
		ui.Text(c, intro).FontSize(12).TextColor(c.Theme().TextMuted)
		if s.data == nil {
			if s.loadErr == "" {
				section(c, "", "", func() { loadingRow(c, "正在读取…") })
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
	section(c, "内置工具", "", func() {
		p.bulkButtons(c, "内置工具", d.BuiltinGroup(), d.BuiltinBulk)
		more := 0
		for _, t := range d.Builtin {
			if t.More {
				more++
				continue
			}
			p.toolRow(c, t.Name, toolSummary(t.Label, t.State, "整个服务器"), t.Rule, t.Own)
		}
		if !s.showMore && more > 0 {
			item(c, "更多内置工具", "", func() {
				ui.Text(c, itoa(more)+" 个").TextColor(c.Theme().TextMuted)
				ui.Button(c, "展开").Label("展开更多内置工具").OnClick(func() { s.showMore = true })
			})
		}
	})
	if s.showMore {
		section(c, "更多内置工具", "", func() {
			for _, t := range d.Builtin {
				if t.More {
					p.toolRow(c, t.Name, toolSummary(t.Label, t.State, "整个服务器"), t.Rule, t.Own)
				}
			}
		})
	}
}

// bulkButtons 是一组上面的一对按钮；已经全是那个状态（没什么可加可删）时置灰。
func (p *Page) bulkButtons(c *ui.Context, label string, g cfg.PermissionGroup, pair cfg.BulkPair) {
	disabled := p.perm.data == nil || p.perm.working
	ask := func(enable bool, change cfg.BulkChange) {
		p.perm.bulk = &bulkRequest{label: label, group: g, enable: enable, change: change, open: true}
	}
	ui.Row(c).Padding(6, 12).Gap(8).Children(func() {
		ui.Button(c, "全部开启").Label("全部开启" + label).Disabled(disabled || pair.Enable.Empty()).OnClick(func() { ask(true, pair.Enable) })
		ui.Button(c, "全部关闭").Label("全部关闭" + label).Disabled(disabled || pair.Disable.Empty()).OnClick(func() { ask(false, pair.Disable) })
	})
}

// bulkMessage 是确认框里的说明：按表列出会加、会删的规则；关掉全部内置工具时另外说清后果。
func bulkMessage(change cfg.BulkChange, warnNoTools bool) string {
	var b strings.Builder
	list := func(verb string, rules []cfg.Rule, suffix string) {
		for _, be := range cfg.Behaviors {
			var texts []string
			for _, r := range rules {
				if r.Behavior == be {
					texts = append(texts, r.Text)
				}
			}
			if len(texts) > 0 {
				b.WriteString(verb + be.Label() + suffix + "（" + itoa(len(texts)) + " 条）：" + strings.Join(texts, "、") + "\n")
			}
		}
	}
	list("加到", change.Add, "")
	list("从", change.Remove, "里删")
	if warnNoTools {
		b.WriteString("\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用 MCP 工具。")
	}
	return strings.TrimRight(b.String(), "\n")
}

func (p *Page) serversSection(c *ui.Context) {
	d := p.perm.data
	section(c, "MCP 服务器", "", func() {
		if len(d.Servers) == 0 {
			emptyRow(c, "没有配置 MCP 服务器", "")
		}
		for _, sv := range d.Servers {
			ui.Column(c.Key("server-" + sv.Name)).Children(func() {
				entry(c, sv.Name, toolSummary(sv.Rule, sv.State, ""), sv.State.Behavior.Label(), true, func() { p.openPermServer(sv) })
			})
		}
	})
}

func (p *Page) rulesSection(c *ui.Context) {
	s := &p.perm
	section(c, "规则 · "+itoa(len(s.data.Rules)), "带参数的规则只在这里改，上面的下拉不动它们。", func() {
		if len(s.data.Rules) == 0 {
			emptyRow(c, "还没有规则", "点右上角的“添加规则”")
		}
		for _, r := range s.data.Rules {
			summary := r.Behavior.Label()
			if r.Malformed {
				summary += " · 括号不配对，Claude Code 认不出这条"
			}
			ui.Column(c.Key(string(r.Behavior) + "/" + r.Text)).Children(func() {
				item(c, r.Text, summary, func() {
					ui.Button(c, "编辑").Label("编辑：" + r.Text).Disabled(s.working).OnClick(func() { p.openRule(&r.Rule) })
					ui.Button(c, "删除").Label("删除：" + r.Text).Disabled(s.working).OnClick(func() { s.deleting = &r.Rule })
				})
			})
		}
	})
}

func (p *Page) openRule(r *cfg.Rule) {
	e := &ruleEdit{original: r, behavior: cfg.Allow, open: true}
	if r != nil {
		e.behavior, e.text = r.Behavior, r.Text
	}
	p.perm.edit = e
}

func (p *Page) saveRule() {
	e := p.perm.edit
	nw := cfg.Rule{Behavior: e.behavior, Text: strings.TrimSpace(e.text)}
	if nw.Text == "" {
		e.problem = "规则不能是空的"
		return
	}
	original := e.original
	e.saving, e.problem = true, ""
	p.applyPermissions(func(ctx context.Context, scope cfg.PermissionScope) (cfg.Permissions, error) {
		if original == nil {
			return p.opts.Client.AddRule(ctx, scope, nw)
		}
		return p.opts.Client.ReplaceRule(ctx, scope, *original, nw)
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
		if ui.AlertDialog(c, &open, "删除规则", "删除"+r.Behavior.Label()+"规则 "+r.Text+"？", "取消", "删除") == 1 {
			rule := *r
			p.applyPermissions(func(ctx context.Context, scope cfg.PermissionScope) (cfg.Permissions, error) {
				return p.opts.Client.RemoveRule(ctx, scope, rule)
			}, nil)
		}
		if !open {
			s.deleting = nil
		}
	}
	p.bulkDialog(c)
}

func (p *Page) ruleDialog(c *ui.Context, e *ruleEdit) {
	title := "添加规则"
	if e.original != nil {
		title = "编辑规则"
	}
	ui.Modal(c, &e.open, func() {
		ui.Column(c).Width(460).Gap(12).Children(func() {
			ui.Text(c, title).FontSize(17).Bold()
			labels := []string{}
			selected := 0
			for i, b := range cfg.Behaviors {
				labels = append(labels, b.Label())
				if b == e.behavior {
					selected = i
				}
			}
			choice(c, "表", labels, selected, e.saving, func(i int) { e.behavior = cfg.Behaviors[i] })
			ui.TextInput(c.Key("rule"), &e.text).Placeholder("Bash(git push:*)").Label("规则").Disabled(e.saving).AutoFocus()
			ui.Text(c, "写成 工具名 或 工具名(参数)。比如 Bash(npm run test:*)、Read(./.env)、Edit(src/**)、WebFetch(domain:github.com)、mcp__github（整个服务器）。").
				FontSize(12).TextColor(c.Theme().TextMuted)
			if e.problem != "" {
				ui.Text(c, e.problem).FontSize(12).TextColor(c.Theme().Danger)
			}
			ui.Row(c).Gap(8).Justify(ui.End).Children(func() {
				ui.Button(c, "取消").Disabled(e.saving).OnClick(func() { e.open = false })
				label := "保存"
				if e.saving {
					label = "正在保存…"
				}
				ui.PrimaryButton(c, label).Disabled(e.saving).OnClick(p.saveRule)
			})
		})
	})
}

func (p *Page) bulkDialog(c *ui.Context) {
	s := &p.perm
	r := s.bulk
	if r == nil {
		return
	}
	verb := "全部开启"
	if !r.enable {
		verb = "全部关闭"
	}
	warn := !r.enable && r.group.Server == ""
	if ui.AlertDialog(c, &r.open, verb+r.label+"？", bulkMessage(r.change, warn), "取消", verb) == 1 {
		g, enable := r.group, r.enable
		p.applyPermissions(func(ctx context.Context, scope cfg.PermissionScope) (cfg.Permissions, error) {
			return p.opts.Client.SetGroup(ctx, scope, g, enable)
		}, nil)
	}
	if !r.open {
		s.bulk = nil
	}
}

// ---------- 一个 MCP 服务器 ----------

func (p *Page) openPermServer(sv cfg.ServerView) {
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
	server := cfg.McpServer{Name: s.server.Name, Scope: s.server.Scope}
	s.toolsBusy, s.toolsErr = true, ""
	p.async(func(ctx context.Context) func() {
		tools, err := p.opts.Client.McpTools(ctx, server)
		return func() {
			s.toolsBusy = false
			if err != nil {
				s.toolsErr = err.Error()
				return
			}
			if tools == nil {
				tools = []cfg.McpTool{}
			}
			s.tools = tools
			p.reloadPermissions() // 算这些工具的状态
		}
	})
}

// serverTool 是新读到的页面里这个工具的状态。
func (p *Page) serverTool(name string) (cfg.ToolView, bool) {
	if p.perm.data == nil {
		return cfg.ToolView{}, false
	}
	for _, t := range p.perm.data.ServerTools {
		if t.Name == name {
			return t, true
		}
	}
	return cfg.ToolView{}, false
}

func (p *Page) permServerPage(c *ui.Context) {
	s := &p.perm
	sv := s.server
	pageTitle(c, sv.Name, p.back, func() {
		ui.Button(c, "刷新工具").Disabled(s.toolsBusy || sv.OAuth).OnClick(p.loadServerTools)
	})
	body(c, "perm-server", func() {
		section(c, "", "整个服务器的设置管它的每个工具；工具自己也设了的，取更严的那个。", func() {
			p.bulkButtons(c, sv.Name+" 的工具", cfg.PermissionGroup{Server: sv.Name}, sv.Bulk)
			p.toolRow(c, "整个服务器", sv.Rule, sv.Rule, sv.State.Behavior)
		})
		title := "工具"
		if s.tools != nil {
			title += " · " + itoa(len(s.tools))
		}
		footer := ""
		if sv.OAuth {
			footer = "要按工具设置，在上一页的“规则”里手写 " + sv.Rule + "__工具名。"
		}
		section(c, title, footer, func() { p.serverToolRows(c) })
	})
	p.permDialogs(c)
}

func (p *Page) serverToolRows(c *ui.Context) {
	s := &p.perm
	switch {
	case s.toolsBusy:
		loadingRow(c, "正在连接…")
	case s.toolsErr != "":
		item(c, "取不到工具清单", s.toolsErr, func() { ui.Button(c, "重试").OnClick(p.loadServerTools) })
	case s.tools == nil:
		summary := "连上服务器取工具清单"
		if s.server.OAuth {
			summary = "用 OAuth 登录的服务器取不到"
		}
		item(c, "列出工具", summary, func() { ui.Button(c, "列出").Disabled(s.server.OAuth).OnClick(p.loadServerTools) })
	case len(s.tools) == 0:
		emptyRow(c, "没有工具", "")
	}
	for _, tool := range s.tools {
		v, ok := p.serverTool(tool.Name)
		if !ok {
			item(c, tool.Name, clipLine(tool.Description, 60), nil)
			continue
		}
		p.toolRow(c, tool.Name, toolSummary(clipLine(tool.Description, 60), v.State, "整个服务器"), v.Rule, v.Own)
	}
}
