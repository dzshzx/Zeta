package claudecfg

import (
	"context"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
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
				p.failed(locales.ClaudecfgSkillsLoadFailed(), err)
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
					p.failed(locales.ClaudecfgSkillInstallFailed(), err)
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
		p.failed(locales.ClaudecfgSkillInstallFailed(), err)
	case res.Exists:
		p.skills.pending = &pendingZip{name: res.Name, existing: res.ExistingDescription, file: file, data: data, open: true}
	default:
		title := locales.ClaudecfgSkillInstalled()
		if res.Replaced {
			title = locales.ClaudecfgSkillReplaced()
		}
		message := locales.ClaudecfgSkillInstalledMessage(res.Name)
		if !res.Enabled {
			message = locales.ClaudecfgSkillInstalledDisabled(res.Name)
		}
		p.say(title, message)
		p.reloadSkills()
	}
}

func (p *Page) skillsPage(c *ui.Context) {
	s := &p.skills
	pageTitle(c, "Skills", p.back, nil)
	body(c, "skills", func() {
		widget.Group(c, "", "", func(l *widget.List) {
			summary := locales.ClaudecfgSkillPick()
			if s.importing {
				summary = locales.ClaudecfgSkillInstalling()
			}
			item(l, locales.ClaudecfgSkillImport(), summary, func() {
				widget.SmallPrimaryButton(c, locales.ClaudecfgSkillChooseFile()).Disabled(s.importing).OnClick(p.pickZip)
			})
		})
		p.userSkills(c)
		p.syncedSkills(c)
	})
	p.skillDialogs(c)
}

func (p *Page) userSkills(c *ui.Context) {
	s := &p.skills
	widget.Group(c, locales.ClaudecfgSkillsUser(p.userSkillCount()), locales.ClaudecfgSkillsUserFooter(), func(l *widget.List) {
		switch {
		case s.data == nil:
			loadingRow(l, locales.ClaudecfgLoading())
			return
		case len(s.data.User) == 0:
			emptyRow(l, locales.ClaudecfgSkillsEmpty(), locales.ClaudecfgSkillsEmptyHint())
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
	widget.Group(c, locales.ClaudecfgSkillsSynced(len(s.data.Synced)), locales.ClaudecfgManagedOnClaudeAi(), func(l *widget.List) {
		for _, sk := range s.data.Synced {
			item(l, sk.Name, clipLine(orNone(sk.Description), 90), func() {
				if !sk.Enabled {
					ui.Text(c, locales.ClaudecfgSkillDisabled()).FontSize(widget.SizeBodyM).TextColor(widget.Of(c).Tertiary)
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
		return locales.ClaudecfgSkillNoDescription()
	}
	return s
}

func (p *Page) skillRow(l *widget.List, sk coreapi.Skill) {
	c := l.Context()
	title := sk.Name
	if sk.Title != "" && sk.Title != sk.Name {
		title = locales.ClaudecfgSkillTitle(sk.Title, sk.Name)
	}
	summary := clipLine(orNone(sk.Description), 90)
	if !sk.Valid {
		summary = locales.ClaudecfgSkillMissingFile()
	}
	item(l, title, summary, func() {
		description := orNone(sk.Description)
		if !sk.Valid {
			description = locales.ClaudecfgSkillMissingFileDetail()
		}
		widget.SmallButton(c, locales.ClaudecfgSkillDetails()).Label(locales.ClaudecfgSkillDetailsOf(sk.Name)).OnClick(func() { p.skills.showing = &notice{title: sk.Name, message: description, open: true} })
		widget.SmallDangerButton(c, locales.ClaudecfgDelete()).Label(locales.ClaudecfgDeleteItem(sk.Name)).OnClick(func() { p.skills.deleting = &sk })
		on := sk.Enabled
		if widget.Switch(c, &on).Label(locales.ClaudecfgSkillEnableOf(sk.Name)).Changed() {
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.SetSkillEnabled(ctx, sk, on)
				return func() {
					if err != nil {
						title := locales.ClaudecfgSkillDisableFailed()
						if on {
							title = locales.ClaudecfgSkillEnableFailed()
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
		message := locales.ClaudecfgSkillReplaceMessage(pz.name)
		if alert(c, &pz.open, locales.ClaudecfgSkillReplaceTitle(), message, widget.Destructive, locales.ClaudecfgCancel(), locales.ClaudecfgSkillReplace()) == 1 {
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
		switch alert(c, &open, locales.ClaudecfgSkillDeleteTitle(), locales.ClaudecfgSkillDeleteMessage(d.Name), widget.Destructive,
			locales.ClaudecfgCancel(), locales.ClaudecfgDelete()) {
		case 1:
			name := d.Name
			p.async(func(ctx context.Context) func() {
				err := p.opts.Core.DeleteSkill(ctx, name)
				return func() {
					if err != nil {
						p.failed(locales.ClaudecfgDeleteFailed(), err)
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
		alert(c, &n.open, n.title, n.message, widget.Recommend, locales.ClaudecfgClose())
		if !n.open {
			s.showing = nil
		}
	}
}
