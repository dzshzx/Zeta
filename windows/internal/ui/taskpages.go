package ui

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 聊天窗格里点进去的几页：后台任务列表、一个任务的详情、子代理的记录（同手机版 BackgroundTasksScreen.kt、SubagentScreen.kt）。
// 它们盖住聊天窗格，返回键一层层退回去；跟着窗格走，换了对话就都关掉。

// pageKind 是聊天窗格上盖着的一页。
type pageKind int

const (
	pageTaskList pageKind = iota
	pageTask
)

// subpage 是盖着的一页：任务详情页的任务 id，子代理页看着的子代理。
type subpage struct {
	kind pageKind
	task string
}

// chatPages 是盖在聊天窗格上的各页，后开的在上面；还有各任务读到的输出、停止的确认框。
type chatPages struct {
	stack   []subpage
	outputs map[string]*taskOutput
	stop    struct {
		open bool
		task tasks.Task
	}
}

// taskOutput 是一个后台命令读到的输出，和读的进度。
type taskOutput struct {
	out tasks.Output
	// read：读到过；reading：正在读；at 是上次读完的时刻（界面的时钟），running 是上次发出时任务还在跑。
	read, reading, running bool
	at                     time.Time
}

// outputPoll 是任务在跑时多久读一次输出。
const outputPoll = 2 * time.Second

// openTasks 打开后台任务列表（状态行的「后台任务」点了）。
func (p *chatPane) openTasks() {
	p.pages.stack = append(p.pages.stack, subpage{kind: pageTaskList})
}

// openTask 打开一个任务的详情。
func (p *chatPane) openTask(id string) {
	p.pages.stack = append(p.pages.stack, subpage{kind: pageTask, task: id})
}

// back 退回上一页。
func (a *App) back(p *chatPane) {
	s := p.pages.stack
	if len(s) == 0 {
		return
	}
	p.pages.stack = s[:len(s)-1]
}

// closePages 退回聊天。
func (a *App) closePages(p *chatPane) {
	for len(p.pages.stack) > 0 {
		a.back(p)
	}
}

// chatSubpage 在聊天窗格上盖着页时画最上面那页，返回 true；没有时什么也不画。
func (a *App) chatSubpage(c *ui.Context, p *chatPane) bool {
	s := p.pages.stack
	if len(s) == 0 {
		return false
	}
	top := s[len(s)-1]
	k := coui.Of(c)
	ui.Column(c.Key(len(s))).Fill().Background(k.Page).Children(func() {
		switch top.kind {
		case pageTaskList:
			a.taskListPage(c, p)
		case pageTask:
			a.taskPage(c, p, top.task)
		}
	})
	a.stopDialog(c, p)
	return true
}

// tasksLink 是状态行里的「后台任务 N 个」：点进去看这个对话的后台任务。
func (a *App) tasksLink(c *ui.Context, p *chatPane, running int) {
	k := coui.Of(c)
	text := "后台任务 " + strconv.Itoa(running) + " 个"
	b := ui.ButtonBase(c).Label(text).Tooltip("查看后台任务").Padding(2, 8).Radius(999).Gap(2).AlignItems(ui.Center).
		Cursor(ui.CursorPointer)
	if b.Hovered() {
		b.Background(k.Hover)
	}
	b.Children(func() {
		ui.Text(c, text).FontSize(coui.SizeBodyS).TextColor(k.PrimaryText)
		ui.Icon(c, coui.IconForward).Size(12, 12).TextColor(k.PrimaryText)
	})
	b.OnClick(p.openTasks)
}

// taskListFooter 是任务列表下面的说明。桌面版的列表不留清除已结束的按钮：结束了的由核心留最近 20 个。
const taskListFooter = "Claude 在对话里放到后台跑的命令、监视器和子代理。它们跑在对话的 Claude Code 进程里：" +
	"对话闲下来被 Zeta 收起、或进程结束时，没跑完的一起结束。结束了的留最近 20 个。"

// taskListPage 是这个对话的后台任务，点一个看详情。
func (a *App) taskListPage(c *ui.Context, p *chatPane) {
	coui.Header(c, "后台任务", func() { a.back(p) })
	list := p.state.Tasks // 核心给的顺序：在跑的在前，各自按开始时间新的在前
	now := a.now().UnixMilli()
	running := false
	coui.Body(c, "tasks", func() {
		if len(list) == 0 {
			coui.Group(c, "", taskListFooter, func(l *coui.List) {
				l.Row(coui.Row{Title: "没有后台任务", Summary: "Claude 用 run_in_background 跑的命令、Monitor 和后台子代理会列在这里"})
			})
			return
		}
		coui.Group(c, a.chatTitle(p), taskListFooter, func(l *coui.List) {
			k := coui.Of(c)
			for _, t := range list {
				running = running || t.Running()
				row := coui.Row{Key: t.ID, Title: taskTitle(t), Summary: taskSummary(t, now), Arrow: true,
					OnClick: func() { p.openTask(t.ID) }}
				switch {
				case t.State == tasks.StateFailed:
					row.SummaryColor = k.Error
				case t.Running():
					row.SummaryColor = k.PrimaryText
				}
				l.Row(row)
			}
		})
	})
	if running {
		c.After(time.Second) // 已运行的时间每秒走一次
	}
}

// findTask 是这个对话里 id 那个任务。
func findTask(s chat.State, id string) (tasks.Task, bool) {
	for _, t := range s.Tasks {
		if t.ID == id {
			return t, true
		}
	}
	return tasks.Task{}, false
}

// taskPage 是一个任务：说明、状态、用时（子代理还有类型、工具调用、在做什么和进它的记录）、所属对话、停止，
// 下面是出错的原因、命令（子代理是交给它的指令）、结果（子代理是报告）和后台命令的输出。
func (a *App) taskPage(c *ui.Context, p *chatPane, id string) {
	t, ok := findTask(p.state, id)
	if !ok {
		coui.Header(c, "后台任务", func() { a.back(p) })
		coui.Body(c, "task-gone", func() {
			coui.Group(c, "", "", func(l *coui.List) { l.Row(coui.Row{Title: "这个任务已经不在了"}) })
		})
		return
	}
	coui.Header(c, taskPageTitle(t), func() { a.back(p) })
	var out *taskOutput
	if t.Readable() {
		out = a.followOutput(c, p, t)
	}
	coui.Body(c, "task/"+t.ID, func() {
		a.taskInfo(c, p, t)
		agent := t.Kind == tasks.KindAgent
		if t.Error != "" {
			textBlock(c, "出错", t.Error, false)
		}
		if t.Command != "" {
			title := "命令"
			if agent {
				title = "交给它的指令"
			}
			textBlock(c, title, t.Command, !agent)
		}
		if t.Summary != "" {
			title := "结果"
			if agent {
				title = "报告"
			}
			textBlock(c, title, t.Summary, false)
		}
		if out != nil {
			outputBlock(c, out, p.state.Process)
		}
	})
	if t.Running() {
		c.After(time.Second)
	}
}

// taskInfo 是任务的概要。运行中最后是停止：输出在下面一直变长，按钮放在最后会被挤着走，所以放在这组里。
func (a *App) taskInfo(c *ui.Context, p *chatPane, t tasks.Task) {
	k := coui.Of(c)
	coui.Group(c, "", "", func(l *coui.List) {
		title := taskTitle(t)
		row := coui.Row{Title: title}
		if t.Description != "" && t.Description != title {
			row.Summary = t.Description
		}
		l.Row(row)
		state := taskState(t)
		l.Row(coui.Row{Title: "状态", End: func() {
			color := k.Tertiary
			switch t.State {
			case tasks.StateRunning:
				color = k.PrimaryText
			case tasks.StateCompleted:
				color = k.Success
			case tasks.StateFailed:
				color = k.Error
			}
			ui.Text(c, state).FontSize(coui.SizeBodyM).TextColor(color)
		}})
		elapsed := "用时"
		if t.Running() {
			elapsed = "已运行"
		}
		l.Row(coui.Row{Title: elapsed, Value: formatElapsed(t.ElapsedMs(a.now().UnixMilli()))})
		if t.Kind == tasks.KindAgent {
			a.agentRows(l, p, t)
		}
		l.Row(coui.Row{Title: "所属对话", Value: a.chatTitle(p), Arrow: true, OnClick: func() { a.closePages(p) }})
		if t.Running() {
			label := "停止这个任务"
			if t.Stopping {
				label = "正在停止…"
			}
			stop := coui.Row{Title: label, Danger: true}
			if !t.Stopping && p.state.Process {
				stop.OnClick = func() { p.pages.stop.open, p.pages.stop.task = true, t }
			}
			l.Row(stop)
		}
	})
}

// agentRows 是子代理才有的几行：类型、工具调用次数、（运行中）在做什么，和进它的记录。
func (a *App) agentRows(l *coui.List, _ *chatPane, t tasks.Task) {
	if t.AgentType != "" {
		l.Row(coui.Row{Title: "子代理类型", Value: t.AgentType})
	}
	if t.ToolUses != nil {
		l.Row(coui.Row{Title: "工具调用", Value: strconv.Itoa(*t.ToolUses) + " 次"})
	}
	if t.Running() && t.Progress != "" {
		l.Row(coui.Row{Title: "正在做", Summary: t.Progress})
	}
}

// stopDialog 是停止任务前的确认。
func (a *App) stopDialog(c *ui.Context, p *chatPane) {
	s := &p.pages.stop
	if !s.open {
		return
	}
	t := s.task
	if coui.Alert(c, &s.open, "停止这个任务？", stopSummary(t),
		coui.Action{Label: "取消"}, coui.Action{Label: "停止", Style: coui.Destructive}) == 1 {
		a.act(func(ctx context.Context, conv *chat.Conversation) error { return conv.StopTask(ctx, t.ID) })
	}
}

// followOutput 跟着读后台命令的输出：先读一次，运行中每两秒再读，结束时再读最后一次；进程没了就不读。
func (a *App) followOutput(c *ui.Context, p *chatPane, t tasks.Task) *taskOutput {
	if p.pages.outputs == nil {
		p.pages.outputs = map[string]*taskOutput{}
	}
	o := p.pages.outputs[t.ID]
	if o == nil {
		o = &taskOutput{}
		p.pages.outputs[t.ID] = o
	}
	now := c.Now()
	due := !o.read || (t.Running() && now.Sub(o.at) >= outputPoll) || (o.running && !t.Running())
	if o.reading || !due || !p.state.Process || p.src.Conv == nil {
		if t.Running() && o.read {
			c.After(max(outputPoll-now.Sub(o.at), 0))
		}
		return o
	}
	o.reading, o.running = true, t.Running()
	id := t.ID
	a.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		out, err := conv.ReadTaskOutput(ctx, id)
		if err != nil {
			out = tasks.Output{Error: err.Error()}
		}
		a.post(func() {
			o.out, o.read, o.reading, o.at = out, true, false, now
			c.Invalidate()
		})
		return nil
	}, nil)
	return o
}

// outputBlock 是后台命令、监视器的输出（最多 8 KiB）；还没读到、读不到或还没有输出时说明。
func outputBlock(c *ui.Context, o *taskOutput, process bool) {
	text := strings.TrimRight(o.out.Text, " \t\r\n")
	switch {
	case !o.read && !process:
		textBlock(c, "输出", "进程已经结束，读不到输出了", false)
	case !o.read:
		textBlock(c, "输出", "正在读取…", false)
	case o.out.Error != "":
		textBlock(c, "输出", "没读到："+o.out.Error, false)
	case text == "":
		textBlock(c, outputHeader(o.out), "（还没有输出）", false)
	default:
		textBlock(c, outputHeader(o.out), text, true)
	}
}

// textBlock 是一段可以选中的文字：命令、输出、报告。mono 时用等宽字。
func textBlock(c *ui.Context, title, text string, mono bool) {
	coui.Group(c, title, "", func(l *coui.List) {
		k := coui.Of(c)
		t := ui.Text(c, text).TextColor(k.Text).FillWidth().Padding(12, 16).Selectable()
		if mono {
			t.Font(coui.Mono).FontSize(11.5).LineHeight(16.0 / 11.5)
		} else {
			t.FontSize(coui.SizeBodyM).LineHeight(1.5)
		}
	})
}
