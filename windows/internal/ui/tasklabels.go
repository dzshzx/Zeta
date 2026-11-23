package ui

import (
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// 后台任务页上的说法（同手机版 TaskLabels.kt）。

// taskKind 是任务的种类：后台命令、监视器、子代理（带类型），别的用 Claude Code 报的类型。
func taskKind(t tasks.Task) string {
	switch t.Kind {
	case tasks.KindCommand:
		return locales.TasksKindCommand()
	case tasks.KindMonitor:
		return locales.TasksKindMonitor()
	case tasks.KindAgent:
		if t.AgentType != "" {
			return locales.TasksKindAgentType(t.AgentType)
		}
		return locales.TasksKindAgent()
	}
	if t.Type != "" {
		return t.Type
	}
	return locales.TasksKindOther()
}

// taskPageTitle 是详情页的标题：只说种类，子代理的类型另起一行，免得被截断。
func taskPageTitle(t tasks.Task) string {
	if t.Kind == tasks.KindAgent {
		return locales.TasksKindAgent()
	}
	return taskKind(t)
}

// taskTitle 是一行标题：任务说明；没有说明就用命令的第一行，都没有时说种类。
func taskTitle(t tasks.Task) string {
	if s := strings.TrimSpace(t.Description); s != "" {
		return t.Description
	}
	for line := range strings.Lines(t.Command) {
		if s := strings.TrimSpace(line); s != "" {
			return s
		}
	}
	return taskKind(t)
}

// tone 是状态文字的颜色档位。
type tone int

const (
	toneQuiet   tone = iota // 已停止、已结束这类：淡色
	toneRunning             // 运行中
	toneDone                // 已完成
	toneFailed              // 失败
)

// color 是这一档的文字色。
func (t tone) color(k *widget.Colors) ui.Color {
	switch t {
	case toneRunning:
		return k.PrimaryText
	case toneDone:
		return k.Success
	case toneFailed:
		return k.Error
	}
	return k.Tertiary
}

// taskState 是任务状态的说法和颜色档位（子代理页照它说，见 subagentState）。stopping：正在停止。
func taskState(s tasks.State, stopping bool) (string, tone) {
	switch {
	case stopping:
		return locales.TasksStateStopping(), toneRunning
	case s == tasks.StateRunning:
		return locales.TasksStateRunning(), toneRunning
	case s == tasks.StateCompleted:
		return locales.TasksStateCompleted(), toneDone
	case s == tasks.StateFailed:
		return locales.TasksStateFailed(), toneFailed
	case s == tasks.StateStopped:
		return locales.TasksStateStopped(), toneQuiet
	}
	return locales.TasksStateEnded(), toneQuiet
}

// taskSummary 是列表里的说明行：「后台命令 · 已运行 2 分 05 秒」「子代理 · 已完成 · 用时 6 秒」。now 是毫秒。
func taskSummary(t tasks.Task, now int64) string {
	elapsed := tasks.FormatElapsedIn(i18n.Locale(), t.ElapsedMs(now))
	switch {
	case t.Stopping:
		return locales.TasksSummaryStopping(taskKind(t), elapsed)
	case t.Running():
		return locales.TasksSummaryRunning(taskKind(t), elapsed)
	}
	state, _ := taskState(t.State, false)
	return locales.TasksSummaryDone(taskKind(t), state, elapsed)
}

// outputHeader 是输出上方的一句：读到多少、是不是只显示了最后一段。
func outputHeader(o tasks.Output) string {
	switch {
	case o.TotalBytes <= 0:
		return locales.TasksOutput()
	case o.Truncated:
		return locales.TasksOutputTail(attach.FormatBytes(o.TotalBytes))
	}
	return locales.TasksOutputSize(attach.FormatBytes(o.TotalBytes))
}

// stopSummary 是停止确认框里的说明：是哪个任务；子代理停下后 Claude 会接着处理。
func stopSummary(t tasks.Task) string {
	if t.Kind == tasks.KindAgent {
		return locales.TasksStopAgentNote(taskTitle(t))
	}
	return taskTitle(t)
}

// conversationState 是后台对话一行的状态（同手机版 TaskLabels.conversation，桌面版没有定时任务，不带它的名字）：
// 等确认、在回答、后台任务在跑，都没有时是快要收起了。
func conversationState(waiting, busy bool, tasks int) string {
	switch {
	case waiting:
		return locales.TasksChatWaiting()
	case busy:
		return locales.TasksChatAnswering()
	case tasks > 0:
		return locales.TasksChatTasks(tasks)
	}
	return locales.TasksChatEnding()
}

// backgroundSummary 是侧栏「后台任务」入口的说明（同手机版 BackgroundTasksEntry 那一行字）：几个后台对话、几个任务在运行，
// 没有在运行的任务时说有几个结束了的；都没有时是空。
func backgroundSummary(conversations, running, finished int) string {
	var parts []string
	if conversations > 0 {
		parts = append(parts, locales.TasksSummaryChats(conversations))
	}
	if running > 0 {
		parts = append(parts, locales.TasksSummaryRunningTasks(running))
	}
	if finished > 0 && running == 0 {
		parts = append(parts, locales.TasksSummaryFinishedTasks(finished))
	}
	return strings.Join(parts, " · ")
}
