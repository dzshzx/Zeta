package claudecfg

import (
	"context"
	"regexp"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// Subagent：~/.claude/agents 下每个 .md 是一个，前言写名称、什么时候用、模型、工具，正文是系统提示词。

type agentsState struct {
	list    []coreapi.Subagent
	loaded  bool
	editing *coreapi.Subagent // nil 是新建
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
var agentModels = []option{
	{"", locales.ClaudecfgAgentModelDefault}, {"inherit", locales.ClaudecfgAgentModelInherit}, {"sonnet", fixed("Sonnet")},
	{"opus", fixed("Opus")}, {"haiku", fixed("Haiku")}, {"fable", fixed("Fable")},
}

// fixed 是不分语言的说法（模型名）。
func fixed(s string) func() string { return func() string { return s } }

// commonTools 是常用的内置工具；别的（比如 MCP 工具 mcp__服务器__工具）写进“其他工具”。
var commonTools = []option{
	{"Read", locales.ClaudecfgToolRead}, {"Edit", locales.ClaudecfgToolEdit}, {"Write", locales.ClaudecfgToolWrite},
	{"Glob", locales.ClaudecfgToolGlob}, {"Grep", locales.ClaudecfgToolGrep}, {"Bash", locales.ClaudecfgToolBash},
	{"WebFetch", locales.ClaudecfgToolWebFetch}, {"WebSearch", locales.ClaudecfgToolWebSearch},
	{"NotebookEdit", locales.ClaudecfgToolNotebookEdit}, {"Skill", locales.ClaudecfgToolSkill},
}

var agentName = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)

func agentModelLabel(model string) string {
	for _, m := range agentModels {
		if strings.EqualFold(m.value, model) {
			return m.label()
		}
	}
	return model
}

func isCommonTool(name string) bool {
	return slices.ContainsFunc(commonTools, func(t option) bool { return t.value == name })
}

func draftOf(a *coreapi.Subagent) agentDraft {
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
		if d.checked[t.value] {
			out = append(out, t.value)
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
		return locales.ClaudecfgAgentNameInvalid()
	case strings.TrimSpace(d.description) == "":
		return locales.ClaudecfgAgentWhenMissing()
	}
	return ""
}

func (p *Page) openAgents() {
	p.agents = agentsState{}
	p.reloadAgents()
}

func (p *Page) reloadAgents() {
	p.async(func(ctx context.Context) func() {
		list, err := p.opts.Core.Agents(ctx)
		return func() {
			if err != nil {
				p.failed(locales.ClaudecfgAgentsLoadFailed(), err)
				return
			}
			p.agents.list, p.agents.loaded = list, true
		}
	})
}

func (p *Page) agentsPage(c *ui.Context) {
	a := &p.agents
	pageTitle(c, locales.ClaudecfgSubagents(), p.back, func() {
		widget.SmallPrimaryButton(c, locales.CommonNew()).Label(locales.ClaudecfgAgentNew()).OnClick(func() { p.openAgent(nil) })
	})
	body(c, "agents", func() {
		footer := locales.ClaudecfgAgentsFooter()
		widget.Group(c, locales.ClaudecfgAgentsCount(len(a.list)), footer, func(l *widget.List) {
			switch {
			case !a.loaded:
				loadingRow(l, locales.CommonLoading())
			case len(a.list) == 0:
				emptyRow(l, locales.ClaudecfgAgentsEmpty(), locales.ClaudecfgAgentsEmptyHint())
			}
			for i := range a.list {
				ag := a.list[i]
				name := ag.Name
				if name == "" {
					name = ag.File
				}
				ui.Column(c.Key(ag.File)).Children(func() {
					entry(l, ag.File, name, clipLine(ag.Description, 90), agentModelLabel(ag.Model), true, func() { p.openAgent(&ag) })
				})
			}
		})
	})
}

func (p *Page) openAgent(a *coreapi.Subagent) {
	p.agents.editing, p.agents.draft, p.agents.problem = a, draftOf(a), ""
	p.push(viewAgent)
}

func (p *Page) saveAgent() {
	a := &p.agents
	d := a.draft
	draft := coreapi.SubagentDraft{Name: strings.TrimSpace(d.name), Description: strings.TrimSpace(d.description), Model: d.model, Tools: d.tools(), Prompt: d.prompt}
	original := a.editing
	a.saving, a.problem = true, ""
	p.async(func(ctx context.Context) func() {
		_, err := p.opts.Core.SaveAgent(ctx, original, draft)
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
	missing := d.missing()
	dirty := a.editing == nil || !agentDraftEqual(*d, draftOf(a.editing))
	title := locales.ClaudecfgAgentNew()
	if a.editing != nil {
		title = locales.ClaudecfgAgentEdit()
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
			widget.SmallDangerButton(c, locales.CommonDelete()).Disabled(a.saving).OnClick(func() { a.delete = true })
		}
		label := locales.CommonSave()
		if a.saving {
			label = locales.ClaudecfgSaving()
		}
		widget.SmallPrimaryButton(c, label).Disabled(a.saving || missing != "").OnClick(p.saveAgent)
	})
	body(c, "agent", func() {
		widget.Group(c, locales.ClaudecfgName(), locales.ClaudecfgAgentNameFooter(), func(l *widget.List) {
			field(l, "name", &d.name, locales.ClaudecfgName(), "code-reviewer", a.saving)
		})
		widget.Group(c, locales.ClaudecfgAgentWhen(), locales.ClaudecfgAgentWhenFooter(), func(l *widget.List) {
			l.Custom(func() {
				area(c.Key("description"), &d.description, 2, 6).Placeholder(locales.ClaudecfgAgentWhenPlaceholder()).Label(locales.ClaudecfgAgentWhen()).
					Disabled(a.saving).Grow(1)
			})
		})
		p.agentModelSection(c)
		p.agentToolsSection(c)
		widget.Group(c, locales.ClaudecfgAgentPrompt(), locales.ClaudecfgAgentPromptFooter(), func(l *widget.List) {
			l.Custom(func() {
				area(c.Key("prompt"), &d.prompt, 8, 24).Placeholder(locales.ClaudecfgAgentPromptPlaceholder()).Label(locales.ClaudecfgAgentPrompt()).Disabled(a.saving).Grow(1)
			})
		})
		if a.problem != "" {
			problemText(c, a.problem).Padding(0, 32)
		} else if missing != "" && (d.name != "" || d.description != "") {
			muted(c, missing)
		}
		if a.editing != nil && len(a.editing.ExtraKeys) > 0 {
			muted(c, locales.ClaudecfgAgentExtraKeys(strings.Join(a.editing.ExtraKeys, locales.ClaudecfgListSeparator())))
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
		values, labels = append(values, m.value), append(labels, m.label())
	}
	selected := slices.IndexFunc(values, func(v string) bool { return strings.EqualFold(v, d.model) })
	if selected < 0 {
		values, labels = append(values, d.model), append(labels, d.model)
		selected = len(values) - 1
	}
	widget.Group(c, locales.ClaudecfgAgentModel(), locales.ClaudecfgAgentModelFooter(), func(l *widget.List) {
		item(l, locales.ClaudecfgAgentModel(), "", func() {
			choice(c, "model", locales.ClaudecfgAgentModel(), labels, selected, p.agents.saving, func(i int) { d.model = values[i] })
		})
	})
}

func (p *Page) agentToolsSection(c *ui.Context) {
	d := &p.agents.draft
	saving := p.agents.saving
	footer := locales.ClaudecfgAgentToolsFooter()
	widget.Group(c, locales.ClaudecfgTools(), footer, func(l *widget.List) {
		item(l, locales.ClaudecfgAllTools(), locales.ClaudecfgAllToolsSummary(), func() {
			widget.Switch(c.Key("all-tools"), &d.allTools).Label(locales.ClaudecfgAllTools()).Disabled(saving)
		})
		if d.allTools {
			return
		}
		for _, tool := range commonTools {
			on := d.checked[tool.value]
			row := widget.Row{Key: "tool-" + tool.value, Title: tool.label(), Summary: tool.value, End: func() { widget.MarkLook(c, widget.MarkCheck, on) }}
			if !saving {
				row.OnClick = func() { d.checked[tool.value] = !on }
			}
			l.Row(row).Role(ui.RoleCheckBox).Checked(on)
		}
		item(l, locales.ClaudecfgOtherTools(), "", func() {
			widget.Input(c.Key("others"), &d.others).Placeholder("mcp__github__create_issue, …").Label(locales.ClaudecfgOtherTools()).Disabled(saving).Width(320)
		})
	})
}

func (p *Page) agentDialogs(c *ui.Context) {
	a := &p.agents
	if alert(c, &a.leaving, locales.ClaudecfgDiscardTitle(), "", widget.Destructive, locales.CommonCancel(), locales.ClaudecfgDiscard()) == 1 {
		p.back()
	}
	if a.editing == nil {
		return
	}
	file, name := a.editing.File, a.editing.Name
	if alert(c, &a.delete, locales.ClaudecfgAgentDeleteTitle(), locales.ClaudecfgAgentDeleteMessage(name), widget.Destructive,
		locales.CommonCancel(), locales.CommonDelete()) == 1 {
		p.async(func(ctx context.Context) func() {
			err := p.opts.Core.DeleteAgent(ctx, file)
			return func() {
				if err != nil {
					p.failed(locales.ClaudecfgDeleteFailed(), err)
					return
				}
				p.back()
				p.reloadAgents()
			}
		})
	}
}
