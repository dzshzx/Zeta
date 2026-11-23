package diag

import (
	"time"

	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/runlog"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 诊断页上 Zeta 自己的两组：运行日志（点进去看、按类别和轻重筛、清空，同手机版的 DiagLogPage）和环境（同手机版的 EnvironmentGroup）。

// logState 是运行日志那一页的状态。
type logState struct {
	open     bool
	topic    int // 选的类别：0 是全部，之后是 runlog.Topics 的下标加一
	level    int // 选的轻重：levelChoices 的下标
	clearing bool
	clearErr error
}

// levelChoices 是「轻重」的几档：标签（按界面当前的语言取）和最轻要到哪一档。
var levelChoices = []struct {
	label func() string
	min   runlog.Level
}{{locales.DiagFilterAll, runlog.Info}, {locales.DiagFilterWarnings, runlog.Warn}, {locales.DiagFilterErrors, runlog.Error}}

// shownLimit 是运行日志页最多列几条（新的在前），再多的看导出的诊断包。
const shownLimit = 300

// runLogGroup 是「运行日志」一组：一行点进去，副标题是共几条、几条警告，右边红字写几条出错。
func (p *Page) runLogGroup(c *ui.Context) {
	if p.o.Log == nil {
		return
	}
	entries := p.o.Log.Entries()
	warnings, errors := 0, 0
	for _, e := range entries {
		switch e.Level {
		case runlog.Warn:
			warnings++
		case runlog.Error:
			errors++
		}
	}
	footer := locales.DiagRunLogFooter(runlog.Capacity)
	widget.Group(c, locales.DiagRunLog(), footer, func(l *widget.List) {
		summary := locales.DiagNoEntries()
		switch {
		case warnings > 0:
			summary = locales.DiagEntryCountWarnings(len(entries), warnings)
		case len(entries) > 0:
			summary = locales.DiagEntryCount(len(entries))
		}
		row := widget.Row{Title: locales.DiagViewRunLog(), Summary: summary, Arrow: true, OnClick: func() { p.log.open = true }}
		if errors > 0 {
			row.End = func() {
				ui.Text(c, locales.DiagErrorCount(errors)).FontSize(widget.SizeBodyM).TextColor(widget.Of(c).Error)
			}
		}
		l.Row(row)
	})
}

// envGroup 是「环境」一组：和诊断包里环境.txt 是同一份。短的值放右边，长的（设备怎么连）放标题下面。
func (p *Page) envGroup(c *ui.Context) {
	if p.o.Env == nil {
		return
	}
	widget.Group(c, locales.DiagEnv(), "", func(l *widget.List) {
		for _, line := range p.o.Env() {
			row := widget.Row{Title: line.Name, Key: line.Name}
			if len([]rune(line.Value)) <= 24 {
				row.Value = line.Value
			} else {
				row.Summary = line.Value
			}
			l.Row(row)
		}
	})
}

// logView 是运行日志这一页：顶上返回、清空；两个筛选；下面新的在前一条一张卡片里的一行。
func (p *Page) logView(c *ui.Context) {
	s := &p.log
	k := widget.Of(c)
	entries := p.o.Log.Entries()
	widget.TitleRow(c).Children(func() {
		widget.IconButton(c, widget.IconBack, locales.WidgetBack()).OnClick(func() { s.open = false })
		ui.Text(c, locales.DiagRunLog()).FontSize(widget.SizeHeadlineS).FontWeight(widget.Medium).TextColor(k.Text).Shrink(1)
		ui.Spacer(c)
		widget.SmallDangerButton(c, locales.DiagClear()).Disabled(len(entries) == 0).OnClick(func() { s.clearing = true })
	})
	topic := runlog.Topic("")
	if s.topic > 0 {
		topic = runlog.Topics[s.topic-1]
	}
	var shown []runlog.Entry
	for i := len(entries) - 1; i >= 0 && len(shown) < shownLimit; i-- {
		if entries[i].Matches(topic, levelChoices[s.level].min) {
			shown = append(shown, entries[i])
		}
	}
	widget.Body(c, "runlog", func() {
		widget.Group(c, "", "", func(l *widget.List) {
			topics := []string{locales.DiagFilterAll()}
			for _, t := range runlog.Topics {
				topics = append(topics, t.Name())
			}
			category := locales.DiagCategory()
			l.Row(widget.Row{Title: category, End: func() { choice(c, category, topics, s.topic, func(i int) { s.topic = i }) }})
			var levels []string
			for _, lv := range levelChoices {
				levels = append(levels, lv.label())
			}
			severity := locales.DiagSeverity()
			l.Row(widget.Row{Title: severity, End: func() { choice(c, severity, levels, s.level, func(i int) { s.level = i }) }})
		})
		footer := ""
		if len(shown) == shownLimit {
			footer = locales.DiagShownLimit(shownLimit)
		}
		if s.clearErr != nil {
			footer = locales.DiagClearFailed(s.clearErr.Error())
		}
		widget.Group(c, "", footer, func(l *widget.List) {
			if len(shown) == 0 {
				empty := locales.DiagNoMatches()
				if len(entries) == 0 {
					empty = locales.DiagNoEntries()
				}
				l.Message(empty)
			}
			for i, e := range shown {
				l.Custom(func() { entryRow(c.Key(i), e, p.o.Now()) })
			}
		})
	})
	if widget.Alert(c, &s.clearing, locales.DiagClearTitle(), locales.DiagClearMessage(), widget.Action{Label: locales.WidgetCancel()},
		widget.Action{Label: locales.DiagClear(), Style: widget.Destructive}) == 1 {
		s.clearErr = p.o.Log.Clear()
	}
}

// entryRow 是一条：上面一行时间 · 类别，右边轻重（警告橙、出错红）；下面是能选中复制的消息。
func entryRow(c *ui.Context, e runlog.Entry, now time.Time) {
	k := widget.Of(c)
	color := k.Secondary
	switch e.Level {
	case runlog.Warn:
		color = k.Warning
	case runlog.Error:
		color = k.Error
	}
	ui.Column(c).Grow(1).MinWidth(0).Gap(4).Padding(4, 0).Children(func() {
		ui.Row(c).FillWidth().AlignItems(ui.Center).Children(func() {
			ui.Text(c, clock(e.At, now)+" · "+e.Topic.Name()).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).Grow(1)
			ui.Text(c, e.Level.Name()).FontSize(widget.SizeBodyXS).TextColor(color)
		})
		ui.Text(c, e.Message).FontSize(widget.SizeBodyS).LineHeight(1.45).TextColor(k.Text).Selectable()
	})
}

// clock 是一条的时间：今天的只写时分秒，别的天带上月日（同手机版的 DiagEntries.shortTime）。
func clock(t, now time.Time) string {
	t, now = t.Local(), now.Local()
	if t.YearDay() == now.YearDay() && t.Year() == now.Year() {
		return t.Format("15:04:05")
	}
	return t.Format("01-02 15:04:05")
}

// choice 是一行右边的下拉：选了别的一项时调 pick。
func choice(c *ui.Context, name string, labels []string, selected int, pick func(int)) {
	widget.MenuButton(c.Key(name), func(t ui.Element) {
		widget.Dropdown(c, t, labels[selected])
		t.Label(name)
	}, func(m *widget.Menu) {
		for i, label := range labels {
			if m.Choice(label, i == selected) && i != selected {
				pick(i)
			}
		}
	})
}
