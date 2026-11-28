package claudecfg

import (
	"context"
	"regexp"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// MCP 服务器：配置里的每个服务器和它的状态（claude mcp list 的健康检查），新加的放进用户配置（所有项目可用）。
// 密钥只说有没有，不显示；Token 只在改的时候输入。

type mcpState struct {
	servers   []coreapi.McpServer
	loaded    bool
	status    *coreapi.McpStatus
	checking  bool
	statusErr string

	adding bool
	form   serverForm

	detail    coreapi.McpServer // 详情页的那个
	tools     []coreapi.McpTool
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

var serverTypes = []option{{"http", fixed("HTTP")}, {"sse", fixed("SSE")}, {"stdio", locales.ClaudecfgMcpTypeCommand}}

func typeName(t string) string {
	for _, st := range serverTypes {
		if st.value == t {
			return st.label()
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
		return locales.ClaudecfgMcpScopeUser()
	case "local":
		return locales.ClaudecfgMcpScopeLocal()
	case "project":
		return locales.ClaudecfgMcpScopeProject()
	}
	return scope
}

func stateLabel(st *coreapi.McpState) string {
	if st == nil {
		return locales.ClaudecfgMcpStateChecking()
	}
	switch st.State {
	case "connected":
		return locales.ClaudecfgMcpStateConnected()
	case "partial":
		return locales.ClaudecfgMcpStatePartial()
	case "failed":
		return locales.ClaudecfgMcpStateFailed()
	case "auth":
		return locales.ClaudecfgMcpStateAuth()
	case "pending":
		return locales.ClaudecfgMcpStatePending()
	case "rejected":
		return locales.ClaudecfgMcpStateRejected()
	case "disabled":
		return locales.ClaudecfgMcpStateDisabled()
	case "unconfigured":
		return locales.ClaudecfgNotConfigured()
	case "shadowed":
		return locales.ClaudecfgMcpStateShadowed()
	}
	return locales.ClaudecfgMcpStateUnknown()
}

// stateOf 是列表和详情里显示的状态：被同名服务器盖住的不会被用到，不看健康检查的结果。
func (m *mcpState) stateOf(s coreapi.McpServer) *coreapi.McpState {
	if s.ShadowedBy != "" {
		return &coreapi.McpState{State: "shadowed", Issue: locales.ClaudecfgMcpShadowedBy(scopeLabel(s.ShadowedBy))}
	}
	if m.status != nil {
		if st, ok := m.status.Servers[s.Key()]; ok {
			return &st
		}
	}
	if m.statusErr != "" || m.status != nil {
		return &coreapi.McpState{State: "unknown"}
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
		list, err := p.opts.Core.McpServers(ctx, p.opts.Cwd)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgMcpLoadFailed(), err)
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
		st, err := p.opts.Core.McpStatus(ctx, p.opts.Cwd)
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
	pageTitle(c, locales.ClaudecfgMcpServers(), p.back, func() {
		widget.SmallButton(c, locales.ClaudecfgMcpRecheck()).Disabled(m.checking).OnClick(p.checkMcp)
		widget.SmallPrimaryButton(c, locales.ClaudecfgMcpAdd()).OnClick(func() { m.adding, m.form = true, serverForm{typ: "http"} })
	})
	body(c, "mcp", func() {
		footer := locales.ClaudecfgMcpFooter()
		if p.isAgy() {
			footer = locales.ClaudecfgAgyMcpFooter()
		}
		if m.statusErr != "" {
			footer += "\n\n" + locales.ClaudecfgMcpStatusFailed(m.statusErr)
		}
		widget.Group(c, locales.ClaudecfgMcpServersCount(len(m.servers)), footer, func(l *widget.List) {
			switch {
			case !m.loaded:
				loadingRow(l, locales.CommonLoading())
			case len(m.servers) == 0:
				emptyRow(l, locales.ClaudecfgMcpEmpty(), locales.ClaudecfgMcpEmptyHint())
			}
			for _, s := range m.servers {
				ui.Column(c.Key(s.Key())).Children(func() {
					entry(l, s.Name, s.Name, serverSummary(s), stateLabel(m.stateOf(s)), true, func() { p.openMcpServer(s) })
				})
			}
		})
		if m.status != nil && len(m.status.Connectors) > 0 {
			widget.Group(c, locales.ClaudecfgMcpConnectors(), locales.ClaudecfgManagedOnClaudeAi(), func(l *widget.List) {
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
func serverSummary(s coreapi.McpServer) string {
	prefix := ""
	if s.Scope != "user" {
		prefix = scopeLabel(s.Scope) + " · "
	}
	return prefix + clipLine(target(s), 80)
}

func target(s coreapi.McpServer) string {
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
	case !validMcpName(strings.TrimSpace(f.name)):
		return locales.ClaudecfgMcpNameInvalid()
	case f.typ != "stdio" && !urlForm.MatchString(strings.TrimSpace(f.url)):
		return locales.ClaudecfgMcpURLInvalid()
	case f.typ == "stdio" && strings.TrimSpace(f.command) == "":
		return locales.ClaudecfgMcpCommandMissing()
	}
	for _, l := range envLines(f.env) {
		if !envLine.MatchString(l) {
			return locales.ClaudecfgMcpEnvInvalid()
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
	dialog(c, &m.adding, locales.ClaudecfgMcpAdd(), func() {
		labels := []string{}
		selected := 0
		for i, st := range serverTypes {
			labels = append(labels, st.label())
			if st.value == f.typ {
				selected = i
			}
		}
		labeled(c, locales.ClaudecfgMcpType(), func() {
			choice(c, "type", locales.ClaudecfgMcpType(), labels, selected, f.working, func(i int) { f.typ = serverTypes[i].value })
		})
		ui.Column(c).FillWidth().Children(func() {
			widget.LineInput(c.Key("name"), &f.name, 1).Placeholder(locales.ClaudecfgMcpNamePlaceholder()).Label(locales.ClaudecfgName())
			if f.typ == "stdio" {
				widget.LineInput(c.Key("command"), &f.command, 1).Placeholder(locales.ClaudecfgMcpCommandPlaceholder()).Label(locales.ClaudecfgMcpTypeCommand())
				widget.LineInput(c.Key("args"), &f.args, 1).Placeholder(locales.ClaudecfgMcpArgsPlaceholder()).Label(locales.ClaudecfgMcpArgs())
				widget.LineInput(c.Key("env"), &f.env, 5).Placeholder(locales.ClaudecfgMcpEnvPlaceholder()).Label(locales.ClaudecfgMcpEnv())
			} else {
				widget.LineInput(c.Key("url"), &f.url, 1).Placeholder(locales.ClaudecfgMcpURLPlaceholder()).Label(locales.ClaudecfgMcpURL())
				widget.LineInput(c.Key("token"), &f.token, 1).Placeholder(locales.ClaudecfgMcpTokenOptional()).Label("Bearer Token").Password()
			}
		})
		if f.problem != "" {
			problemText(c, f.problem)
		}
	}, func() {
		widget.DialogButton(c, locales.CommonCancel(), widget.Plain).Disabled(f.working).OnClick(func() { m.adding = false })
		label := locales.CommonAdd()
		if f.working {
			label = locales.ClaudecfgMcpAdding()
		}
		widget.DialogButton(c, label, widget.Recommend).Disabled(f.working).OnClick(p.addServer)
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
			err = p.opts.Core.AddStdioMcp(ctx, coreapi.StdioMcp{Name: name, Command: strings.TrimSpace(f.command), Args: splitArgs(f.args), Env: env})
		} else {
			err = p.opts.Core.AddHTTPMcp(ctx, coreapi.HTTPMcp{Name: name, Type: f.typ, URL: strings.TrimSpace(f.url), Token: strings.TrimSpace(f.token)})
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

func (p *Page) openMcpServer(s coreapi.McpServer) {
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
		tools, err := p.opts.Core.McpTools(ctx, p.opts.Cwd, s)
		return func() {
			m.toolsBusy = false
			if err != nil {
				m.toolsErr = err.Error()
				return
			}
			m.tools = tools
			if tools == nil {
				m.tools = []coreapi.McpTool{}
			}
		}
	})
}

func (p *Page) mcpServerPage(c *ui.Context) {
	m := &p.mcp
	s := m.detail
	pageTitle(c, s.Name, p.back, func() {
		widget.SmallButton(c, locales.ClaudecfgMcpRefreshTools()).Disabled(m.toolsBusy || s.OAuth).OnClick(p.loadTools)
	})
	body(c, "mcp-server", func() {
		widget.Group(c, locales.ClaudecfgMcpServer(), "", func(l *widget.List) {
			st := m.stateOf(s)
			l.Row(widget.Row{Title: locales.ClaudecfgMcpStatus(), Summary: issueOf(st), Value: stateLabel(st)})
			valueItem(l, locales.ClaudecfgMcpScope(), scopeLabel(s.Scope))
			valueItem(l, locales.ClaudecfgMcpType(), typeName(s.Type))
			if s.Type == "stdio" {
				item(l, locales.ClaudecfgMcpTypeCommand(), target(s), nil)
				if len(s.EnvKeys) > 0 {
					l.Row(widget.Row{Title: locales.ClaudecfgMcpEnv(), Summary: strings.Join(s.EnvKeys, locales.ClaudecfgListSeparator()),
						Value: locales.ClaudecfgMcpValuesHidden()})
				}
				return
			}
			item(l, locales.ClaudecfgMcpAddress(), s.URL, nil)
			if len(s.HeaderKeys) > 0 {
				l.Row(widget.Row{Title: locales.ClaudecfgMcpHeaders(), Summary: strings.Join(s.HeaderKeys, locales.ClaudecfgListSeparator()),
					Value: locales.ClaudecfgMcpValuesHidden()})
			}
			editable := s.Scope == "user" || s.Scope == "local"
			summary := authSummary(s)
			if !editable {
				summary = locales.ClaudecfgMcpEditInProject(summary)
			}
			item(l, locales.ClaudecfgMcpAuth(), summary, func() {
				widget.SmallButton(c, locales.ClaudecfgMcpChange()).Label(locales.ClaudecfgMcpChangeToken()).Disabled(!editable || m.working).OnClick(func() { m.tokenOpen, m.token = true, "" })
			})
		})
		p.toolsSection(c)
		if s.Scope != "project" {
			widget.Group(c, "", "", func(l *widget.List) {
				item(l, locales.ClaudecfgMcpDelete(), locales.ClaudecfgMcpDeleteSummary(), func() {
					widget.SmallDangerButton(c, locales.CommonDelete()).Disabled(m.working).OnClick(func() { m.removing = true })
				})
			})
		}
	})
	p.mcpServerDialogs(c)
}

func issueOf(st *coreapi.McpState) string {
	if st == nil {
		return ""
	}
	return st.Issue
}

func authSummary(s coreapi.McpServer) string {
	switch {
	case s.Bearer:
		return "Bearer Token"
	case s.AuthHeader:
		return locales.ClaudecfgMcpAuthHeader()
	case s.OAuth:
		return locales.ClaudecfgMcpAuthOAuth()
	}
	return locales.ClaudecfgNotConfigured()
}

func (p *Page) toolsSection(c *ui.Context) {
	m := &p.mcp
	title := locales.ClaudecfgTools()
	if m.tools != nil {
		title = locales.ClaudecfgToolsCount(len(m.tools))
	}
	widget.Group(c, title, "", func(l *widget.List) {
		switch {
		case m.toolsBusy:
			loadingRow(l, locales.CommonConnecting())
		case m.toolsErr != "":
			item(l, locales.ClaudecfgToolsListFailed(), m.toolsErr, func() { widget.SmallButton(c, locales.CommonRetry()).OnClick(p.loadTools) })
		case m.tools == nil:
			summary := locales.ClaudecfgToolsConnectHint()
			if m.detail.OAuth {
				summary = locales.ClaudecfgToolsOAuth()
			}
			item(l, locales.ClaudecfgToolsList(), summary, func() {
				widget.SmallButton(c, locales.ClaudecfgToolsListButton()).Disabled(m.detail.OAuth).OnClick(p.loadTools)
			})
		case len(m.tools) == 0:
			emptyRow(l, locales.ClaudecfgToolsNone(), "")
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
	dialog(c, &m.tokenOpen, locales.ClaudecfgMcpAuth(), func() {
		ui.Text(c, locales.ClaudecfgMcpTokenHint()).FontSize(widget.SizeBodyS).LineHeight(1.45).
			TextColor(widget.Of(c).Secondary).TextAlign(ui.Center).FillWidth()
		widget.LineInput(c.Key("token"), &m.token, 1).Placeholder("Bearer Token").Label("Bearer Token").Password().AutoFocus()
	}, func() {
		widget.DialogButton(c, locales.CommonCancel(), widget.Plain).OnClick(func() { m.tokenOpen, m.token = false, "" })
		widget.DialogButton(c, locales.CommonSave(), widget.Recommend).OnClick(func() {
			token := m.token
			m.tokenOpen, m.token, m.working = false, "", true
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.SetMcpToken(ctx, p.opts.Cwd, s, token)
				return func() {
					m.working = false
					if err != nil {
						p.failed(locales.ClaudecfgChangeFailed(), err)
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
	if alert(c, &m.removing, locales.ClaudecfgMcpDelete(), locales.ClaudecfgMcpDeleteMessage(s.Name), widget.Destructive,
		locales.CommonCancel(), locales.CommonDelete()) == 1 {
		m.working = true
		p.async(func(ctx context.Context) func() {
			err := p.opts.Core.RemoveMcp(ctx, p.opts.Cwd, s)
			return func() {
				m.working = false
				if err != nil {
					p.failed(locales.ClaudecfgDeleteFailed(), err)
					return
				}
				p.back()
				p.reloadMcp(true)
			}
		})
	}
}
