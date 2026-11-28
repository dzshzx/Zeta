package claudecfg

import (
	"context"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
)

// 设备页「Claude」组里的「Claude Code」：那台主机上的版本，点「更新」在那台上跑 claude update（docs/core.md「主机」的
// system.claude、system.claude_update，同手机版的 ClaudeCodeUpdateRow）。已经在跑的对话不受影响，之后起的用新版本。

// claudeState 是这台主机上 Claude Code 的版本和更新的进展。
type claudeState struct {
	loaded   bool
	version  string // 空是没装
	updating bool
	note     string // 读版本或上次更新的结果，没有时为空
}

// loadClaude 读这台主机上当前工具（claude 或 agy）的版本（打开设备页时）。
func (p *Page) loadClaude() {
	p.async(func(ctx context.Context) func() {
		v, err := p.opts.Core.CLIVersion(ctx)
		return func() {
			p.claude.loaded = err == nil
			p.claude.version = v
			p.claude.note = ""
			if err != nil {
				p.claude.note = locales.ClaudecfgClaudeVersionFailed(err.Error())
			}
		}
	})
}

// claudeRow 是 CLI 版本与更新一行：右边版本和「更新」，说明是上次的结果。
func (p *Page) claudeRow(l *widget.List) {
	c := l.Context()
	st := &p.claude
	summary := st.note
	if st.updating {
		summary = locales.ClaudecfgClaudeUpdating()
	}
	value := "…"
	if st.loaded {
		value = st.version
		if value == "" {
			value = locales.ClaudecfgClaudeNotInstalled()
		}
	}
	title := "Claude Code"
	if p.isAgy() {
		title = "Antigravity CLI"
	}
	l.Row(widget.Row{Title: title, Summary: summary, Value: value, End: func() {
		widget.SmallButton(c, locales.ClaudecfgClaudeUpdate()).Disabled(!st.loaded || st.updating).OnClick(p.updateClaude)
	}})
}

// updateClaude 在这台主机上跑 claude update 或 agy update，最多几分钟。
func (p *Page) updateClaude() {
	p.claude.updating, p.claude.note = true, ""
	p.async(func(ctx context.Context) func() {
		u, err := p.opts.Core.UpdateCLI(ctx)
		return func() {
			p.claude.updating = false
			if err != nil {
				p.claude.note = locales.ClaudecfgClaudeUpdateFailed(err.Error())
				return
			}
			p.claude.version = u.Version
			p.claude.note = updateNote(u)
		}
	})
}

// updateNote 是更新完的一句话：成了说新旧版本，没成说 claude update 输出的最后一行（同手机版的 updateNote）。
func updateNote(u coreapi.ClaudeUpdate) string {
	switch {
	case !u.OK:
		last := locales.ClaudecfgClaudeUpdateError()
		for _, line := range strings.Split(u.Output, "\n") {
			if strings.TrimSpace(line) != "" {
				last = line
			}
		}
		return locales.ClaudecfgClaudeUpdateFailed(last)
	case u.Version != "" && u.Version != u.Before:
		before := u.Before
		if before == "" {
			before = locales.ClaudecfgClaudeNotInstalled()
		}
		return locales.ClaudecfgClaudeUpdated(before, u.Version)
	}
	return locales.ClaudecfgClaudeUpToDate()
}
