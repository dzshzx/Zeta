package claudecfg

import (
	"context"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// Skills：~/.claude/skills 下每个含 SKILL.md 的文件夹是一个 Skill。开关写的是 Claude Code 自己的 skillOverrides，
// 和它的 /skills 菜单一样，文件不动。账号同步的只列出来。

type skillsState struct {
	data      *coreapi.Skills
	importing bool
	pending   *pendingZip // 已经有同名的，正问着替换不替换
	deleting  *coreapi.Skill
	showing   *notice // 看说明
}

type pendingZip struct {
	name, existing string
	file           string
	data           []byte
	open           bool
}

func (p *Page) openSkills() {
	p.skills = skillsState{}
	p.reloadSkills()
}

func (p *Page) reloadSkills() {
	p.async(func(ctx context.Context) func() {
		s, err := p.opts.Core.Skills(ctx)
		return func() {
			if err != nil {
				p.failed("读取 Skills 失败", err)
				return
			}
			p.skills.data = &s
		}
	})
}

// pickZip 让用户选 ZIP，读好后装上。
func (p *Page) pickZip() {
	p.skills.importing = true
	p.async(func(ctx context.Context) func() {
		name, data, err := p.opts.OpenZip()
		if err != nil || name == "" {
			return func() {
				p.skills.importing = false
				if err != nil {
					p.failed("没有装上", err)
				}
			}
		}
		res, err := p.opts.Core.ImportSkill(ctx, name, data, false)
		return func() { p.installed(name, data, res, err) }
	})
}

func (p *Page) installed(file string, data []byte, res coreapi.SkillImport, err error) {
	p.skills.importing = false
	switch {
	case err != nil:
		p.failed("没有装上", err)
	case res.Exists:
		p.skills.pending = &pendingZip{name: res.Name, existing: res.ExistingDescription, file: file, data: data, open: true}
	default:
		title := "已安装"
		if res.Replaced {
			title = "已替换"
		}
		message := "“" + res.Name + "”装好了，Claude 会按需调用它。"
		if !res.Enabled {
			message = "“" + res.Name + "”装好了，但它被设成停用，打开开关后才能用。"
		}
		p.say(title, message)
		p.reloadSkills()
	}
}

func (p *Page) skillsPage(c *ui.Context) {
	s := &p.skills
	pageTitle(c, "Skills", p.back, nil)
	body(c, "skills", func() {
		coui.Group(c, "", "", func(l *coui.List) {
			summary := "选一个含 SKILL.md 的 Skill 包"
			if s.importing {
				summary = "正在检查并安装…"
			}
			item(l, "从 ZIP 导入", summary, func() {
				coui.SmallPrimaryButton(c, "选择文件").Disabled(s.importing).OnClick(p.pickZip)
			})
		})
		p.userSkills(c)
		p.syncedSkills(c)
	})
	p.skillDialogs(c)
}

func (p *Page) userSkills(c *ui.Context) {
	s := &p.skills
	coui.Group(c, "用户安装 · "+itoa(p.userSkillCount()), "装在 ~/.claude/skills 里。关掉后文件留着，Claude 不再用它；改动对之后新开始的对话生效。", func(l *coui.List) {
		switch {
		case s.data == nil:
			loadingRow(l, "正在读取…")
			return
		case len(s.data.User) == 0:
			emptyRow(l, "还没有 Skill", "从 ZIP 导入，或让 Claude 写一个")
		}
		for _, sk := range s.data.User {
			ui.Column(c.Key(sk.Name)).Children(func() { p.skillRow(l, sk) })
		}
	})
}

// syncedSkills 是账号同步的，只列出来。
func (p *Page) syncedSkills(c *ui.Context) {
	s := &p.skills
	if s.data == nil || len(s.data.Synced) == 0 {
		return
	}
	coui.Group(c, "账号同步 · "+itoa(len(s.data.Synced)), "在 claude.ai 上管理，这里只能看。", func(l *coui.List) {
		for _, sk := range s.data.Synced {
			item(l, sk.Name, clipLine(orNone(sk.Description), 90), func() {
				if !sk.Enabled {
					ui.Text(c, "已停用").FontSize(coui.SizeBodyM).TextColor(coui.Of(c).Tertiary)
				}
			})
		}
	})
}

func (p *Page) userSkillCount() int {
	if p.skills.data == nil {
		return 0
	}
	return len(p.skills.data.User)
}

func orNone(s string) string {
	if s == "" {
		return "无描述"
	}
	return s
}

func (p *Page) skillRow(l *coui.List, sk coreapi.Skill) {
	c := l.Context()
	title := sk.Name
	if sk.Title != "" && sk.Title != sk.Name {
		title = sk.Title + "（" + sk.Name + "）"
	}
	summary := clipLine(orNone(sk.Description), 90)
	if !sk.Valid {
		summary = "缺少 SKILL.md"
	}
	item(l, title, summary, func() {
		description := orNone(sk.Description)
		if !sk.Valid {
			description = "这个文件夹里没有 SKILL.md，Claude Code 不会把它当成 Skill。"
		}
		coui.SmallButton(c, "说明").Label("说明：" + sk.Name).OnClick(func() { p.skills.showing = &notice{title: sk.Name, message: description, open: true} })
		coui.SmallDangerButton(c, "删除").Label("删除：" + sk.Name).OnClick(func() { p.skills.deleting = &sk })
		on := sk.Enabled
		if coui.Switch(c, &on).Label("启用：" + sk.Name).Changed() {
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.SetSkillEnabled(ctx, sk, on)
				return func() {
					if err != nil {
						title := "没有停用"
						if on {
							title = "没有启用"
						}
						p.failed(title, err)
					}
					p.reloadSkills()
				}
			})
		}
	})
}

func (p *Page) skillDialogs(c *ui.Context) {
	s := &p.skills
	if pz := s.pending; pz != nil {
		message := "已经有一个叫“" + pz.name + "”的 Skill。替换会覆盖它当前的全部文件。"
		if alert(c, &pz.open, "替换 Skill？", message, coui.Destructive, "取消", "替换") == 1 {
			s.importing = true
			p.async(func(ctx context.Context) func() {
				res, err := p.opts.Core.ImportSkill(ctx, pz.file, pz.data, true)
				return func() { p.installed(pz.file, pz.data, res, err) }
			})
		}
		if !pz.open {
			s.pending = nil
		}
	}
	if d := s.deleting; d != nil {
		open := true
		switch alert(c, &open, "删除 Skill", "删除“"+d.Name+"”的整个文件夹，之后需要重新导入才能恢复。", coui.Destructive, "取消", "删除") {
		case 1:
			name := d.Name
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.DeleteSkill(ctx, name)
				return func() {
					if err != nil {
						p.failed("删除失败", err)
					}
					p.reloadSkills()
				}
			})
		}
		if !open {
			s.deleting = nil
		}
	}
	if n := s.showing; n != nil {
		alert(c, &n.open, n.title, n.message, coui.Recommend, "关闭")
		if !n.open {
			s.showing = nil
		}
	}
}
