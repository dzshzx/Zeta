package claudecfg

import (
	"context"
	"regexp"
	"strings"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// MCP 服务器：配置里的每个服务器和它的状态（claude mcp list 的健康检查），新加的放进用户配置（所有项目可用）。
// 密钥只说有没有，不显示；Token 只在改的时候输入。

type mcpState struct {
	servers   []cfg.McpServer
	loaded    bool
	status    *cfg.McpStatus
	checking  bool
	statusErr string

	adding bool
	form   serverForm

	detail    cfg.McpServer // 详情页的那个
	tools     []cfg.McpTool
	toolsBusy bool
	toolsErr  string
	tokenOpen bool
	token     string
	removing  bool
	working   bool
}

type serverForm struct {
	typ, name, url, token, command, args, env string
	problem                                   string
	working                                   bool
}

var serverTypes = []struct{ value, label string }{{"http", "HTTP"}, {"sse", "SSE"}, {"stdio", "命令"}}

func typeName(t string) string {
	for _, st := range serverTypes {
		if st.value == t {
			return st.label
		}
	}
	if t == "ws" {
		return "WebSocket"
	}
	return t
}

func scopeLabel(scope string) string {
	switch scope {
	case "user":
		return "所有项目"
	case "local":
		return "只在默认工作目录"
	case "project":
		return "项目的 .mcp.json"
	}
	return scope
}

func stateLabel(st *cfg.McpState) string {
	if st == nil {
		return "检查中…"
	}
	switch st.State {
	case "connected":
		return "已连接"
	case "partial":
		return "已连接 · 取工具出错"
	case "failed":
		return "连不上"
	case "auth":
		return "需要登录"
	case "pending":
		return "等待批准"
	case "rejected":
		return "已拒绝"
	case "disabled":
		return "已停用"
	case "unconfigured":
		return "未配置"
	case "shadowed":
		return "未生效"
	}
	return "未知"
}

// stateOf 是列表和详情里显示的状态：被同名服务器盖住的不会被用到，不看健康检查的结果。
func (m *mcpState) stateOf(s cfg.McpServer) *cfg.McpState {
	if s.ShadowedBy != "" {
		return &cfg.McpState{State: "shadowed", Issue: "被“" + scopeLabel(s.ShadowedBy) + "”里的同名服务器盖住"}
	}
	if m.status != nil {
		if st, ok := m.status.Servers[s.Key()]; ok {
			return &st
		}
	}
	if m.statusErr != "" || m.status != nil {
		return &cfg.McpState{State: "unknown"}
	}
	return nil
}

func (p *Page) openMcp() {
	p.mcp = mcpState{}
	p.reloadMcp(true)
}

// reloadMcp 重读配置；check 时接着做一遍健康检查（慢）。
func (p *Page) reloadMcp(check bool) {
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Client.McpServers(ctx)
		return func() {
			if err != nil {
				p.failed("读取 MCP 配置失败", err)
				return
			}
			p.mcp.servers, p.mcp.loaded = list, true
			if check {
				p.checkMcp()
			}
		}
	})
}

func (p *Page) checkMcp() {
	m := &p.mcp
	m.checking, m.status, m.statusErr = true, nil, ""
	p.async(func(ctx context.Context) func() {
		st, err := p.opts.Client.McpStatus(ctx)
		return func() {
			m.checking = false
			if err != nil {
				m.statusErr = err.Error()
				return
			}
			m.status = &st
		}
	})
}

func (p *Page) mcpPage(c *ui.Context) {
	m := &p.mcp
	pageTitle(c, "MCP 服务器", p.back, func() {
		coui.SmallButton(c, "重新检查").Disabled(m.checking).OnClick(p.checkMcp)
		coui.SmallPrimaryButton(c, "添加服务器").OnClick(func() { m.adding, m.form = true, serverForm{typ: "http"} })
	})
	body(c, "mcp", func() {
		footer := "新加的服务器所有项目都能用，改动对之后新开始的对话生效。"
		if m.statusErr != "" {
			footer += "\n\n检查状态没有成功：" + m.statusErr
		}
		coui.Group(c, "服务器 · "+itoa(len(m.servers)), footer, func(l *coui.List) {
			switch {
			case !m.loaded:
				loadingRow(l, "正在读取…")
			case len(m.servers) == 0:
				emptyRow(l, "还没有 MCP 服务器", "点右上角的“添加服务器”")
			}
			for _, s := range m.servers {
				ui.Column(c.Key(s.Key())).Children(func() {
					entry(l, s.Name, serverSummary(s), stateLabel(m.stateOf(s)), true, func() { p.openMcpServer(s) })
				})
			}
		})
		if m.status != nil && len(m.status.Connectors) > 0 {
			coui.Group(c, "Claude 账号的连接器", "在 claude.ai 上管理，这里只能看。", func(l *coui.List) {
				for _, cn := range m.status.Connectors {
					st := cn.State
					valueItem(l, strings.TrimPrefix(cn.Name, "claude.ai "), stateLabel(&st))
				}
			})
		}
	})
	p.addServerDialog(c)
}

// serverSummary：不是用户配置的先写范围，再是地址或命令。
func serverSummary(s cfg.McpServer) string {
	prefix := ""
	if s.Scope != "user" {
		prefix = scopeLabel(s.Scope) + " · "
	}
	return prefix + clipLine(target(s), 80)
}

func target(s cfg.McpServer) string {
	if s.Type == "stdio" {
		return strings.Join(append([]string{s.Command}, s.Args...), " ")
	}
	return s.URL
}

var (
	urlForm = regexp.MustCompile(`^https?://\S+$`)
	envLine = regexp.MustCompile(`^([A-Za-z_][A-Za-z0-9_]*)=(.*)$`)
)

// formProblem 是添加服务器的表单还差什么；没问题时为空。
func formProblem(f serverForm) string {
	switch {
	case !cfg.ValidMcpName(strings.TrimSpace(f.name)):
		return "名称只能用字母、数字、下划线和连字符"
	case f.typ != "stdio" && !urlForm.MatchString(strings.TrimSpace(f.url)):
		return "地址要以 http:// 或 https:// 开头"
	case f.typ == "stdio" && strings.TrimSpace(f.command) == "":
		return "请输入要运行的命令"
	}
	for _, l := range envLines(f.env) {
		if !envLine.MatchString(l) {
			return "环境变量每行一个，写成 KEY=VALUE"
		}
	}
	return ""
}

func envLines(text string) []string {
	var out []string
	for _, l := range strings.Split(text, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}

// splitArgs 把一行参数拆开：空白分隔，单双引号里的空白不拆。
func splitArgs(line string) []string {
	var out []string
	var cur strings.Builder
	var quote rune
	has := false
	for _, r := range line {
		switch {
		case quote != 0 && r == quote:
			quote = 0
		case quote != 0:
			cur.WriteRune(r)
		case r == '"' || r == '\'':
			quote, has = r, true
		case r == ' ' || r == '\t' || r == '\n':
			if has || cur.Len() > 0 {
				out = append(out, cur.String())
				cur.Reset()
				has = false
			}
		default:
			cur.WriteRune(r)
		}
	}
	if has || cur.Len() > 0 {
		out = append(out, cur.String())
	}
	return out
}

func (p *Page) addServerDialog(c *ui.Context) {
	m := &p.mcp
	f := &m.form
	dialog(c, &m.adding, "添加服务器", func() {
		labels := []string{}
		selected := 0
		for i, st := range serverTypes {
			labels = append(labels, st.label)
			if st.value == f.typ {
				selected = i
			}
		}
		labeled(c, "类型", func() {
			choice(c, "类型", labels, selected, f.working, func(i int) { f.typ = serverTypes[i].value })
		})
		ui.Column(c).FillWidth().Children(func() {
			coui.LineInput(c.Key("name"), &f.name, 1).Placeholder("名称，比如 github").Label("名称")
			if f.typ == "stdio" {
				coui.LineInput(c.Key("command"), &f.command, 1).Placeholder("命令，比如 npx").Label("命令")
				coui.LineInput(c.Key("args"), &f.args, 1).Placeholder("参数（可选，空格分开）").Label("参数")
				coui.LineInput(c.Key("env"), &f.env, 5).Placeholder("环境变量（可选，每行一个 KEY=VALUE）").Label("环境变量")
			} else {
				coui.LineInput(c.Key("url"), &f.url, 1).Placeholder("服务器地址 https://").Label("服务器地址")
				coui.LineInput(c.Key("token"), &f.token, 1).Placeholder("Bearer Token（可选）").Label("Bearer Token").Password()
			}
		})
		if f.problem != "" {
			problemText(c, f.problem)
		}
	}, func() {
		dialogButton(coui.DialogButton(c, "取消", coui.Plain)).Disabled(f.working).OnClick(func() { m.adding = false })
		label := "添加"
		if f.working {
			label = "正在添加…"
		}
		dialogButton(coui.DialogButton(c, label, coui.Recommend)).Disabled(f.working).OnClick(p.addServer)
	})
}

func (p *Page) addServer() {
	m := &p.mcp
	f := m.form
	if m.form.problem = formProblem(f); m.form.problem != "" {
		return
	}
	m.form.working = true
	p.async(func(ctx context.Context) func() {
		name := strings.TrimSpace(f.name)
		var err error
		if f.typ == "stdio" {
			env := map[string]string{}
			for _, l := range envLines(f.env) {
				k, v, _ := strings.Cut(l, "=")
				env[k] = v
			}
			err = p.opts.Client.AddStdioServer(ctx, name, strings.TrimSpace(f.command), splitArgs(f.args), env)
		} else {
			err = p.opts.Client.AddHTTPServer(ctx, name, f.typ, strings.TrimSpace(f.url), strings.TrimSpace(f.token))
		}
		return func() {
			m.form.working = false
			if err != nil {
				m.form.problem = err.Error()
				return
			}
			m.adding, m.form = false, serverForm{}
			p.reloadMcp(true)
		}
	})
}

// ---------- 一个服务器 ----------

func (p *Page) openMcpServer(s cfg.McpServer) {
	m := &p.mcp
	m.detail, m.tools, m.toolsErr = s, nil, ""
	p.push(viewMcpServer)
	if s.Scope != "project" && !s.OAuth {
		p.loadTools()
	}
}

func (p *Page) loadTools() {
	m := &p.mcp
	s := m.detail
	m.toolsBusy, m.toolsErr = true, ""
	p.async(func(ctx context.Context) func() {
		tools, err := p.opts.Client.McpTools(ctx, s)
		return func() {
			m.toolsBusy = false
			if err != nil {
				m.toolsErr = err.Error()
				return
			}
			m.tools = tools
			if tools == nil {
				m.tools = []cfg.McpTool{}
			}
		}
	})
}

func (p *Page) mcpServerPage(c *ui.Context) {
	m := &p.mcp
	s := m.detail
	pageTitle(c, s.Name, p.back, func() {
		coui.SmallButton(c, "刷新工具").Disabled(m.toolsBusy || s.OAuth).OnClick(p.loadTools)
	})
	body(c, "mcp-server", func() {
		coui.Group(c, "服务器", "", func(l *coui.List) {
			st := m.stateOf(s)
			l.Row(coui.Row{Title: "状态", Summary: issueOf(st), Value: stateLabel(st)})
			valueItem(l, "范围", scopeLabel(s.Scope))
			valueItem(l, "类型", typeName(s.Type))
			if s.Type == "stdio" {
				item(l, "命令", target(s), nil)
				if len(s.EnvKeys) > 0 {
					l.Row(coui.Row{Title: "环境变量", Summary: strings.Join(s.EnvKeys, "、"), Value: "值已隐藏"})
				}
				return
			}
			item(l, "地址", s.URL, nil)
			if len(s.HeaderKeys) > 0 {
				l.Row(coui.Row{Title: "其他请求头", Summary: strings.Join(s.HeaderKeys, "、"), Value: "值已隐藏"})
			}
			editable := s.Scope == "user" || s.Scope == "local"
			summary := authSummary(s)
			if !editable {
				summary += " · 在项目里改"
			}
			item(l, "身份认证", summary, func() {
				coui.SmallButton(c, "更换").Label("更换 Token").Disabled(!editable || m.working).OnClick(func() { m.tokenOpen, m.token = true, "" })
			})
		})
		p.toolsSection(c)
		if s.Scope != "project" {
			coui.Group(c, "", "", func(l *coui.List) {
				item(l, "删除服务器", "它的地址、Token 和设置都会从配置里去掉", func() {
					coui.SmallDangerButton(c, "删除").Disabled(m.working).OnClick(func() { m.removing = true })
				})
			})
		}
	})
	p.mcpServerDialogs(c)
}

func issueOf(st *cfg.McpState) string {
	if st == nil {
		return ""
	}
	return st.Issue
}

func authSummary(s cfg.McpServer) string {
	switch {
	case s.Bearer:
		return "Bearer Token"
	case s.AuthHeader:
		return "Authorization 请求头"
	case s.OAuth:
		return "OAuth：在终端用 claude 的 /mcp 登录"
	}
	return "未配置"
}

func (p *Page) toolsSection(c *ui.Context) {
	m := &p.mcp
	title := "工具"
	if m.tools != nil {
		title += " · " + itoa(len(m.tools))
	}
	coui.Group(c, title, "", func(l *coui.List) {
		switch {
		case m.toolsBusy:
			loadingRow(l, "正在连接…")
		case m.toolsErr != "":
			item(l, "取不到工具清单", m.toolsErr, func() { coui.SmallButton(c, "重试").OnClick(p.loadTools) })
		case m.tools == nil:
			summary := "连上服务器取工具清单"
			if m.detail.OAuth {
				summary = "用 OAuth 登录的服务器取不到"
			}
			item(l, "列出工具", summary, func() { coui.SmallButton(c, "列出").Disabled(m.detail.OAuth).OnClick(p.loadTools) })
		case len(m.tools) == 0:
			emptyRow(l, "没有工具", "")
		default:
			for _, tool := range m.tools {
				item(l, tool.Name, clipLine(tool.Description, 120), nil)
			}
		}
	})
}

func (p *Page) mcpServerDialogs(c *ui.Context) {
	m := &p.mcp
	s := m.detail
	dialog(c, &m.tokenOpen, "身份认证", func() {
		ui.Text(c, "输入新 Token，留空则移除身份认证。原来的 Token 不会显示出来。").FontSize(coui.SizeBodyS).LineHeight(1.45).
			TextColor(coui.Of(c).Secondary).TextAlign(ui.Center).FillWidth()
		coui.LineInput(c.Key("token"), &m.token, 1).Placeholder("Bearer Token").Label("Bearer Token").Password().AutoFocus()
	}, func() {
		dialogButton(coui.DialogButton(c, "取消", coui.Plain)).OnClick(func() { m.tokenOpen, m.token = false, "" })
		dialogButton(coui.DialogButton(c, "保存", coui.Recommend)).OnClick(func() {
			token := m.token
			m.tokenOpen, m.token, m.working = false, "", true
			p.async(func(ctx context.Context) func() {
				err := p.opts.Client.SetMcpToken(ctx, s, token)
				return func() {
					m.working = false
					if err != nil {
						p.failed("没有改成", err)
						return
					}
					p.reloadMcp(true)
					p.loadTools()
				}
			})
		})
	})
	if !m.tokenOpen {
		m.token = ""
	}
	if alert(c, &m.removing, "删除服务器", "删除 "+s.Name+"？它的地址、Token 和设置都会从配置里去掉，无法撤销。", coui.Destructive, "取消", "删除") == 1 {
		m.working = true
		p.async(func(ctx context.Context) func() {
			err := p.opts.Client.RemoveMcp(ctx, s)
			return func() {
				m.working = false
				if err != nil {
					p.failed("删除失败", err)
					return
				}
				p.back()
				p.reloadMcp(true)
			}
		})
	}
}
