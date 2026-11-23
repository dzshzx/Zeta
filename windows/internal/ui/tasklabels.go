package ui

import (
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// 后台任务页上的说法（同手机版 TaskLabels.kt）。

// taskKind 是任务的种类：后台命令、监视器、子代理（带类型），别的用 Claude Code 报的类型。
func taskKind(t tasks.Task) string {
	switch t.Kind {
	case tasks.KindCommand:
		return "后台命令"
	case tasks.KindMonitor:
		return "监视器"
	case tasks.KindAgent:
		if t.AgentType != "" {
			return "子代理 · " + t.AgentType
		}
		return "子代理"
	}
	if t.Type != "" {
		return t.Type
	}
	return "后台任务"
}

// taskPageTitle 是详情页的标题：只说种类，子代理的类型另起一行，免得被截断。
func taskPageTitle(t tasks.Task) string {
	if t.Kind == tasks.KindAgent {
		return "子代理"
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
		return "正在停止", toneRunning
	case s == tasks.StateRunning:
		return "运行中", toneRunning
	case s == tasks.StateCompleted:
		return "已完成", toneDone
	case s == tasks.StateFailed:
		return "失败", toneFailed
	case s == tasks.StateStopped:
		return "已停止", toneQuiet
	}
	return "已结束", toneQuiet
}

// taskSummary 是列表里的说明行：「后台命令 · 已运行 2 分 05 秒」「子代理 · 已完成 · 用时 6 秒」。now 是毫秒。
func taskSummary(t tasks.Task, now int64) string {
	elapsed := tasks.FormatElapsed(t.ElapsedMs(now))
	switch {
	case t.Stopping:
		return taskKind(t) + " · 正在停止 · 已运行 " + elapsed
	case t.Running():
		return taskKind(t) + " · 已运行 " + elapsed
	}
	state, _ := taskState(t.State, false)
	return taskKind(t) + " · " + state + " · 用时 " + elapsed
}

// outputHeader 是输出上方的一句：读到多少、是不是只显示了最后一段。
func outputHeader(o tasks.Output) string {
	switch {
	case o.TotalBytes <= 0:
		return "输出"
	case o.Truncated:
		return "输出 · 共 " + attach.FormatBytes(o.TotalBytes) + "，只显示最后 8 KB"
	}
	return "输出 · " + attach.FormatBytes(o.TotalBytes)
}

// stopSummary 是停止确认框里的说明：是哪个任务；子代理停下后 Claude 会接着处理。
func stopSummary(t tasks.Task) string {
	if t.Kind == tasks.KindAgent {
		return taskTitle(t) + "\n子代理停下后，Claude 会收到通知并接着处理。"
	}
	return taskTitle(t)
}
