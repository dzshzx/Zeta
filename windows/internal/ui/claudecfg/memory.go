package claudecfg

import (
	"context"
	"errors"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/ui/chatview"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 记忆：Claude Code 有两种长期记忆。一是用户写给它的指令 ~/.claude/CLAUDE.md，每次对话都载入；二是它自己记的自动记忆，
// 每个项目一个目录，每条一个文件，MEMORY.md 是索引。

type memoryState struct {
	data    *coreapi.Memory
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
		m, err := p.opts.Core.Memory(ctx, p.opts.Cwd, project)
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
		widget.Group(c, "用户指令", "~/.claude/CLAUDE.md 里是你写给 Claude 的长期指令，所有项目通用。改动对之后新开始的对话生效。", func(l *widget.List) {
			if m == nil {
				loadingRow(l, "正在读取…")
				return
			}
			summary := "还没有创建，打开就能写"
			if m.Instructions.Exists {
				summary = sizeText(m.Instructions.Size)
			}
			entry(l, "CLAUDE.md", summary, "", true, func() {
				p.openDocument(document{path: m.Instructions.Path, title: "用户指令", file: "CLAUDE.md",
					placeholder: "比如：回答用中文；我的主要技术栈是 Go；改代码前先说明打算怎么改"})
			})
		})
		widget.Group(c, "自动记忆", "Claude 会在对话中自己记下你的偏好、纠正和项目背景。开关对所有项目生效；关掉后不再读写，记忆留着。", func(l *widget.List) {
			if m == nil {
				loadingRow(l, "正在读取…")
				return
			}
			p.autoMemoryRows(l, *m)
		})
		if m != nil && m.Index != nil {
			p.memoryFiles(c, *m)
		}
	})
}

func (p *Page) autoMemoryRows(l *widget.List, m coreapi.Memory) {
	c := l.Context()
	on := m.AutoMemory && !m.ForcedOff
	summary := ""
	if m.ForcedOff {
		summary = "被环境变量关掉了，这里改不了"
	}
	item(l, "启用自动记忆", summary, func() {
		sw := widget.Switch(c.Key("auto-memory"), &on).Label("启用自动记忆").Disabled(m.ForcedOff || p.memory.working)
		if sw.Changed() {
			p.memory.working = true
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.SetAutoMemory(ctx, on)
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
		l.Row(widget.Row{Title: "记忆目录", Summary: m.CustomDir, Value: "所有项目共用"})
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
		item(l, "项目", "", func() {
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
		entry(l, "MEMORY.md", summary, value, true, func() {
			p.openDocument(document{path: idx.Path, title: "MEMORY.md", file: "MEMORY.md", placeholder: "一行一条记忆：- [标题](文件名.md) — 一句话说明"})
		})
	}
}

func (p *Page) memoryFiles(c *ui.Context, m coreapi.Memory) {
	widget.Group(c, "记忆 · "+itoa(len(m.Files)), "", func(l *widget.List) {
		if len(m.Files) == 0 {
			emptyRow(l, "这个项目还没有记忆", "让 Claude 记住的东西会出现在这里")
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
				entry(l, name, clipLine(summary, 90), typeLabel(f.Type), true, func() {
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
	preview  bool // 在看排好的样子（预览），不是在编辑；有内容的打开时先预览
	md       *chatview.Document
}

func (p *Page) openDocument(d document) {
	p.doc = documentState{doc: d, loading: true, md: chatview.NewDocument()}
	p.push(viewDocument)
	p.async(func(ctx context.Context) func() {
		got, err := p.opts.Core.ReadMemoryFile(ctx, d.path)
		return func() {
			p.doc.loading = false
			if err != nil {
				p.back()
				p.failed("读取失败", err)
				return
			}
			p.doc.saved, p.doc.draft = got.Content, got.Content
			p.doc.preview = strings.TrimSpace(got.Content) != ""
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
		newSHA, err := p.opts.Core.WriteMemoryFile(ctx, path, content, sha, force)
		return func() {
			s.saving = false
			switch {
			case errors.Is(err, coreapi.ErrConflict):
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

// documentPage 是整页的文件编辑（同手机版的 DocumentEditorScreen）：上面一行文件名和大小，右边「预览 / 编辑」切换；中间是正文，
// 预览时照聊天里回答的样子排（去掉开头的 --- 前言块），编辑时是输入框；有改动时底下是保存、放弃。保存时文件在编辑期间被改过
// （多半是 Claude 改的）先问一声。
func (p *Page) documentPage(c *ui.Context) {
	s := &p.doc
	k := widget.Of(c)
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
			widget.SmallDangerButton(c, "删除").Disabled(s.loading || s.saving).OnClick(func() { s.deleting = true })
		}
	})
	ui.Column(c).Grow(1).MinHeight(0).FillWidth().MaxWidth(widget.PageWidth).AlignSelf(ui.Center).Padding(8, 16, 16, 16).Gap(8).
		Children(func() {
			size := int64(len(s.draft))
			over := size > coreapi.MaxMemoryFile
			info := s.doc.file + " · " + sizeText(size) + " / 1 MiB"
			if over {
				info += " · 超过上限"
			}
			ui.Row(c).FillWidth().Padding(0, 0, 0, 16).AlignItems(ui.Center).Children(func() {
				ui.Text(c, info).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).Grow(1)
				if !s.loading {
					mode := 1
					if s.preview {
						mode = 0
					}
					if widget.Segmented(c, "预览或编辑", &mode, "预览", "编辑") {
						s.preview = mode == 0
					}
				}
			})
			if s.loading {
				widget.Group(c, "", "", func(l *widget.List) { loadingRow(l, "正在读取…") })
				return
			}
			if s.preview {
				p.documentPreview(c)
			} else {
				// 正文在一张白卡片里，占满剩下的高
				editor := ui.TextAreaBase(c.Key("document"), &s.draft).Placeholder(s.doc.placeholder).Label(s.doc.file).
					Disabled(s.saving).Grow(1).FillWidth().Padding(14, 16).FontSize(widget.SizeBodyL).LineHeight(1.55).
					PlaceholderColor(k.Tertiary).TextColor(k.Text).FocusRing(false)
				widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(editor)
			}
			if dirty || s.saving {
				p.saveBar(c, over)
			}
		})
	p.documentDialogs(c)
}

// documentPreview 是预览：正文照聊天里回答的样子排在一张白卡片里，能滚动；没有内容时说一句，点一下去编辑。
func (p *Page) documentPreview(c *ui.Context) {
	s := &p.doc
	k := widget.Of(c)
	body := stripFrontmatter(s.draft)
	if strings.TrimSpace(body) == "" {
		empty := ui.ButtonBase(c.Key("empty")).Label("还没有内容，去编辑").Grow(1).FillWidth().Cursor(ui.CursorText).Padding(24)
		widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(empty)
		empty.OnClick(func() { s.preview = false })
		empty.Children(func() {
			ui.Column(c).Grow(1).Center().Gap(6).Children(func() {
				ui.Text(c, "还没有内容").FontSize(widget.SizeBodyXL).FontWeight(widget.Medium).TextColor(k.Text)
				ui.Text(c, s.doc.placeholder).FontSize(widget.SizeBodyM).TextColor(k.Secondary)
			})
		})
		return
	}
	box := ui.Scroll(c.Key("preview")).Grow(1).MinHeight(0).FillWidth()
	widget.Surface{Shape: widget.ShapeCard, Fill: k.Card}.Paint(box)
	box.Children(func() {
		ui.Column(c).FillWidth().Padding(14, 16).Children(func() { s.md.View(c, body).FillWidth() })
	})
}

// saveBar 是有改动时底下那一行：说明（超过上限时红字），放弃、保存。
func (p *Page) saveBar(c *ui.Context, over bool) {
	s := &p.doc
	k := widget.Of(c)
	ui.Row(c).Gap(8).Padding(4, 0, 0, 16).AlignItems(ui.Center).Children(func() {
		hint := "未保存的更改"
		color := k.Secondary
		if over {
			hint, color = "已超过 1 MiB 上限，请删减", k.Error
		}
		ui.Text(c, hint).FontSize(widget.SizeBodyXS).TextColor(color)
		ui.Spacer(c)
		widget.SmallButton(c, "放弃").Disabled(s.saving).OnClick(func() { s.draft = s.saved })
		label := "保存"
		if s.saving {
			label = "保存中"
		}
		widget.SmallPrimaryButton(c, label).Disabled(s.saving || over).OnClick(func() { p.saveDocument(false) })
	})
}

// stripFrontmatter 去掉正文开头的 --- 前言块（记忆文件的 name、description、type）：预览时不显示，Markdown 会把它排成分隔线和标题
// （同手机版的 stripFrontmatter）。没有收尾的 --- 时原样。
func stripFrontmatter(text string) string {
	if !strings.HasPrefix(text, "---") {
		return text
	}
	lines := strings.Split(text, "\n")
	if strings.TrimSpace(lines[0]) != "---" {
		return text
	}
	for i := 1; i < len(lines); i++ {
		if strings.TrimSpace(lines[i]) == "---" {
			return strings.TrimLeft(strings.Join(lines[i+1:], "\n"), "\n")
		}
	}
	return text
}

func (p *Page) documentDialogs(c *ui.Context) {
	s := &p.doc
	if alert(c, &s.conflict, "文件已经变了", "编辑期间这个文件被改过了，多半是 Claude 在对话里更新了它。保存会用你的版本覆盖。",
		widget.Destructive, "取消", "仍然保存") == 1 {
		p.saveDocument(true)
	}
	if alert(c, &s.leaving, "放弃未保存的更改？", "", widget.Destructive, "取消", "放弃") == 1 {
		p.back()
	}
	message := "删除 " + s.doc.file + "，并从 MEMORY.md 里去掉它那一行。删了就找不回来。"
	if alert(c, &s.deleting, "删除这条记忆", message, widget.Destructive, "取消", "删除") == 1 {
		path := s.doc.path
		p.async(func(ctx context.Context) func() {
			err := p.opts.Core.DeleteMemory(ctx, path)
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
