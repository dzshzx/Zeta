package ui

import (
	"time"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 后台任务面板（同手机版 BackgroundTasksScreen 的 AllConversations）：侧栏「后台任务」点进来，一次列出后台对话和
// 聊天窗格里这个对话的后台任务。桌面版只拿着眼前这一个对话（切走就交还核心，见 hub.open），所以「后台对话」是侧栏列着的
// 设备上核心报还在进行的别的会话（hub.BackgroundChats），后台任务只有当前对话的：别的会话的任务清单要打开它才拿得到，
// 后台对话一行里写着它有几个任务在跑。面板占右边主区域；点一个任务看详情，详情和子代理页就是聊天窗格那几页
// （taskpages.go，压在窗格的页栈上），返回回到面板；点一个对话切过去，回到聊天。

// pageTasks 是主区域在看后台任务面板。
const pageTasks = "tasks"

// openAllTasks 打开后台任务面板（侧栏的「后台任务」）：聊天窗格上盖着的页先关掉，面板从列表开始。
func (a *App) openAllTasks() {
	if p := a.pane; p != nil {
		a.closePages(p)
	}
	a.page = pageTasks
}

// leaveAllTasks 回到聊天。
func (a *App) leaveAllTasks() { a.page = pageHome }

// leftAllTasks：上一帧还在面板、这一帧离开了（返回、点了对话、进了设置……）：面板里点开的任务页一起关掉，
// 回到聊天时不会还压着它们。
func (a *App) leftAllTasks() {
	if a.shown == pageTasks && a.page != pageTasks && a.pane != nil {
		a.closePages(a.pane)
	}
}

// tasksEntry 是侧栏顶上的「后台任务」：有后台对话或任务在运行时行尾是个数（同手机版侧栏入口那一行，它把两样分开写，
// 这里合成一个数，分开的说法在悬停提示里）。数的是面板里列着、还在跑的行：后台对话和当前对话在跑的任务；
// 后台对话自己的任务面板里不列（见上），也不另算，免得个数和面板对不上。
func (a *App) tasksEntry(c *ui.Context) {
	chats := a.hub.BackgroundChats()
	running, finished := 0, 0
	if p := a.pane; p != nil {
		running = p.state.RunningTasks()
		finished = len(p.state.Tasks) - running
	}
	widget.NavCount(c, widget.IconTimer, locales.TasksTitle(), len(chats)+running, backgroundSummary(len(chats), running, finished),
		a.page == pageTasks).OnClick(a.openAllTasks)
}

// tasksPanel 是后台任务面板；从它点开了任务时画那一页（聊天窗格的页栈）。
func (a *App) tasksPanel(c *ui.Context) {
	p := a.pane
	if p != nil && a.chatSubpage(c, p) {
		return
	}
	c.OnWindowShortcut(0, ui.KeyEscape, a.leaveAllTasks)
	widget.Header(c, locales.TasksTitle(), a.leaveAllTasks)
	running := false
	widget.Body(c, "all-tasks", func() {
		a.backgroundChats(c)
		var list []tasks.Task
		if p != nil {
			list = p.state.Tasks
		}
		if len(list) == 0 {
			noTasks(c)
			return
		}
		widget.Group(c, locales.TasksCurrentChat(a.chatTitle(p)), locales.TasksListFooter(), func(l *widget.List) {
			running = a.taskRows(c, l, p, list)
		})
	})
	if running {
		c.After(time.Second) // 已运行的时间每秒走一次
	}
}

// backgroundChats 是「后台对话」一组：一行一个，下面一行是它在干什么（列着全部设备时前面是设备名），点了切过去。
func (a *App) backgroundChats(c *ui.Context) {
	chats := a.hub.BackgroundChats()
	widget.Group(c, locales.TasksBackgroundChats(len(chats)), locales.TasksBackgroundChatsFooter(), func(l *widget.List) {
		if len(chats) == 0 {
			l.Row(widget.Row{Title: locales.TasksNoBackgroundChats()})
		}
		for _, b := range chats {
			s := b.Session
			summary := conversationState(b.Activity.State == "waiting", b.Activity.State == "answering", b.Activity.Tasks)
			if a.hub.AllHosts() {
				summary = locales.TasksDeviceAndState(a.hub.Name(s.Backend), summary)
			}
			l.Row(widget.Row{Key: hub.Key(s), Title: rowTitle(s), Summary: summary, Arrow: true, OnClick: func() { a.openSession(s) }})
		}
	})
}

// noTasks 是没有后台任务时的一组。
func noTasks(c *ui.Context) {
	widget.Group(c, "", locales.TasksListFooter(), func(l *widget.List) {
		l.Row(widget.Row{Title: locales.TasksNone(), Summary: locales.TasksNoneSummary()})
	})
}
