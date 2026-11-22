package claudecfg

import (
	"context"
	"regexp"
	"slices"
	"strings"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/egoist/mygo/ui"
)

// Subagent：~/.claude/agents 下每个 .md 是一个，前言写名称、什么时候用、模型、工具，正文是系统提示词。

type agentsState struct {
	list    []cfg.Subagent
	loaded  bool
	editing *cfg.Subagent // nil 是新建
	draft   agentDraft
	saving  bool
	problem string // 保存没成功的原因
	leaving bool
	delete  bool
}

// agentDraft 是编辑页上的内容；tools 只在 allTools 为假时用。
type agentDraft struct {
	name, description, model, prompt string
	allTools                         bool
	checked                          map[string]bool
	others                           string
}

// agentModels 是模型下拉里的选项；不写时 Subagent 跟随主对话的模型（除非设了 CLAUDE_CODE_SUBAGENT_MODEL）。
var agentModels = []struct{ value, label string }{
	{"", "默认"}, {"inherit", "跟随主对话"}, {"sonnet", "Sonnet"}, {"opus", "Opus"}, {"haiku", "Haiku"}, {"fable", "Fable"},
}

// commonTools 是常用的内置工具；别的（比如 MCP 工具 mcp__服务器__工具）写进“其他工具”。
var commonTools = []struct{ name, label string }{
	{"Read", "读文件"}, {"Edit", "修改文件"}, {"Write", "新建、覆盖文件"}, {"Glob", "按文件名查找"}, {"Grep", "在文件里搜索"},
	{"Bash", "执行命令"}, {"WebFetch", "抓取网页"}, {"WebSearch", "网络搜索"}, {"NotebookEdit", "编辑 Jupyter 笔记本"}, {"Skill", "使用 Skill"},
}

var agentName = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)

func agentModelLabel(model string) string {
	for _, m := range agentModels {
		if strings.EqualFold(m.value, model) {
			return m.label
		}
	}
	return model
}

func isCommonTool(name string) bool {
	return slices.ContainsFunc(commonTools, func(t struct{ name, label string }) bool { return t.name == name })
}

func draftOf(a *cfg.Subagent) agentDraft {
	d := agentDraft{allTools: true, checked: map[string]bool{}}
	if a == nil {
		return d
	}
	d.name, d.description, d.model, d.prompt = a.Name, a.Description, a.Model, a.Prompt
	d.allTools = a.Tools == nil
	var others []string
	for _, t := range a.Tools {
		if isCommonTool(t) {
			d.checked[t] = true
		} else {
			others = append(others, t)
		}
	}
	d.others = strings.Join(others, ", ")
	return d
}

// tools 是要写进文件的工具：勾选的常用工具按固定顺序，再加“其他工具”里用逗号分开的；nil 是全部工具。
func (d agentDraft) tools() []string {
	if d.allTools {
		return nil
	}
	out := []string{}
	for _, t := range commonTools {
		if d.checked[t.name] {
			out = append(out, t.name)
		}
	}
	for _, t := range strings.FieldsFunc(d.others, func(r rune) bool { return r == ',' || r == '，' }) {
		if t = strings.TrimSpace(t); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// missing 是保存前还差什么；不差时为空。
func (d agentDraft) missing() string {
	switch {
	case !agentName.MatchString(strings.TrimSpace(d.name)):
		return "名称只能用小写字母、数字和连字符，并以字母或数字开头"
	case strings.TrimSpace(d.description) == "":
		return "写上什么时候用它才能保存"
	}
	return ""
}

func (p *Page) openAgents() {
	p.agents = agentsState{}
	p.reloadAgents()
}

func (p *Page) reloadAgents() {
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Client.Agents(ctx)
		return func() {
			if err != nil {
				p.failed("读取 Subagent 失败", err)
				return
			}
			p.agents.list, p.agents.loaded = list, true
		}
	})
}

func (p *Page) agentsPage(c *ui.Context) {
	a := &p.agents
	pageTitle(c, "Subagent", p.back, func() {
		ui.Button(c, "新建").Label("新建 Subagent").OnClick(func() { p.openAgent(nil) })
	})
	body(c, "agents", func() {
		footer := "存在 ~/.claude/agents，所有项目都能用。Claude 按“什么时候用”自己决定交给哪个，你也可以在对话里点名。"
		section(c, "自定义 Subagent · "+itoa(len(a.list)), footer, func() {
			switch {
			case !a.loaded:
				loadingRow(c, "正在读取…")
			case len(a.list) == 0:
				emptyRow(c, "还没有自定义 Subagent", "让 Claude 写一个，或点右上角的“新建”")
			}
			for i := range a.list {
				ag := a.list[i]
				name := ag.Name
				if name == "" {
					name = ag.File
				}
				ui.Column(c.Key(ag.File)).Children(func() {
					entry(c, name, clipLine(ag.Description, 90), agentModelLabel(ag.Model), true, func() { p.openAgent(&ag) })
				})
			}
		})
	})
}

func (p *Page) openAgent(a *cfg.Subagent) {
	p.agents.editing, p.agents.draft, p.agents.problem = a, draftOf(a), ""
	p.push(viewAgent)
}

func (p *Page) saveAgent() {
	a := &p.agents
	d := a.draft
	draft := cfg.SubagentDraft{Name: strings.TrimSpace(d.name), Description: strings.TrimSpace(d.description), Model: d.model, Tools: d.tools(), Prompt: d.prompt}
	original := a.editing
	a.saving, a.problem = true, ""
	p.async(func(ctx context.Context) func() {
		_, err := p.opts.Client.SaveAgent(ctx, original, draft)
		return func() {
			a.saving = false
			if err != nil {
				a.problem = err.Error()
				return
			}
			p.back()
			p.reloadAgents()
		}
	})
}

func (p *Page) agentPage(c *ui.Context) {
	a := &p.agents
	d := &a.draft
	t := c.Theme()
	missing := d.missing()
	dirty := a.editing == nil || !agentDraftEqual(*d, draftOf(a.editing))
	title := "新建 Subagent"
	if a.editing != nil {
		title = "编辑 Subagent"
	}
	leave := func() {
		if dirty && (a.editing != nil || d.name != "" || d.description != "" || d.prompt != "") {
			a.leaving = true
		} else {
			p.back()
		}
	}
	pageTitle(c, title, leave, func() {
		if a.editing != nil {
			ui.Button(c, "删除").TextColor(t.Danger).Disabled(a.saving).OnClick(func() { a.delete = true })
		}
		label := "保存"
		if a.saving {
			label = "保存中"
		}
		ui.PrimaryButton(c, label).Disabled(a.saving || missing != "").OnClick(p.saveAgent)
	})
	body(c, "agent", func() {
		section(c, "名称", "小写字母、数字和连字符，比如 code-reviewer。", func() {
			ui.TextInput(c.Key("name"), &d.name).Placeholder("code-reviewer").Label("名称").Disabled(a.saving)
		})
		section(c, "什么时候用", "Claude 靠这段话决定要不要交给它，写清楚它擅长什么。", func() {
			ui.TextArea(c.Key("description"), &d.description).Placeholder("比如：改完代码后用它审查改动，找出 bug 和不清楚的地方").
				Label("什么时候用").Lines(2, 6).Disabled(a.saving)
		})
		p.agentModelSection(c)
		p.agentToolsSection(c)
		section(c, "系统提示词", "写它的角色和做事方式。它看不到主对话，只看到这段话和交给它的任务。", func() {
			ui.TextArea(c.Key("prompt"), &d.prompt).Placeholder("你是一名代码审查员……").Label("系统提示词").Lines(8, 24).Disabled(a.saving)
		})
		if a.problem != "" {
			ui.Text(c, a.problem).FontSize(12).TextColor(t.Danger)
		} else if missing != "" && (d.name != "" || d.description != "") {
			ui.Text(c, missing).FontSize(12).TextColor(t.TextMuted)
		}
		if a.editing != nil && len(a.editing.ExtraKeys) > 0 {
			ui.Text(c, "另外还设了 "+strings.Join(a.editing.ExtraKeys, "、")+"，保存时原样保留。").FontSize(12).TextColor(t.TextMuted)
		}
	})
	p.agentDialogs(c)
}

func agentDraftEqual(a, b agentDraft) bool {
	return a.name == b.name && a.description == b.description && a.model == b.model && a.prompt == b.prompt &&
		slices.Equal(a.tools(), b.tools())
}

// agentModelSection 选模型；文件里写的不在选项里时加在最后，原样保留。
func (p *Page) agentModelSection(c *ui.Context) {
	d := &p.agents.draft
	values := []string{}
	labels := []string{}
	for _, m := range agentModels {
		values, labels = append(values, m.value), append(labels, m.label)
	}
	selected := slices.IndexFunc(values, func(v string) bool { return strings.EqualFold(v, d.model) })
	if selected < 0 {
		values, labels = append(values, d.model), append(labels, d.model)
		selected = len(values) - 1
	}
	section(c, "模型", "默认时跟随主对话的模型。", func() {
		item(c, "模型", "", func() {
			choice(c, "模型", labels, selected, p.agents.saving, func(i int) { d.model = values[i] })
		})
	})
}

func (p *Page) agentToolsSection(c *ui.Context) {
	d := &p.agents.draft
	saving := p.agents.saving
	footer := "关掉“全部工具”后只能用勾选的。MCP 工具写成 mcp__服务器__工具，多个用逗号分开。"
	section(c, "工具", footer, func() {
		item(c, "全部工具", "包括 MCP 工具", func() {
			ui.Switch(c.Key("all-tools"), &d.allTools).Label("全部工具").Disabled(saving)
		})
		if d.allTools {
			return
		}
		for _, tool := range commonTools {
			on := d.checked[tool.name]
			item(c, tool.label, tool.name, func() {
				if ui.Checkbox(c.Key("tool-"+tool.name), &on, "").Label(tool.label).Disabled(saving).Changed() {
					d.checked[tool.name] = on
				}
			})
		}
		item(c, "其他工具", "", func() {
			ui.TextInput(c.Key("others"), &d.others).Placeholder("mcp__github__create_issue, …").Label("其他工具").Disabled(saving).Width(320)
		})
	})
}

func (p *Page) agentDialogs(c *ui.Context) {
	a := &p.agents
	if ui.AlertDialog(c, &a.leaving, "放弃未保存的更改？", "", "取消", "放弃") == 1 {
		p.back()
	}
	if a.editing == nil {
		return
	}
	file, name := a.editing.File, a.editing.Name
	if ui.AlertDialog(c, &a.delete, "删除 Subagent", "删除“"+name+"”？删了就找不回来。", "取消", "删除") == 1 {
		p.async(func(ctx context.Context) func() {
			err := p.opts.Client.DeleteAgent(ctx, file)
			return func() {
				if err != nil {
					p.failed("删除失败", err)
					return
				}
				p.back()
				p.reloadAgents()
			}
		})
	}
}
