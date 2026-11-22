package ui

import (
	"fmt"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/windows/internal/attach"
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

// taskState 是状态的说法。
func taskState(t tasks.Task) string {
	if t.Stopping {
		return "正在停止"
	}
	switch t.State {
	case tasks.StateRunning:
		return "运行中"
	case tasks.StateCompleted:
		return "已完成"
	case tasks.StateFailed:
		return "失败"
	case tasks.StateStopped:
		return "已停止"
	}
	return "已结束"
}

// taskSummary 是列表里的说明行：「后台命令 · 已运行 2 分 05 秒」「子代理 · 已完成 · 用时 6 秒」。now 是毫秒。
func taskSummary(t tasks.Task, now int64) string {
	elapsed := formatElapsed(t.ElapsedMs(now))
	switch {
	case t.Stopping:
		return taskKind(t) + " · 正在停止 · 已运行 " + elapsed
	case t.Running():
		return taskKind(t) + " · 已运行 " + elapsed
	}
	return taskKind(t) + " · " + taskState(t) + " · 用时 " + elapsed
}

// formatElapsed 是用时：「45 秒」「2 分 05 秒」「1 小时 03 分」。
func formatElapsed(ms int64) string {
	s := max(ms/1000, 0)
	h, m := s/3600, s%3600/60
	switch {
	case h > 0:
		return fmt.Sprintf("%d 小时 %02d 分", h, m)
	case m > 0:
		return fmt.Sprintf("%d 分 %02d 秒", m, s%60)
	}
	return fmt.Sprintf("%d 秒", s)
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
