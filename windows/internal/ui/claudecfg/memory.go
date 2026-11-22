package claudecfg

import (
	"context"
	"errors"

	cfg "github.com/dzshzx/Zeta/windows/internal/claudecfg"
	"github.com/egoist/mygo/ui"
)

// 记忆：Claude Code 有两种长期记忆。一是用户写给它的指令 ~/.claude/CLAUDE.md，每次对话都载入；二是它自己记的自动记忆，
// 每个项目一个目录，每条一个文件，MEMORY.md 是索引。

type memoryState struct {
	data    *cfg.Memory
	project string // 选着的项目，空是默认工作目录所在的
	working bool
}

func (p *Page) openMemory() {
	p.memory = memoryState{project: p.memory.project}
	p.reloadMemory()
}

func (p *Page) reloadMemory() {
	project := p.memory.project
	p.async(func(ctx context.Context) func() {
		m, err := p.opts.Client.Memory(ctx, project)
		return func() {
			if err != nil {
				p.failed("读取记忆失败", err)
				return
			}
			p.memory.data = &m
		}
	})
}

// typeLabel 是记忆的类型。
func typeLabel(t string) string {
	switch t {
	case "user":
		return "用户"
	case "feedback":
		return "反馈"
	case "project":
		return "项目"
	case "reference":
		return "参考"
	}
	return t
}

func (p *Page) memoryPage(c *ui.Context) {
	m := p.memory.data
	pageTitle(c, "记忆", p.back, nil)
	body(c, "memory", func() {
		section(c, "用户指令", "~/.claude/CLAUDE.md 里是你写给 Claude 的长期指令，所有项目通用。改动对之后新开始的对话生效。", func() {
			if m == nil {
				loadingRow(c, "正在读取…")
				return
			}
			summary := "还没有创建，打开就能写"
			if m.Instructions.Exists {
				summary = sizeText(m.Instructions.Size)
			}
			entry(c, "CLAUDE.md", summary, "", true, func() {
				p.openDocument(document{path: m.Instructions.Path, title: "用户指令", file: "CLAUDE.md",
					placeholder: "比如：回答用中文；我的主要技术栈是 Go；改代码前先说明打算怎么改"})
			})
		})
		section(c, "自动记忆", "Claude 会在对话中自己记下你的偏好、纠正和项目背景。开关对所有项目生效；关掉后不再读写，记忆留着。", func() {
			if m == nil {
				loadingRow(c, "正在读取…")
				return
			}
			p.autoMemoryRows(c, *m)
		})
		if m != nil && m.Index != nil {
			p.memoryFiles(c, *m)
		}
	})
}

func (p *Page) autoMemoryRows(c *ui.Context, m cfg.Memory) {
	on := m.AutoMemory && !m.ForcedOff
	summary := ""
	if m.ForcedOff {
		summary = "被环境变量关掉了，这里改不了"
	}
	item(c, "启用自动记忆", summary, func() {
		sw := ui.Switch(c.Key("auto-memory"), &on).Label("启用自动记忆").Disabled(m.ForcedOff || p.memory.working)
		if sw.Changed() {
			p.memory.working = true
			p.async(func(ctx context.Context) func() {
				err := p.opts.Client.SetAutoMemory(ctx, on)
				return func() {
					p.memory.working = false
					if err != nil {
						p.failed("没有改成", err)
					}
					p.reloadMemory()
				}
			})
		}
	})
	switch {
	case m.CustomDir != "":
		// settings.json 里设了 autoMemoryDirectory：所有项目共用这一个目录，没有项目可选
		item(c, "记忆目录", m.CustomDir, func() { ui.Text(c, "所有项目共用").TextColor(c.Theme().TextMuted) })
	case len(m.Projects) > 0:
		var labels []string
		selected := 0
		for i, pr := range m.Projects {
			label := pr.Label
			if pr.Default {
				label += "（默认工作目录）"
			}
			labels = append(labels, label)
			if pr.Key == m.Selected {
				selected = i
			}
		}
		item(c, "项目", "", func() {
			choice(c, "项目", labels, selected, false, func(i int) {
				p.memory.project = m.Projects[i].Key
				p.reloadMemory()
			})
		})
	}
	if idx := m.Index; idx != nil {
		summary, value := "记忆的索引，还没有", ""
		if idx.Exists {
			summary, value = "记忆的索引", sizeText(idx.Size)
		}
		entry(c, "MEMORY.md", summary, value, true, func() {
			p.openDocument(document{path: idx.Path, title: "MEMORY.md", file: "MEMORY.md", placeholder: "一行一条记忆：- [标题](文件名.md) — 一句话说明"})
		})
	}
}

func (p *Page) memoryFiles(c *ui.Context, m cfg.Memory) {
	section(c, "记忆 · "+itoa(len(m.Files)), "", func() {
		if len(m.Files) == 0 {
			emptyRow(c, "这个项目还没有记忆", "让 Claude 记住的东西会出现在这里")
		}
		for _, f := range m.Files {
			name := f.Name
			if name == "" {
				name = f.FileName
			}
			summary := f.Description
			if summary == "" {
				summary = f.FileName
			}
			ui.Column(c.Key(f.Path)).Children(func() {
				entry(c, name, clipLine(summary, 90), typeLabel(f.Type), true, func() {
					p.openDocument(document{path: f.Path, title: name, file: f.FileName, deletable: true})
				})
			})
		}
	})
}

// ---------- 文档编辑 ----------

// document 是要编辑的一个文件。
type document struct {
	path, title, file, placeholder string
	deletable                      bool // 一条记忆：可以删
}

type documentState struct {
	doc      document
	loading  bool
	saved    string // 读到或存下的内容
	sha      string // 写回时比对的版本；原来不存在时是空串
	draft    string
	saving   bool
	conflict bool // 正问着：文件在编辑期间被改过了，覆盖不覆盖
	leaving  bool // 正问着：放弃未保存的更改吗
	deleting bool
}

func (p *Page) openDocument(d document) {
	p.doc = documentState{doc: d, loading: true}
	p.push(viewDocument)
	p.async(func(ctx context.Context) func() {
		got, err := p.opts.Client.ReadFile(ctx, d.path)
		return func() {
			p.doc.loading = false
			if err != nil {
				p.back()
				p.failed("读取失败", err)
				return
			}
			p.doc.saved, p.doc.draft = got.Content, got.Content
			if got.Exists {
				p.doc.sha = got.SHA
			}
		}
	})
}

func (p *Page) saveDocument(force bool) {
	s := &p.doc
	s.saving = true
	path, content, sha := s.doc.path, s.draft, s.sha
	p.async(func(ctx context.Context) func() {
		newSHA, err := p.opts.Client.WriteFile(ctx, path, content, sha, force)
		return func() {
			s.saving = false
			switch {
			case errors.Is(err, cfg.ErrConflict):
				s.conflict = true
			case err != nil:
				p.failed("保存失败", err)
			default:
				s.saved, s.sha = content, newSHA
				p.reloadMemory()
			}
		}
	})
}

// documentPage 是整页的文件编辑：上面一行文件名和大小，中间是正文，有改动时底下是保存、放弃。保存时文件在编辑期间被改过
// （多半是 Claude 改的）先问一声。
func (p *Page) documentPage(c *ui.Context) {
	s := &p.doc
	t := c.Theme()
	dirty := !s.loading && s.draft != s.saved
	leave := func() {
		if dirty {
			s.leaving = true
		} else {
			p.back()
		}
	}
	pageTitle(c, s.doc.title, leave, func() {
		if s.doc.deletable {
			ui.Button(c, "删除").TextColor(t.Danger).Disabled(s.loading || s.saving).OnClick(func() { s.deleting = true })
		}
	})
	ui.Column(c).Grow(1).Padding(0, 24, 24, 24).Gap(12).Children(func() {
		size := int64(len(s.draft))
		over := size > cfg.MaxDocument
		info := s.doc.file + " · " + sizeText(size) + " / 1 MiB"
		if over {
			info += " · 超过上限"
		}
		ui.Text(c, info).FontSize(12).TextColor(t.TextMuted)
		if s.loading {
			loadingRow(c, "正在读取…")
			return
		}
		ui.TextArea(c.Key("document"), &s.draft).Placeholder(s.doc.placeholder).Label(s.doc.file).Grow(1).Disabled(s.saving)
		if dirty || s.saving {
			ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
				hint := "未保存的更改"
				if over {
					hint = "已超过 1 MiB 上限，请删减"
				}
				ui.Text(c, hint).FontSize(12).TextColor(t.TextMuted)
				ui.Spacer(c)
				ui.Button(c, "放弃").Disabled(s.saving).OnClick(func() { s.draft = s.saved })
				label := "保存"
				if s.saving {
					label = "保存中"
				}
				ui.PrimaryButton(c, label).Disabled(s.saving || over).OnClick(func() { p.saveDocument(false) })
			})
		}
	})
	p.documentDialogs(c)
}

func (p *Page) documentDialogs(c *ui.Context) {
	s := &p.doc
	if ui.AlertDialog(c, &s.conflict, "文件已经变了", "编辑期间这个文件被改过了，多半是 Claude 在对话里更新了它。保存会用你的版本覆盖。", "取消", "仍然保存") == 1 {
		p.saveDocument(true)
	}
	if ui.AlertDialog(c, &s.leaving, "放弃未保存的更改？", "", "取消", "放弃") == 1 {
		p.back()
	}
	message := "删除 " + s.doc.file + "，并从 MEMORY.md 里去掉它那一行。删了就找不回来。"
	if ui.AlertDialog(c, &s.deleting, "删除这条记忆", message, "取消", "删除") == 1 {
		path := s.doc.path
		p.async(func(ctx context.Context) func() {
			err := p.opts.Client.DeleteMemory(ctx, path)
			return func() {
				if err != nil {
					p.failed("删除失败", err)
				} else {
					p.back()
				}
				p.reloadMemory()
			}
		})
	}
}
